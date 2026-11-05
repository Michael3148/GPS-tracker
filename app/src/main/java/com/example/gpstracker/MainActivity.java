package com.example.gpstracker;

import static androidx.core.app.ActivityCompat.finishAffinity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
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
    private boolean hasCenteredInitialLocation = false;
    private long sessionStartTimeMillis = 0L;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    /* TODO :The below code works on the timer on the acitity ,
        and responsible for displaying the time elapsed in the screen */

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsedMillis = System.currentTimeMillis() - sessionStartTimeMillis;
            valueTime.setText(formatElapsedTime(elapsedMillis));
            timerHandler.postDelayed(this, 1000);
        }
    };


    // TODO: foregroundlocationlauncher is used to ask for GPS location permission
    private final ActivityResultLauncher<String[]> foregroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean fineGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarseGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));

                if (fineGranted || coarseGranted) { // If either one is granted, start tracking...
                    attemptStartTracking();
                } else {
                    Toast.makeText(this, "Location permission is required to track your route", Toast.LENGTH_LONG).show();
                }
            });

    // TODO: backgroundlocationlauncher is used to ask for "Allow all the time" request in the phone
    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this,
                            "Without \"Allow all the time\", tracking will stop when you leave the app",
                            Toast.LENGTH_LONG).show();
                }
                attemptStartTracking();
            });
    //TODO: Used for asking a permission to show notification
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> attemptStartTracking());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Context ctx = getApplicationContext();
        SharedPreferences prefs =
                ctx.getSharedPreferences(
                ctx.getPackageName() + "_osmdroid",
                MODE_PRIVATE); //TODO: This totally says "Give me SharedPreference storage named com.example.gpstracker_osmdroid

        Configuration.getInstance().load(ctx, prefs);
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
        observeTrackingRepository();   //TODO: Usually a good practice to make functions like this instead of coding all of them inside "onCreate"
    }

    // TODO: This is the Stadia's  key my app used , and it is also free
    private static final String STADIA_MAPS_API_KEY = "b635fde5-a56a-4dd8-8aa2-0d8f8b54282f";

    private void setupMap() { //TODO : It prepares the map, the button , the pin , the line (Without this function , the app loads but with no map and dead buttons)
        mapView = findViewById(R.id.map_view);


        XYTileSource stadiaSource = //TODO: This tells the app “get them from Stadia’s servers. (This is the part that fixed your gray “Access blocked” squares.)
                new XYTileSource(
                        "StadiaAlidadeSmooth",
                        0, 20, 256,
                        ".png?api_key=" + STADIA_MAPS_API_KEY,
                        new String[]{"https://tiles.stadiamaps.com/tiles/alidade_smooth/"}
                );
        mapView.setTileSource(stadiaSource);


        CopyrightOverlay copyrightOverlay =
                new CopyrightOverlay(this);
        copyrightOverlay.setCopyrightNotice("© Stadia Maps, © OpenMapTiles © OpenStreetMap contributors");
        mapView.getOverlays().add(copyrightOverlay);

        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(17.0);
        mapView.getController().setCenter(new GeoPoint(9.03, 38.74)); // placeholder center

        routeLine = new Polyline();
        mapView.getOverlays().add(routeLine);

        currentLocationMarker = new Marker(mapView);
        currentLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        mapView.getOverlays().add(currentLocationMarker); //TODO: currentLocationMarker is who draws the pathline in the map

        FloatingActionButton fabCurrentLocation = findViewById(R.id.fab_current_location);
        fabCurrentLocation.setOnClickListener(v -> {
            TrackPoint point = TrackingRepository.getInstance().latestPoint.getValue();
            if (point != null) {
                GeoPoint geoPoint = new GeoPoint(point.latitude, point.longitude);
                mapView.getController().animateTo(geoPoint);
            } else {
                Toast.makeText(this, "Current location not available yet", Toast.LENGTH_SHORT).show();
            }
        });//TODO: This minifunction tells the map "If you know this way draw a line if not not available yet"
    }

    private void setupStatViews() { //TODO: This method is used to connect my java code to the UI (Stop and Start Tracking button)
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
                hasCenteredInitialLocation = false;
            }
        });
    }

    private void onNewPoint(TrackPoint point) {
        if (point == null) {
            routeLine.setPoints(new ArrayList<>());
            hasCenteredInitialLocation = false;
            return;
        }

        GeoPoint geoPoint = new GeoPoint(point.latitude, point.longitude);

        // Center map camera once when the first fix arrives
        if (!hasCenteredInitialLocation) {
            mapView.getController().animateTo(geoPoint);
            hasCenteredInitialLocation = true;
        }

        List<GeoPoint> currentPoints = new ArrayList<>(routeLine.getPoints());
        currentPoints.add(geoPoint);
        routeLine.setPoints(currentPoints);

        currentLocationMarker.setPosition(geoPoint);

        float speedKmh = point.speedMetersPerSecond * 3.6f;
        valueSpeed.setText(String.format(Locale.getDefault(), "%.1f km/h", speedKmh));

        mapView.invalidate();
    }

    private void updateButtonAppearance() {
        if (isTracking) {
            buttonStartStop.setText("TRACKING . . .");
            buttonStartStop.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.red)));
            buttonStartStop.setIconResource(android.R.drawable.ic_media_pause);
        } else {
            buttonStartStop.setText("START TRACKING");
            buttonStartStop.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.blue)));
            buttonStartStop.setIconResource(android.R.drawable.ic_media_play);
        }
    }


    private void attemptStartTracking() {
        if (!hasForegroundLocationPermission()) {
            foregroundLocationLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
            return;
        }

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