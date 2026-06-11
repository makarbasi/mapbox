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
    }

    private var client: Client? = null
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
                tools = listResult.tools
                Log.i(TAG, "Discovered ${tools.size} tools:")
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
     * Returns the list of tools discovered from the MCP server.
     */
    fun getTools(): List<Tool> = tools

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
     * Build a tools prompt string from the discovered MCP tools
     * for injection into the LLM system prompt.
     */
    fun buildToolsPrompt(): String {
        if (tools.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("You have access to Mapbox location tools. To use a tool, respond ONLY with:")
        sb.appendLine("""<tool_call>{"name":"TOOL_NAME","arguments":{...}}</tool_call>""")
        sb.appendLine()
        sb.appendLine("Available tools:")

        for (tool in tools) {
            sb.appendLine("- ${tool.name}")
            tool.description?.let { sb.appendLine("  $it") }
            // Include parameter schema if available
            tool.inputSchema.properties?.let { props ->
                val paramNames = props.keys.joinToString(", ")
                if (paramNames.isNotEmpty()) {
                    sb.appendLine("  Parameters: $paramNames")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("You can chain multiple tool calls. After receiving results, provide a helpful natural language answer.")
        sb.appendLine("When showing locations on a map, use the map tool as your LAST tool call.")
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
