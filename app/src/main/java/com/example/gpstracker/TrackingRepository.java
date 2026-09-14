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

    // Any implied speed above this between two consecutive fixes is treated
    // as a GPS glitch, not real motion — generous enough to cover running or
    // even casual cycling, but catches the "GPS teleport" artifacts common
    // indoors/urban canyons. Raise this if you extend the app to cover
    // driving.
    private static final float MAX_PLAUSIBLE_SPEED_MPS = 15f; // ~54 km/h

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
            float distanceMeters = results[0];

            // 1. Accuracy-aware threshold: a GPS fix's "accuracy" value is
            // the radius (in meters) of the circle the real position is
            // statistically likely to be within. If the reported movement
            // is smaller than the combined uncertainty of both fixes, we
            // genuinely cannot tell it apart from standing still — count
            // it as noise, not distance.
            float noiseFloor = previousPoint.accuracyMeters + point.accuracyMeters;

            // 2. Speed sanity check: reject fixes implying an impossible
            // speed, which is the signature of a GPS "jump" artifact rather
            // than real motion.
            long elapsedMillis = point.timestampMillis - previousPoint.timestampMillis;
            boolean speedPlausible = true;
            if (elapsedMillis > 0) {
                float impliedSpeedMps = distanceMeters / (elapsedMillis / 1000f);
                speedPlausible = impliedSpeedMps <= MAX_PLAUSIBLE_SPEED_MPS;
            }

            if (distanceMeters > noiseFloor && speedPlausible) {
                double current = totalDistanceMeters.getValue() == null ? 0 : totalDistanceMeters.getValue();
                totalDistanceMeters.postValue(current + distanceMeters);
            }
            // else: silently discarded as noise/glitch. The map marker and
            // latestPoint still update above, so the UI position still
            // moves — only the distance TOTAL is protected from inflation.
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