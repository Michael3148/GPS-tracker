package com.example.gpstracker;

import static androidx.core.app.ActivityCompat.finishAffinity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.gpstracker.model.TrackPoint;
import com.example.gpstracker.service.LocationTrackingService;
import com.google.android.material.button.MaterialButton;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private boolean doubleBackToExitPressedOnce = false;

    private MapView mapView;
    private Polyline routeLine;
    private Marker currentLocationMarker;

    private MaterialButton buttonStartStop;
    private TextView valueDistance;
    private TextView valueTime;
    private TextView valueSpeed;

    private boolean isTracking = false;
    private long sessionStartTimeMillis = 0L;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    // Ticks once a second while tracking is active, purely to update the
    // elapsed-time TextView. Deliberately independent of GPS fix arrival —
    // fixes can be seconds apart or batched, but the clock should still
    // move smoothly.
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsedMillis = System.currentTimeMillis() - sessionStartTimeMillis;
            valueTime.setText(formatElapsedTime(elapsedMillis));
            timerHandler.postDelayed(this, 1000);
        }
    };

    // ===== Permission launchers =====
    // These MUST be registered unconditionally in onCreate (not inside a
    // click handler) — ActivityResultLauncher requires registration before
    // STARTED state ,or it throws at runtime. Each callback re-invokes
    // attemptStartTracking(), which simply re-checks what's still missing
    // and asks for the next thing, or starts the service once nothing's
    // left to ask for.

    private final ActivityResultLauncher<String[]> foregroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean fineGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarseGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (fineGranted || coarseGranted) {
                    attemptStartTracking();
                } else {
                    Toast.makeText(this, "Location permission is required to track your route", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    // Not fatal — tracking still works while the app is in
                    // the foreground, it just won't survive backgrounding.
                    // Worth telling the user explicitly rather than silently
                    // degrading, since that's a confusing failure to debug
                    // for a hiking app.
                    Toast.makeText(this,
                            "Without \"Allow all the time\", tracking will stop when you leave the app",
                            Toast.LENGTH_LONG).show();
                }
                attemptStartTracking();
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> attemptStartTracking());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // osmdroid MUST be configured before its views inflate, so this has
        // to run before setContentView(). We use a plain SharedPreferences
        // file rather than pulling in the androidx.preference library just
        // for this one call — Configuration.load() only needs something
        // that implements the SharedPreferences interface.
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(ctx.getPackageName() + "_osmdroid", MODE_PRIVATE);
        Configuration.getInstance().load(ctx, prefs);
        // REQUIRED: OpenStreetMap's tile servers reject requests with a
        // missing/default user-agent to deter abuse. This single line is
        // exactly what was missing before, causing the gray checkerboard.
        Configuration.getInstance().setUserAgentValue(getPackageName());

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Double back to exit logic
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (doubleBackToExitPressedOnce) {
                    finishAffinity();
                    return;
                }

                doubleBackToExitPressedOnce = true;
                Toast.makeText(MainActivity.this, "Press back again to exit", Toast.LENGTH_SHORT).show();

                new Handler(Looper.getMainLooper()).postDelayed(() -> doubleBackToExitPressedOnce = false, 2000);
            }
        });

        setupMap();
        setupStatViews();
        observeTrackingRepository();
    }

    private void setupMap() {
        mapView = findViewById(R.id.map_view);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(17.0);
        // Reasonable default center (adjust freely) until the first real
        // GPS fix arrives and we recenter automatically.
        mapView.getController().setCenter(new GeoPoint(9.03, 38.74)); // placeholder center

        routeLine = new Polyline();
        mapView.getOverlays().add(routeLine);

        currentLocationMarker = new Marker(mapView);
        currentLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        mapView.getOverlays().add(currentLocationMarker);
    }

    private void setupStatViews() {
        valueDistance = findViewById(R.id.valueDistance);
        valueTime = findViewById(R.id.valueTime);
        valueSpeed = findViewById(R.id.valueSpeed);
        buttonStartStop = findViewById(R.id.buttonStartStop);

        buttonStartStop.setOnClickListener(v -> {
            if (isTracking) {
                stopTrackingService();
            } else {
                attemptStartTracking();
            }
        });
    }

    private void observeTrackingRepository() {
        TrackingRepository repo = TrackingRepository.getInstance();

        repo.latestPoint.observe(this, this::onNewPoint);

        repo.totalDistanceMeters.observe(this, meters -> {
            double km = (meters == null ? 0 : meters) / 1000.0;
            valueDistance.setText(String.format(Locale.getDefault(), "%.2f km", km));
        });

        repo.isTracking.observe(this, tracking -> {
            isTracking = Boolean.TRUE.equals(tracking);
            updateButtonAppearance();
            if (isTracking) {
                sessionStartTimeMillis = System.currentTimeMillis();
                timerHandler.post(timerRunnable);
            } else {
                timerHandler.removeCallbacks(timerRunnable);
            }
        });
    }

    private void onNewPoint(TrackPoint point) {
        if (point == null) {
            routeLine.setPoints(new ArrayList<>());
            return;
        }

        GeoPoint geoPoint = new GeoPoint(point.latitude, point.longitude);

        // Extend the drawn route
        List<GeoPoint> currentPoints = new ArrayList<>(routeLine.getPoints());
        currentPoints.add(geoPoint);
        routeLine.setPoints(currentPoints);

        // Move the "you are here" marker and recenter the camera
        currentLocationMarker.setPosition(geoPoint);
        mapView.getController().animateTo(geoPoint);

        // Speed comes straight from the GPS fix, convert m/s -> km/h
        float speedKmh = point.speedMetersPerSecond * 3.6f;
        valueSpeed.setText(String.format(Locale.getDefault(), "%.1f km/h", speedKmh));

        mapView.invalidate();
    }

    private void updateButtonAppearance() {
        if (isTracking) {
            buttonStartStop.setText("STOP TRACKING");
            buttonStartStop.setIconResource(android.R.drawable.ic_media_pause);
        } else {
            buttonStartStop.setText("START TRACKING");
            buttonStartStop.setIconResource(android.R.drawable.ic_media_play);
        }
    }

    /**
     * Walks through the permission chain one missing piece at a time.
     * Safe to call repeatedly — each check is a no-op if already granted,
     * so calling this again after any single permission result naturally
     * advances to the next requirement or starts the service.
     */
    private void attemptStartTracking() {
        if (!hasForegroundLocationPermission()) {
            foregroundLocationLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
            return;
        }

        // Background location is API 29+ only, and MUST be requested in a
        // separate call from fine/coarse — bundling them on API 30+ causes
        // the system to silently limit the grant to foreground-only.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }

        startTrackingService();
    }

    private boolean hasForegroundLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startTrackingService() {
        Intent intent = new Intent(this, LocationTrackingService.class);
        intent.setAction(LocationTrackingService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopTrackingService() {
        Intent intent = new Intent(this, LocationTrackingService.class);
        intent.setAction(LocationTrackingService.ACTION_STOP);
        startService(intent);
    }

    private String formatElapsedTime(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    // osmdroid explicitly requires these lifecycle calls to manage its tile
    // cache and sensor overlays correctly — skipping them causes memory
    // leaks and occasional crashes on resume.
    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }
}