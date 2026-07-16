package com.duoshield.app.call;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.duoshield.app.BuildConfig;
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
    }

    public enum CallState {
        IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, CONNECTED, ENDED, FAILED
    }

    private final Context context;
    private final CallSignalRepository repo;
    private CallListener listener;

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private CameraVideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;

    private String callId;
    private String myUid;
    private String partnerUid;
    private boolean isCaller;
    private boolean isVideo;
    /** F6: chatId passed to the call doc so Firestore rules can enforce bilateral contact gate. */
    private String chatIdForCall;

    private ListenerRegistration callDocListener;
    private ListenerRegistration remoteCandidateListener;

    private final List<IceCandidate> pendingCandidates = new ArrayList<>();
    private boolean remoteDescSet = false;
    /** SDP of the most-recently applied ICE-restart offer — prevents double-applying the same offer. */
    private String lastRestartOfferSdp = null;

    private CallState currentState = CallState.IDLE;

    // ── Bandwidth tracking ────────────────────────────────────────────────────
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
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsPollRunnable = new Runnable() {
        @Override public void run() {
            collectStats();
            statsHandler.postDelayed(this, STATS_POLL_INTERVAL_MS);
        }
    };

    // ── ICE restart on disconnect ─────────────────────────────────────────────
    /** How long to wait after DISCONNECTED before triggering an ICE restart (ms). */
    private static final long ICE_RESTART_DELAY_MS = 5_000L;
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
                restartConstraints.mandatory.add(
                        new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                restartConstraints.mandatory.add(
                        new MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideo ? "true" : "false"));
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

    // ── 32-bit thermal watchdog ───────────────────────────────────────────────
    /**
     * Hard bitrate ceiling for 32-bit devices (armeabi-v7a, e.g. POCO C51 / Helio G36).
     *
     * <p>The Helio G36's four Cortex-A53 cores cannot sustain 1 280×720 @ 30 fps
     * encoding without thermal throttle.  640×480 @ 24 fps + 400 kbps cap is the
     * largest operating point that stays below the thermal threshold on a 5-minute
     * call in a warm room.
     *
     * <p>64-bit devices use BWE (Bandwidth Estimation) instead of a static cap — see
     * {@link #applyBitrateConstraints()} for the full rationale.
     */
    private static final int VIDEO_BITRATE_32BIT_MAX_BPS =  400_000;
    private static final int VIDEO_BITRATE_32BIT_MIN_BPS =  150_000;
    private static final int AUDIO_BITRATE_32BIT_MAX_BPS =   20_000;
    private static final int AUDIO_BITRATE_32BIT_MIN_BPS =   16_000;

    /**
     * BWE floor for 64-bit devices — the minimum bitrate libwebrtc's congestion
     * controller is allowed to settle at.  Without a floor, BWE may drop all the way
     * to ~30 kbps on a saturated network, causing severe pixelation.  With a floor it
     * degrades gracefully to the lowest still-watchable quality and only drops further
     * if the network genuinely cannot sustain even that.
     *
     * <p>No {@code maxBitrateBps} is set for 64-bit; instead, libwebrtc's Transport-CC
     * congestion controller picks the ceiling dynamically.  On a good WiFi connection
     * it will climb to 2–4 Mbps naturally; on LTE it typically stabilises at 500–
     * 1 500 kbps — all without hard-coding a number that is almost certainly wrong
     * for some fraction of network conditions.
     */
    private static final int VIDEO_BITRATE_64BIT_MIN_BPS =  300_000;
    private static final int AUDIO_BITRATE_64BIT_MIN_BPS =   24_000;

    /**
     * Thermal step: if the outbound-rtp encoder is delivering fewer than this many
     * frames per second on a 32-bit device, we downgrade the camera to 320×240 @ 15 fps
     * to shed CPU/thermal load and prevent ANR.  Only triggered once per call.
     */
    private static final float THERMAL_FPS_THRESHOLD  = 12.0f;
    private boolean thermalDowngradeApplied = false;
    /** framesEncoded counter from the last outbound-rtp stats snapshot (for FPS delta). */
    private long lastFramesEncoded = -1L;
    /** Timestamp (ms) of the last outbound-rtp stats snapshot. */
    private long lastFramesTs = -1L;

    public CallManager(Context context) {
        this.context = context.getApplicationContext();
        this.repo = new CallSignalRepository();
    }

    public void setListener(CallListener listener) {
        this.listener = listener;
    }

    public EglBase getEglBase() {
        return eglBase;
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

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
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
        list.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478")
                .createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
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
        // Use ALL transport type so the ICE agent can negotiate direct P2P
        // (srflx / host) and fall back to TURN relay only when P2P genuinely
        // fails (strict CGNAT / symmetric NAT on both sides).  The previous
        // RELAY-only mode forced every call through Cloudflare even when both
        // peers could connect directly, draining the free-tier allowance
        // unnecessarily.  With ALL, TURN is still available as a fallback but
        // is not the first choice, keeping most calls free of relay cost.
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL;

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
                if (receiver.track() instanceof VideoTrack) {
                    VideoTrack remote = (VideoTrack) receiver.track();
                    remote.setEnabled(true);
                    if (listener != null) listener.onRemoteVideoTrack(remote);
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
                new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
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
            }
        }

        MediaConstraints pcConstraints = new MediaConstraints();
        peerConnection.addTrack(localAudioTrack, Arrays.asList("stream0"));
        if (localVideoTrack != null) {
            peerConnection.addTrack(localVideoTrack, Arrays.asList("stream0"));
        }
    }

    // ─── Adaptive video capture resolution ───────────────────────────────────
    // POCO C51 (and any other 32-bit-only device) uses a Helio G36 (quad Cortex-A53
    // @ 2.2 GHz). Encoding 1280×720 @ 30 fps saturates the CPU within seconds,
    // triggering thermal throttle, dropped frames, and ANR-level audio glitches.
    // On 32-bit devices (SUPPORTED_64_BIT_ABIS is empty) we cap to 640×480 @ 24 fps
    // which the G36 handles comfortably and still looks fine on a 6.5" screen.

    private static boolean is32BitOnly() {
        return Build.SUPPORTED_64_BIT_ABIS == null || Build.SUPPORTED_64_BIT_ABIS.length == 0;
    }

    private static int captureWidth()  { return is32BitOnly() ? 640  : 1280; }
    private static int captureHeight() { return is32BitOnly() ? 480  : 720;  }
    private static int captureFps()    { return is32BitOnly() ? 24   : 30;   }

    private CameraVideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(context)
                ? new Camera2Enumerator(context)
                : new Camera1Enumerator(true);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null);
            }
        }
        for (String name : enumerator.getDeviceNames()) {
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

    // ─── Callee flow ─────────────────────────────────────────────────────────

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
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideo ? "true" : "false"));

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
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
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

    public void flipCamera() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            ((CameraVideoCapturer) videoCapturer).switchCamera(null);
        }
    }

    public void setSpeakerOn(boolean on) {
        // FIX #8: Both ternary branches were identical (MODE_IN_COMMUNICATION), making
        // setMode() a no-op. The mode must stay MODE_IN_COMMUNICATION throughout the call;
        // only the speaker routing changes via setSpeakerphoneOn().
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setSpeakerphoneOn(on);
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
        if (callDocListener != null) { callDocListener.remove(); callDocListener = null; }
        if (remoteCandidateListener != null) { remoteCandidateListener.remove(); remoteCandidateListener = null; }
        if (!releasePc) return;

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
        if (eglBase != null) { eglBase.release(); eglBase = null; }
    }

    private void setState(CallState state) {
        currentState = state;
        if (listener != null) listener.onCallStateChanged(state);

        if (state == CallState.CONNECTED) {
            // Cancel the connection watchdog — we made it.
            connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
            startStatsPolling();
        } else if (state == CallState.ENDED || state == CallState.FAILED) {
            // Cancel the watchdog in case we're terminating before ever connecting.
            connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
            stopStatsPolling();
        }
    }

    public CallState getCurrentState() { return currentState; }

    // ── Bandwidth stats polling ───────────────────────────────────────────────

    // ── Bitrate / encoder constraints ─────────────────────────────────────────

    /**
     * Configures per-sender bitrate policy immediately after ICE connects.
     *
     * <h3>Strategy by device class</h3>
     *
     * <b>32-bit (armeabi-v7a) devices</b> — hard ceiling + floor:
     * <ul>
     *   <li>Video: {@link #VIDEO_BITRATE_32BIT_MAX_BPS} / {@link #VIDEO_BITRATE_32BIT_MIN_BPS}</li>
     *   <li>Audio: {@link #AUDIO_BITRATE_32BIT_MAX_BPS} / {@link #AUDIO_BITRATE_32BIT_MIN_BPS}</li>
     *   <li>Rationale: the Helio G36 (and similar low-end SoCs) cannot encode 640×480 @ 24 fps
     *       at more than ~400 kbps without thermal throttle.  Locking the ceiling prevents the
     *       encoder from overshooting on a fast WiFi link and then getting throttled.</li>
     * </ul>
     *
     * <b>64-bit (arm64-v8a) devices</b> — BWE-managed ceiling, floor only:
     * <ul>
     *   <li>Video floor: {@link #VIDEO_BITRATE_64BIT_MIN_BPS}; ceiling: {@code null} (BWE)</li>
     *   <li>Audio floor: {@link #AUDIO_BITRATE_64BIT_MIN_BPS}; ceiling: {@code null} (BWE)</li>
     *   <li>Rationale: libwebrtc's Transport-CC congestion controller picks a ceiling that
     *       matches the real available bandwidth — it climbs on good WiFi (1–4 Mbps) and backs
     *       off on congested LTE without ever hardcoding a number that is wrong for some
     *       fraction of network conditions.  A floor prevents BWE from degrading past the
     *       point where the call is unusable rather than just lower quality.</li>
     * </ul>
     *
     * <p>Called once from {@link #startStatsPolling()} (i.e. on every CONNECTED transition)
     * so it also fires on call resume after a temporary ICE disconnect.
     */
    private void applyBitrateConstraints() {
        if (peerConnection == null) return;
        boolean is32Bit = is32BitOnly();

        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() == null) continue;
            RtpParameters params = sender.getParameters();
            if (params == null || params.encodings == null || params.encodings.isEmpty()) continue;

            boolean isVideoTrack = sender.track() instanceof VideoTrack;

            for (RtpParameters.Encoding enc : params.encodings) {
                if (is32Bit) {
                    // Hard ceiling — prevents thermal throttle on weak SoCs.
                    enc.maxBitrateBps = isVideoTrack
                            ? VIDEO_BITRATE_32BIT_MAX_BPS : AUDIO_BITRATE_32BIT_MAX_BPS;
                    enc.minBitrateBps = isVideoTrack
                            ? VIDEO_BITRATE_32BIT_MIN_BPS : AUDIO_BITRATE_32BIT_MIN_BPS;
                } else {
                    // BWE-managed ceiling: null tells libwebrtc to use Transport-CC output.
                    enc.maxBitrateBps = null;
                    enc.minBitrateBps = isVideoTrack
                            ? VIDEO_BITRATE_64BIT_MIN_BPS : AUDIO_BITRATE_64BIT_MIN_BPS;
                }
            }
            sender.setParameters(params);
            Log.d(TAG, String.format(Locale.US,
                    "Bitrate policy: %s — %s, floor=%d kbps (32-bit=%b)",
                    isVideoTrack ? "video" : "audio",
                    is32Bit ? "hard cap" : "BWE-managed",
                    (isVideoTrack
                            ? (is32Bit ? VIDEO_BITRATE_32BIT_MIN_BPS : VIDEO_BITRATE_64BIT_MIN_BPS)
                            : (is32Bit ? AUDIO_BITRATE_32BIT_MIN_BPS : AUDIO_BITRATE_64BIT_MIN_BPS)) / 1000,
                    is32Bit));
        }
    }

    private void startStatsPolling() {
        statsHandler.removeCallbacks(statsPollRunnable);
        lastTransportBytesSent     = -1L;
        lastTransportBytesReceived = -1L;
        lastFramesEncoded          = -1L;
        lastFramesTs               = -1L;
        thermalDowngradeApplied    = false;
        isRelayCall                = false; // determined on first stats poll
        applyBitrateConstraints(); // cap bitrate immediately on connect
        statsHandler.postDelayed(statsPollRunnable, STATS_POLL_INTERVAL_MS);
        Log.d(TAG, "TURN stats polling started (32-bit=" + is32BitOnly() + ")");
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
        thermalDowngradeApplied    = false;
        isRelayCall                = false;
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

                // ── Thermal watchdog: outbound-rtp encoder FPS (32-bit only) ──
                if (is32BitOnly() && !thermalDowngradeApplied
                        && "video".equals(outboundKind) && framesEncoded >= 0) {
                    long nowMs = System.currentTimeMillis();
                    if (lastFramesEncoded >= 0 && lastFramesTs > 0) {
                        double elapsedSec = (nowMs - lastFramesTs) / 1000.0;
                        double actualFps  = (framesEncoded - lastFramesEncoded) / elapsedSec;
                        Log.d(TAG, String.format(Locale.US,
                                "Thermal watchdog: encoder %.1f fps (threshold %.0f fps)",
                                actualFps, THERMAL_FPS_THRESHOLD));
                        if (actualFps < THERMAL_FPS_THRESHOLD && videoCapturer != null) {
                            thermalDowngradeApplied = true;
                            Log.w(TAG, "Thermal downgrade triggered on 32-bit device: "
                                    + "320×240 @ 15 fps to relieve encoder CPU.");
                            try {
                                videoCapturer.changeCaptureFormat(320, 240, 15);
                            } catch (Exception ex) {
                                Log.w(TAG, "changeCaptureFormat failed: " + ex.getMessage());
                            }
                            applyBitrateConstraintsForResolution(150_000 /* 150 kbps */);
                        }
                    }
                    lastFramesEncoded = framesEncoded;
                    lastFramesTs      = nowMs;
                }
            }
        });
    }

    /**
     * Applies an emergency video bitrate ceiling after a thermal-downgrade resolution change.
     *
     * <p>This is the one case where we force a hard ceiling even on 64-bit devices: the
     * thermal watchdog has already lowered the capture to 320×240 @ 15 fps because the
     * encoder was visibly falling behind.  Giving BWE a ceiling consistent with that
     * resolution (150 kbps) prevents the encoder from immediately clawing back towards its
     * uncapped target and re-triggering the thermal event.
     *
     * @param videoBps hard ceiling in bps (typically 150 000 after downgrade to 320×240 @ 15 fps)
     */
    private void applyBitrateConstraintsForResolution(int videoBps) {
        if (peerConnection == null) return;
        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() == null || !(sender.track() instanceof VideoTrack)) continue;
            RtpParameters params = sender.getParameters();
            if (params == null || params.encodings == null) continue;
            for (RtpParameters.Encoding enc : params.encodings) {
                enc.maxBitrateBps = videoBps;
                enc.minBitrateBps = Math.min(enc.minBitrateBps != null
                        ? enc.minBitrateBps : videoBps, videoBps);
            }
            sender.setParameters(params);
            Log.d(TAG, "Thermal downgrade: video hard cap → " + videoBps / 1000 + " kbps");
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Long)    return (Long) o;
        if (o instanceof Integer) return ((Integer) o).longValue();
        if (o instanceof Number)  return ((Number) o).longValue();
        return 0L;
    }
}
