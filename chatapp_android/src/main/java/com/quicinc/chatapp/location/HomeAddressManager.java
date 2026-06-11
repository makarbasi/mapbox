// ---------------------------------------------------------------------
// ChatApp - Home Address Manager
// ---------------------------------------------------------------------
package com.quicinc.chatapp.location;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * HomeAddressManager: Manages persistent storage of the user's home address,
 * home coordinates, and Mapbox access token using SharedPreferences.
 */
public class HomeAddressManager {

    private static final String PREFS_NAME = "mapbox_settings";
    private static final String KEY_HOME_ADDRESS = "home_address";
    private static final String KEY_HOME_LAT = "home_lat";
    private static final String KEY_HOME_LON = "home_lon";
    private static final String KEY_MAPBOX_TOKEN = "mapbox_token";

    /**
     * setHomeAddress: Saves the user's home address string
     *
     * @param context application or activity context
     * @param address the home address to save
     */
    public static void setHomeAddress(Context context, String address) {
        getPrefs(context).edit().putString(KEY_HOME_ADDRESS, address).apply();
    }

    /**
     * getHomeAddress: Retrieves the saved home address
     *
     * @param context application or activity context
     * @return the saved home address, or null if not set
     */
    public static String getHomeAddress(Context context) {
        return getPrefs(context).getString(KEY_HOME_ADDRESS, null);
    }

    /**
     * setHomeCoordinates: Saves the home latitude and longitude
     *
     * @param context application or activity context
     * @param lat     home latitude
     * @param lon     home longitude
     */
    public static void setHomeCoordinates(Context context, double lat, double lon) {
        getPrefs(context).edit()
                .putFloat(KEY_HOME_LAT, (float) lat)
                .putFloat(KEY_HOME_LON, (float) lon)
                .apply();
    }

    /**
     * getHomeCoordinates: Retrieves the saved home coordinates
     *
     * @param context application or activity context
     * @return double array [lat, lon], or null if not set
     */
    public static double[] getHomeCoordinates(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (!prefs.contains(KEY_HOME_LAT)) {
            return null;
        }
        return new double[]{
                prefs.getFloat(KEY_HOME_LAT, 0),
                prefs.getFloat(KEY_HOME_LON, 0)
        };
    }

    /**
     * setMapboxToken: Saves the Mapbox API access token
     *
     * @param context application or activity context
     * @param token   Mapbox access token
     */
    public static void setMapboxToken(Context context, String token) {
        getPrefs(context).edit().putString(KEY_MAPBOX_TOKEN, token).apply();
    }

    /**
     * getMapboxToken: Retrieves the saved Mapbox access token
     *
     * @param context application or activity context
     * @return the saved token, or null if not set
     */
    public static String getMapboxToken(Context context) {
        return getPrefs(context).getString(KEY_MAPBOX_TOKEN, null);
    }

    /**
     * isConfigured: Checks whether a Mapbox token has been saved
     *
     * @param context application or activity context
     * @return true if a non-empty Mapbox token is stored
     */
    public static boolean isConfigured(Context context) {
        String token = getMapboxToken(context);
        return token != null && !token.isEmpty();
    }

    /**
     * getPrefs: Returns the SharedPreferences instance for Mapbox settings
     *
     * @param context application or activity context
     * @return SharedPreferences instance
     */
    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
