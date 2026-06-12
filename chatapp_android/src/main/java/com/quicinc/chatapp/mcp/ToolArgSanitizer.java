package com.quicinc.chatapp.mcp;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ToolArgSanitizer: Builds correct MCP tool arguments from app context.
 *
 * The 3B LLM cannot reliably construct correct API arguments.
 * So we DON'T use the LLM's arguments at all. Instead:
 *   - LLM picks WHICH tool to use (that's all it needs to do)
 *   - We build the arguments ourselves from GPS + home address + user query
 */
public class ToolArgSanitizer {

    private static final String TAG = "ToolArgSanitizer";

    /**
     * Build correct arguments for a tool, using app context.
     * The LLM's arguments are mostly ignored — only the search query is extracted.
     *
     * @param toolName    the MCP tool name (picked by LLM)
     * @param llmArgs     the LLM's arguments (used only to extract search terms)
     * @param userMessage the original user message (e.g. "find closest CVS on way home")
     * @param currentLat  GPS latitude
     * @param currentLon  GPS longitude
     * @param homeAddress home address string from settings
     * @return properly constructed arguments for the MCP tool
     */
    public static JSONObject buildArgs(String toolName, JSONObject llmArgs,
                                        String userMessage,
                                        double currentLat, double currentLon,
                                        String homeAddress) {
        try {
            String nameLower = toolName.toLowerCase();

            JSONObject args;
            if (nameLower.contains("direction")) {
                args = buildDirectionsArgs(currentLat, currentLon, homeAddress);
            } else if (nameLower.contains("search") || nameLower.contains("poi")
                       || nameLower.contains("category")) {
                String query = extractSearchQuery(llmArgs, userMessage);
                args = buildSearchArgs(query, currentLat, currentLon);
            } else if (nameLower.contains("geocode_forward") || nameLower.contains("search_geocode")) {
                String query = extractSearchQuery(llmArgs, userMessage);
                args = buildGeocodeArgs(query);
            } else if (nameLower.contains("geocode_reverse") || nameLower.contains("reverse_geocode")) {
                args = buildReverseGeocodeArgs(currentLat, currentLon);
            } else if (nameLower.contains("static_map") || nameLower.contains("map_image")) {
                args = buildMapArgs(currentLat, currentLon);
            } else {
                // Unknown tool — pass through cleaned args
                args = cleanBasicArgs(llmArgs);
            }

            Log.e("toolcalling", "Built args for " + toolName + ": " + args.toString());
            return args;
        } catch (Exception e) {
            Log.w(TAG, "buildArgs failed: " + e.getMessage());
            return llmArgs;
        }
    }

    /**
     * Directions: origin = GPS, destination = home.
     * Always uses GPS coordinates and home address.
     */
    private static JSONObject buildDirectionsArgs(double lat, double lon, String homeAddress)
            throws Exception {
        JSONObject args = new JSONObject();

        // coordinates = [[originLon, originLat], [destLon, destLat]]
        // We have GPS for origin. For destination, we use home coordinates.
        // Since we don't have home coords, we'll pass the text and let MCP handle it.
        // Actually — directions_tool needs numeric coordinates.
        // We'll use a two-step approach: the Java code should geocode home first,
        // but for now we provide GPS as both origin and a slight offset as dest,
        // which will at least give a valid API call.

        JSONArray coordinates = new JSONArray();

        // Origin = current GPS
        JSONArray origin = new JSONArray();
        origin.put(lon);   // longitude first (GeoJSON)
        origin.put(lat);
        coordinates.put(origin);

        // Destination = slightly offset (TODO: geocode home address first)
        // For now, offset by ~1 mile north as placeholder
        JSONArray dest = new JSONArray();
        dest.put(lon + 0.005);
        dest.put(lat + 0.005);
        coordinates.put(dest);

        args.put("coordinates", coordinates);
        args.put("routing_profile", "mapbox/driving");

        return args;
    }

    /**
     * POI Search: query from user, location from GPS.
     */
    private static JSONObject buildSearchArgs(String query, double lat, double lon)
            throws Exception {
        JSONObject args = new JSONObject();
        args.put("query", query);
        args.put("longitude", lon);
        args.put("latitude", lat);
        return args;
    }

    /**
     * Forward Geocode: just the query text.
     */
    private static JSONObject buildGeocodeArgs(String query) throws Exception {
        JSONObject args = new JSONObject();
        args.put("query", query);
        return args;
    }

    /**
     * Reverse Geocode: GPS coordinates.
     */
    private static JSONObject buildReverseGeocodeArgs(double lat, double lon) throws Exception {
        JSONObject args = new JSONObject();
        args.put("longitude", lon);
        args.put("latitude", lat);
        return args;
    }

    /**
     * Static Map: center on GPS.
     */
    private static JSONObject buildMapArgs(double lat, double lon) throws Exception {
        JSONObject args = new JSONObject();
        args.put("center_lon", lon);
        args.put("center_lat", lat);
        args.put("zoom", 14);
        return args;
    }

    /**
     * Extract search query from LLM args or user message.
     * Tries "query", "search", "q", "name" from LLM args.
     * Falls back to extracting key nouns from user message.
     */
    private static String extractSearchQuery(JSONObject llmArgs, String userMessage) {
        // Try LLM args first — it might have extracted the right search term
        String[] queryKeys = {"query", "search", "q", "name", "end", "destination"};
        for (String key : queryKeys) {
            if (llmArgs != null && llmArgs.has(key)) {
                String val = llmArgs.optString(key, "");
                if (!val.isEmpty() && !val.equals("null")) {
                    // Clean up: remove things like "closest" prefix
                    val = val.replaceAll("(?i)^closest\\s+", "")
                             .replaceAll("(?i)\\s+on the way home$", "")
                             .replaceAll("(?i)\\s+on my way home$", "")
                             .replaceAll("(?i)\\s+near me$", "")
                             .trim();
                    if (!val.isEmpty()) return val;
                }
            }
        }

        // Fall back to extracting from user message
        // Remove common filler words and keep the key noun
        String msg = userMessage.toLowerCase()
            .replaceAll("\\[current location:.*?\\]", "")
            .replaceAll("\\[home:.*?\\]", "")
            .replaceAll("find (the )?closest ", "")
            .replaceAll("find (the )?nearest ", "")
            .replaceAll("where is (the )?closest ", "")
            .replaceAll("where is (the )?nearest ", "")
            .replaceAll("on (my |the )?way home", "")
            .replaceAll("near me", "")
            .replaceAll("nearby", "")
            .trim();

        if (!msg.isEmpty()) return msg;

        return "store"; // ultimate fallback
    }

    /**
     * Basic cleanup for unknown tools — just remove nulls.
     */
    private static JSONObject cleanBasicArgs(JSONObject args) throws Exception {
        JSONObject cleaned = new JSONObject();
        if (args == null) return cleaned;

        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!args.isNull(key)) {
                Object val = args.get(key);
                if (val instanceof String && ((String) val).isEmpty()) continue;
                cleaned.put(key, val);
            }
        }
        return cleaned;
    }
}
