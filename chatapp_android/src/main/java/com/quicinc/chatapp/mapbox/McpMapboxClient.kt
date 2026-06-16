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

        // Only include tools useful for our use case — the MCP server returns 30
        // but the 2048-token context can't fit them all.
        // Tool names verified against https://mcp.mapbox.com/mcp via MCP Inspector.
        private val ALLOWED_TOOLS = setOf(
            "ground_location_tool",      // what's near me, reverse geocode + POI combined
            "search_and_geocode_tool",   // specific brands/chains (Starbucks, CVS, etc.)
            "category_search_tool",      // generic categories (coffee shops, restaurants)
            "directions_tool",           // turn-by-turn routing
            "reverse_geocode_tool",      // coords → address
            "static_map_image_tool"      // generate a map image
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
     * Only 4 core tools. No param lists (ToolArgSanitizer builds args from GPS).
     * Format matches what the 3B model naturally outputs (<tool query="..." />).
     */
    fun buildToolsPrompt(): String {
        if (tools.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("You have tools. To call one, respond ONLY with the tag below, nothing else:")
        sb.appendLine("<TOOL query=\"...\"/>")
        sb.appendLine("")
        sb.appendLine("Tools:")
        sb.appendLine("search_and_geocode_tool — find a specific place/brand: CVS, Starbucks, McDonald's, Target")
        sb.appendLine("category_search_tool — find by type: pharmacy, coffee, restaurant, gas station")
        sb.appendLine("directions_tool — get driving route")
        sb.appendLine("ground_location_tool — what is near me")
        sb.appendLine("")
        sb.appendLine("Examples:")
        sb.appendLine("User: closest CVS → <search_and_geocode_tool query=\"CVS\"/>")
        sb.appendLine("User: nearest coffee shop → <category_search_tool query=\"coffee shop\"/>")
        sb.appendLine("User: directions home → <directions_tool query=\"home\"/>")
        sb.appendLine("User: what is around me → <ground_location_tool query=\"nearby\"/>")
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
     * Handles primitives, arrays, and nested objects recursively.
     */
    private fun convertToJsonObject(jsonObject: JSONObject): Map<String, kotlinx.serialization.json.JsonElement> {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = convertValue(jsonObject.get(key))
        }
        return map
    }

    private fun convertValue(value: Any?): kotlinx.serialization.json.JsonElement {
        return when (value) {
            null, JSONObject.NULL -> kotlinx.serialization.json.JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JSONObject -> {
                val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = convertValue(value.get(k))
                }
                kotlinx.serialization.json.JsonObject(map)
            }
            is org.json.JSONArray -> {
                val list = mutableListOf<kotlinx.serialization.json.JsonElement>()
                for (i in 0 until value.length()) {
                    list.add(convertValue(value.get(i)))
                }
                kotlinx.serialization.json.JsonArray(list)
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}
