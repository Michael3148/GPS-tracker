package com.example.gpstracker;

import android.location.Location;

import androidx.lifecycle.MutableLiveData;

import com.example.gpstracker.model.TrackPoint;

/**
 * Bridges LocationTrackingService (which runs independently of any Activity's
 * lifecycle) to MainActivity's UI. A singleton is used instead of a bound
 * service or LocalBroadcastManager because:
 *  - LiveData is lifecycle-aware: if MainActivity is destroyed (rotation,
 *    backgrounding), we don't leak observers or crash trying to update
 *    destroyed views.
 *  - No broadcast permission/intent-filter setup needed.
 *  - Survives configuration changes automatically since it's not tied to
 *    any Activity instance.
 *
 * NOTE: this does NOT survive process death (app fully killed by the OS).
 * If the service outlives a killed-and-recreated process, this in-memory
 * state resets to zero even though tracking continues. Fixing that properly
 * means persisting running totals to Room or SharedPreferences — worth
 * doing before you rely on this for real multi-hour hikes, flagged here so
 * it doesn't surprise you later.
 */
public class TrackingRepository {

    private static TrackingRepository instance;

    public final MutableLiveData<TrackPoint> latestPoint = new MutableLiveData<>();
    public final MutableLiveData<Double> totalDistanceMeters = new MutableLiveData<>(0.0);
    public final MutableLiveData<Boolean> isTracking = new MutableLiveData<>(false);

    private TrackPoint previousPoint;

    private TrackingRepository() {
    }

    public static synchronized TrackingRepository getInstance() {
        if (instance == null) {
            instance = new TrackingRepository();
        }
        return instance;
    }

    /** Called by LocationTrackingService every time a new fix arrives. */
    public void addPoint(TrackPoint point) {
        latestPoint.postValue(point);

        if (previousPoint != null) {
            float[] results = new float[1];
            Location.distanceBetween(
                    previousPoint.latitude, previousPoint.longitude,
                    point.latitude, point.longitude,
                    results
            );
            // Ignore sub-meter jumps — raw GPS jitter while standing still
            // otherwise silently inflates the distance total over a long
            // session, which is a very common bug in tutorial trackers.
            if (results[0] > 1.0f) {
                double current = totalDistanceMeters.getValue() == null ? 0 : totalDistanceMeters.getValue();
                totalDistanceMeters.postValue(current + results[0]);
            }
        }
        previousPoint = point;
    }

    /** Called when starting a fresh tracking session. */
    public void reset() {
        previousPoint = null;
        totalDistanceMeters.postValue(0.0);
        latestPoint.postValue(null);
    }
}