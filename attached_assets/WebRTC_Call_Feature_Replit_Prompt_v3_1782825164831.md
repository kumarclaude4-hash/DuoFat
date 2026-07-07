# Replit Prompt: Add Voice & Video Calling (WebRTC, Metered.ca free TURN tier)

Paste everything below into Replit's agent/chat.

---

## Task (paste as-is)

Add 1:1 voice and video calling using WebRTC. Do not touch `CryptoHelper.java`,
`KeyManager.java`, `CryptoInitializer.java`, or `PairingManager.java`.

**Constraint:** both users are on SIM/cellular data, which usually means
carrier-grade NAT (CGNAT) — so STUN alone will fail for most calls and a TURN
relay will be required often, not as a rare fallback. For now, use Metered.ca's
free TURN tier (500MB/month, no card required) as the relay provider — this is a
testing/validation phase to confirm the full call flow works end-to-end before
deciding whether to scale to a self-hosted or higher-capacity TURN setup later.
The ICE server list must be a config value (via `BuildConfig`/secrets), not
hardcoded, so the TURN provider can be swapped later without code changes.
Implement this end to end: Android client code, Firestore signaling, FCM
call-ringing wakeup, and signaling cleanup.

---

## 1. Architecture summary

- **Signaling**: Firestore (`calls/{callId}` collection) — reuse the existing
  Firebase project, no new signaling server needed.
- **Call ringing while backgrounded/killed**: extend the existing FCM path, same
  pattern already used for message push notifications.
- **Media transport**: WebRTC peer-to-peer where possible; relayed through
  Metered.ca's free TURN tier when CGNAT requires it (expect this to be the common
  case given both users are on SIM data).
- **TURN server**: Metered.ca free tier (500MB/month, no card). Treat this as a
  swappable config value — when usage outgrows it, the plan is to migrate to a
  self-hosted `coturn` server on a free-tier VM with an internally enforced usage
  cap, without changing any call logic, only the ICE server config.
- **Scaling later**: not handled now — no group calls, no SFU. This is a 2-person,
  P2P/relay architecture only.

---

## 2. TURN credentials (Metered.ca)

- Sign up free at metered.ca, generate a TURN credential set from their dashboard
  (no card required for the 500MB/month free tier).
- Store as secrets (`TURN_URL`, `TURN_USERNAME`, `TURN_CREDENTIAL`) — do not commit
  to the repo. Expose via `BuildConfig` the same way other secrets in this project
  are handled.
- Since the free tier is capped at 500MB/month and CGNAT means most calls will
  need relay, expect this to be exhausted quickly during testing — that's expected
  and fine for validating the call flow. Monitor usage in the Metered dashboard.
- Add a clear in-app failure state when TURN is unavailable (cap hit, provider
  down) rather than letting the call silently hang in "Connecting…" — see §6.

---

## 3. Android dependencies

In `app/build.gradle`:

```gradle
dependencies {
    implementation 'io.getstream:stream-webrtc-android:1.1.1' // confirm latest stable on Maven Central
}
```

Manifest permissions:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

Runtime-request `CAMERA` and `RECORD_AUDIO` (and `BLUETOOTH_CONNECT` on API 31+)
before starting a call, with a graceful audio-only fallback if camera permission is
denied.

---

## 4. Firestore schema + security rules

```
calls/{callId}
  callerId: string
  calleeId: string
  type: "voice" | "video"
  status: "ringing" | "accepted" | "declined" | "ended" | "missed" | "timeout"
  offer: { sdp: string, type: "offer" }
  answer: { sdp: string, type: "answer" }
  createdAt: serverTimestamp
  endedAt: serverTimestamp | null
  endReason: "hangup" | "declined" | "timeout" | "network_error" | null

calls/{callId}/callerCandidates/{autoId}
  candidate: string, sdpMid: string, sdpMLineIndex: number

calls/{callId}/calleeCandidates/{autoId}
  candidate: string, sdpMid: string, sdpMLineIndex: number
```

`firestore.rules` addition:

```
match /calls/{callId} {
  allow read, write: if request.auth != null &&
    (request.auth.uid == resource.data.callerId || request.auth.uid == resource.data.calleeId);
  allow create: if request.auth != null && request.auth.uid == request.resource.data.callerId;

  match /callerCandidates/{candId} {
    allow read, write: if request.auth != null;
  }
  match /calleeCandidates/{candId} {
    allow read, write: if request.auth != null;
  }
}
```

---

## 5. New Android classes

### `com/duoshield/app/call/CallSignalRepository.java`
Thin Firestore wrapper for all `calls/{callId}` reads/writes — isolates signaling
plumbing from call logic.

