package com.example.gpstracker;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class serviceses extends Service {
    public serviceses() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}