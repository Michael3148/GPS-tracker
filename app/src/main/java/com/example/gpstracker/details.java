package com.example.gpstracker;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

public class details extends AppCompatActivity {

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_details);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.details), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Set up the back button
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        // Get data from Intent
        double distanceMeters = getIntent().getDoubleExtra("EXTRA_DISTANCE", 0.0);
        long durationMillis = getIntent().getLongExtra("EXTRA_DURATION", 0);

        // Calculate values
        double distanceKm = distanceMeters / 1000.0;
        double durationHours = durationMillis / 3600000.0;
        double avgSpeed = durationHours > 0 ? (distanceKm / durationHours) : 0;

        // Display values
        TextView tvDistance = findViewById(R.id.detailDistance);
        TextView tvTime = findViewById(R.id.detailTime);
        TextView tvSpeed = findViewById(R.id.detailSpeed);

        tvDistance.setText(String.format(Locale.getDefault(), "%.2f", distanceKm));
        tvTime.setText(formatDuration(durationMillis));
        tvSpeed.setText(String.format(Locale.getDefault(), "%.1f km/h", avgSpeed));
    }

    private String formatDuration(long millis) {
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / (1000 * 60)) % 60;
        long hours = (millis / (1000 * 60 * 60));
        return String.format(Locale.getDefault(), "%02d : %02d : %02d", hours, minutes, seconds);
    }
}