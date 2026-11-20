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
public class LocationTrackingService extends Service { // TODO : LocationTrackingRepository collects the GPS points in the back ground and pass it to MainActivity using TrackingRepository.

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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this); // TODO : gets your connection to Google’s location system. It doesn’t start tracking, it only picks up the tool.
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { // TODO: It is a command reciver for your service (Weather start tracking , notification stop ... any command )
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

        // Already tracking? Ignore the repeated command. Never wipe a live session.
        if (locationCallback != null) {
            return START_STICKY;
        }

        // A real START from the button carries an intent = new trip, so reset.
        // A null intent means Android recreated us after a kill = keep the numbers.
        if (intent != null) {
            TrackingRepository.getInstance().reset();
        }

        TrackingRepository.getInstance().isTracking.postValue(true);
        startLocationUpdates();

        return START_STICKY;
    }

    private void startLocationUpdates() {
        if (locationCallback != null) { // TODO : If callback already exist , Don't register twice
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

        LocationRequest locationRequest = // TODO : This is the heart of target 2
                new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)  // TODO : PRIORITY_HIGH_ACCURACY means Most accurate , Most battery & UPDATE_INTERVAL_MS means ask a location every 5s average
                        .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS) // TODO : 3s , Never faster than this
                        // setMaxUpdateDelayMillis enables "batching": the GPS chip
                        // can buffer several fixes in hardware and deliver them in
                        // one burst instead of waking the main CPU for every single
                        // fix. This is one of the single biggest battery wins
                        // available and is almost never mentioned in tutorials.
                        .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MS) // TODO : 15s , is the maximum delay
                        .setWaitForAccurateLocation(false)
                        .build();

        locationCallback = new LocationCallback() { // TODO : This is what you do when a package arrives: open it, take what’s inside, and put it where it belongs. Also doesn't run right now
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

        fusedLocationClient.requestLocationUpdates( // TODO : Gets the GPS data and calls you back
                locationRequest, // TODO : How you want locations (Every 5s , high accuracy , batching)
                locationCallback,// TODO : What to do when each delivery arrives
                getMainLooper() // TODO : Run my call backs in the main threads
        );
    }

    private void acquireShortWakeLock() { // TODO : When the screen goes off , also the CPU tries to sleep . This function prevents the CPU form sleeping
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsTracker:pointWriteLock"); // TODO : Keeps only the CPU not the screen
        wakeLock.acquire(10_000L); // safety timeout so a bug can never hold it forever
    }

    private void releaseShortWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void stopTracking() { // TODO : This function is the shutdown sequence
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
    public void onTaskRemoved(Intent rootIntent) { // TODO: It is a swipe-away handler , Functions when the user swipes out the application from the screen
        super.onTaskRemoved(rootIntent);

        if (locationCallback == null) { // TODO : Checks weather we were actually tracking while the swipe happens.
            return;
        }

        Intent restartIntent = new Intent(getApplicationContext(), LocationTrackingService.class);  // TODO : These two lines create the “start tracking” message and holds it (it doesn't pass any where).
        restartIntent.setAction(ACTION_START);

        PendingIntent pendingIntent = PendingIntent.getService(  //TODO : Is an intent passed to AlarmManager so it can fire it later even if the application is dead
                getApplicationContext(), // PendingIntent wraps an intent so another application can use it later (Not imediate like Intent) , But you have to declare normal intent in order to use pendingIntent
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE); // TODO : After 2 seconds lunch the pendingIntent and start the service again
        if (alarmManager != null) {
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2_000L,
                    pendingIntent
            );
        }
    }

    private void createNotificationChannel() { // TODO : This function is responsible for creating the notification bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // TODO : Checks the phone is new enough to handle the notification
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Location tracking",
                    NotificationManager.IMPORTANCE_LOW // LOW = no sound/heads-up, still visible
            );
            channel.setDescription("Shows when your route is being recorded");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) { // TODO : Checks if android gives me the notification manager
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() { // TODO : Makes the sticky note that proves tracking is running, with a Stop button the phone’s notification bar can press on my behalf.
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