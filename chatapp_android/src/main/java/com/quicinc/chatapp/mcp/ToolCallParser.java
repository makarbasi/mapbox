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

    // Known tool names from the Mapbox MCP server (verified via MCP Inspector).
    private static final String[] KNOWN_TOOLS = {
        "ground_location_tool",
        "search_and_geocode_tool",
        "category_search_tool",
        "directions_tool",
        "reverse_geocode_tool",
        "static_map_image_tool",
        "isochrone_tool",
        "distance_tool",
        "nearest_point_tool"
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
                // Tool name found — check if it looks like a call (not just a mention)
                if (lower.contains("\"name\"") || lower.contains("<" + tool.toLowerCase())
                    || lower.contains("arguments")
                    || lower.contains("[" + tool.toLowerCase() + "(")
                    || lower.contains(tool.toLowerCase() + "(")) {
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

        // Strategy 0: Bracket function-call syntax [tool_name(key=val, ...)]
        // The 3B model often outputs this Python-like format instead of XML/JSON.
        ToolCall result = parseBracketFunctionCall(llmOutput);
        if (result != null) return result;

        // Strategy 1: Standard <tool_call>{json}</tool_call>
        result = parseStandardTags(llmOutput);
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
     * Strategy 0: Parse bracket function-call syntax.
     * Handles formats like:
     *   [category_search_tool(category="pharmacy", limit=5, ...)]
     *   [search_and_geocode_tool(query="Starbucks", longitude=-117.16)]
     *
     * The 3B model commonly uses this format, ignoring the XML tag instructions.
     */
    private static ToolCall parseBracketFunctionCall(String output) {
        for (String tool : KNOWN_TOOLS) {
            // Match: [tool_name( or tool_name( — with or without brackets
            String lowerOutput = output.toLowerCase();
            String lowerTool = tool.toLowerCase();

            int toolIdx = lowerOutput.indexOf(lowerTool + "(");
            if (toolIdx < 0) continue;

            // Find the opening ( after the tool name
            int parenStart = output.indexOf("(", toolIdx);
            if (parenStart < 0) continue;

            // Find the matching closing )
            int parenEnd = findMatchingParen(output, parenStart);
            if (parenEnd < 0) parenEnd = output.length() - 1;

            String argsStr = output.substring(parenStart + 1, parenEnd).trim();
            Log.i(TAG, "Bracket call found: " + tool + ", raw args: " + argsStr);

            // Parse key=value pairs into a JSONObject
            JSONObject arguments = parseKeyValueArgs(argsStr);

            Log.e("toolcalling", "Parsed (strategy 0 bracket): " + tool + " args=" + arguments.toString());
            return new ToolCall(tool, arguments);
        }
        return null;
    }

    /**
     * Find the closing parenthesis matching the one at openIdx.
     */
    private static int findMatchingParen(String text, int openIdx) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            }
            if (!inQuote) {
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Parse key=value argument pairs from a function-call string.
     * Handles: category="pharmacy", limit=5, proximity="home"
     * Also handles bracket values like bbox=[-117.16, 32.71, ...]
     */
    private static JSONObject parseKeyValueArgs(String argsStr) {
        JSONObject args = new JSONObject();
        if (argsStr == null || argsStr.isEmpty()) return args;

        try {
            // Split on commas, but not commas inside quotes or brackets
            int depth = 0;
            boolean inQuote = false;
            StringBuilder current = new StringBuilder();

            for (int i = 0; i < argsStr.length(); i++) {
                char c = argsStr.charAt(i);
                if (c == '"' && (i == 0 || argsStr.charAt(i - 1) != '\\')) {
                    inQuote = !inQuote;
                }
                if (!inQuote) {
                    if (c == '[' || c == '(') depth++;
                    else if (c == ']' || c == ')') depth--;
                }

                if (c == ',' && depth == 0 && !inQuote) {
                    addKeyValue(args, current.toString().trim());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            // Last pair
            if (current.length() > 0) {
                addKeyValue(args, current.toString().trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing key=value args: " + e.getMessage());
        }
        return args;
    }

    /**
     * Parse a single key=value pair and add to JSONObject.
     */
    private static void addKeyValue(JSONObject args, String pair) {
        int eq = pair.indexOf('=');
        if (eq < 0) return;

        String key = pair.substring(0, eq).trim();
        String val = pair.substring(eq + 1).trim();

        try {
            // Remove surrounding quotes
            if (val.startsWith("\"") && val.endsWith("\"")) {
                args.put(key, val.substring(1, val.length() - 1));
            } else if (val.startsWith("[")) {
                // Array — try to parse as JSONArray
                args.put(key, new org.json.JSONArray(val));
            } else if (val.equalsIgnoreCase("true") || val.equalsIgnoreCase("false")) {
                args.put(key, Boolean.parseBoolean(val));
            } else {
                // Try number
                try {
                    if (val.contains(".")) {
                        args.put(key, Double.parseDouble(val));
                    } else {
                        args.put(key, Integer.parseInt(val));
                    }
                } catch (NumberFormatException e) {
                    args.put(key, val);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to add key=" + key + " val=" + val);
        }
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

    // Note: parseBracketFunctionCall, findMatchingParen, parseKeyValueArgs,
    // and addKeyValue are defined above parse() as Strategy 0.

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
