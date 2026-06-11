// ---------------------------------------------------------------------
// ChatApp - Tool Call Parser
// ---------------------------------------------------------------------
package com.quicinc.chatapp.mcp;

import android.util.Log;

import org.json.JSONObject;

/**
 * ToolCallParser: Static utility class that parses LLM output for tool calls.
 * Looks for &lt;tool_call&gt;...&lt;/tool_call&gt; XML tags containing JSON
 * with "name" and "arguments" fields.
 */
public class ToolCallParser {

    private static final String TAG = "ToolCallParser";
    private static final String TOOL_CALL_START = "<tool_call>";
    private static final String TOOL_CALL_END = "</tool_call>";

    /**
     * hasToolCall: Checks whether the LLM output contains a tool call
     *
     * @param llmOutput raw text output from the LLM
     * @return true if the output contains both start and end tool call tags
     */
    public static boolean hasToolCall(String llmOutput) {
        return llmOutput != null
                && llmOutput.contains(TOOL_CALL_START)
                && llmOutput.contains(TOOL_CALL_END);
    }

    /**
     * parse: Extracts and parses the last tool call from LLM output.
     * Finds the last occurrence of &lt;tool_call&gt;...&lt;/tool_call&gt;,
     * extracts the JSON between the tags, and parses the "name" and
     * "arguments" fields.
     *
     * @param llmOutput raw text output from the LLM
     * @return a ToolCall instance, or null if parsing fails
     */
    public static ToolCall parse(String llmOutput) {
        if (!hasToolCall(llmOutput)) {
            return null;
        }

        try {
            // Find the LAST occurrence of the tool call tags
            int startIndex = llmOutput.lastIndexOf(TOOL_CALL_START);
            int endIndex = llmOutput.indexOf(TOOL_CALL_END, startIndex);

            if (startIndex < 0 || endIndex < 0) {
                Log.w(TAG, "Could not locate tool call tags");
                return null;
            }

            // Extract JSON content between the tags
            String jsonContent = llmOutput.substring(
                    startIndex + TOOL_CALL_START.length(), endIndex).trim();

            // Parse the JSON
            JSONObject toolCallJson = new JSONObject(jsonContent);

            String name = toolCallJson.getString("name");
            JSONObject arguments = toolCallJson.optJSONObject("arguments");
            if (arguments == null) {
                arguments = new JSONObject();
            }

            return new ToolCall(name, arguments);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse tool call from LLM output: " + e.getMessage());
            return null;
        }
    }
}
