package com.example.gpstracker.model;

public class TrackPoint {

    public final double latitude;
    public final double longitude;
    public final double altitudeMeters;
    public final float accuracyMeters;
    public final float speedMetersPerSecond;
    public final long timestampMillis;

    public TrackPoint(double latitude,
                      double longitude,
                      double altitudeMeters,
                      float accuracyMeters,
                      float speedMetersPerSecond,
                      long timestampMillis) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.accuracyMeters = accuracyMeters;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.timestampMillis = timestampMillis;
    }

    @Override
    public String toString() {
        return "TrackPoint{" +
                "lat=" + latitude +
                ", lng=" + longitude +
                ", alt=" + altitudeMeters +
                ", acc=" + accuracyMeters +
                ", speed=" + speedMetersPerSecond +
                ", t=" + timestampMillis +
                '}';
    }
}