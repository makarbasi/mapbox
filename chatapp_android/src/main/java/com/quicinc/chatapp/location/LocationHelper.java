// ---------------------------------------------------------------------
// ChatApp - Location Helper
// ---------------------------------------------------------------------
package com.quicinc.chatapp.location;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

/**
 * LocationHelper: Simple wrapper for obtaining the device GPS location
 * using Google Play Services FusedLocationProviderClient.
 * Handles permission checking, current location retrieval, and
 * fallback to last known location.
 */
public class LocationHelper {

    private static final String TAG = "LocationHelper";
    public static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    private final FusedLocationProviderClient fusedClient;
    private Location lastLocation;

    /**
     * LocationCallback: Interface for receiving location results asynchronously
     */
    public interface LocationCallback {
        /**
         * Called when a location has been successfully obtained
         *
         * @param location the device location
         */
        void onLocationReceived(Location location);

        /**
         * Called when location retrieval fails
         *
         * @param error description of the error
         */
        void onLocationError(String error);
    }

    /**
     * LocationHelper: Creates a new helper bound to the given Activity
     *
     * @param activity the activity to use for location services
     */
    public LocationHelper(Activity activity) {
        fusedClient = LocationServices.getFusedLocationProviderClient(activity);
    }

    /**
     * hasLocationPermission: Checks whether fine location permission is granted
     *
     * @param activity the activity to check permissions against
     * @return true if ACCESS_FINE_LOCATION is granted
     */
    public static boolean hasLocationPermission(Activity activity) {
        return ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * requestLocationPermission: Requests fine and coarse location permissions
     *
     * @param activity the activity to request permissions from
     */
    public static void requestLocationPermission(Activity activity) {
        ActivityCompat.requestPermissions(activity,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                LOCATION_PERMISSION_REQUEST_CODE);
    }

    /**
     * getCurrentLocation: Gets the current device location with high accuracy.
     * Falls back to last known location if the current location is null.
     *
     * @param activity the activity context
     * @param callback callback to receive the location or error
     */
    public void getCurrentLocation(Activity activity, LocationCallback callback) {
        if (!hasLocationPermission(activity)) {
            callback.onLocationError("Location permission not granted");
            return;
        }
        try {
            CancellationTokenSource cts = new CancellationTokenSource();
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            lastLocation = location;
                            callback.onLocationReceived(location);
                        } else {
                            // Fall back to last known location
                            getLastKnownLocation(activity, callback);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to get current location", e);
                        callback.onLocationError(e.getMessage());
                    });
        } catch (SecurityException e) {
            callback.onLocationError("Security exception: " + e.getMessage());
        }
    }

    /**
     * getLastKnownLocation: Gets the last known device location
     *
     * @param activity the activity context
     * @param callback callback to receive the location or error
     */
    public void getLastKnownLocation(Activity activity, LocationCallback callback) {
        if (!hasLocationPermission(activity)) {
            callback.onLocationError("Location permission not granted");
            return;
        }
        try {
            fusedClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            lastLocation = location;
                            callback.onLocationReceived(location);
                        } else {
                            callback.onLocationError("No location available");
                        }
                    })
                    .addOnFailureListener(e -> callback.onLocationError(e.getMessage()));
        } catch (SecurityException e) {
            callback.onLocationError("Security exception: " + e.getMessage());
        }
    }

    /**
     * getLastLocation: Returns the most recently cached location, if any
     *
     * @return the last obtained Location, or null if none available
     */
    public Location getLastLocation() {
        return lastLocation;
    }
}
