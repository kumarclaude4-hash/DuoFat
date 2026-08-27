package com.duoshield.app.call;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.duoshield.app.BuildConfig;
import com.duoshield.app.util.DevicePerformanceTier;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the WebRTC peer connection and all signaling state for a single call.
 *
 * <p>Signaling flow (caller):
 * <ol>
 *   <li>{@link #startCall} → creates local media, makes SDP offer, writes to Firestore</li>
 *   <li>Listens for answer SDP and ICE candidates from callee</li>
 * </ol>
 *
 * <p>Signaling flow (callee):
 * <ol>
 *   <li>{@link #acceptCall} → creates local media, sets remote offer, makes answer, writes to Firestore</li>
 *   <li>Listens for ICE candidates from caller</li>
 * </ol>
 */
public class CallManager {

    private static final String TAG = "CallManager";

    public interface CallListener {
        void onCallStateChanged(CallState state);
        void onRemoteVideoTrack(VideoTrack track);
        void onLocalVideoTrack(VideoTrack track);
        void onError(String message);

        /**
         * Fired when the shared call-start anchor is known, so the duration timer can run in
         * lock-step on both devices instead of each side counting from its own ICE-connect
         * moment. {@code callStartLocalMs} is already translated into this device's
         * {@link System#currentTimeMillis()} frame, so it can be subtracted directly.
         *
         * <p>May fire more than once (e.g. once optimistically, then again once the clock
         * offset is measured); the newest value always wins.
         */
        default void onCallTimerAnchor(long callStartLocalMs) { }

        /**
         * Fired when the local preview's mirroring should change because the active camera
         * switched. Front-facing previews are mirrored so they read like a mirror; rear-facing
         * previews are not.
         */
        default void onLocalMirrorChanged(boolean mirror) { }

        /**
         * Fired when the <em>peer</em> starts or stops recording the call, as published in the
         * shared call document's {@code recording.<uid>} field. The UI must make this audible and
         * visible to this user — an on-screen indicator plus a spoken notice — so nobody is
         * recorded without knowing. Fires only on an actual change of state.
         */
        default void onPeerRecordingChanged(boolean peerRecording) { }
    }

    public enum CallState {
        IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, CONNECTED, ENDED, FAILED
    }

    private final Context context;
    private final CallSignalRepository repo;
    private CallListener listener;

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    /**
     * Explicitly-owned audio device module.
     *
     * <p>The factory would create one internally, but only an app-owned module exposes the
     * post-AEC mic tap that {@link CallAudioRecorder} needs. Owning it also means we are
     * responsible for releasing it in {@link #cleanup}.
     */
    private AudioDeviceModule audioDeviceModule;
    /**
     * Call recorder. Created alongside the factory and kept for the whole call so its taps can
     * stay attached; it ignores audio until {@link #startRecording} opens the gate.
     */
    private CallAudioRecorder callRecorder;
    private PeerConnection peerConnection;
    private CameraVideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    /** Remote video track, retained so secondary screens can render a live PiP. */
    private VideoTrack remoteVideoTrack;
    /**
     * Remote audio track, retained so "Turn off sound" can silence playback at the WebRTC
     * layer instead of fighting the system call volume.
     */
    private AudioTrack remoteAudioTrack;
    /** Current microphone mute state, mirrored so other screens can draw the badge. */
    private boolean micMuted = false;
    /**
     * True while the front (selfie) camera is active. Drives local-preview mirroring: the
     * self-view is mirrored on the front camera and un-mirrored on the rear one.
     */
    private boolean isFrontCamera = true;

    // ── Synced duration timer state ───────────────────────────────────────────
    /**
     * Offset between this device's clock and the Firestore server clock, as
     * {@code serverMs - localMs}. Measured once per call via a server-timestamp probe so the
     * shared {@code connectedAt} anchor can be translated into local time without inheriting
     * wall-clock skew between the two devices.
     */
    private long    clockOffsetMs      = 0L;
    private boolean clockOffsetKnown   = false;
    /** Local timestamp taken immediately before the clock probe write is issued. */
    private long    clockProbeSentMs   = 0L;
    /** Raw server value of {@code connectedAt}, held until the clock offset resolves. */
    private long    pendingAnchorServerMs = 0L;
    /** Guards against re-issuing the anchor write/probe on every CONNECTED transition. */
    private boolean anchorRequested    = false;
    /** Last anchor already published to the listener, to avoid redundant callbacks. */
    private long    publishedAnchorMs  = 0L;
    private final Handler anchorFallbackHandler = new Handler(Looper.getMainLooper());
    /**
     * True while local playback of the partner's audio is disabled ("Turn off sound").
     * Kept separate from {@link #micMuted}: this only silences what <em>we</em> hear, the
     * partner still receives our microphone.
     */
    private boolean outputMuted = false;

    /**
     * The one route controller for this call. Shared with {@link CallActivity} so the picker,
     * the header button and {@link #setSpeakerOn(boolean)} all read and write the same state —
     * separate instances would each keep their own mute bookkeeping and drift apart.
     */
    private AudioRouteController audioRoute;

    /**
     * The manager driving the call that is on screen right now, or {@code null} when no call
     * is active.
     *
     * <p>Needed because the live WebRTC tracks and the shared EGL context belong to this
     * object, while {@link InCallChatActivity} is a separate activity that has to render the
     * same video into its floating PiP. Re-creating a capturer there would fight the running
     * call for the camera, so the chat screen attaches an extra sink to these tracks instead.
     * Cleared in {@link #cleanup(boolean)} so the reference can never outlive the tracks.
     */
    private static volatile CallManager active;

    private String callId;
    private String myUid;
    private String partnerUid;
    private boolean isCaller;
    private boolean isVideo;

    /**
     * Last observed value of the peer's {@code recording.<partnerUid>} flag, so the disclosure
     * callback fires only on an actual change rather than on every snapshot. {@code null} means
     * "not yet observed".
     */
    private Boolean lastPeerRecording = null;
    /** F6: chatId passed to the call doc so Firestore rules can enforce bilateral contact gate. */
    private String chatIdForCall;

    private ListenerRegistration callDocListener;
    private ListenerRegistration remoteCandidateListener;

    private final List<IceCandidate> pendingCandidates = new ArrayList<>();
    private boolean remoteDescSet = false;
    /** SDP of the most-recently applied ICE-restart offer — prevents double-applying the same offer. */
    private String lastRestartOfferSdp = null;

    private CallState currentState = CallState.IDLE;

    // ── Bandwidth tracking ───────────────────────────────────────────��────────
    /** Interval between WebRTC stats polls while connected. */
    private static final long STATS_POLL_INTERVAL_MS = 10_000L;
    /** Cumulative transport bytes (sent + received) accumulated this call. */
    private long sessionBytesTotal = 0L;
    /** Last snapshot of transport bytes from the stats report (to compute deltas). */
    private long lastTransportBytesSent     = -1L;
    private long lastTransportBytesReceived = -1L;
    /**
     * True once WebRTC stats confirm the selected candidate pair is a relay
     * (i.e. traffic is actually going through Cloudflare TURN).  We only
     * accumulate bytes into {@link #sessionBytesTotal} — and thus charge against
     * the monthly Cloudflare quota — when this flag is set.  P2P calls (srflx
     * or host candidate pairs) are free and must not erode the quota counter.
     */
    private boolean isRelayCall = false;
    /**
     * Used to hop back to the main thread for listener callbacks that originate off it
     * (e.g. WebRTC camera-switch callbacks, which arrive on a camera thread).
     */
    private final Handler mainHandler  = new Handler(Looper.getMainLooper());
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsPollRunnable = new Runnable() {
        @Override public void run() {
            collectStats();
            statsHandler.postDelayed(this, STATS_POLL_INTERVAL_MS);
        }
    };

    // ── ICE restart on disconnect ─────────────────────────────────────────────
    /** How long to wait after DISCONNECTED before triggering an ICE restart (ms).
     * Reduced from 5 s → 2 s: modern networks (WiFi↔LTE handover) recover within
     * 1–2 s, so the 5 s grace period was adding unnecessary perceived lag. */
    private static final long ICE_RESTART_DELAY_MS = 2_000L;

    /**
     * How long to wait for the server clock probe before giving up and trusting the local
     * clock for the timer anchor. Keeps the duration display from stalling at 00:00 when the
     * probe cannot resolve.
     */
    private static final long ANCHOR_FALLBACK_MS = 3_000L;
    private final Handler  iceRestartHandler  = new Handler(Looper.getMainLooper());
    private final Runnable iceRestartRunnable = new Runnable() {
        @Override public void run() {
            if (peerConnection == null || currentState == CallState.ENDED
                    || currentState == CallState.FAILED) return;
            Log.w(TAG, "ICE still disconnected after grace period — triggering ICE restart");
            // restartIce() marks the local description as needing a new ICE ufrag/pwd.
            // The caller re-offers; the callee re-answers. This recovers from transient
            // network changes (WiFi→LTE hand-off, brief VPN reconnection, etc.).
            if (isCaller) {
                // Caller side: create a new offer with iceRestart=true
                MediaConstraints restartConstraints = new MediaConstraints();
                restartConstraints.mandatory.add(
                        new MediaConstraints.KeyValuePair("IceRestart", "true"));
                // Do NOT add OfferToReceiveAudio/Video — see createAnswer() for why.
                // Transceivers are already established; only the ICE credentials change.
                peerConnection.createOffer(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription sdp) {
                        peerConnection.setLocalDescription(new SdpObserver() {
                            @Override public void onCreateSuccess(SessionDescription s) {}
                            @Override public void onSetSuccess() {
                                // Write the restart offer so the callee picks it up.
                                repo.writeRestartOffer(callId, sdp.description);
                            }
                            @Override public void onCreateFailure(String s) {}
                            @Override public void onSetFailure(String s) {
                                Log.w(TAG, "ICE restart setLocal failed: " + s);
                            }
                        }, sdp);
                    }
                    @Override public void onSetSuccess() {}
                    @Override public void onCreateFailure(String s) {
                        Log.w(TAG, "ICE restart createOffer failed: " + s);
                    }
                    @Override public void onSetFailure(String s) {}
                }, restartConstraints);
            } else {
                // Callee side: the caller will push a new offer; we just wait for it.
                // Signal the caller that we need a restart via a Firestore flag.
                repo.requestIceRestart(callId);
            }
        }
    };

    // ── Connection timeout ────────────────────────────────────────────────────
    /**
     * Maximum time to wait for ICE to reach CONNECTED/COMPLETED before giving up.
     * WhatsApp uses ~60 s; Signal uses ~45 s.  We use 60 s to be generous on
     * slow-start TURN relays while still not leaving the caller hanging indefinitely.
     */
    private static final long CONNECTION_TIMEOUT_MS = 60_000L;
    private final Handler  connectionTimeoutHandler  = new Handler(Looper.getMainLooper());
    private final Runnable connectionTimeoutRunnable = () -> {
        if (currentState != CallState.CONNECTED
                && currentState != CallState.ENDED
                && currentState != CallState.FAILED) {
            Log.w(TAG, "Connection timed out after " + CONNECTION_TIMEOUT_MS / 1000 + "s");
            setState(CallState.FAILED);
            if (listener != null) {
                listener.onError("Call connection timed out — check your network");
            }
            cleanup(true);
        }
    };

    // ── Tiered capture / bitrate policy ──────────────────────────────────────
    /**
     * Hard bitrate ceiling for {@link DevicePerformanceTier#LOW} devices.
     *
     * <p>Applies to any device built entirely from in-order ARM cores — the POCO C51 /
     * Helio G36 (4× Cortex-A53) this was originally written for, but equally the
     * MediaTek Helio P35 / MT6765 (8× Cortex-A53 @ 2.2/1.6 GHz) and the rest of the
     * budget arm64 field.  Extra A53 cores do not help a real-time encoder: the
     * bottleneck is single-core throughput, and these are in-order designs.
     *
     * <p>640×480 @ 20 fps + 600 kbps is the largest operating point that stays below
     * the thermal threshold on a multi-minute call in a warm room.
     */
    private static final int VIDEO_BITRATE_LOW_MAX_BPS   =  600_000;
    private static final int VIDEO_BITRATE_LOW_MIN_BPS   =  150_000;
    private static final int AUDIO_BITRATE_LOW_MAX_BPS   =   20_000;
    private static final int AUDIO_BITRATE_LOW_MIN_BPS   =   16_000;

    /**
     * Ceiling for {@link DevicePerformanceTier#MID} devices — hardware we could not
     * positively identify as a flagship.  Capped rather than unbounded, because an
     * unknown device is far more likely to be budget silicon than a flagship.
     */
    private static final int VIDEO_BITRATE_MID_MAX_BPS   = 1_200_000;
    private static final int VIDEO_BITRATE_MID_MIN_BPS   =   250_000;
    private static final int AUDIO_BITRATE_MID_MAX_BPS   =    24_000;
    private static final int AUDIO_BITRATE_MID_MIN_BPS   =    20_000;

    /**
     * BWE floor for {@link DevicePerformanceTier#HIGH} devices — the minimum bitrate
     * libwebrtc's congestion controller is allowed to settle at.  Without a floor, BWE
     * may drop all the way to ~30 kbps on a saturated network, causing severe
     * pixelation.  With a floor it degrades gracefully to the lowest still-watchable
     * quality and only drops further if the network genuinely cannot sustain even that.
     *
     * <p>No {@code maxBitrateBps} is set for HIGH; instead, libwebrtc's Transport-CC
     * congestion controller picks the ceiling dynamically.  On a good WiFi connection
     * it will climb to 2–4 Mbps naturally; on LTE it typically stabilises at 500–
     * 1 500 kbps — all without hard-coding a number that is almost certainly wrong
     * for some fraction of network conditions.
     */
    private static final int VIDEO_BITRATE_HIGH_MIN_BPS  =  300_000;
    private static final int AUDIO_BITRATE_HIGH_MIN_BPS  =   24_000;

    /**
     * Quality ladder used by the thermal watchdog.  Each step is
     * {@code {width, height, fps, videoBps}}, ordered best → worst.  A call starts at
     * the step matching its {@link DevicePerformanceTier} and walks down under thermal
     * or encoder-FPS pressure, then back up once the device recovers.
     */
    private static final int[][] QUALITY_LADDER = {
            {1280, 720, 30, 0 /* 0 == unbounded, let BWE decide */},
            { 960, 540, 24, VIDEO_BITRATE_MID_MAX_BPS},
            { 640, 480, 20, VIDEO_BITRATE_LOW_MAX_BPS},
            { 480, 360, 18, 350_000},
            { 320, 240, 15, 150_000},
    };

    private static final int LADDER_HIGH_START = 0;
    private static final int LADDER_MID_START  = 1;
    private static final int LADDER_LOW_START  = 2;

    /**
     * Thermal step: if the outbound-rtp encoder delivers fewer than this many frames
     * per second, step one rung down the {@link #QUALITY_LADDER}.
     */
    private static final float THERMAL_FPS_THRESHOLD  = 12.0f;

    /**
     * Encoder FPS above which the call is considered healthy enough to consider
     * stepping back up.  The gap between this and {@link #THERMAL_FPS_THRESHOLD} is
     * the hysteresis band that stops the ladder oscillating.
     */
    private static final float RECOVERY_FPS_THRESHOLD = 18.0f;

    /** Consecutive healthy polls required before stepping back up a rung. */
    private static final int RECOVERY_POLLS_REQUIRED = 3;

    /**
     * Consecutive <em>unhealthy</em> polls required before stepping down a rung.
     *
     * <p>Previously a single sub-threshold poll stepped down immediately. Encoder FPS measured
     * over one short poll window is noisy — a GC pause, a keyframe request, or the camera
     * ramping exposure after a lighting change all dip it briefly — so one bad sample is not
     * evidence of thermal pressure. Requiring two consecutive samples costs one poll interval
     * of reaction time and removes the large majority of spurious downgrades.
     */
    private static final int DOWNGRADE_POLLS_REQUIRED = 2;

    /**
     * Minimum time a rung must be held before any further change is allowed.
     *
     * <p>This is the fix for mid-call glitching and the camera appearing to flip on its own.
     * Every rung change calls {@code changeCaptureFormat()}, which tears down and re-creates
     * the camera capture session: the preview drops frames, the encoder emits a fresh
     * keyframe, and on many devices the surface is re-created — which is the visible "flip".
     * Without a dwell floor, a device sitting near the FPS threshold could ping-pong between
     * rungs on consecutive polls and restart the capturer every few seconds for the entire
     * call. The ladder is a slow adaptation mechanism, so a floor here costs nothing real.
     */
    private static final long MIN_QUALITY_DWELL_MS = 12_000L;

    /**
     * Current index into {@link #QUALITY_LADDER}. Written on the main thread, read from the
     * WebRTC stats callback thread, hence volatile.
     */
    private volatile int qualityStep = LADDER_HIGH_START;
    /** Best rung this call is allowed to use, set from the device tier at connect. */
    private volatile int qualityCeiling = LADDER_HIGH_START;
    /** Consecutive healthy polls observed since the last downgrade. */
    private int healthyPolls = 0;
    /** Consecutive unhealthy polls observed since the last upgrade. */
    private int unhealthyPolls = 0;
    /**
     * {@link android.os.SystemClock#elapsedRealtime()} of the last rung change, used to enforce
     * {@link #MIN_QUALITY_DWELL_MS}. Uses elapsedRealtime rather than currentTimeMillis so an
     * NTP correction mid-call cannot make the dwell window appear to have already elapsed.
     */
    private long lastQualityChangeMs = 0L;
    /** Thermal listener registered for proactive downgrades (API 29+). */
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    /** framesEncoded counter from the last outbound-rtp stats snapshot (for FPS delta). */
    private long lastFramesEncoded = -1L;
    /** Timestamp (ms) of the last outbound-rtp stats snapshot. */
    private long lastFramesTs = -1L;

    public CallManager(Context context) {
        this.context = context.getApplicationContext();
        // Live call quality is intentionally tier-independent. DevicePerformanceTier governs
        // app/UI resource budgets only; capture resolution, frame rate and bitrate keep the
        // existing maximum-quality call target on every supported phone.
        DevicePerformanceTier.get(this.context); // Resolve once for non-sensitive diagnostics.
        this.qualityCeiling = LADDER_HIGH_START;
        this.qualityStep = LADDER_HIGH_START;
        this.repo = new CallSignalRepository();
    }

    public void setListener(CallListener listener) {
        this.listener = listener;
    }

    public EglBase getEglBase() {
        return eglBase;
    }

    /**
     * Initialises the EGL context and PeerConnectionFactory without starting a call.
     *
     * <p>CallActivity calls this <em>before</em> {@code startCall}/{@code acceptCall} so
     * that {@link #getEglBase()} is non-null by the time {@code initVideoRenderers()} runs.
     * Without this the renderers are initialised only <em>after</em> {@code createLocalTracks()}
     * has already called {@link CallListener#onLocalVideoTrack}, resulting in
     * {@code addSink()} being called on an uninitialised {@code SurfaceViewRenderer} — causing
     * the "You" PiP to be blank for the entire call.
     */
    public void prepareEgl() {
        initFactory();
        // Publish before tracks exist: the chat screen re-reads the tracks each time it binds,
        // so being visible early costs nothing and avoids a race on fast call setup.
        active = this;
    }

    public String getCallId() {
        return callId;
    }

    public boolean isVideo() {
        return isVideo;
    }

    // ─── Initialise WebRTC ───────────────────────────────────────────────────

    private void initFactory() {
        if (factory != null) return;
        eglBase = EglBase.create();
        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        // The recorder must exist before the module is built, because the mic tap is registered
        // as part of the builder and cannot be attached afterwards.
        callRecorder = new CallAudioRecorder();

        // Build the audio device module explicitly rather than letting the factory create one
        // internally. This is what makes the post-AEC mic tap reachable.
        //
        // Every setting here is chosen to match the module WebRTC would have built by default,
        // so this swap changes what we can *observe* about the audio, not how it behaves:
        //   - VOICE_COMMUNICATION source keeps the platform's telephony tuning.
        //   - Hardware AEC/NS are left enabled, so echo cancellation is unchanged.
        // Deviating here would risk regressing echo/noise on live calls for every user, whether
        // or not they ever record.
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setSamplesReadyCallback(callRecorder)
                .createAudioDeviceModule();

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    // ─── ICE server config ───────────────────────────────────────────────────

    /**
     * Builds the ICE server list for the peer connection.
     *
     * <p>STUN: always adds Cloudflare + Google public STUN servers.
     *
     * <p>TURN: reads credentials from {@link TurnCredentialCache}, which is
     * populated asynchronously by {@link TurnCredentialFetcher#prefetch} before
     * the call starts.  If the cache is empty (server unreachable at call start)
     * the call proceeds with STUN only and may fail on strict CGNAT.
     *
     * <p>Bandwidth guard: if {@link TurnBandwidthTracker#isLimitReached()} returns
     * {@code true} the TURN servers are skipped entirely.
     */
    private List<PeerConnection.IceServer> buildIceServers() {
        List<PeerConnection.IceServer> list = new ArrayList<>();

        // Always include STUN so direct P2P works regardless of TURN availability.
        // Multiple STUN servers across different providers give ICE more candidate
        // sources, reducing the time to find a working reflexive (srflx) candidate
        // on restricted NATs. Google provides 4 STUN servers that round-robin across
        // different PoPs; Cloudflare and Metered add geographic diversity.
        list.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478")
                .createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                .createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302")
                .createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302")
                .createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302")
                .createIceServer());

        TurnCredentialCache cache = TurnCredentialCache.get();
        if (!cache.isValid()) {
            Log.w(TAG, "TURN credentials not cached — CGNAT calls may fail");
            return list;
        }

        // Bandwidth cap check — skip TURN if 900 GB monthly limit is reached.
        TurnBandwidthTracker tracker = TurnBandwidthTracker.get(context);
        if (tracker.isLimitReached()) {
            Log.e(TAG, "TURN monthly cap reached (" + tracker.getSummary()
                    + ") — TURN disabled for this call. Call may fail on CGNAT.");
            if (listener != null) {
                listener.onError("Monthly TURN data cap reached (100 GB). "
                        + "Calls over restricted networks may not connect until next month.");
            }
            return list;
        }

        if (tracker.isNearLimit()) {
            Log.w(TAG, "TURN near monthly cap: " + tracker.getSummary());
        }

        String   turnUser = cache.getUsername();
        String   turnCred = cache.getCredential();
        // All URLs from Cloudflare share the same username/credential.
        for (String url : cache.getUrls()) {
            if (url != null && !url.isEmpty()) {
                list.add(PeerConnection.IceServer.builder(url)
                        .setUsername(turnUser)
                        .setPassword(turnCred)
                        .createIceServer());
                Log.d(TAG, "Added TURN server: " + url);
            }
        }
        return list;
    }

    // ─── PeerConnection ──────────────────────────────────────────────────────

    private PeerConnection createPeerConnection() {
        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(buildIceServers());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        // Default: ALL transport types — the same strategy WhatsApp and Signal use.
        //
        // The ICE agent gathers host, STUN-reflexive (srflx), and TURN relay
        // candidates simultaneously and runs connectivity checks in parallel.
        // Whichever path succeeds first wins:
        //   • Same LAN / hotspot  → host candidate connects in <100 ms
        //   • Different networks  → srflx (STUN) or relay (TURN) connects in 1-3 s
        //
        // RELAY-only guarantees the call partner never learns this device's real
        // IP (host/srflx candidates are never gathered or offered) at the cost of
        // always routing media through the Cloudflare TURN relay and burning TURN
        // quota. It is opt-in via Settings → Security & Privacy → "Relay-only
        // calls" (F8 fix) because most users prefer the lower latency of ALL.
        boolean relayOnly = context
                .getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE)
                .getBoolean("relay_only_calls_enabled", false);
        config.iceTransportsType = relayOnly
                ? PeerConnection.IceTransportsType.RELAY
                : PeerConnection.IceTransportsType.ALL;
        Log.d(TAG, "createPeerConnection: iceTransportsType=" + config.iceTransportsType);

        return factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState s) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE state: " + s);
                if (s == PeerConnection.IceConnectionState.CONNECTED
                        || s == PeerConnection.IceConnectionState.COMPLETED) {
                    // Cancel any pending ICE-restart timer — we're good again.
                    iceRestartHandler.removeCallbacks(iceRestartRunnable);
                    setState(CallState.CONNECTED);
                } else if (s == PeerConnection.IceConnectionState.FAILED) {
                    iceRestartHandler.removeCallbacks(iceRestartRunnable);
                    setState(CallState.FAILED);
                    if (listener != null) listener.onError("Call failed — network unavailable");
                } else if (s == PeerConnection.IceConnectionState.DISCONNECTED) {
                    // Schedule an ICE restart if the connection does not recover on its own.
                    // WhatsApp / Signal both use a ~5-second grace period before restarting.
                    Log.w(TAG, "ICE disconnected — scheduling restart in "
                            + ICE_RESTART_DELAY_MS + " ms");
                    iceRestartHandler.removeCallbacks(iceRestartRunnable);
                    iceRestartHandler.postDelayed(iceRestartRunnable, ICE_RESTART_DELAY_MS);
                } else if (s == PeerConnection.IceConnectionState.CLOSED) {
                    iceRestartHandler.removeCallbacks(iceRestartRunnable);
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                if (isCaller) {
                    repo.addCallerCandidate(callId, candidate.sdp,
                            candidate.sdpMid, candidate.sdpMLineIndex);
                } else {
                    repo.addCalleeCandidate(callId, candidate.sdp,
                            candidate.sdpMid, candidate.sdpMLineIndex);
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {}

            @Override
            public void onAddStream(MediaStream stream) {}

            @Override
            public void onRemoveStream(MediaStream stream) {}

            @Override
            public void onDataChannel(org.webrtc.DataChannel dc) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                org.webrtc.MediaStreamTrack track = receiver.track();
                if (track instanceof VideoTrack) {
                    VideoTrack remote = (VideoTrack) track;
                    remote.setEnabled(true);
                    remoteVideoTrack = remote;
                    if (listener != null) listener.onRemoteVideoTrack(remote);
                } else if (track instanceof AudioTrack) {
                    // Root-cause fix for one-way audio: the remote AudioTrack can arrive
                    // in a disabled state on certain devices / libwebrtc builds.
                    // Without this explicit enable() call, the local user hears the remote
                    // peer but the remote peer hears nothing — even though both sides
                    // have added their local audio tracks to the PeerConnection.
                    // Honour a "Turn off sound" selection made before the track arrived,
                    // otherwise the partner's audio would suddenly become audible again the
                    // moment the remote track lands.
                    remoteAudioTrack = (AudioTrack) track;
                    track.setEnabled(!outputMuted);
                    Log.d(TAG, "Remote audio track received, enabled=" + !outputMuted);

                    // Attach the recorder's sink unconditionally. The recorder discards audio
                    // while inactive, and attaching here (rather than in startRecording) is what
                    // makes the "started recording before the remote track arrived" case work —
                    // the track can land seconds after the user hits record.
                    if (callRecorder != null) {
                        try {
                            remoteAudioTrack.addSink(callRecorder);
                        } catch (Exception e) {
                            // Never let a recording-only failure break call audio.
                            Log.w(TAG, "Failed to attach recorder sink to remote track", e);
                        }
                    }
                }
            }
        });
    }

    // ─── Local media ─────────────────────────────────────────────────────────

    private void createLocalTracks(boolean withVideo) {
        // Echo cancellation, noise suppression, and AGC are mandatory for voice quality.
        // Without these the remote party hears their own voice echoed back and
        // background noise bleeds through during silence gaps — the same constraints
        // WhatsApp, Meet, and Signal use in their Android WebRTC stacks.
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googEchoCancellation2", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googDAEchoCancellation", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googAutoGainControl2", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"));
        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);

        if (withVideo) {
            videoCapturer = createCameraCapturer();
            if (videoCapturer != null) {
                surfaceTextureHelper = SurfaceTextureHelper.create(
                        "CaptureThread", eglBase.getEglBaseContext());
                videoSource = factory.createVideoSource(videoCapturer.isScreencast());
                videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
                videoCapturer.startCapture(captureWidth(), captureHeight(), captureFps());
                localVideoTrack = factory.createVideoTrack("video0", videoSource);
                localVideoTrack.setEnabled(true);
                if (listener != null) listener.onLocalVideoTrack(localVideoTrack);
            } else {
                // No camera device found — most commonly caused by CAMERA permission being
                // denied, or the device reporting no cameras.  Surface this to the UI so the
                // user isn't left staring at a black PiP with no explanation.
                Log.e(TAG, "createCameraCapturer() returned null — no camera available " +
                        "(check CAMERA permission and device hardware)");
                if (listener != null) {
                    listener.onError("Camera unavailable — check app permissions in Settings");
                }
            }
        }

        MediaConstraints pcConstraints = new MediaConstraints();
        peerConnection.addTrack(localAudioTrack, Arrays.asList("stream0"));
        if (localVideoTrack != null) {
            peerConnection.addTrack(localVideoTrack, Arrays.asList("stream0"));
        }
    }

    // ─── Adaptive video capture resolution ───────────────────────────────────
    // Budget SoCs built from in-order Cortex-A53/A55 cores — the POCO C51's Helio G36
    // (4× A53) and the Helio P35 / MT6765 (8× A53 @ 2.2/1.6 GHz) alike — saturate the
    // CPU within seconds encoding 1280×720 @ 30 fps, triggering thermal throttle,
    // dropped frames and ANR-level audio glitches.
    //
    // This used to be gated on SUPPORTED_64_BIT_ABIS being empty, which quietly gave
    // every arm64 budget phone the flagship path. Resolution now comes from
    // DevicePerformanceTier (CPU microarchitecture first, then RAM), and the live
    // thermal watchdog moves qualityStep along QUALITY_LADDER from there.

    /** Best ladder rung a device of the given tier may start at. */
    private static int ladderStartForTier(DevicePerformanceTier tier) {
        switch (tier) {
            case LOW:
                return LADDER_LOW_START;
            case MID:
                return LADDER_MID_START;
            case HIGH:
            default:
                return LADDER_HIGH_START;
        }
    }

    private int captureWidth()    { return QUALITY_LADDER[qualityStep][0]; }
    private int captureHeight()   { return QUALITY_LADDER[qualityStep][1]; }
    private int captureFps()      { return QUALITY_LADDER[qualityStep][2]; }
    /** Video ceiling for the current rung; {@code 0} means "unbounded, let BWE decide". */
    private int captureVideoBps() { return QUALITY_LADDER[qualityStep][3]; }

    private CameraVideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(context)
                ? new Camera2Enumerator(context)
                : new Camera1Enumerator(true);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                isFrontCamera = true;
                return enumerator.createCapturer(name, null);
            }
        }
        for (String name : enumerator.getDeviceNames()) {
            // No front camera on this device — fall back to whatever exists and record the
            // real facing so the preview is not mirrored incorrectly.
            isFrontCamera = enumerator.isFrontFacing(name);
            return enumerator.createCapturer(name, null);
        }
        return null;
    }

    // ─── Caller flow ─────────────────────────────────────────────────────────

    public void startCall(String myUid, String calleeId, boolean video) {
        startCall(myUid, calleeId, video, null);
    }

    /** F6: overload that accepts the chatId for the bilateral contact gate. */
    public void startCall(String myUid, String calleeId, boolean video, String chatId) {
        this.myUid = myUid;
        this.partnerUid = calleeId;
        this.isCaller = true;
        this.isVideo = video;
        this.callId = UUID.randomUUID().toString();
        this.chatIdForCall = chatId;

        setState(CallState.OUTGOING_RINGING);
        // Start connection watchdog — if ICE never reaches CONNECTED within 60 s, fail the call.
        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
        connectionTimeoutHandler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT_MS);
        initFactory();
        peerConnection = createPeerConnection();
        createLocalTracks(video);

        MediaConstraints offerConstraints = new MediaConstraints();
        offerConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        offerConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", video ? "true" : "false"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onSetSuccess() {
                        repo.createCallDoc(callId, myUid, calleeId,
                                video ? "video" : "voice", sdp.description, chatIdForCall,
                                new CallSignalRepository.OnCompleteCallback() {
                                    @Override public void onSuccess() {
                                        listenForAnswer();
                                        listenForCalleeCandidates();
                                    }
                                    @Override public void onFailure(Exception e) {
                                        setState(CallState.FAILED);
                                        if (listener != null) listener.onError("Failed to create call: " + e.getMessage());
                                    }
                                });
                    }
                    @Override public void onCreateFailure(String s) {}
                    @Override public void onSetFailure(String s) {
                        Log.e(TAG, "setLocalDescription failed: " + s);
                    }
                }, sdp);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String s) {
                Log.e(TAG, "createOffer failed: " + s);
                setState(CallState.FAILED);
                if (listener != null) listener.onError("Failed to create offer");
            }
            @Override public void onSetFailure(String s) {}
        }, offerConstraints);
    }

    private void listenForAnswer() {
        callDocListener = repo.listenToCall(callId, (snap, e) -> {
            if (e != null || snap == null) return;
            // Doc deleted (e.g. remote hangup called deleteCallDoc) → treat as ENDED.
            if (!snap.exists()) {
                setState(CallState.ENDED);
                cleanup(true);
                return;
            }
            String status = snap.getString("status");

            if ("declined".equals(status) || "ended".equals(status)
                    || "missed".equals(status) || "timeout".equals(status)) {
                setState(CallState.ENDED);
                cleanup(true);
                return;
            }

            // Shared call-start anchor + server clock probe (see requestTimerAnchor()).
            handleTimerAnchor(snap);

            // Peer recording disclosure — indicator + audible notice on change.
            handlePeerRecording(snap);

            // Callee wrote a restart offer into the doc — apply it.
            Object restartObj = snap.get("restartOffer");
            if (restartObj instanceof java.util.Map && remoteDescSet && peerConnection != null) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> restartOffer =
                        (java.util.Map<String, Object>) restartObj;
                String restartSdp = (String) restartOffer.get("sdp");
                // Only process once — check if this is newer than what we have.
                String marker = restartOffer.get("ts") != null
                        ? restartOffer.get("ts").toString() : "";
                if (restartSdp != null && !restartSdp.isEmpty()
                        && !restartSdp.equals(lastRestartOfferSdp)) {
                    lastRestartOfferSdp = restartSdp;
                    SessionDescription restartDesc =
                            new SessionDescription(SessionDescription.Type.OFFER, restartSdp);
                    peerConnection.setRemoteDescription(new SdpObserver() {
                        @Override public void onCreateSuccess(SessionDescription s) {}
                        @Override public void onSetSuccess() { createAnswer(); }
                        @Override public void onCreateFailure(String s) {}
                        @Override public void onSetFailure(String s) {
                            Log.w(TAG, "ICE restart setRemoteDesc (caller→callee) failed: " + s);
                        }
                    }, restartDesc);
                }
            }

            if (remoteDescSet) return;
            Object answerObj = snap.get("answer");
            if (!(answerObj instanceof java.util.Map)) return;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> answer = (java.util.Map<String, Object>) answerObj;
            String sdp = (String) answer.get("sdp");
            if (sdp == null || sdp.isEmpty()) return;

            remoteDescSet = true;
            setState(CallState.CONNECTING);
            SessionDescription remoteSdp = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription s) {}
                @Override public void onSetSuccess() {
                    drainPendingCandidates();
                }
                @Override public void onCreateFailure(String s) {}
                @Override public void onSetFailure(String s) {
                    Log.e(TAG, "setRemoteDescription (answer) failed: " + s);
                }
            }, remoteSdp);
        });
    }

    // ─── Callee flow ──────────────────────────────���──────────────────────────

    public void acceptCall(String myUid, String callId, boolean video) {
        this.myUid = myUid;
        this.isCaller = false;
        this.isVideo = video;
        this.callId = callId;

        setState(CallState.CONNECTING);
        // Start connection watchdog — if ICE never reaches CONNECTED within 60 s, fail the call.
        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
        connectionTimeoutHandler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT_MS);
        initFactory();
        peerConnection = createPeerConnection();
        createLocalTracks(video);

        repo.callRef(callId).get().addOnSuccessListener(snap -> {
            if (!snap.exists()) {
                setState(CallState.FAILED);
                if (listener != null) listener.onError("Call no longer available");
                return;
            }
            partnerUid = snap.getString("callerId");
            Object offerObj = snap.get("offer");
            if (!(offerObj instanceof java.util.Map)) return;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> offer = (java.util.Map<String, Object>) offerObj;
            String offerSdp = (String) offer.get("sdp");
            if (offerSdp == null) return;

            SessionDescription remoteSdp = new SessionDescription(SessionDescription.Type.OFFER, offerSdp);
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription s) {}
                @Override public void onSetSuccess() {
                    remoteDescSet = true;
                    drainPendingCandidates();
                    createAnswer();
                }
                @Override public void onCreateFailure(String s) {}
                @Override public void onSetFailure(String s) {
                    Log.e(TAG, "setRemoteDescription (offer) failed: " + s);
                }
            }, remoteSdp);
        });

        listenForCallerCandidates();
        listenForCallStatus();
    }

    private void createAnswer() {
        // UNIFIED_PLAN: do NOT pass OfferToReceiveAudio/Video here.
        //
        // These are legacy Plan-B constraints. In UNIFIED_PLAN the direction of
        // each m-line is governed exclusively by the transceivers that were created
        // via addTrack() — not by these constraints. However, some versions of
        // libwebrtc-android still process the mandatory constraints and use them to
        // override transceiver direction, forcing the callee's audio/video m-lines
        // to "recvonly" even though addTrack() set up a "sendrecv" transceiver.
        // The result: the callee's audio never reaches the caller — one-way audio.
        //
        // The fix: pass empty constraints. The transceivers from addTrack() already
        // encode the correct "sendrecv" direction; nothing needs to override them.
        MediaConstraints constraints = new MediaConstraints();

        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onSetSuccess() {
                        repo.writeAnswer(callId, sdp.description, null);
                    }
                    @Override public void onCreateFailure(String s) {}
                    @Override public void onSetFailure(String s) {
                        Log.e(TAG, "setLocalDescription (answer) failed: " + s);
                    }
                }, sdp);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String s) {
                Log.e(TAG, "createAnswer failed: " + s);
            }
            @Override public void onSetFailure(String s) {}
        }, constraints);
    }

    private void listenForCallStatus() {
        callDocListener = repo.listenToCall(callId, (snap, e) -> {
            if (e != null || snap == null) return;
            // Doc deleted (remote hangup called deleteCallDoc) → treat as ENDED.
            if (!snap.exists()) {
                setState(CallState.ENDED);
                cleanup(true);
                return;
            }
            String status = snap.getString("status");
            if ("ended".equals(status) || "declined".equals(status)
                    || "timeout".equals(status)) {
                setState(CallState.ENDED);
                cleanup(true);
                return;
            }

            // Shared call-start anchor + server clock probe (see requestTimerAnchor()).
            handleTimerAnchor(snap);

            // Peer recording disclosure — indicator + audible notice on change.
            handlePeerRecording(snap);

            // Caller requested an ICE restart — create a new answer.
            Object restartFlagObj = snap.get("iceRestartRequested");
            if (Boolean.TRUE.equals(restartFlagObj) && remoteDescSet
                    && peerConnection != null && !isCaller) {
                // Clear the flag so we don't re-trigger on the same event.
                repo.clearIceRestartFlag(callId);
                // The caller will push a new offer via restartOffer field; handled in answer path.
                // For callee, just trigger a new answer from whatever offer the caller updates.
                Log.d(TAG, "Callee received ICE restart request from caller");
            }
        });
    }

    // ─── ICE candidate listeners ─────────────────────────────────────────────

    private void listenForCalleeCandidates() {
        remoteCandidateListener = repo.listenToCalleeCandidates(callId, (snap, e) -> {
            if (e != null || snap == null) return;
            for (DocumentChange change : snap.getDocumentChanges()) {
                if (change.getType() != DocumentChange.Type.ADDED) continue;
                addRemoteCandidate(change.getDocument());
            }
        });
    }

    private void listenForCallerCandidates() {
        remoteCandidateListener = repo.listenToCallerCandidates(callId, (snap, e) -> {
            if (e != null || snap == null) return;
            for (DocumentChange change : snap.getDocumentChanges()) {
                if (change.getType() != DocumentChange.Type.ADDED) continue;
                addRemoteCandidate(change.getDocument());
            }
        });
    }

    private void addRemoteCandidate(DocumentSnapshot doc) {
        String candidateSdp = doc.getString("candidate");
        String sdpMid = doc.getString("sdpMid");
        Long sdpMLineIndexLong = doc.getLong("sdpMLineIndex");
        if (candidateSdp == null || sdpMid == null || sdpMLineIndexLong == null) return;
        int sdpMLineIndex = sdpMLineIndexLong.intValue();

        IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, candidateSdp);
        if (remoteDescSet && peerConnection != null) {
            peerConnection.addIceCandidate(candidate);
        } else {
            pendingCandidates.add(candidate);
        }
    }

    private void drainPendingCandidates() {
        if (peerConnection == null) return;
        for (IceCandidate c : pendingCandidates) {
            peerConnection.addIceCandidate(c);
        }
        pendingCandidates.clear();
    }

    // ─── Controls ────────────────────────────────────────────────────────────

    public void setMuted(boolean muted) {
        micMuted = muted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
    }

    // ─── Shared access for secondary in-call screens ─────────────────────────

    /** The manager driving the call currently on screen, or {@code null} when idle. */
    public static CallManager getActive() { return active; }

    /** Live local (camera) track, or {@code null} before capture starts / after release. */
    public VideoTrack getLocalVideoTrack() { return localVideoTrack; }

    /** Live remote track, or {@code null} until the partner's video arrives. */
    public VideoTrack getRemoteVideoTrack() { return remoteVideoTrack; }

    /** Live remote audio track, or {@code null} until the partner's audio arrives. */
    public AudioTrack getRemoteAudioTrack() { return remoteAudioTrack; }

    /** Whether the microphone is muted — drawn as a badge on the chat screen's PiP. */
    public boolean isMicMuted() { return micMuted; }

    /**
     * Silences (or restores) local playback of the partner's audio by disabling the remote
     * track. This is what "Turn off sound" is: the outbound microphone is untouched, so the
     * partner keeps hearing us.
     *
     * @return {@code true} when the track state was actually applied. {@code false} means the
     *         remote track has not arrived yet — the flag is still remembered and applied in
     *         {@code onAddTrack}, so the caller can treat this as "queued", not "failed".
     */
    public boolean setOutputMuted(boolean muted) {
        outputMuted = muted;
        if (remoteAudioTrack == null) return false;
        try {
            remoteAudioTrack.setEnabled(!muted);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "remote audio track mute failed", e);
            return false;
        }
    }

    /** True while the partner's audio is muted locally. */
    public boolean isOutputMuted() { return outputMuted; }

    // ── Call recording ────────────────────────────────────────────────────────

    /**
     * Starts recording both sides of the call to a local, on-device file.
     *
     * <p>Recording is <b>disclosed</b>: the moment capture starts, {@code recording.<myUid>} is
     * published to the shared call document so the peer's device shows an indicator and plays an
     * audible spoken notice. The finished audio stays on this device — it is handed to {@code cb}
     * as a local file path for on-device storage only — but the fact that recording is happening
     * is always announced to the other party. Do not remove the {@code repo.setRecording} call.
     *
     * <p>{@code cb} is a UI listener only. Persisting the finished file is <i>not</i> its job and
     * must not be: the encoder flushes asynchronously, so on a hangup-while-recording the calling
     * Activity is already gone by the time the file is ready. This method therefore wraps {@code cb}
     * and hands every finished recording to the app-scoped {@link CallRecordingStore} first, then
     * forwards to {@code cb} for UI feedback if it is still interested.
     *
     * @return {@code false} if there is no active call or the encoder could not start, in which
     *         case {@code cb} is never invoked.
     */
    public boolean startRecording(CallAudioRecorder.Listener cb) {
        if (callRecorder == null) {
            Log.w(TAG, "startRecording ignored — no active call");
            return false;
        }
        if (callRecorder.isRecording()) return true;

        CallRecordingStore store = CallRecordingStore.get(context);
        CallAudioRecorder.Listener persisting = new CallAudioRecorder.Listener() {
            @Override public void onStopped(String filePath, int durationSeconds) {
                // Storage first, UI second: the store outlives the Activity, cb may not.
                store.onRecordingFinished(filePath);
                if (cb != null) cb.onStopped(filePath, durationSeconds);
            }
            @Override public void onError(String message) {
                if (cb != null) cb.onError(message);
            }
            @Override public void onMaxDurationReached() {
                if (cb != null) cb.onMaxDurationReached();
            }
        };

        if (!callRecorder.start(context, persisting)) return false;

        // Disclose to the peer immediately. Their device shows an indicator and plays an audible
        // spoken notice off this flag, so the other party is never recorded without knowing.
        if (callId != null && myUid != null) {
            repo.setRecording(callId, myUid, true, null);
        }

        // The remote track may already have been attached before the recorder existed, or may
        // still be absent. Attaching here covers the former; onAddTrack covers the latter.
        if (remoteAudioTrack != null) {
            try {
                remoteAudioTrack.addSink(callRecorder);
            } catch (Exception e) {
                Log.w(TAG, "Failed to attach recorder sink on start", e);
            }
        }
        return true;
    }

    /**
     * Stops recording. The finished file is delivered asynchronously to the listener passed to
     * {@link #startRecording}.
     *
     * <p>Clears {@code recording.<myUid>} on the call document so the peer's indicator and
     * "recording stopped" state stay in sync with reality.
     */
    public void stopRecording() {
        if (callRecorder == null || !callRecorder.isRecording()) return;
        callRecorder.stop();
        if (callId != null && myUid != null) {
            repo.setRecording(callId, myUid, false, null);
        }
    }

    /** True while we are recording. Does not reflect whether the <i>peer</i> is recording. */
    public boolean isRecording() {
        return callRecorder != null && callRecorder.isRecording();
    }

    /**
     * Reads the peer's {@code recording.<partnerUid>} flag from a call-doc snapshot and, on a
     * genuine change, notifies the UI so it can show an indicator and play the audible spoken
     * notice. Called from both the caller and callee call-doc listeners.
     *
     * <p>Own pending writes are ignored so this device's <i>own</i> {@code recording.<myUid>}
     * write can never be misread as the peer recording. State is tracked in
     * {@link #lastPeerRecording} so the notice fires once per transition, not once per snapshot.
     */
    private void handlePeerRecording(DocumentSnapshot snap) {
        if (snap == null || partnerUid == null) return;
        if (snap.getMetadata().hasPendingWrites()) return;

        Object recObj = snap.get("recording");
        boolean peerRecording = false;
        if (recObj instanceof java.util.Map) {
            Object flag = ((java.util.Map<?, ?>) recObj).get(partnerUid);
            peerRecording = Boolean.TRUE.equals(flag);
        }

        if (lastPeerRecording != null && lastPeerRecording == peerRecording) return;
        lastPeerRecording = peerRecording;

        final boolean rec = peerRecording;
        final CallListener l = listener;
        if (l != null) mainHandler.post(() -> l.onPeerRecordingChanged(rec));
    }

    /**
     * The shared audio-route controller for this call, created on first use.
     *
     * <p>Handed to {@link CallActivity} so the bottom-sheet picker, the header speaker button
     * and {@link #setSpeakerOn(boolean)} all mutate one object. The controller mutes playback
     * through {@link #setOutputMuted(boolean)} rather than touching the call volume.
     */
    public AudioRouteController audioRoute() {
        if (audioRoute == null) {
            audioRoute = new AudioRouteController(context);
            audioRoute.setOutputMuter(this::setOutputMuted);
        }
        return audioRoute;
    }

    public void setCameraEnabled(boolean enabled) {
        if (localVideoTrack != null) localVideoTrack.setEnabled(enabled);
        if (videoCapturer != null) {
            try {
                if (enabled) videoCapturer.startCapture(captureWidth(), captureHeight(), captureFps());
                else videoCapturer.stopCapture();
            } catch (Exception e) {
                Log.w(TAG, "Camera toggle failed: " + e.getMessage());
            }
        }
    }

    /** True while the front (selfie) camera is active. */
    public boolean isFrontCamera() { return isFrontCamera; }

    public void flipCamera() {
        if (!(videoCapturer instanceof CameraVideoCapturer)) return;
        // A real switch handler is required so the local preview's mirroring follows the
        // active camera. Passing null (the old behaviour) left the self-view mirrored for the
        // rear camera, which reversed text and made the preview look wrong.
        ((CameraVideoCapturer) videoCapturer).switchCamera(
                new CameraVideoCapturer.CameraSwitchHandler() {
                    @Override
                    public void onCameraSwitchDone(boolean isFrontFacing) {
                        isFrontCamera = isFrontFacing;
                        CallListener l = listener;
                        if (l != null) mainHandler.post(() -> l.onLocalMirrorChanged(isFrontFacing));
                    }

                    @Override
                    public void onCameraSwitchError(String error) {
                        Log.w(TAG, "Camera switch failed: " + error);
                    }
                });
    }

    /**
     * Routes call audio to the speaker or back to the earpiece.
     *
     * <p>This used to call {@code setSpeakerphoneOn()} directly, which Android 12+ ignores
     * whenever a communication device is selected — the reason the speaker toggle did nothing
     * on modern devices. Routing now goes through {@link AudioRouteController}, which uses
     * {@code setCommunicationDevice()} on API 31+ and only falls back to the legacy call on
     * older releases.
     */
    public void setSpeakerOn(boolean on) {
        AudioRouteController controller = audioRoute();
        AudioRouteController.Kind want = on
                ? AudioRouteController.Kind.SPEAKER
                : AudioRouteController.Kind.EARPIECE;
        for (AudioRouteController.Route r : controller.availableRoutes()) {
            if (r.kind == want) { controller.apply(r); return; }
        }
    }

    // ─── Hangup / cleanup ────────────────────────────────────────────────────

    public void hangup() {
        if (currentState == CallState.IDLE || currentState == CallState.ENDED) return;
        repo.writeStatus(callId, "ended", "hangup");
        repo.deleteCallDoc(callId);
        setState(CallState.ENDED);
        cleanup(true);
    }

    public void declineCall(String callId) {
        this.callId = callId;
        repo.writeStatus(callId, "declined", "declined");
        repo.deleteCallDoc(callId);
        setState(CallState.ENDED);
        cleanup(true);
    }

    public void timeoutCall(String callId) {
        this.callId = callId;
        repo.writeStatus(callId, "timeout", "timeout");
        repo.deleteCallDoc(callId);
        setState(CallState.ENDED);
        cleanup(true);
    }

    /**
     * Releases all native WebRTC resources without writing anything to Firestore.
     * Called from {@code CallActivity.onDestroy()} to ensure resources are freed
     * even when the call was already in ENDED/FAILED state (where hangup() is skipped).
     */
    public void release() {
        iceRestartHandler.removeCallbacks(iceRestartRunnable);
        statsHandler.removeCallbacks(statsPollRunnable);
        cleanup(true);
    }

    private void cleanup(boolean releasePc) {
        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
        iceRestartHandler.removeCallbacks(iceRestartRunnable);
        anchorFallbackHandler.removeCallbacksAndMessages(null);
        if (callDocListener != null) { callDocListener.remove(); callDocListener = null; }
        if (remoteCandidateListener != null) { remoteCandidateListener.remove(); remoteCandidateListener = null; }
        if (!releasePc) return;

        // Tear the recorder down first. It holds a sink on the remote track and receives mic
        // callbacks from the audio device module, both of which are destroyed below — letting it
        // outlive them risks a native-layer crash on a freed track. Detaching the sink is enough to
        // make that safe: ingest stops here, while the encoder thread only touches its own rings.
        //
        // stop() rather than cancel() when a recording is in flight. Hanging up *is* the normal way
        // to end a recording — most users never press stop first — and cancel() deletes the partial
        // file, so cancelling here would silently destroy the recording on the most common path.
        // stop() lets the encoder flush and deliver, and CallRecordingStore (not the already-dying
        // Activity) is what receives the result.
        if (callRecorder != null) {
            if (remoteAudioTrack != null) {
                try { remoteAudioTrack.removeSink(callRecorder); } catch (Exception ignored) {}
            }
            if (callRecorder.isRecording()) {
                callRecorder.stop();
            } else {
                callRecorder.cancel();
            }
            callRecorder = null;
        }

        // Stop handing these tracks out before they are disposed below — a secondary screen
        // that renders a disposed track crashes in native code.
        if (active == this) active = null;
        remoteVideoTrack = null;
        remoteAudioTrack = null;

        try {
            if (videoCapturer != null) { videoCapturer.stopCapture(); videoCapturer.dispose(); videoCapturer = null; }
        } catch (Exception ignored) {}
        if (surfaceTextureHelper != null) { surfaceTextureHelper.dispose(); surfaceTextureHelper = null; }
        if (localVideoTrack != null) { localVideoTrack.dispose(); localVideoTrack = null; }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (localAudioTrack != null) { localAudioTrack.dispose(); localAudioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        // We own this module now, so we must release it. The default module the factory used to
        // create was released internally; ours would leak its audio thread if we skipped this.
        // Ordering matters: it must outlive the factory that was using it.
        if (audioDeviceModule != null) { audioDeviceModule.release(); audioDeviceModule = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
    }

    private void setState(CallState state) {
        currentState = state;
        if (listener != null) listener.onCallStateChanged(state);

        if (state == CallState.CONNECTED) {
            // Cancel the connection watchdog — we made it.
            connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
            requestTimerAnchor();
            startStatsPolling();
        } else if (state == CallState.ENDED || state == CallState.FAILED) {
            // Cancel the watchdog in case we're terminating before ever connecting.
            connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
            stopStatsPolling();
        }
    }

    public CallState getCurrentState() { return currentState; }

    // ── Synced duration timer ─────────────────────────────────────────────────

    /**
     * Kicks off the shared call-start anchor: stamps {@code connectedAt} on the call doc (a
     * no-op if the peer already did) and probes the server clock so the shared stamp can be
     * translated into this device's time base.
     *
     * <p>Guarded by {@link #anchorRequested} because the CONNECTED state is re-entered on
     * every ICE restart; without the guard each restart would re-probe and, worse, the old
     * code reset the timer to zero.
     */
    private void requestTimerAnchor() {
        if (anchorRequested || callId == null || myUid == null) return;
        anchorRequested = true;

        repo.markConnected(callId);

        clockProbeSentMs = System.currentTimeMillis();
        repo.writeClockProbe(callId, myUid, null);

        // If the probe never resolves (offline write, restrictive rules), fall back to trusting
        // the local clock so the timer always starts rather than hanging at 00:00.
        anchorFallbackHandler.postDelayed(() -> {
            if (!clockOffsetKnown) {
                clockOffsetKnown = true;
                clockOffsetMs    = 0L;
                Log.w(TAG, "Clock probe unresolved — falling back to local clock for timer anchor");
                publishAnchorIfReady();
            }
        }, ANCHOR_FALLBACK_MS);
    }

    /**
     * Reads the clock probe and the shared {@code connectedAt} out of a call-doc snapshot.
     * Shared by the caller and callee listeners.
     */
    private void handleTimerAnchor(DocumentSnapshot snap) {
        // An unresolved serverTimestamp reads back as null on the local (pending) snapshot, so
        // only trust server-acknowledged data here.
        if (snap == null || snap.getMetadata().hasPendingWrites()) return;

        if (!clockOffsetKnown && myUid != null && clockProbeSentMs > 0L) {
            Object clockObj = snap.get("clock");
            if (clockObj instanceof java.util.Map) {
                Object mine = ((java.util.Map<?, ?>) clockObj).get(myUid);
                if (mine instanceof com.google.firebase.Timestamp) {
                    long serverMs = ((com.google.firebase.Timestamp) mine).toDate().getTime();
                    // The server evaluated the stamp somewhere between when we sent the write
                    // and when we observed it, so the midpoint is the best point estimate.
                    long midLocalMs = (clockProbeSentMs + System.currentTimeMillis()) / 2L;
                    clockOffsetMs    = serverMs - midLocalMs;
                    clockOffsetKnown = true;
                    Log.d(TAG, "Clock offset vs server: " + clockOffsetMs + " ms");
                }
            }
        }

        Object connectedObj = snap.get("connectedAt");
        if (connectedObj instanceof com.google.firebase.Timestamp) {
            pendingAnchorServerMs =
                    ((com.google.firebase.Timestamp) connectedObj).toDate().getTime();
        }

        publishAnchorIfReady();
    }

    /**
     * Publishes the anchor once both the shared server timestamp and the clock offset are
     * known, converting server time into this device's {@link System#currentTimeMillis()}
     * frame. Because the value is absolute rather than relative, a reconnect or an activity
     * recreation resumes at the correct elapsed time instead of restarting from zero.
     */
    private void publishAnchorIfReady() {
        if (pendingAnchorServerMs <= 0L || !clockOffsetKnown) return;
        long localAnchorMs = pendingAnchorServerMs - clockOffsetMs;
        // A bad clock reading could place the anchor in the future, which would render a
        // negative duration. Clamp to now.
        long now = System.currentTimeMillis();
        if (localAnchorMs > now) localAnchorMs = now;
        if (localAnchorMs == publishedAnchorMs) return;
        publishedAnchorMs = localAnchorMs;

        CallListener l = listener;
        if (l != null) {
            final long anchor = localAnchorMs;
            mainHandler.post(() -> l.onCallTimerAnchor(anchor));
        }
    }

    // ── Bandwidth stats polling ───────────────────────────────────────────────

    // ── Bitrate / encoder constraints ─────────────────────────────────────────

    /**
     * Configures per-sender bitrate policy immediately after ICE connects.
     *
     * <h3>Strategy by device tier</h3>
     *
     * <b>{@link DevicePerformanceTier#LOW} / {@link DevicePerformanceTier#MID}</b> — hard
     * ceiling + floor:
     * <ul>
     *   <li>LOW video: {@link #VIDEO_BITRATE_LOW_MAX_BPS} / {@link #VIDEO_BITRATE_LOW_MIN_BPS}</li>
     *   <li>MID video: {@link #VIDEO_BITRATE_MID_MAX_BPS} / {@link #VIDEO_BITRATE_MID_MIN_BPS}</li>
     *   <li>Rationale: in-order A53/A55 SoCs cannot encode their tier's capture format above
     *       these rates without thermal throttle.  Locking the ceiling stops the encoder
     *       overshooting on a fast WiFi link and then getting throttled — which costs far more
     *       quality than simply never overshooting in the first place.</li>
     * </ul>
     *
     * <b>{@link DevicePerformanceTier#HIGH}</b> — BWE-managed ceiling, floor only:
     * <ul>
     *   <li>Video floor: {@link #VIDEO_BITRATE_HIGH_MIN_BPS}; ceiling: {@code null} (BWE)</li>
     *   <li>Audio floor: {@link #AUDIO_BITRATE_HIGH_MIN_BPS}; ceiling: {@code null} (BWE)</li>
     *   <li>Rationale: libwebrtc's Transport-CC congestion controller picks a ceiling that
     *       matches the real available bandwidth — it climbs on good WiFi (1–4 Mbps) and backs
     *       off on congested LTE without ever hardcoding a number that is wrong for some
     *       fraction of network conditions.  A floor prevents BWE from degrading past the
     *       point where the call is unusable rather than just lower quality.</li>
     * </ul>
     *
     * <p>Video senders on LOW/MID also get
     * {@link RtpParameters.DegradationPreference#MAINTAIN_FRAMERATE} so libwebrtc sheds
     * <em>resolution</em> rather than frame rate under load: a smooth 20 fps at a lower
     * resolution reads far better on a small screen than a stuttering 8 fps sharp image.
     *
     * <p>Called from {@link #startStatsPolling()} (i.e. on every CONNECTED transition) so it
     * also fires on call resume after a temporary ICE disconnect.
     */
    private void applyBitrateConstraints() {
        if (peerConnection == null) return;

        int videoCap = captureVideoBps();          // 0 == unbounded (HIGH tier / top rung)
        boolean cappedVideo = videoCap > 0;

        // Bitrate floors/caps are derived from the *device tier*, not from qualityCeiling.
        //
        // qualityCeiling is pinned to LADDER_HIGH_START (0) on every device so all calls can
        // start at the top rung, which made the old `qualityCeiling >= LADDER_LOW_START` and
        // `>= LADDER_MID_START` tests permanently false. Every branch below therefore always
        // collapsed to the HIGH arm: audioCap became 0 (unbounded) and the video floor was
        // pinned to VIDEO_BITRATE_HIGH_MIN_BPS even on a budget phone that had already been
        // stepped down to 320x240 — the floor then fought the downgrade, holding the bitrate
        // high enough to re-provoke the thermal event we had just stepped down to escape.
        DevicePerformanceTier tier = DevicePerformanceTier.getCachedOrDefault();
        boolean lowTier = tier == DevicePerformanceTier.LOW;
        boolean midTier = tier == DevicePerformanceTier.MID;

        int videoFloor = lowTier ? VIDEO_BITRATE_LOW_MIN_BPS
                : (midTier ? VIDEO_BITRATE_MID_MIN_BPS
                           : VIDEO_BITRATE_HIGH_MIN_BPS);
        int audioCap = lowTier ? AUDIO_BITRATE_LOW_MAX_BPS
                : (midTier ? AUDIO_BITRATE_MID_MAX_BPS : 0);
        int audioFloor = lowTier ? AUDIO_BITRATE_LOW_MIN_BPS
                : (midTier ? AUDIO_BITRATE_MID_MIN_BPS
                           : AUDIO_BITRATE_HIGH_MIN_BPS);

        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() == null) continue;
            RtpParameters params = sender.getParameters();
            if (params == null || params.encodings == null || params.encodings.isEmpty()) continue;

            boolean isVideoTrack = sender.track() instanceof VideoTrack;

            if (isVideoTrack && cappedVideo) {
                // Shed resolution, not frame rate, when the encoder cannot keep up.
                params.degradationPreference =
                        RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE;
            }

            for (RtpParameters.Encoding enc : params.encodings) {
                if (isVideoTrack) {
                    // null ceiling tells libwebrtc to use Transport-CC output instead.
                    enc.maxBitrateBps = cappedVideo ? videoCap : null;
                    enc.minBitrateBps = Math.min(videoFloor, cappedVideo ? videoCap : videoFloor);
                } else {
                    enc.maxBitrateBps = audioCap > 0 ? audioCap : null;
                    enc.minBitrateBps = audioFloor;
                }
            }
            sender.setParameters(params);
            Log.d(TAG, String.format(Locale.US,
                    "Bitrate policy: %s — %s, floor=%d kbps (tier=%s, rung=%d %dx%d@%d)",
                    isVideoTrack ? "video" : "audio",
                    (isVideoTrack ? cappedVideo : audioCap > 0) ? "hard cap" : "BWE-managed",
                    (isVideoTrack ? videoFloor : audioFloor) / 1000,
                    DevicePerformanceTier.getCachedOrDefault(),
                    qualityStep, captureWidth(), captureHeight(), captureFps()));
        }
    }

    private void startStatsPolling() {
        statsHandler.removeCallbacks(statsPollRunnable);
        lastTransportBytesSent     = -1L;
        lastTransportBytesReceived = -1L;
        lastFramesEncoded          = -1L;
        lastFramesTs               = -1L;
        healthyPolls               = 0;
        isRelayCall                = false; // determined on first stats poll
        // Device and thermal tiers must not alter live call quality. Keep the existing top rung
        // on every CONNECTED transition; generic WebRTC BWE and hardware failure handling remain.
        qualityCeiling = LADDER_HIGH_START;
        qualityStep = LADDER_HIGH_START;
        registerThermalListener();
        applyBitrateConstraints();
        statsHandler.postDelayed(statsPollRunnable, STATS_POLL_INTERVAL_MS);
        Log.d(TAG, "TURN stats polling started (tier="
                + DevicePerformanceTier.getCachedOrDefault() + ", rung=" + qualityStep + ")");
    }

    private void stopStatsPolling() {
        statsHandler.removeCallbacks(statsPollRunnable);
        // Attempt a final stats snapshot to capture bytes from the last polling interval.
        // Guard: peerConnection may already be null/closed when stopStatsPolling is called
        // from the FAILED path; the async callback would fire on a disposed object.
        if (peerConnection != null) {
            collectStats();
        }
        if (sessionBytesTotal > 0) {
            TurnBandwidthTracker.get(context).recordCallBytes(sessionBytesTotal);
            Log.i(TAG, String.format(Locale.US,
                    "Call ended — reported %.2f MB to TURN tracker. Monthly: %s",
                    sessionBytesTotal / (1024.0 * 1024.0),
                    TurnBandwidthTracker.get(context).getSummary()));
        }
        sessionBytesTotal          = 0L;
        lastTransportBytesSent     = -1L;
        lastTransportBytesReceived = -1L;
        lastFramesEncoded          = -1L;
        lastFramesTs               = -1L;
        healthyPolls               = 0;
        qualityStep                = qualityCeiling;
        isRelayCall                = false;
        unregisterThermalListener();
    }

    /**
     * Requests a WebRTC stats snapshot.  Two things happen on each poll:
     *
     * <ol>
     *   <li><b>Bandwidth accounting</b> — transport-level bytes delta is accumulated
     *       into {@link #sessionBytesTotal} and later flushed to {@link TurnBandwidthTracker}.</li>
     *   <li><b>Thermal watchdog (32-bit only)</b> — the outbound-rtp {@code framesEncoded}
     *       counter is compared with the previous snapshot to derive actual encoder FPS.
     *       If it falls below {@link #THERMAL_FPS_THRESHOLD} (12 fps), the camera
     *       capturer is down-stepped to 320×240 @ 15 fps exactly once per call.  This
     *       sheds ~60% of encoder CPU on the Helio G36 and prevents the thermal throttle
     *       cascade that causes audio glitches and ANR dialogs on long video calls.</li>
     * </ol>
     */
    private void collectStats() {
        if (peerConnection == null) return;
        peerConnection.getStats(new RTCStatsCollectorCallback() {
            @Override
            public void onStatsDelivered(RTCStatsReport report) {
                if (report == null) return;

                // ── Pass 1: build lookup maps ───────────────────────────────
                // local-candidate id → candidateType ("host","srflx","relay")
                Map<String, String> localCandidateTypes = new java.util.HashMap<>();
                // transport id → {bytesSent, bytesReceived}
                long transportSent = -1L, transportRecv = -1L;
                // nominated candidate-pair local candidate id
                String nominatedLocalId = null;
                // outbound-rtp fields for thermal watchdog
                long framesEncoded = -1L;
                String outboundKind = null;

                for (Map.Entry<String, RTCStats> entry : report.getStatsMap().entrySet()) {
                    RTCStats stats = entry.getValue();
                    String type = stats.getType();
                    Map<String, Object> m = stats.getMembers();

                    if ("local-candidate".equals(type)) {
                        Object ct = m.get("candidateType");
                        if (ct != null) localCandidateTypes.put(entry.getKey(), ct.toString());

                    } else if ("candidate-pair".equals(type)) {
                        // Find the nominated (active) pair to determine relay vs P2P.
                        Object nomObj  = m.get("nominated");
                        Object stateObj = m.get("state");
                        boolean nominated = Boolean.TRUE.equals(nomObj);
                        boolean succeeded = "succeeded".equals(stateObj);
                        if (nominated || succeeded) {
                            Object lcid = m.get("localCandidateId");
                            if (lcid != null) nominatedLocalId = lcid.toString();
                        }

                    } else if ("transport".equals(type)) {
                        Object sentObj = m.get("bytesSent");
                        Object recvObj = m.get("bytesReceived");
                        if (sentObj != null) transportSent = toLong(sentObj);
                        if (recvObj != null) transportRecv = toLong(recvObj);

                    } else if ("outbound-rtp".equals(type)) {
                        Object kindObj = m.get("kind");
                        Object framesObj = m.get("framesEncoded");
                        if ("video".equals(kindObj) && framesObj != null) {
                            outboundKind  = "video";
                            framesEncoded = toLong(framesObj);
                        }
                    }
                }

                // ── Pass 2: determine relay status ──────────────────────────
                if (nominatedLocalId != null) {
                    String ct = localCandidateTypes.get(nominatedLocalId);
                    boolean nowRelay = "relay".equals(ct);
                    if (nowRelay != isRelayCall) {
                        isRelayCall = nowRelay;
                        Log.i(TAG, "Active candidate pair: "
                                + (isRelayCall ? "RELAY (TURN)" : "DIRECT P2P (" + ct + ")")
                                + " — TURN quota " + (isRelayCall ? "ACTIVE" : "NOT charged"));
                    }
                }

                // ── Transport bytes (bandwidth accounting) ──────────────────
                // Only charge against the Cloudflare quota when actually relayed.
                if (transportSent >= 0 && transportRecv >= 0) {
                    long deltaSent = (lastTransportBytesSent >= 0 && transportSent >= lastTransportBytesSent)
                            ? transportSent - lastTransportBytesSent : 0L;
                    long deltaRecv = (lastTransportBytesReceived >= 0 && transportRecv >= lastTransportBytesReceived)
                            ? transportRecv - lastTransportBytesReceived : 0L;
                    lastTransportBytesSent     = transportSent;
                    lastTransportBytesReceived = transportRecv;
                    if (isRelayCall) {
                        sessionBytesTotal += deltaSent + deltaRecv;
                    }
                    Log.d(TAG, String.format(Locale.US,
                            "Stats poll: +%.1f KB tx, +%.1f KB rx — session TURN: %.2f MB [%s]",
                            deltaSent / 1024.0, deltaRecv / 1024.0,
                            sessionBytesTotal / (1024.0 * 1024.0),
                            isRelayCall ? "relay" : "direct"));
                }

                // ── Thermal watchdog: outbound-rtp encoder FPS ──────────────
                if (thermalWatchdogEnabled()
                        && "video".equals(outboundKind) && framesEncoded >= 0) {
                    long nowMs = System.currentTimeMillis();
                    if (lastFramesEncoded >= 0 && lastFramesTs > 0) {
                        double elapsedSec = (nowMs - lastFramesTs) / 1000.0;
                        if (elapsedSec > 0) {
                            double actualFps =
                                    (framesEncoded - lastFramesEncoded) / elapsedSec;
                            // Hop to the main thread: the capturer and RtpSenders must not be
                            // mutated from the WebRTC stats callback thread.
                            statsHandler.post(() -> evaluateQualityStep(actualFps));
                        }
                    }
                    lastFramesEncoded = framesEncoded;
                    lastFramesTs      = nowMs;
                }
            }
        });
    }

    // ── Thermal quality ladder ────────────────────────────────────────────────

    /**
     * The watchdog runs for {@link DevicePerformanceTier#LOW} and
     * {@link DevicePerformanceTier#MID} devices. It used to be gated on
     * {@code is32BitOnly()}, which meant every arm64 budget phone — the Helio P35 included —
     * encoded 720p30 with no thermal protection whatsoever.
     */
    private boolean thermalWatchdogEnabled() {
        // Generic encoder/thermal failure safety applies equally to every device. Tier selection
        // never opts a phone into lower live-call quality; all calls start at the top rung.
        return isVideo;
    }

    /**
     * Moves {@link #qualityStep} along {@link #QUALITY_LADDER} based on observed encoder FPS.
     *
     * <p>Replaces the previous one-way {@code thermalDowngradeApplied} latch, which pinned a
     * call to 320×240 for its entire remaining duration after a single transient dip — so a
     * momentary hiccup 20 seconds into a 30-minute call cost the user every one of the
     * remaining 29 minutes.
     *
     * <p>Steps down after {@link #DOWNGRADE_POLLS_REQUIRED} consecutive polls below
     * {@link #THERMAL_FPS_THRESHOLD} (or reporting thermal throttling), and steps back
     * up only after {@link #RECOVERY_POLLS_REQUIRED} consecutive polls above
     * {@link #RECOVERY_FPS_THRESHOLD} while not throttling. The gap between the two
     * thresholds is the hysteresis band that stops the ladder oscillating.
     *
     * <p>The two streak counters are mutually exclusive: entering one resets the other, so a
     * run of alternating good/bad polls accumulates neither and the rung is held. Combined
     * with {@link #MIN_QUALITY_DWELL_MS}, this is what stops the capturer being restarted
     * repeatedly on a device hovering right at the threshold.
     */
    private void evaluateQualityStep(double actualFps) {
        boolean throttling = DevicePerformanceTier.isThrottling(
                DevicePerformanceTier.currentThermalStatus(context));

        Log.d(TAG, String.format(Locale.US,
                "Thermal watchdog: encoder %.1f fps (down<%.0f, up>%.0f), rung=%d, throttling=%b",
                actualFps, THERMAL_FPS_THRESHOLD, RECOVERY_FPS_THRESHOLD,
                qualityStep, throttling));

        if (actualFps < THERMAL_FPS_THRESHOLD || throttling) {
            healthyPolls = 0;
            unhealthyPolls++;
            if (unhealthyPolls >= DOWNGRADE_POLLS_REQUIRED) {
                unhealthyPolls = 0;
                stepDownQuality(throttling ? "device thermal throttling"
                        : String.format(Locale.US, "encoder at %.1f fps", actualFps));
            }
        } else if (actualFps > RECOVERY_FPS_THRESHOLD) {
            unhealthyPolls = 0;
            healthyPolls++;
            if (healthyPolls >= RECOVERY_POLLS_REQUIRED) {
                healthyPolls = 0;
                stepUpQuality();
            }
        } else {
            // In the hysteresis band: hold the current rung and reset both streaks.
            healthyPolls = 0;
            unhealthyPolls = 0;
        }
    }

    /**
     * True when the current rung has been held long enough to allow another change.
     *
     * <p>Applied to both directions. A downgrade blocked here is not lost: the streak counter
     * re-accumulates and the next qualifying poll after the window applies it.
     */
    private boolean qualityDwellElapsed() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastQualityChangeMs != 0L && now - lastQualityChangeMs < MIN_QUALITY_DWELL_MS) {
            Log.d(TAG, "Quality change suppressed: only "
                    + (now - lastQualityChangeMs) + " ms since last change (min "
                    + MIN_QUALITY_DWELL_MS + " ms)");
            return false;
        }
        return true;
    }

    /** Drops one rung, unless already at the bottom of the ladder or inside the dwell window. */
    private void stepDownQuality(String reason) {
        if (qualityStep >= QUALITY_LADDER.length - 1) return;
        if (!qualityDwellElapsed()) return;
        qualityStep++;
        lastQualityChangeMs = android.os.SystemClock.elapsedRealtime();
        Log.w(TAG, "Thermal downgrade → rung " + qualityStep + " ("
                + captureWidth() + "×" + captureHeight() + " @ " + captureFps()
                + " fps): " + reason);
        applyCurrentQualityStep();
    }

    /** Climbs one rung, never above the tier's ceiling, and never inside the dwell window. */
    private void stepUpQuality() {
        if (qualityStep <= qualityCeiling) return;
        if (!qualityDwellElapsed()) return;
        qualityStep--;
        lastQualityChangeMs = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "Thermal recovery → rung " + qualityStep + " ("
                + captureWidth() + "×" + captureHeight() + " @ " + captureFps() + " fps)");
        applyCurrentQualityStep();
    }

    /** Pushes the current rung's format and bitrate ceiling to the capturer and senders. */
    private void applyCurrentQualityStep() {
        if (videoCapturer != null) {
            try {
                videoCapturer.changeCaptureFormat(
                        captureWidth(), captureHeight(), captureFps());
            } catch (Exception ex) {
                Log.w(TAG, "changeCaptureFormat failed: " + ex.getMessage());
            }
        }
        // Re-derive the bitrate policy so the ceiling stays consistent with the resolution;
        // otherwise the encoder immediately claws back toward its old target and re-triggers
        // the very thermal event we just stepped down to escape.
        applyBitrateConstraints();
    }

    /**
     * Registers a thermal-status listener so we can downgrade <em>proactively</em>, as soon as
     * the platform reports throttling, instead of waiting for frames to already be dropping.
     * No-op below API 29.
     */
    private void registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener != null) return;
        if (!thermalWatchdogEnabled()) return;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            thermalListener = status -> {
                if (DevicePerformanceTier.isThrottling(status)) {
                    statsHandler.post(() -> {
                        healthyPolls = 0;
                        stepDownQuality("thermal status " + status);
                    });
                }
            };
            pm.addThermalStatusListener(thermalListener);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to register thermal listener", t);
            thermalListener = null;
        }
    }

    private void unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener == null) return;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                pm.removeThermalStatusListener(thermalListener);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to remove thermal listener", t);
        } finally {
            thermalListener = null;
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Long)    return (Long) o;
        if (o instanceof Integer) return ((Integer) o).longValue();
        if (o instanceof Number)  return ((Number) o).longValue();
        return 0L;
    }
}
