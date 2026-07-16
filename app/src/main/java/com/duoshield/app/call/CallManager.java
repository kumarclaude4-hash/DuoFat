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

    private CallState currentState = CallState.IDLE;

    // ── Bandwidth tracking ────────────────────────────────────────────────────
    /** Interval between WebRTC stats polls while connected. */
    private static final long STATS_POLL_INTERVAL_MS = 10_000L;
    /** Cumulative transport bytes (sent + received) accumulated this call. */
    private long sessionBytesTotal = 0L;
    /** Last snapshot of transport bytes from the stats report (to compute deltas). */
    private long lastTransportBytesSent     = -1L;
    private long lastTransportBytesReceived = -1L;
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsPollRunnable = new Runnable() {
        @Override public void run() {
            collectStats();
            statsHandler.postDelayed(this, STATS_POLL_INTERVAL_MS);
        }
    };

    // ── 32-bit thermal watchdog ───────────────────────────────────────────────
    /**
     * Max video bitrate for 32-bit devices (armeabi-v7a, e.g. POCO C51 / Helio G36).
     * 400 kbps at 640×480 is well within the encoder budget; 64-bit devices use
     * 1 500 kbps at 1280×720.
     */
    private static final int VIDEO_BITRATE_32BIT_BPS  = 400_000;
    private static final int VIDEO_BITRATE_64BIT_BPS  = 1_500_000;
    /** Audio bitrate: 32-bit gets OPUS 20 kbps; 64-bit gets 32 kbps. */
    private static final int AUDIO_BITRATE_32BIT_BPS  =  20_000;
    private static final int AUDIO_BITRATE_64BIT_BPS  =  32_000;

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
                listener.onError("Monthly TURN data cap reached (900 GB). "
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
        // F8 fix: use RELAY-only transport when TURN credentials are cached to
        // prevent real-IP leakage via ICE candidate gathering. Fall back to ALL
        // (P2P allowed) only when TURN is unavailable — a broken call is worse
        // than the privacy reduction of a direct P2P path.
        config.iceTransportsType = TurnCredentialCache.get().isValid()
                ? PeerConnection.IceTransportsType.RELAY
                : PeerConnection.IceTransportsType.ALL;

        return factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState s) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE state: " + s);
                if (s == PeerConnection.IceConnectionState.CONNECTED
                        || s == PeerConnection.IceConnectionState.COMPLETED) {
                    setState(CallState.CONNECTED);
                } else if (s == PeerConnection.IceConnectionState.FAILED) {
                    setState(CallState.FAILED);
                    if (listener != null) listener.onError("Call failed — network unavailable");
                } else if (s == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.w(TAG, "ICE disconnected — may recover");
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
        MediaConstraints audioConstraints = new MediaConstraints();
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
            if (e != null || snap == null || !snap.exists()) return;
            String status = snap.getString("status");

            if ("declined".equals(status) || "ended".equals(status)
                    || "missed".equals(status) || "timeout".equals(status)) {
                setState(CallState.ENDED);
                cleanup(false);
                return;
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
            if (e != null || snap == null || !snap.exists()) return;
            String status = snap.getString("status");
            if ("ended".equals(status) || "declined".equals(status)
                    || "timeout".equals(status)) {
                setState(CallState.ENDED);
                cleanup(false);
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

    private void cleanup(boolean releasePc) {
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

        // Start stats polling once connected; stop and flush on terminal states.
        if (state == CallState.CONNECTED) {
            startStatsPolling();
        } else if (state == CallState.ENDED || state == CallState.FAILED) {
            stopStatsPolling();
        }
    }

    public CallState getCurrentState() { return currentState; }

    // ── Bandwidth stats polling ───────────────────────────────────────────────

    // ── Bitrate / encoder constraints ─────────────────────────────────────────

    /**
     * Caps bitrate on every video and audio RtpSender.
     *
     * <p>On 32-bit devices (e.g. POCO C51 / Helio G36) the video encoder is already
     * running at a reduced 640×480 @ 24fps.  Capping the bitrate to 400 kbps gives
     * the encoder an easy target that comfortably fits within the Cortex-A53's compute
     * budget, preventing the encode thread from saturating the CPU and triggering ANR
     * or audio-glitch conditions on long calls.
     *
     * <p>Called once from {@link #startStatsPolling()} (i.e. on every CONNECTED transition)
     * so it also fires on call resume after a temporary ICE disconnect.
     */
    private void applyBitrateConstraints() {
        if (peerConnection == null) return;
        int videoBps = is32BitOnly() ? VIDEO_BITRATE_32BIT_BPS : VIDEO_BITRATE_64BIT_BPS;
        int audioBps = is32BitOnly() ? AUDIO_BITRATE_32BIT_BPS : AUDIO_BITRATE_64BIT_BPS;

        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() == null) continue;
            RtpParameters params = sender.getParameters();
            if (params == null || params.encodings == null || params.encodings.isEmpty()) continue;

            boolean isVideo = sender.track() instanceof VideoTrack;
            int cap = isVideo ? videoBps : audioBps;
            for (RtpParameters.Encoding enc : params.encodings) {
                enc.maxBitrateBps = cap;
            }
            sender.setParameters(params);
            Log.d(TAG, String.format(Locale.US,
                    "Bitrate cap applied: %s → %d kbps (32-bit=%b)",
                    isVideo ? "video" : "audio", cap / 1000, is32BitOnly()));
        }
    }

    private void startStatsPolling() {
        statsHandler.removeCallbacks(statsPollRunnable);
        lastTransportBytesSent     = -1L;
        lastTransportBytesReceived = -1L;
        lastFramesEncoded          = -1L;
        lastFramesTs               = -1L;
        thermalDowngradeApplied    = false;
        applyBitrateConstraints(); // cap bitrate immediately on connect
        statsHandler.postDelayed(statsPollRunnable, STATS_POLL_INTERVAL_MS);
        Log.d(TAG, "TURN stats polling started (32-bit=" + is32BitOnly() + ")");
    }

    private void stopStatsPolling() {
        statsHandler.removeCallbacks(statsPollRunnable);
        // Do a final stats collection synchronously and flush to tracker.
        collectStats();
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

                for (Map.Entry<String, RTCStats> entry : report.getStatsMap().entrySet()) {
                    RTCStats stats = entry.getValue();
                    String type = stats.getType();

                    // ── Transport bytes (bandwidth accounting) ──────────────
                    if ("transport".equals(type)) {
                        Map<String, Object> m = stats.getMembers();
                        Object sentObj = m.get("bytesSent");
                        Object recvObj = m.get("bytesReceived");
                        if (sentObj != null && recvObj != null) {
                            long sent = toLong(sentObj);
                            long recv = toLong(recvObj);
                            long deltaSent = (lastTransportBytesSent >= 0 && sent >= lastTransportBytesSent)
                                    ? sent - lastTransportBytesSent : 0L;
                            long deltaRecv = (lastTransportBytesReceived >= 0 && recv >= lastTransportBytesReceived)
                                    ? recv - lastTransportBytesReceived : 0L;
                            lastTransportBytesSent     = sent;
                            lastTransportBytesReceived = recv;
                            sessionBytesTotal += deltaSent + deltaRecv;
                            Log.d(TAG, String.format(Locale.US,
                                    "Stats poll: +%.1f KB tx, +%.1f KB rx — session: %.2f MB",
                                    deltaSent / 1024.0, deltaRecv / 1024.0,
                                    sessionBytesTotal / (1024.0 * 1024.0)));
                        }
                    }

                    // ── Thermal watchdog: outbound-rtp encoder FPS (32-bit only) ──
                    if (is32BitOnly() && !thermalDowngradeApplied
                            && "outbound-rtp".equals(type)) {
                        Map<String, Object> m = stats.getMembers();
                        // Only examine video streams.
                        Object kindObj = m.get("kind");
                        if (!"video".equals(kindObj)) continue;

                        Object framesObj = m.get("framesEncoded");
                        if (framesObj == null) continue;
                        long frames = toLong(framesObj);
                        long nowMs  = System.currentTimeMillis();

                        if (lastFramesEncoded >= 0 && lastFramesTs > 0) {
                            double elapsedSec = (nowMs - lastFramesTs) / 1000.0;
                            double actualFps  = (frames - lastFramesEncoded) / elapsedSec;
                            Log.d(TAG, String.format(Locale.US,
                                    "Thermal watchdog: encoder %.1f fps (threshold %.0f fps)",
                                    actualFps, THERMAL_FPS_THRESHOLD));

                            if (actualFps < THERMAL_FPS_THRESHOLD && videoCapturer != null) {
                                // Encoder is lagging — step down to 320×240 @ 15 fps.
                                thermalDowngradeApplied = true;
                                Log.w(TAG, "Thermal downgrade triggered on 32-bit device: "
                                        + "320×240 @ 15 fps to relieve encoder CPU.");
                                try {
                                    videoCapturer.changeCaptureFormat(320, 240, 15);
                                } catch (Exception ex) {
                                    Log.w(TAG, "changeCaptureFormat failed: " + ex.getMessage());
                                }
                                // Tighten the bitrate cap to match the lower resolution.
                                applyBitrateConstraintsForResolution(150_000 /* 150 kbps */);
                            }
                        }
                        lastFramesEncoded = frames;
                        lastFramesTs      = nowMs;
                    }
                }
            }
        });
    }

    /**
     * Overrides video {@code maxBitrateBps} on all existing video senders.
     * Used by the thermal watchdog to tighten the cap after a resolution downgrade.
     */
    private void applyBitrateConstraintsForResolution(int videoBps) {
        if (peerConnection == null) return;
        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() == null || !(sender.track() instanceof VideoTrack)) continue;
            RtpParameters params = sender.getParameters();
            if (params == null || params.encodings == null) continue;
            for (RtpParameters.Encoding enc : params.encodings) {
                enc.maxBitrateBps = videoBps;
            }
            sender.setParameters(params);
            Log.d(TAG, "Thermal downgrade: video bitrate capped to " + videoBps / 1000 + " kbps");
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Long)    return (Long) o;
        if (o instanceof Integer) return ((Integer) o).longValue();
        if (o instanceof Number)  return ((Number) o).longValue();
        return 0L;
    }
}