### `com/duoshield/app/call/CallManager.java`
- Owns `PeerConnectionFactory`, `PeerConnection`, local/remote `MediaStream`.
- Exposes `startCall(calleeId, isVideo)`, `acceptCall(callId)`, `declineCall(callId)`,
  `endCall()`.
- Builds the ICE server list from config (Google STUN as a free first entry, plus
  the Metered TURN credentials):

```java
List<PeerConnection.IceServer> iceServers = Arrays.asList(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder(BuildConfig.TURN_URL)
        .setUsername(BuildConfig.TURN_USERNAME)
        .setPassword(BuildConfig.TURN_CREDENTIAL)
        .createIceServer()
);
```

- Creates SDP offer/answer, sets local/remote descriptions, writes to Firestore.
- Listens for remote SDP and `status` changes via `addSnapshotListener`.
- Listens to the opposite-side ICE-candidate subcollection, calls
  `addIceCandidate()` as candidates arrive; writes our own candidates to our own
  subcollection on the local `onIceCandidate` callback.
- On connection-state `FAILED` (e.g. TURN quota exhausted or provider unreachable),
  surface a clear "Call failed — network unavailable" state rather than hanging in
  "Connecting…" indefinitely.
- Cleans up the Firestore call doc and candidate subcollections on call end.

### `com/duoshield/app/call/CallActivity.java`
Full-screen call UI: local PiP view, remote full-screen video (or avatar+waveform
for voice-only), mute/camera-toggle/speaker/end-call buttons, state UI
(Ringing/Connecting/Connected/Ended/Failed), call-duration timer, `SurfaceViewRenderer`
for local and remote video.

### `com/duoshield/app/call/IncomingCallActivity.java`
Shows over the lock screen (`setShowWhenLocked(true)`, `setTurnScreenOn(true)`,
`KeyguardManager.requestDismissKeyguard`), Accept/Decline buttons, ringtone +
vibration, 30s auto-timeout writing `status: "timeout"` and triggering a missed-call
notification (reuse `NotificationHelper.java` patterns).

---

## 6. FCM wakeup for backgrounded/killed app

In `DuoShieldMessagingService.java`, handle a new data message `type: "call_invite"`
(`callId`, `callerId`, `callerName`, `isVideo`):
- Foreground app: broadcast locally to show `IncomingCallActivity`.
- Backgrounded/killed: build a full-screen-intent notification
  (`NotificationCompat.Builder.setFullScreenIntent`, `CATEGORY_CALL`, high
  priority) pointing at `IncomingCallActivity`.

Extend `server/index.js` to send this FCM push when it sees a new `calls` doc with
`status: "ringing"`, mirroring its existing logic for message pushes. The caller's
`CallManager.startCall()` writes the Firestore doc first, which triggers this.

---

## 7. Cleanup

- On hangup/decline/timeout, the ending side deletes the `calls/{callId}` doc and
  its candidate subcollections; the other side just stops listening.
- Add a `WorkManager` job (matching `StorageCleanupWorker.java` /
  `B2CleanupWorker.java` patterns) that purges `calls/*` docs older than 24h with
  status in `["ringing","missed","timeout"]`, in case a client crashes mid-call.

---

## 8. Testing checklist

1. Outgoing video call, both devices foregrounded, both on cellular data (not
   WiFi) — confirm relay (`relay` candidate type via `getStats()`) is used and the
   call connects.
2. Backgrounded callee — confirm full-screen incoming-call notification rings.
3. Decline → confirm Firestore cleanup.
4. 30s unanswered → confirm timeout/missed-call notification.
5. Audio-only call path tested separately from video.
6. **Monitor TURN usage**: check the Metered dashboard after each test call to
   track consumption against the 500MB/month cap; confirm the app shows a clear
   "Call failed" state (not a silent hang) once the quota is exhausted.
7. Confirm `CryptoHelper.java`, `KeyManager.java`, `CryptoInitializer.java`,
   `PairingManager.java` are untouched.
8. Confirm no leftover `calls/*` Firestore docs after normal hangup.

---

## 9. Explicitly out of scope

- Group calls / multi-party (would need an SFU — future work).
- Call recording, screen sharing.
- Extra media encryption beyond WebRTC's built-in DTLS-SRTP (already on by default
  — don't disable it, no extra work needed).
- TURN credential rotation / REST-based time-limited credentials (fine to add
  later if multi-user; static long-lived credential is fine at 2-user scale).
- Self-hosted TURN setup (deferred until the Metered free tier proves
  insufficient — swap the config when that happens, no code changes needed).

---

Implement this fully: the Android dependency and manifest changes, the four new
Java classes, the Firestore rules update, the FCM `call_invite` push extension,
and a call button wired into the existing chat/conversation screen to launch
`CallActivity`.
