package com.quicinc.chatapp.mapbox

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

/**
 * McpMapboxClient: MCP client that connects to the Mapbox MCP server
 * using the official Kotlin MCP SDK and Streamable HTTP transport.
 *
 * This replaces direct REST API calls with proper MCP protocol communication.
 */
class McpMapboxClient(private val accessToken: String) {

    companion object {
        private const val TAG = "McpMapboxClient"
        private const val MAPBOX_MCP_URL = "https://mcp.mapbox.com/mcp"

        // Only include tools useful for our use case — the MCP server returns 15+
        // but the 2048-token context can't fit them all.
        private val ALLOWED_TOOLS = setOf(
            "directions_tool",
            "directions",
            "geocode_forward_tool",
            "search_geocode",
            "geocode_reverse_tool",
            "reverse_geocode",
            "search_poi_tool",
            "category_search",
            "static_map_image_tool",
            "static_map"
        )
    }

    private var client: Client? = null
    private var allTools: List<Tool> = emptyList()
    private var tools: List<Tool> = emptyList()
    private var connected = false

    /**
     * Connect to the Mapbox MCP server, initialize session, and discover tools.
     * This must be called from a background thread.
     */
    fun connect(): Boolean {
        return try {
            runBlocking {
                // Configure HttpClient with Bearer auth and SSE support
                val httpClient = HttpClient(OkHttp) {
                    install(SSE) {
                        reconnectionTime = 5.seconds
                    }
                    defaultRequest {
                        header("Authorization", "Bearer $accessToken")
                    }
                }

                val mcpClient = Client(
                    clientInfo = Implementation(
                        name = "chatapp-android",
                        version = "1.0.0"
                    )
                )

                val transport = StreamableHttpClientTransport(
                    client = httpClient,
                    url = MAPBOX_MCP_URL
                )

                // Connect and initialize MCP session
                mcpClient.connect(transport)
                Log.i(TAG, "Connected to Mapbox MCP server")

                // Discover available tools
                val listResult = mcpClient.listTools()
                allTools = listResult.tools
                Log.i(TAG, "Server has ${allTools.size} tools total")

                // Filter to only useful tools
                tools = allTools.filter { it.name.lowercase() in ALLOWED_TOOLS.map { t -> t.lowercase() } }
                if (tools.isEmpty()) {
                    // If no exact match, take first 5
                    tools = allTools.take(5)
                }
                Log.i(TAG, "Using ${tools.size} tools:")
                tools.forEach { tool ->
                    Log.i(TAG, "  - ${tool.name}: ${tool.description}")
                }

                client = mcpClient
                connected = true
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Mapbox MCP server", e)
            connected = false
            false
        }
    }

    /**
     * Returns the filtered list of tools.
     */
    fun getTools(): List<Tool> = tools

    /**
     * Returns all tools from the MCP server (for settings display).
     */
    fun getAllTools(): List<Tool> = allTools

    /**
     * Returns true if connected to the MCP server.
     */
    fun isConnected(): Boolean = connected

    /**
     * Call a tool on the Mapbox MCP server by name with the given arguments.
     * This must be called from a background thread.
     *
     * @param toolName name of the tool to call
     * @param arguments JSON arguments for the tool
     * @return result as a string, or error message
     */
    fun callTool(toolName: String, arguments: JSONObject): String {
        val mcpClient = client
        if (mcpClient == null || !connected) {
            return """{"error": "Not connected to Mapbox MCP server"}"""
        }

        return try {
            runBlocking {
                // Convert org.json.JSONObject to Map<String, JsonPrimitive>
                val jsonArgs = convertToJsonObject(arguments)

                val result: CallToolResult = mcpClient.callTool(
                    name = toolName,
                    arguments = jsonArgs
                )

                // Extract text content from the result
                val textContents = result.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text ?: "" }

                if (textContents.isNotEmpty()) {
                    textContents
                } else {
                    """{"message": "Tool executed successfully but returned no text content"}"""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool call failed: $toolName", e)
            """{"error": "${e.message?.replace("\"", "'")}"}"""
        }
    }

    /**
     * Build a compact tools prompt for the LLM system prompt.
     * Must be very short — model context is only 2048 tokens.
     * Includes ultra-short descriptions so the model picks the right tool.
     */
    fun buildToolsPrompt(): String {
        if (tools.isEmpty()) return ""

        // Hardcoded short hints per tool name — the MCP descriptions are too verbose
        val hints = mapOf(
            "directions_tool" to "get route between two points",
            "directions" to "get route between two points",
            "geocode_forward_tool" to "address/place to coordinates",
            "search_geocode" to "address/place to coordinates",
            "geocode_reverse_tool" to "coordinates to address",
            "reverse_geocode" to "coordinates to address",
            "search_poi_tool" to "find nearby places like Starbucks, restaurants, gas stations",
            "category_search" to "find nearby places like Starbucks, restaurants, gas stations",
            "static_map_image_tool" to "generate a map image (use LAST)",
            "static_map" to "generate a map image (use LAST)"
        )

        val sb = StringBuilder()
        sb.appendLine("To use a tool respond ONLY with: <tool_call>{\"name\":\"TOOL\",\"arguments\":{...}}</tool_call>")
        sb.appendLine("Tools:")

        for (tool in tools) {
            val params = tool.inputSchema.properties?.keys?.joinToString(",") ?: ""
            val hint = hints[tool.name] ?: ""
            sb.appendLine("${tool.name}($params) - $hint")
        }

        sb.appendLine("Pick the RIGHT tool for the task. Answer briefly after getting results.")
        return sb.toString()
    }

    /**
     * Disconnect from the MCP server.
     */
    fun disconnect() {
        try {
            runBlocking {
                client?.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect", e)
        }
        client = null
        connected = false
    }

    /**
     * Convert an org.json.JSONObject to a Map for MCP tool call arguments.
     */
    private fun convertToJsonObject(jsonObject: JSONObject): Map<String, JsonPrimitive> {
        val map = mutableMapOf<String, JsonPrimitive>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = jsonObject.get(key)) {
                is String -> map[key] = JsonPrimitive(value)
                is Number -> map[key] = JsonPrimitive(value)
                is Boolean -> map[key] = JsonPrimitive(value)
                else -> map[key] = JsonPrimitive(value.toString())
            }
        }
        return map
    }
}
