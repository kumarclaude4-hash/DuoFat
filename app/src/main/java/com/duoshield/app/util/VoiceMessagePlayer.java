package com.duoshield.app.util;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;

public class VoiceMessagePlayer {

    private static final String TAG = "VoiceMessagePlayer";

    public interface PlayerListener {
        void onStart(int durationMs);
        void onProgress(int posMs);
        void onComplete();
        void onError(String msg);
    }

    private MediaPlayer                    player;
    private Handler                        handler;
    private Runnable                       progressRunnable;
    private WeakReference<PlayerListener>  listenerRef;

    public void play(String url, PlayerListener cb) {
        listenerRef = new WeakReference<>(cb);
        releaseInternal();
        try {
            player = new MediaPlayer();
            player.setDataSource(url);
            player.prepareAsync();
            player.setOnPreparedListener(mp -> {
                mp.start();
                PlayerListener l = listenerRef != null ? listenerRef.get() : null;
                if (l != null) l.onStart(mp.getDuration());
                startProgressPolling();
            });
            player.setOnCompletionListener(mp -> {
                PlayerListener l = listenerRef != null ? listenerRef.get() : null;
                if (l != null) l.onComplete();
                releaseInternal();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
                PlayerListener l = listenerRef != null ? listenerRef.get() : null;
                if (l != null) l.onError("Playback error (" + what + ")");
                releaseInternal();
                return true;
            });
        } catch (Exception e) {
            Log.e(TAG, "play() failed for url=" + url, e);
            PlayerListener l = listenerRef != null ? listenerRef.get() : null;
            if (l != null) l.onError(e.getMessage() != null ? e.getMessage() : "Playback setup failed");
        }
    }

    public void pause() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (Exception e) {
                Log.w(TAG, "pause() failed: " + e.getMessage());
            }
        }
        stopProgressPolling();
    }

    public void resume() {
        if (player != null) {
            try {
                if (!player.isPlaying()) {
                    player.start();
                    startProgressPolling();
                }
            } catch (Exception e) {
                Log.w(TAG, "resume() failed: " + e.getMessage());
            }
        }
    }

    public boolean isPaused() {
        return player != null && !player.isPlaying();
    }

    public void release() {
        releaseInternal();
        listenerRef = null;
    }

    private void startProgressPolling() {
        stopProgressPolling();
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        progressRunnable = new Runnable() {
            @Override public void run() {
                if (player == null) return;
                try {
                    if (player.isPlaying()) {
                        PlayerListener pl = listenerRef != null ? listenerRef.get() : null;
                        if (pl == null) {
                            stopProgressPolling();
                            return;
                        }
                        pl.onProgress(player.getCurrentPosition());
                        handler.postDelayed(this, 200);
                    }
                } catch (Exception ignored) {}
            }
        };
        handler.post(progressRunnable);
    }

    private void stopProgressPolling() {
        if (handler != null && progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void releaseInternal() {
        stopProgressPolling();
        if (player != null) {
            try {
                if (player.isPlaying()) player.pause();
                player.reset();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }
}
