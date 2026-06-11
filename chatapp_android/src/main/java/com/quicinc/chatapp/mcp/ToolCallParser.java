// ---------------------------------------------------------------------
// ChatApp - Tool Call Parser
// ---------------------------------------------------------------------
package com.quicinc.chatapp.mcp;

import android.util.Log;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ToolCallParser: Parses LLM output for tool calls.
 *
 * The 3B model doesn't reliably follow a specific format.
 * It may generate tool calls as:
 *   - <tool_call>{"name":"X","arguments":{...}}</tool_call>   (what we ask for)
 *   - <directions_tool>{"name":"X","arguments":{...}}</directions_tool>  (tool name as tag)
 *   - <directions_tool>\n"name": "X",\n"arguments": {...}\n</directions_tool>  (loose JSON)
 *
 * This parser handles all three formats.
 */
public class ToolCallParser {

    private static final String TAG = "ToolCallParser";

    // Pattern 1: <tool_call>...</tool_call>
    private static final String TOOL_CALL_START = "<tool_call>";
    private static final String TOOL_CALL_END = "</tool_call>";

    // Pattern 2: <any_tool_name>...</any_tool_name> — regex to find XML-like tags ending with _tool
    // Matches: <directions_tool>...</directions_tool>, <search_poi_tool>...</search_poi_tool>, etc.
    private static final Pattern TOOL_TAG_PATTERN = Pattern.compile(
        "<(\\w+_tool)>(.*?)</\\1>",
        Pattern.DOTALL
    );

    // Also match tags without _tool suffix, like <directions>...</directions>
    private static final Pattern GENERIC_TAG_PATTERN = Pattern.compile(
        "<(\\w+)>\\s*\\{?\\s*\"name\"\\s*:\\s*\"(\\w+)\".*?</\\1>",
        Pattern.DOTALL
    );

    /**
     * hasToolCall: Checks whether the LLM output contains any recognizable tool call.
     */
    public static boolean hasToolCall(String llmOutput) {
        if (llmOutput == null || llmOutput.isEmpty()) return false;

        // Check standard format
        if (llmOutput.contains(TOOL_CALL_START) && llmOutput.contains(TOOL_CALL_END)) {
            return true;
        }

        // Check <toolname>...</toolname> format
        Matcher m = TOOL_TAG_PATTERN.matcher(llmOutput);
        if (m.find()) return true;

        // Check generic tag with "name" field inside
        m = GENERIC_TAG_PATTERN.matcher(llmOutput);
        return m.find();
    }

    /**
     * parse: Extracts and parses a tool call from LLM output.
     * Handles multiple formats the 3B model may generate.
     *
     * @param llmOutput raw text output from the LLM
     * @return a ToolCall instance, or null if parsing fails
     */
    public static ToolCall parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isEmpty()) return null;

        // Try standard <tool_call> format first
        ToolCall result = parseStandardFormat(llmOutput);
        if (result != null) return result;

        // Try <toolname>...</toolname> format
        result = parseToolNameTagFormat(llmOutput);
        if (result != null) return result;

        Log.w(TAG, "No parseable tool call found in LLM output");
        return null;
    }

    /**
     * Parse standard format: <tool_call>{"name":"X","arguments":{...}}</tool_call>
     */
    private static ToolCall parseStandardFormat(String llmOutput) {
        if (!llmOutput.contains(TOOL_CALL_START) || !llmOutput.contains(TOOL_CALL_END)) {
            return null;
        }

        try {
            int startIndex = llmOutput.lastIndexOf(TOOL_CALL_START);
            int endIndex = llmOutput.indexOf(TOOL_CALL_END, startIndex);
            if (startIndex < 0 || endIndex < 0) return null;

            String jsonContent = llmOutput.substring(
                startIndex + TOOL_CALL_START.length(), endIndex).trim();
            return parseJsonContent(jsonContent, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse standard tool_call format: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parse tool-name-as-tag format: <directions_tool>...</directions_tool>
     * The LLM uses the tool name itself as the XML tag.
     */
    private static ToolCall parseToolNameTagFormat(String llmOutput) {
        try {
            Matcher m = TOOL_TAG_PATTERN.matcher(llmOutput);
            String tagName = null;
            String content = null;

            // Find the last match
            while (m.find()) {
                tagName = m.group(1);
                content = m.group(2);
            }

            if (tagName == null || content == null) {
                // Try broader pattern for non _tool suffixed tags
                m = GENERIC_TAG_PATTERN.matcher(llmOutput);
                while (m.find()) {
                    tagName = m.group(1);
                    content = m.group(0);
                    // Extract content between tags
                    int start = content.indexOf(">") + 1;
                    int end = content.lastIndexOf("<");
                    if (start > 0 && end > start) {
                        content = content.substring(start, end);
                    }
                }
            }

            if (tagName == null || content == null) return null;

            Log.i(TAG, "Found tool tag: <" + tagName + ">, content length: " + content.length());
            return parseJsonContent(content.trim(), tagName);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse tool-name-tag format: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parse JSON content from inside a tool call tag.
     * Handles both strict JSON and loose "key": "value" format.
     *
     * @param content the text between the tags
     * @param fallbackToolName if the JSON doesn't have "name", use this (the tag name)
     */
    private static ToolCall parseJsonContent(String content, String fallbackToolName) {
        try {
            // Ensure content looks like JSON — wrap in braces if needed
            String jsonStr = content.trim();
            if (!jsonStr.startsWith("{")) {
                jsonStr = "{" + jsonStr;
            }

            // Balance braces: count { and } and add missing closing braces
            int openCount = 0;
            int closeCount = 0;
            for (char c : jsonStr.toCharArray()) {
                if (c == '{') openCount++;
                else if (c == '}') closeCount++;
            }
            while (closeCount < openCount) {
                jsonStr = jsonStr + "}";
                closeCount++;
            }

            Log.i(TAG, "Attempting JSON parse (" + jsonStr.length() + " chars)");
            JSONObject json = new JSONObject(jsonStr);

            // Get tool name — from JSON "name" field or from the tag name
            String name = json.optString("name", "");
            if (name.isEmpty() && fallbackToolName != null) {
                name = fallbackToolName;
            }
            if (name.isEmpty()) {
                Log.w(TAG, "No tool name found in JSON or tag");
                return null;
            }

            // Get arguments
            JSONObject arguments = json.optJSONObject("arguments");
            if (arguments == null) {
                // Maybe all the fields ARE the arguments (no nested "arguments" key)
                // Remove "name" and treat the rest as arguments
                arguments = new JSONObject(jsonStr);
                arguments.remove("name");
            }

            Log.e("toolcalling", "Parsed tool call: " + name + " with args: " + arguments.toString());
            return new ToolCall(name, arguments);
        } catch (Exception e) {
            Log.w(TAG, "JSON parse failed: " + e.getMessage() + " | content: " + content.substring(0, Math.min(100, content.length())));
            return null;
        }
    }
}
