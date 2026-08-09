package com.duoshield.app.call.watch;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable-ish snapshot of a Watch Together session, plus the pure synchronization
 * math used to decide whether a follower must seek.
 *
 * <p><strong>Transport.</strong> One state document lives at
 * {@code calls/{callId}/watch/state}. It is written by whichever participant performs a
 * control action and read by both via a single snapshot listener. This mirrors the
 * existing WebRTC signaling pattern ({@code calls/{callId}} + candidate subcollections)
 * rather than introducing a new service or a WebRTC data channel.
 *
 * <p><strong>The YouTube video itself never touches WebRTC.</strong> Each participant's
 * WebView loads the video directly from YouTube; only the small state fields below cross
 * the wire.
 *
 * <p><strong>Clock safety.</strong> {@link #updatedAtMs} is the <em>writer's</em> wall
 * clock and is therefore NOT comparable against a reader's clock (device clocks drift and
 * users can change them). Followers must instead record their own local receipt time when
 * the snapshot arrives and project forward using
 * {@link #projectedPositionMs(WatchTogetherState, long)} with locally measured elapsed
 * time. {@link #updatedAtMs} is kept only for debugging and for staleness display.
 *
 * <p><strong>Ordering.</strong> {@link #seq} is a monotonic counter incremented on every
 * write. Followers ignore any snapshot whose {@code seq} is not greater than the last one
 * applied, which suppresses Firestore local-echo and out-of-order delivery.
 *
 * <p>This class is deliberately free of Android and Firebase SDK types so it can be unit
 * tested on the JVM, matching the existing {@code CallStateTest} style.
 */
public class WatchTogetherState {

    // ── Firestore field names (single source of truth) ─────────────────────────

    public static final String F_ACTIVE         = "active";
    public static final String F_VIDEO_ID       = "videoId";
    public static final String F_HOST_UID       = "hostUid";
    public static final String F_PLAYING        = "playing";
    public static final String F_POSITION_MS    = "positionMs";
    public static final String F_PLAYBACK_RATE  = "playbackRate";
    public static final String F_UPDATED_AT_MS  = "updatedAtMs";
    public static final String F_SEQ            = "seq";
    public static final String F_LAST_ACTION_BY = "lastActionBy";
    public static final String F_LAST_ACTION    = "lastAction";

    // ── Action labels (diagnostics + UI copy; not control flow) ────────────────

    public static final String ACTION_START = "start";
    public static final String ACTION_PLAY  = "play";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_SEEK  = "seek";
    public static final String ACTION_RATE  = "rate";
    public static final String ACTION_STOP  = "stop";
    /** Periodic host position write so followers can correct slow drift. */
    public static final String ACTION_HEARTBEAT = "heartbeat";

    // ── Tuning constants ──────────────────────────────────────────────────────

    /**
     * A follower only seeks when it is off the projected target by more than this.
     * Below this, correcting would be more jarring than the drift itself.
     */
    public static final long DRIFT_THRESHOLD_MS = 1500L;

    /** Minimum spacing between host heartbeat writes. Keeps Firestore write cost bounded. */
    public static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    public static final double DEFAULT_PLAYBACK_RATE = 1.0d;

    // ── Fields ────────────────────────────────────────────────────────────────

    public boolean active;
    public String  videoId;
    public String  hostUid;
    public boolean playing;
    public long    positionMs;
    public double  playbackRate = DEFAULT_PLAYBACK_RATE;
    public long    updatedAtMs;
    public long    seq;
    public String  lastActionBy;
    public String  lastAction;

    public WatchTogetherState() {
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    /** Builds the Firestore payload for this state. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put(F_ACTIVE, active);
        m.put(F_VIDEO_ID, videoId != null ? videoId : "");
        m.put(F_HOST_UID, hostUid != null ? hostUid : "");
        m.put(F_PLAYING, playing);
        m.put(F_POSITION_MS, positionMs);
        m.put(F_PLAYBACK_RATE, playbackRate);
        m.put(F_UPDATED_AT_MS, updatedAtMs);
        m.put(F_SEQ, seq);
        m.put(F_LAST_ACTION_BY, lastActionBy != null ? lastActionBy : "");
        m.put(F_LAST_ACTION, lastAction != null ? lastAction : "");
        return m;
    }

    /**
     * Rebuilds state from a Firestore document's field map.
     *
     * <p>Tolerates missing fields and the Long/Double/Integer ambiguity of Firestore
     * numbers so a partially written or older-shaped document can never crash the call
     * screen.
     *
     * @return the parsed state, or {@code null} if {@code data} is null.
     */
    public static WatchTogetherState fromMap(Map<String, Object> data) {
        if (data == null) return null;

        WatchTogetherState s = new WatchTogetherState();
        s.active       = asBool(data.get(F_ACTIVE), false);
        s.videoId      = asString(data.get(F_VIDEO_ID));
        s.hostUid      = asString(data.get(F_HOST_UID));
        s.playing      = asBool(data.get(F_PLAYING), false);
        s.positionMs   = asLong(data.get(F_POSITION_MS), 0L);
        s.playbackRate = asDouble(data.get(F_PLAYBACK_RATE), DEFAULT_PLAYBACK_RATE);
        s.updatedAtMs  = asLong(data.get(F_UPDATED_AT_MS), 0L);
        s.seq          = asLong(data.get(F_SEQ), 0L);
        s.lastActionBy = asString(data.get(F_LAST_ACTION_BY));
        s.lastAction   = asString(data.get(F_LAST_ACTION));

        if (s.playbackRate <= 0d) s.playbackRate = DEFAULT_PLAYBACK_RATE;
        if (s.positionMs < 0L)    s.positionMs   = 0L;
        return s;
    }

    private static boolean asBool(Object o, boolean def) {
        return (o instanceof Boolean) ? (Boolean) o : def;
    }

    private static String asString(Object o) {
        if (!(o instanceof String)) return null;
        String v = (String) o;
        return v.isEmpty() ? null : v;
    }

    private static long asLong(Object o, long def) {
        return (o instanceof Number) ? ((Number) o).longValue() : def;
    }

    private static double asDouble(Object o, double def) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : def;
    }

    // ── Pure synchronization math ─────────────────────────────────────────────

    /**
     * Where playback <em>should</em> be now, given how much local time has passed since
     * this snapshot was received.
     *
     * <p>A paused session projects to its stored position — time does not advance. A
     * playing session advances by elapsed local time scaled by the playback rate.
     *
     * @param state                  the last applied state; {@code null} yields 0.
     * @param elapsedSinceSnapshotMs locally measured ms since the snapshot arrived.
     *                               Negative values are treated as 0.
     */
    public static long projectedPositionMs(WatchTogetherState state, long elapsedSinceSnapshotMs) {
        if (state == null) return 0L;
        if (!state.playing) return Math.max(0L, state.positionMs);

        long elapsed = Math.max(0L, elapsedSinceSnapshotMs);
        double rate = state.playbackRate > 0d ? state.playbackRate : DEFAULT_PLAYBACK_RATE;
        long projected = state.positionMs + (long) (elapsed * rate);
        return Math.max(0L, projected);
    }

    /**
     * True when the local player is far enough from the target that seeking is warranted.
     * Uses {@link #DRIFT_THRESHOLD_MS}.
     */
    public static boolean shouldSeek(long localPositionMs, long targetPositionMs) {
        return shouldSeek(localPositionMs, targetPositionMs, DRIFT_THRESHOLD_MS);
    }

    /** Threshold-parameterized variant of {@link #shouldSeek(long, long)}. */
    public static boolean shouldSeek(long localPositionMs, long targetPositionMs, long thresholdMs) {
        return Math.abs(targetPositionMs - localPositionMs) > thresholdMs;
    }

    /**
     * True when {@code incoming} should replace {@code applied}.
     *
     * <p>Any first state is applied. Afterwards only a strictly greater {@link #seq} wins,
     * which drops Firestore's local echo of our own write and any out-of-order delivery.
     */
    public static boolean shouldApply(WatchTogetherState applied, WatchTogetherState incoming) {
        if (incoming == null) return false;
        if (applied == null)  return true;
        return incoming.seq > applied.seq;
    }

    /** True when a session is running and actually has a video to show. */
    public boolean isPlayable() {
        return active && videoId != null && !videoId.isEmpty();
    }

    /** Copy used as the base for the next write, so unchanged fields are preserved. */
    public WatchTogetherState copy() {
        WatchTogetherState s = new WatchTogetherState();
        s.active       = active;
        s.videoId      = videoId;
        s.hostUid      = hostUid;
        s.playing      = playing;
        s.positionMs   = positionMs;
        s.playbackRate = playbackRate;
        s.updatedAtMs  = updatedAtMs;
        s.seq          = seq;
        s.lastActionBy = lastActionBy;
        s.lastAction   = lastAction;
        return s;
    }

    @Override
    public String toString() {
        return "WatchTogetherState{active=" + active
                + ", videoId='" + videoId + '\''
                + ", host='" + hostUid + '\''
                + ", playing=" + playing
                + ", positionMs=" + positionMs
                + ", rate=" + playbackRate
                + ", seq=" + seq
                + ", lastAction='" + lastAction + '\''
                + ", lastActionBy='" + lastActionBy + '\''
                + '}';
    }
}
