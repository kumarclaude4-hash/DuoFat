package com.duoshield.app.util;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Detects a shake gesture using the device accelerometer.
 *
 * <p>Usage:
 * <pre>
 *   ShakeDetector detector = new ShakeDetector(ctx, this::onShake);
 *   detector.start();   // in onResume()
 *   detector.stop();    // in onPause()
 * </pre>
 *
 * <p>Fires {@link OnShakeListener#onShake()} when the measured G-force exceeds
 * {@link #SHAKE_THRESHOLD_G} and at least {@link #COOLDOWN_MS} have elapsed since
 * the last event (prevents multiple rapid firings from a single shake motion).
 */
public final class ShakeDetector implements SensorEventListener {

    public interface OnShakeListener {
        void onShake();
    }

    private static final float SHAKE_THRESHOLD_G = 2.7f;
    private static final long  COOLDOWN_MS       = 600L;

    private final SensorManager    sensorManager;
    private final Sensor           accelerometer;
    private final OnShakeListener  listener;
    private       long             lastShakeMs = 0;

    public ShakeDetector(Context ctx, OnShakeListener listener) {
        sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                : null;
        this.listener = listener;
    }

    /** Returns {@code true} if the device has an accelerometer. */
    public boolean isAvailable() {
        return accelerometer != null;
    }

    /** Registers the listener. Call from {@code onResume()}. */
    public void start() {
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_UI);
        }
    }

    /** Unregisters the listener. Call from {@code onPause()}. */
    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float gX = x / SensorManager.GRAVITY_EARTH;
        float gY = y / SensorManager.GRAVITY_EARTH;
        float gZ = z / SensorManager.GRAVITY_EARTH;
        double gForce = Math.sqrt(gX * gX + gY * gY + gZ * gZ);

        if (gForce > SHAKE_THRESHOLD_G) {
            long now = System.currentTimeMillis();
            if (now - lastShakeMs > COOLDOWN_MS) {
                lastShakeMs = now;
                if (listener != null) listener.onShake();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
