// ---------------------------------------------------------------------
// ChatApp - Mapbox Tool Registry (MCP-powered)
// ---------------------------------------------------------------------
package com.quicinc.chatapp.mapbox;

import android.util.Log;

import org.json.JSONObject;

/**
 * MapboxToolRegistry: Manages Mapbox tools via the MCP protocol.
 * Connects to the Mapbox MCP server, discovers available tools dynamically,
 * generates tool prompts for the LLM, and dispatches tool execution via MCP.
 */
public class MapboxToolRegistry {

    private static final String TAG = "MapboxToolRegistry";
    private final McpMapboxClient mcpClient;
    private boolean initialized = false;

    /**
     * MapboxToolRegistry: Creates a new registry that will connect to
     * the Mapbox MCP server using the provided access token.
     *
     * @param accessToken Mapbox API access token
     */
    public MapboxToolRegistry(String accessToken) {
        this.mcpClient = new McpMapboxClient(accessToken);
    }

    /**
     * initialize: Connect to the Mapbox MCP server and discover tools.
     * Must be called from a background thread.
     *
     * @return true if connection and tool discovery succeeded
     */
    public boolean initialize() {
        initialized = mcpClient.connect();
        if (initialized) {
            Log.i(TAG, "MCP initialized with " + mcpClient.getTools().size() + " tools");
        } else {
            Log.e(TAG, "Failed to initialize MCP connection");
        }
        return initialized;
    }

    /**
     * isInitialized: Returns whether the MCP connection is established.
     *
     * @return true if connected to Mapbox MCP server
     */
    public boolean isInitialized() {
        return initialized && mcpClient.isConnected();
    }

    /**
     * getToolsPrompt: Returns the tool definitions formatted for the LLM
     * system prompt. Tools are discovered dynamically from the MCP server.
     *
     * @return formatted prompt string describing all available tools
     */
    public String getToolsPrompt() {
        return mcpClient.buildToolsPrompt();
    }

    /**
     * execute: Execute a tool by name with the given arguments via MCP.
     * Sends a tools/call JSON-RPC request to the Mapbox MCP server.
     *
     * @param toolName name of the tool to execute
     * @param args     JSON object containing the tool arguments
     * @return result as a string from the MCP server
     */
    public String execute(String toolName, JSONObject args) {
        if (!initialized || !mcpClient.isConnected()) {
            return "{\"error\": \"MCP not connected\"}";
        }

        try {
            return mcpClient.callTool(toolName, args);
        } catch (Exception e) {
            Log.e(TAG, "Error executing tool " + toolName + " via MCP", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * disconnect: Disconnect from the Mapbox MCP server.
     */
    public void disconnect() {
        mcpClient.disconnect();
        initialized = false;
    }
}
