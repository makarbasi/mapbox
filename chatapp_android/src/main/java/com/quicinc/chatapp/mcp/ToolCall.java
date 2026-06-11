// ---------------------------------------------------------------------
// ChatApp - Tool Call Data Class
// ---------------------------------------------------------------------
package com.quicinc.chatapp.mcp;

import org.json.JSONObject;

/**
 * ToolCall: Simple data class representing a parsed tool invocation
 * extracted from LLM output. Holds the tool name and its arguments.
 */
public class ToolCall {

    private final String toolName;
    private final JSONObject arguments;

    /**
     * ToolCall: Creates a new tool call instance
     *
     * @param toolName  name of the tool to invoke
     * @param arguments JSON object containing the tool arguments
     */
    public ToolCall(String toolName, JSONObject arguments) {
        this.toolName = toolName;
        this.arguments = arguments;
    }

    /**
     * getToolName: Returns the name of the tool
     *
     * @return tool name string
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * getArguments: Returns the tool arguments as a JSONObject
     *
     * @return arguments JSON object
     */
    public JSONObject getArguments() {
        return arguments;
    }
}
