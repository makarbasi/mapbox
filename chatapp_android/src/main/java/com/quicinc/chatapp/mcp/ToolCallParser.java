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
 * The 3B model is unreliable with structured output. It may:
 *   - Use <tool_call>...</tool_call> tags
 *   - Use <tool_name>...</tool_name> tags (sometimes without closing >)
 *   - Miss closing tags entirely
 *   - Output loose JSON with "name" and "arguments" fields
 *
 * This parser doesn't rely on tags at all. Instead, it looks for
 * JSON patterns containing "name" and "arguments" fields.
 */
public class ToolCallParser {

    private static final String TAG = "ToolCallParser";

    // Known tool name suffixes/patterns the LLM might reference
    private static final String[] KNOWN_TOOLS = {
        "directions_tool", "directions",
        "geocode_forward_tool", "search_geocode",
        "geocode_reverse_tool", "reverse_geocode",
        "search_poi_tool", "category_search",
        "static_map_image_tool", "static_map"
    };

    /**
     * hasToolCall: Checks whether the LLM output contains any tool call.
     * Looks for known tool names OR "name" + "arguments" JSON pattern.
     */
    public static boolean hasToolCall(String llmOutput) {
        if (llmOutput == null || llmOutput.isEmpty()) return false;

        String lower = llmOutput.toLowerCase();

        // Check for any known tool name in the output
        for (String tool : KNOWN_TOOLS) {
            if (lower.contains(tool.toLowerCase())) {
                // Also make sure it looks like a tool call, not just a mention
                if (lower.contains("\"name\"") || lower.contains("<" + tool.toLowerCase())
                    || lower.contains("arguments")) {
                    return true;
                }
            }
        }

        // Check for generic tool_call tags
        if (lower.contains("<tool_call>")) return true;

        return false;
    }

