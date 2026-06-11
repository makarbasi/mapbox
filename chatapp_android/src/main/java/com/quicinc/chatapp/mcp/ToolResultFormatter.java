package com.quicinc.chatapp.mcp;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * ToolResultFormatter: Pre-processes raw MCP tool results into
 * simple text that a small LLM (3B, 2048 context) can understand.
 *
 * Instead of feeding raw JSON to the LLM and asking it to summarize,
 * we extract key information here in Java and give the LLM a short,
 * clear text to base its response on.
 */
public class ToolResultFormatter {

    private static final String TAG = "ToolResultFormatter";

    /**
     * Format a raw tool result into a short text summary.
     *
     * @param toolName the name of the tool that was called
     * @param rawResult the raw result string from MCP
     * @return a short, LLM-friendly text summary (max ~300 chars)
     */
    public static String format(String toolName, String rawResult) {
        try {
            // Try to parse as JSON and extract key info
            String summary = extractSummary(toolName, rawResult);
            if (summary != null && !summary.isEmpty()) {
                return summary;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not parse tool result as JSON", e);
        }

        // Fallback: truncate raw text
        if (rawResult.length() > 300) {
            return rawResult.substring(0, 300) + "...";
        }
        return rawResult;
    }

    private static String extractSummary(String toolName, String raw) throws Exception {
        String nameLower = toolName.toLowerCase();

        // Handle directions results
        if (nameLower.contains("direction")) {
            return extractDirections(raw);
        }

        // Handle POI / search results
        if (nameLower.contains("search") || nameLower.contains("poi") || nameLower.contains("category")) {
            return extractSearchResults(raw);
        }

        // Handle geocode results
        if (nameLower.contains("geocode")) {
            return extractGeocode(raw);
        }

        // Handle map image results
        if (nameLower.contains("map") || nameLower.contains("static")) {
            return extractMapInfo(raw);
        }

        // Generic: try to extract any useful text from JSON
        return extractGeneric(raw);
    }

    private static String extractDirections(String raw) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Route found: ");

        // Try to find distance and duration in the JSON
        if (raw.contains("distance") && raw.contains("duration")) {
            JSONObject json = findObjectWithKey(raw, "distance");
            if (json != null) {
                double distMeters = json.optDouble("distance", 0);
                double durSeconds = json.optDouble("duration", 0);
                double miles = distMeters / 1609.34;
                int minutes = (int) (durSeconds / 60);
                sb.append(String.format("%.1f miles, %d minutes", miles, minutes));
                return sb.toString();
            }
        }

        // Fallback
        return truncate(raw, 300);
    }

    private static String extractSearchResults(String raw) throws Exception {
        StringBuilder sb = new StringBuilder();

        // Try to find features array (GeoJSON style)
        JSONObject json = new JSONObject(raw);
        JSONArray features = findArray(json, "features");
        if (features == null) {
            features = findArray(json, "results");
        }
        if (features == null) {
            // Maybe the result itself is an array
            try {
                features = new JSONArray(raw);
            } catch (Exception e) {
                // not an array
            }
        }

        if (features != null && features.length() > 0) {
            int count = Math.min(features.length(), 3); // Top 3
            sb.append("Found ").append(features.length()).append(" places. Top results:\n");
            for (int i = 0; i < count; i++) {
                JSONObject item = features.getJSONObject(i);
                String name = item.optString("name",
                    item.optString("place_name",
                        item.optString("text", "Unknown")));

                // Try to get address
                String address = item.optString("address", "");
                if (address.isEmpty()) {
                    JSONObject props = item.optJSONObject("properties");
                    if (props != null) {
                        address = props.optString("address",
                            props.optString("full_address", ""));
                    }
                }

                sb.append(i + 1).append(". ").append(name);
                if (!address.isEmpty()) {
                    sb.append(" at ").append(address);
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        }

        return truncate(raw, 300);
    }

    private static String extractGeocode(String raw) throws Exception {
        JSONObject json = new JSONObject(raw);

        // Try standard geocode response
        String placeName = json.optString("place_name", "");
        double lat = json.optDouble("latitude", 0);
        double lon = json.optDouble("longitude", 0);

        if (!placeName.isEmpty()) {
            return String.format("Location: %s (%.4f, %.4f)", placeName, lat, lon);
        }

        // Try features array
        JSONArray features = findArray(json, "features");
        if (features != null && features.length() > 0) {
            JSONObject first = features.getJSONObject(0);
            String name = first.optString("place_name",
                first.optString("text", "Unknown"));
            return "Location: " + name;
        }

        return truncate(raw, 300);
    }

    private static String extractMapInfo(String raw) throws Exception {
        // Map results usually contain a URL
        if (raw.contains("http")) {
            return "Map image generated.";
        }
        return truncate(raw, 200);
    }

    private static String extractGeneric(String raw) {
        // Just truncate for unknown tool types
        return truncate(raw, 300);
    }

    /**
     * Search a JSON string for an object containing a specific key.
     */
    private static JSONObject findObjectWithKey(String raw, String key) {
        try {
            JSONObject json = new JSONObject(raw);
            if (json.has(key)) return json;

            // Look in nested objects
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object val = json.get(k);
                if (val instanceof JSONObject) {
                    JSONObject nested = (JSONObject) val;
                    if (nested.has(key)) return nested;
                } else if (val instanceof JSONArray) {
                    JSONArray arr = (JSONArray) val;
                    for (int i = 0; i < arr.length(); i++) {
                        if (arr.get(i) instanceof JSONObject) {
                            JSONObject item = arr.getJSONObject(i);
                            if (item.has(key)) return item;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Find a JSONArray by key, searching one level deep.
     */
    private static JSONArray findArray(JSONObject json, String key) {
        if (json.has(key)) {
            try { return json.getJSONArray(key); } catch (Exception e) {}
        }
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            try {
                Object val = json.get(k);
                if (val instanceof JSONObject) {
                    JSONObject nested = (JSONObject) val;
                    if (nested.has(key)) {
                        return nested.getJSONArray(key);
                    }
                }
            } catch (Exception e) {}
        }
        return null;
    }

    private static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
