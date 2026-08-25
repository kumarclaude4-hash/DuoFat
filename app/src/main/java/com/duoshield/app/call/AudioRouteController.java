package com.duoshield.app.call;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Single owner of the in-call audio route.
 *
 * <h3>Why this class exists</h3>
 * <p>The call screen used to route audio with {@link AudioManager#setSpeakerphoneOn(boolean)}
 * and {@link AudioManager#startBluetoothSco()}. Both were deprecated in Android 12 (API 31)
 * and on API 31+ they are <em>silently ignored</em> while a communication device is selected —
 * the call returns without throwing and the route never changes. That is why every entry in
 * the audio-output sheet appeared to do nothing on a modern device: the sheet updated its own
 * checkmark, but the framework kept playing out of whatever device it had picked.
 *
 * <p>On API 31+ the only API that actually moves call audio is
 * {@link AudioManager#setCommunicationDevice(AudioDeviceInfo)}, which also drives the matching
 * input device (so selecting a headset switches its microphone too). This controller uses that
 * path on API 31+ and keeps the legacy calls only for API 26–30, where they still work.
 *
 * <p>The controller is also the source of truth for "which route is active" — it reads the
 * route back from the framework rather than trusting a local boolean, so the sheet checkmark
 * and the header button icon can never drift from reality.
 */
public class AudioRouteController {

    private static final String TAG = "AudioRouteController";

    /** Route families the picker offers. */
    public enum Kind { EARPIECE, SPEAKER, WIRED, BLUETOOTH, MUTED }

    /** One selectable output, resolved from the framework's live device list. */
    public static class Route {
        public final Kind   kind;
        public final String label;
        /** Framework device id, or -1 for the synthetic "Turn off sound" route. */
        public final int    deviceId;

        Route(Kind kind, String label, int deviceId) {
            this.kind = kind;
            this.label = label;
            this.deviceId = deviceId;
        }
    }

    private final Context      context;
    private final AudioManager am;

    /** Set while the "Turn off sound" route is active, so we can restore on the next pick. */
    private boolean outputMuted = false;
    /** STREAM_VOICE_CALL volume captured before muting, restored when leaving MUTED. */
    private int savedVoiceVolume = -1;

    public AudioRouteController(Context context) {
        this.context = context.getApplicationContext();
        this.am = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * Puts the audio system into communication mode and picks the sensible default route:
     * a connected headset wins, otherwise speaker for video calls and earpiece for voice.
     */
    public void beginSession(boolean videoCall) {
        if (am == null) return;
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);

        Route headset = firstOf(Kind.BLUETOOTH);
        if (headset == null) headset = firstOf(Kind.WIRED);
        if (headset != null) {
            apply(headset);
            return;
        }
        Route preferred = firstOf(videoCall ? Kind.SPEAKER : Kind.EARPIECE);
        if (preferred != null) apply(preferred);
    }

    /** Restores volume and releases any device selection when the call ends. */
    public void endSession() {
        if (am == null) return;
        restoreVolume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { am.clearCommunicationDevice(); } catch (Exception ignored) { }
        } else {
            try {
                am.stopBluetoothSco();
                am.setBluetoothScoOn(false);
                am.setSpeakerphoneOn(false);
            } catch (Exception ignored) { }
        }
        am.setMode(AudioManager.MODE_NORMAL);
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    /**
     * Lists the outputs the framework will actually accept right now, in picker order:
     * Speaker, Phone (earpiece), wired headset, then each Bluetooth headset.
     *
     * <p>On API 31+ this comes from {@link AudioManager#getAvailableCommunicationDevices()},
     * which is exactly the set {@code setCommunicationDevice} accepts — so a listed row can
     * never fail to apply. Below API 31 it is derived from the output device list.
     */
    public List<Route> availableRoutes() {
        List<Route> routes = new ArrayList<>();
        if (am == null) return routes;

        List<AudioDeviceInfo> devices = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                devices.addAll(am.getAvailableCommunicationDevices());
            } catch (Exception e) {
                Log.w(TAG, "getAvailableCommunicationDevices failed", e);
            }
        } else {
            try {
                AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo d : outs) devices.add(d);
            } catch (Exception e) {
                Log.w(TAG, "getDevices failed", e);
            }
        }

        // Speaker and earpiece first, so the sheet order is stable regardless of
        // the order the framework happens to report devices in.
        addFirstMatch(routes, devices, Kind.SPEAKER);
        addFirstMatch(routes, devices, Kind.EARPIECE);
        addFirstMatch(routes, devices, Kind.WIRED);

        for (AudioDeviceInfo d : devices) {
            if (kindOf(d.getType()) == Kind.BLUETOOTH) {
                routes.add(new Route(Kind.BLUETOOTH, labelFor(d), d.getId()));
            }
        }
        return routes;
    }

    /** The route the framework is playing through right now, or MUTED when silenced. */
    public Kind currentKind() {
        if (outputMuted) return Kind.MUTED;
        if (am == null) return Kind.EARPIECE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                AudioDeviceInfo dev = am.getCommunicationDevice();
                if (dev != null) return kindOf(dev.getType());
            } catch (Exception ignored) { }
            return Kind.EARPIECE;
        }
        if (am.isBluetoothScoOn()) return Kind.BLUETOOTH;
        if (am.isSpeakerphoneOn()) return Kind.SPEAKER;
        if (isWiredConnected())    return Kind.WIRED;
        return Kind.EARPIECE;
    }

    /** Device id of the active route, or -1 when unknown — distinguishes two BT headsets. */
    public int currentDeviceId() {
        if (am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return -1;
        try {
            AudioDeviceInfo dev = am.getCommunicationDevice();
            return dev != null ? dev.getId() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    // ── Applying a route ──────────────────────────────────────────────────────

    /**
     * Moves call audio to {@code route}.
     *
     * @return {@code true} when the framework accepted the change. A {@code false} result is
     *         surfaced to the user instead of silently leaving a checkmark on a route that
     *         never took effect.
     */
    public boolean apply(Route route) {
        if (am == null || route == null) return false;

        if (route.kind == Kind.MUTED) return muteOutput();

        // Leaving MUTED — bring the call stream back before switching device.
        restoreVolume();

        am.setMode(AudioManager.MODE_IN_COMMUNICATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo target = findCommunicationDevice(route);
            if (target == null) {
                Log.w(TAG, "no communication device for " + route.kind);
                return false;
            }
            try {
                boolean ok = am.setCommunicationDevice(target);
                if (!ok) Log.w(TAG, "setCommunicationDevice rejected " + route.kind);
                return ok;
            } catch (Exception e) {
                Log.w(TAG, "setCommunicationDevice failed", e);
                return false;
            }
        }

        // API 26–30 legacy routing.
        try {
            switch (route.kind) {
                case BLUETOOTH:
                    am.setSpeakerphoneOn(false);
                    am.setBluetoothScoOn(true);
                    am.startBluetoothSco();
                    return true;
                case SPEAKER:
                    am.stopBluetoothSco();
                    am.setBluetoothScoOn(false);
                    am.setSpeakerphoneOn(true);
                    return true;
                case WIRED:
                case EARPIECE:
                default:
                    am.stopBluetoothSco();
                    am.setBluetoothScoOn(false);
                    am.setSpeakerphoneOn(false);
                    return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "legacy routing failed", e);
            return false;
        }
    }

    /**
     * Silences call playback by dropping STREAM_VOICE_CALL to zero.
     *
     * <p>The old implementation also muted the microphone, which silenced the caller for the
     * <em>other</em> party — the opposite of "turn off sound". Outbound audio is left alone
     * here; only what this device plays is muted.
     */
    private boolean muteOutput() {
        if (am == null) return false;
        try {
            if (savedVoiceVolume < 0) {
                savedVoiceVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            }
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0);
            outputMuted = true;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "mute output failed", e);
            return false;
        }
    }

    /** Restores the pre-mute call volume. No-op when the output was never muted. */
    private void restoreVolume() {
        if (am == null || !outputMuted) return;
        try {
            int restore = savedVoiceVolume > 0
                    ? savedVoiceVolume
                    : Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) / 2);
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, restore, 0);
        } catch (Exception ignored) { }
        outputMuted      = false;
        savedVoiceVolume = -1;
    }

    /** True while the "Turn off sound" route is active. */
    public boolean isOutputMuted() { return outputMuted; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Route firstOf(Kind kind) {
        for (Route r : availableRoutes()) {
            if (r.kind == kind) return r;
        }
        return null;
    }

    private void addFirstMatch(List<Route> out, List<AudioDeviceInfo> devices, Kind kind) {
        for (AudioDeviceInfo d : devices) {
            if (kindOf(d.getType()) == kind) {
                out.add(new Route(kind, labelFor(d), d.getId()));
                return;
            }
        }
    }

    private AudioDeviceInfo findCommunicationDevice(Route route) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        try {
            List<AudioDeviceInfo> devices = am.getAvailableCommunicationDevices();
            // Prefer the exact device the user tapped (two paired headsets are both listed).
            for (AudioDeviceInfo d : devices) {
                if (d.getId() == route.deviceId) return d;
            }
            for (AudioDeviceInfo d : devices) {
                if (kindOf(d.getType()) == route.kind) return d;
            }
        } catch (Exception e) {
            Log.w(TAG, "device lookup failed", e);
        }
        return null;
    }

    private boolean isWiredConnected() {
        if (am == null) return false;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (kindOf(d.getType()) == Kind.WIRED) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static Kind kindOf(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return Kind.EARPIECE;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return Kind.SPEAKER;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return Kind.WIRED;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return Kind.BLUETOOTH;
            default:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                         || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
                    return Kind.BLUETOOTH;
                }
                return null;
        }
    }

    /**
     * Human label for a device row. Bluetooth names need BLUETOOTH_CONNECT; when it is not
     * granted the framework returns an empty name instead of throwing, so we fall back to a
     * generic label rather than dropping the row (the route itself still works).
     */
    private static String labelFor(AudioDeviceInfo d) {
        Kind kind = kindOf(d.getType());
        if (kind == Kind.SPEAKER)  return "Speaker";
        if (kind == Kind.EARPIECE) return "Phone";
        if (kind == Kind.WIRED)    return "Wired headset";
        try {
            CharSequence name = d.getProductName();
            if (name != null && name.length() > 0) return name.toString();
        } catch (Exception ignored) { }
        return "Bluetooth device";
    }
}