    /**
     * parse: Extracts a tool call from LLM output.
     * Tries multiple strategies in order of reliability.
     */
    public static ToolCall parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isEmpty()) return null;

        // Strategy 1: Standard <tool_call>{json}</tool_call>
        ToolCall result = parseStandardTags(llmOutput);
        if (result != null) return result;

        // Strategy 2: Find "name" field and extract tool name + arguments
        result = parseJsonPattern(llmOutput);
        if (result != null) return result;

        // Strategy 3: Find <toolname> tag (with or without closing >)
        result = parseToolNameTag(llmOutput);
        if (result != null) return result;

        Log.w(TAG, "No parseable tool call found");
        return null;
    }

    /**
     * Strategy 1: <tool_call>{"name":"X","arguments":{...}}</tool_call>
     */
    private static ToolCall parseStandardTags(String output) {
        int start = output.lastIndexOf("<tool_call>");
        if (start < 0) return null;
        int contentStart = start + "<tool_call>".length();

        int end = output.indexOf("</tool_call>", contentStart);
        if (end < 0) end = output.length(); // No closing tag — take rest

        String json = output.substring(contentStart, end).trim();
        return parseJsonContent(json, null);
    }

    /**
     * Strategy 2: Look for "name" : "tool_name" pattern anywhere in the output.
     * Then try to extract "arguments" object.
     */
    private static ToolCall parseJsonPattern(String output) {
        // Find "name" : "some_tool_name"
        Pattern namePattern = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
        );
        Matcher nameMatcher = namePattern.matcher(output);
        if (!nameMatcher.find()) return null;

        String toolName = nameMatcher.group(1).trim();
        // Normalize: "Directions Tool" → "directions_tool"
        toolName = normalizeToolName(toolName);

        Log.i(TAG, "Found tool name via JSON pattern: " + toolName);

        // Try to extract "arguments" : { ... }
        int argsStart = output.indexOf("\"arguments\"", nameMatcher.end());
        if (argsStart < 0) {
            argsStart = output.indexOf("\"arguments\"");
        }

        JSONObject arguments = new JSONObject();
        if (argsStart >= 0) {
            // Find the opening { after "arguments" :
            int braceStart = output.indexOf("{", argsStart);
            if (braceStart >= 0) {
                // Find matching closing brace
                String argsJson = extractBalancedBraces(output, braceStart);
                if (argsJson != null) {
                    try {
                        arguments = new JSONObject(argsJson);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse arguments JSON: " + e.getMessage());
                    }
                }
            }
        }

        Log.e("toolcalling", "Parsed (strategy 2): " + toolName + " args=" + arguments.toString());
        return new ToolCall(toolName, arguments);
    }

    /**
     * Strategy 3: Find <toolname or <toolname> tag and extract content.
     */
    private static ToolCall parseToolNameTag(String output) {
        for (String tool : KNOWN_TOOLS) {
            String tagOpen = "<" + tool;
            int idx = output.toLowerCase().lastIndexOf(tagOpen.toLowerCase());
            if (idx < 0) continue;

            // Skip past the tag (may or may not have closing >)
            int contentStart = output.indexOf(">", idx);
            if (contentStart < 0) {
                // No > found — tag is malformed, skip past tag name
                contentStart = idx + tagOpen.length();
            } else {
                contentStart++; // skip the >
            }

            // Find closing tag if it exists
            String tagClose = "</" + tool + ">";
            int contentEnd = output.indexOf(tagClose, contentStart);
            if (contentEnd < 0) contentEnd = output.length();

            String content = output.substring(contentStart, contentEnd).trim();
            if (content.isEmpty()) continue;

            Log.i(TAG, "Found tool tag <" + tool + ">, content: " + content.length() + " chars");
            ToolCall result = parseJsonContent(content, tool);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Parse JSON content, handling missing braces.
     */
    private static ToolCall parseJsonContent(String content, String fallbackName) {
        try {
            String jsonStr = content.trim();

            // Wrap in braces if needed
            if (!jsonStr.startsWith("{")) {
                jsonStr = "{" + jsonStr;
            }

            // Balance braces
            int open = 0, close = 0;
            for (char c : jsonStr.toCharArray()) {
                if (c == '{') open++;
                else if (c == '}') close++;
            }
            while (close < open) {
                jsonStr += "}";
                close++;
            }

            JSONObject json = new JSONObject(jsonStr);

            String name = json.optString("name", "");
            if (name.isEmpty() && fallbackName != null) {
                name = fallbackName;
            }
            name = normalizeToolName(name);

            if (name.isEmpty()) return null;

            JSONObject arguments = json.optJSONObject("arguments");
            if (arguments == null) {
                arguments = new JSONObject(jsonStr);
                arguments.remove("name");
            }

            Log.e("toolcalling", "Parsed tool: " + name + " args=" + arguments.toString());
            return new ToolCall(name, arguments);
        } catch (Exception e) {
            Log.w(TAG, "JSON parse failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract a balanced {...} substring starting at braceStart.
     */
    private static String extractBalancedBraces(String text, int braceStart) {
        int depth = 0;
        for (int i = braceStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth == 0) {
                return text.substring(braceStart, i + 1);
            }
        }
        // Unbalanced — return what we have and close it
        return text.substring(braceStart) + "}";
    }

    /**
     * Normalize tool names the LLM might generate.
     * "Directions Tool" → "directions_tool"
     * "directions" → "directions_tool" (if not already a known tool)
     */
    private static String normalizeToolName(String name) {
        if (name == null || name.isEmpty()) return "";

        // Replace spaces with underscores, lowercase
        String normalized = name.toLowerCase().trim().replaceAll("\\s+", "_");

        // Check if it's already a known tool
        for (String tool : KNOWN_TOOLS) {
            if (tool.equalsIgnoreCase(normalized)) return tool;
        }

        // Try adding _tool suffix
        String withSuffix = normalized + "_tool";
        for (String tool : KNOWN_TOOLS) {
            if (tool.equalsIgnoreCase(withSuffix)) return tool;
        }

        // Return as-is
        return normalized;
    }
}
