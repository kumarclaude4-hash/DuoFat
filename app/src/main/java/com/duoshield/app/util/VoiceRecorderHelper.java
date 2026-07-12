package com.duoshield.app.util;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class VoiceRecorderHelper {

    private static final String TAG = "VoiceRecorderHelper";

    public interface RecorderListener {
        void onAmplitude(int amplitude);
        void onStopped(String filePath, List<Integer> amplitudes);
        void onError(String msg);
    }

    private MediaRecorder            recorder;
    private String                   outputPath;
    private final List<Integer>      amplitudes = new ArrayList<>();
    private Handler                  handler;
    private Runnable                 ampRunnable;
    // WeakReference prevents leaking the host Activity/Fragment if stop() is never called
    private WeakReference<RecorderListener> listenerRef;

    public void start(Context ctx, RecorderListener cb) {
        listenerRef = new WeakReference<>(cb);
        try {
            File out = new File(ctx.getCacheDir(), "voice_" + System.currentTimeMillis() + ".m4a");
            outputPath = out.getAbsolutePath();
            amplitudes.clear();

            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(ctx) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(22050);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(outputPath);
            recorder.prepare();
            recorder.start();

            handler = new Handler(Looper.getMainLooper());
            ampRunnable = new Runnable() {
                @Override public void run() {
                    if (recorder == null) return;
                    RecorderListener l = listenerRef != null ? listenerRef.get() : null;
                    if (l == null) {
                        handler.removeCallbacks(this);
                        return;
                    }
                    int amp = recorder.getMaxAmplitude();
                    // Normalise to 0-100 so the waveform looks correct regardless of device mic gain.
                    // MediaRecorder can spike wildly on some devices, so clamp hard at the top.
                    int normAmp = Math.max(0, Math.min(100, Math.round(amp / 327.67f)));
                    amplitudes.add(normAmp);
                    l.onAmplitude(normAmp);
                    handler.postDelayed(this, 100);
                }
            };
            // Delay first poll to let MediaRecorder warm up — immediate calls return 0 on most devices
            handler.postDelayed(ampRunnable, 300);
        } catch (Exception e) {
            RecorderListener l = listenerRef != null ? listenerRef.get() : null;
            if (l != null) l.onError(e.getMessage());
        }
    }

    public void stop() {
        if (handler != null && ampRunnable != null) {
            handler.removeCallbacks(ampRunnable);
        }

        boolean recordingStopped = false;
        if (recorder != null) {
            try {
                recorder.stop();
                recordingStopped = true;
            } catch (Exception e) {
                // IllegalStateException if stop() called before any data was recorded
                Log.w(TAG, "recorder.stop() failed (recording may be too short): " + e.getMessage());
            }
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }

        RecorderListener l = listenerRef != null ? listenerRef.get() : null;
        if (recordingStopped && l != null) {
            l.onStopped(outputPath, new ArrayList<>(amplitudes));
        } else if (!recordingStopped && l != null) {
            l.onError("Recording was too short or failed to stop.");
        }
        listenerRef = null;
    }

    public void cancel() {
        if (handler != null && ampRunnable != null) {
            handler.removeCallbacks(ampRunnable);
        }
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (outputPath != null) new File(outputPath).delete();
        listenerRef = null;
    }
}
