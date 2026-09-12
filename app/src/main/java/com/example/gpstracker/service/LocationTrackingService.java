package com.example.gpstracker.service;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Html;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.example.gpstracker.model.TrackPoint;
import com.example.gpstracker.TrackingRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * The service that actually keeps GPS alive while the app is backgrounded,
 * Doze kicks in, or the user swipes the app out of Recents.
 *
 * Three separate survival problems are handled here, and they are NOT the
 * same problem even though tutorials often conflate them:
 *
 *   1. Stock Android Doze / App Standby  -> solved by being a proper
 *      foreground service with a visible notification (Doze mostly leaves
 *      FGS alone, that's the whole point of the API).
 *
 *   2. The user (or the system under memory pressure) swipes the app away
 *      from Recents -> onTaskRemoved() fires, and on MANY OEM skins
 *      (including Samsung One UI on budget devices like the A07) this is
 *      treated as "the user wants this dead" and the process is killed
 *      outright, foreground service or not. We fight this with a short
 *      self-rescheduling alarm.
 *
 *   3. OEM battery managers (One UI's "Sleeping apps" / "Deep sleeping
 *      apps", MIUI's battery saver, etc.) which sit ABOVE stock Android and
 *      kill or freeze processes based on vendor heuristics Google doesn't
 *      control. No code-only trick reliably solves this — it requires the
 *      user to allow-list the app, which is Layer 3 (a later message).
 */
public class LocationTrackingService extends Service {

    private static final String TAG = "LocationTrackingSvc";

    public static final String ACTION_START = "com.example.gpstracker.action.START";
    public static final String ACTION_STOP = "com.example.gpstracker.action.STOP";

    private static final String CHANNEL_ID = "tracking_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Battery-vs-accuracy tuning lives here, in one place, on purpose.
    // These are the knobs you'll actually want to expose as user settings later
    // (e.g. a "battery saver" toggle for long hikes).
    private static final long UPDATE_INTERVAL_MS = 5_000L;       // desired cadence
    private static final long MIN_UPDATE_INTERVAL_MS = 3_000L;   // fastest we'll accept
    private static final long MAX_UPDATE_DELAY_MS = 15_000L;     // batching window (see below)

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private PowerManager.WakeLock wakeLock;

    private final List<TrackPoint> sessionPoints = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopTracking();
            return START_NOT_STICKY;
        }

        // Default / ACTION_START: (re)start tracking.
        // startForeground() must be called within a few ms of the service
        // starting on API 26+, so we do it unconditionally here before any
        // other setup work, even if location updates fail to register.
        startForeground(NOTIFICATION_ID, buildNotification());
        TrackingRepository.getInstance().reset();
        TrackingRepository.getInstance().isTracking.postValue(true);
        startLocationUpdates();

        // START_STICKY: if the system kills us purely for memory (not an
        // explicit user swipe-away), recreate the service with a null intent
        // and let onStartCommand's default branch above restart tracking.
        // This does NOT protect against onTaskRemoved-triggered kills on
        // aggressive OEMs — that's handled separately below.
        return START_STICKY;
    }

    private void startLocationUpdates() {
        if (locationCallback != null) {
            // already running, avoid double-registering
            return;
        }

        // Defensive check: even though MainActivity will request this
        // permission before ever starting this service, the OS or user can
        // revoke it mid-session (Settings -> Apps -> Permissions), and the
        // compiler enforces this check statically because of the
        // @RequiresPermission annotation on requestLocationUpdates().
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission missing, stopping service.");
            stopSelf();
            return;
        }

        LocationRequest locationRequest =
                new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                        .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
                        // setMaxUpdateDelayMillis enables "batching": the GPS chip
                        // can buffer several fixes in hardware and deliver them in
                        // one burst instead of waking the main CPU for every single
                        // fix. This is one of the single biggest battery wins
                        // available and is almost never mentioned in tutorials.
                        .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MS)
                        .setWaitForAccurateLocation(false)
                        .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;

                // Briefly hold a partial wake lock while we process/persist a
                // batch of fixes, so the CPU doesn't slip back into Doze
                // mid-write and corrupt/drop a point. Released immediately
                // after — this is NOT a long-held lock, which would be the
                // classic battery-drain mistake.
                acquireShortWakeLock();
                try {
                    for (Location location : result.getLocations()) {
                        TrackPoint point = new TrackPoint(
                                location.getLatitude(),
                                location.getLongitude(),
                                location.getAltitude(),
                                location.getAccuracy(),
                                location.getSpeed(),
                                location.getTime()
                        );
                        sessionPoints.add(point);
                        TrackingRepository.getInstance().addPoint(point);
                        Log.d(TAG, "Recorded point: " + point);
                    }
                } finally {
                    releaseShortWakeLock();
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                getMainLooper()
        );
    }

    private void acquireShortWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsTracker:pointWriteLock");
        wakeLock.acquire(10_000L); // safety timeout so a bug can never hold it forever
    }

    private void releaseShortWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void stopTracking() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        locationCallback = null;
        TrackingRepository.getInstance().isTracking.postValue(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /**
     * Fires when the app is swiped away from Recents. On stock Android this
     * is harmless for a foreground service. On aggressive OEM skins it is
     * often the actual kill trigger. We schedule a tiny exact alarm a few
     * seconds out to relaunch ourselves — cheap, and gives the process a
     * fighting chance of surviving the OEM's kill sweep.
     *
     * This is a mitigation, not a guarantee. Layer 3's OEM allow-listing is
     * the real fix; this is a safety net underneath it.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        if (locationCallback == null) {
            // We weren't actively tracking, nothing to rescue.
            return;
        }

        Intent restartIntent = new Intent(getApplicationContext(), LocationTrackingService.class);
        restartIntent.setAction(ACTION_START);
        PendingIntent pendingIntent = PendingIntent.getService(
                getApplicationContext(),
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2_000L,
                    pendingIntent
            );
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Location tracking",
                    NotificationManager.IMPORTANCE_LOW // LOW = no sound/heads-up, still visible
            );
            channel.setDescription("Shows when your route is being recorded");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, LocationTrackingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Professional text with red accent and "working" dots
        CharSequence contentTitle = Html.fromHtml("<font color='#D32F2F'><b>LIVE TRACKING</b></font>", Html.FROM_HTML_MODE_LEGACY);
        String contentText = "Recording your route in real-time . . .";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setColor(Color.RED) // Tints the icon and app name red
                .setColorized(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Tracking", stopPendingIntent)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // started service, not bound
    }
}