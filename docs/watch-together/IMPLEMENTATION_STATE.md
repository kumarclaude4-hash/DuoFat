# Watch Together — Implementation State

> **Persistent cross-session handoff document.**
> Update this file whenever meaningful implementation progress is made.
> It must describe the ACTUAL state of the code, not the original plan.
> Do not delete prior useful context — amend it.

- **Last updated:** Session 7 (2026-08-10)
- **Branch:** `v0/kevibaf520-3621-84819b1b` (off `main`)
- **Reconciled against commit:** `19a11ff` — merge of PR #42
  ("Enable Watch Together for synchronized YouTube viewing in calls"), which merged
  `0c9e6e6` (the `BaseActivity` FCM de-registration fix) and `76eb2d0` (the Session 5
  doc update). **The repo + HEAD are the source of truth for everything below.**
- **Overall feature status:** **FEATURE-COMPLETE FOR THE CORE FLOW + POLISH, AND
  BUILD-VERIFIED — BUT NOT RUNTIME-VERIFIED.**
  - The feature is reachable from the call UI (Session 3: `CallActivity`
    binds/reveals/launches the watch button; manifest entry exists). The **heartbeat writer**
    (`maybeWriteHeartbeat`, single-writer via a last-actor guard, `ACTION_HEARTBEAT`) is
    present in `WatchTogetherActivity`, and Session 4 added a **one-shot awareness cue**
    ("Rejoin Watch Together") driven by a single `fetchState` with no second listener.
  - **Session 6 re-established a JDK 17 + Android SDK 34 toolchain from scratch and
    independently re-verified the build in a fresh container:**
    `:app:compileDebugJavaWithJavac` **PASS**, `:app:assembleDebug` **PASS** (real APKs),
    `:app:lintDebug` **PASS**, **58/58 Watch Together JVM tests PASS** (forced rerun, not a
    cached result), static checker **21/21 PASS**.
  - **What still remains is on-device / two-participant runtime verification.** It is
    **BLOCKED**, not skipped: this container has no `/dev/kvm`, no `vmx`/`svm` CPU flags,
    no emulator binary, and no attached device. See §10 and §11 item 9.
  - **Correction (Session 7):** the "zero bugs ever found" claim above was wrong. A full
    audit against the actual repo (not this doc) found and fixed two blocking defects and
    four secondary ones — see the Session 7 entry in §10-adjacent history below and the
    `git log` for the exact diffs. The most significant: `shouldApply`'s strict-greater
    `seq` check could permanently desync two devices that raced to the same `seq` (e.g.
    simultaneous session start), and playback rate was fully plumbed end-to-end but had
    **no UI control**, making it unreachable despite being listed as done in §1 item 4.
    Both are fixed as of this session. Static/compile verification has not been re-run in
    this sandbox (no JDK available here); re-run the Session 6 toolchain before trusting
    a "PASS" claim again.

---

## 1. Objective

Add a **Watch Together** feature to DuoShield's existing WebRTC video call, so two
participants on a call can watch the same YouTube video in sync.

Intended user experience:

1. Two users are in an existing DuoShield video/voice call.
2. From inside the call, one taps a "Watch Together" control and pastes a YouTube link.
3. Both participants' devices load that video **locally, directly from YouTube**.
4. Play, pause, seek, and playback rate are synchronized between them.
5. The existing WebRTC call (audio/video) continues to run untouched.
6. The existing temporary in-call chat remains available.

Hard constraints (from the feature request):

- The YouTube video itself **MUST NOT** be transported through DuoShield's WebRTC
  infrastructure. Each client fetches the video from YouTube itself.
- Synchronization must use **lightweight state/events** over the communication
  infrastructure **already present** in the call.
- Do **not** rewrite working WebRTC code. Do **not** add a new backend/service.
- No unrelated refactoring.

---

## 2. Repository Architecture Relevant to This Feature

DuoShield is a **native Android application written in Java** (not a web app).
Gradle build, Firebase backend, package root `com.duoshield.app`.

| Path | What it actually does |
|---|---|
| `app/src/main/java/com/duoshield/app/call/CallActivity.java` | The in-call screen. ~1130 lines. Owns the call UI, control bar, WebRTC renderers, call lifecycle, foreground service, and the entry point to in-call chat. **This is the integration point for Watch Together UI.** |
| `app/src/main/java/com/duoshield/app/call/CallManager.java` | WebRTC engine: `PeerConnectionFactory`, `PeerConnection`, tracks, SDP, ICE, `CallState` enum. **Do not modify for this feature.** |
| `app/src/main/java/com/duoshield/app/call/CallSignalRepository.java` | Thin Firestore wrapper for all `calls/{callId}` signaling reads/writes. Modified this session (subcollection sweep only). |
| `app/src/main/java/com/duoshield/app/call/InCallChatActivity.java` | Temporary in-call chat screen. The closest existing analogue to Watch Together and the pattern that was copied. |
| `app/src/main/java/com/duoshield/app/call/InCallChatMessage.java`, `InCallChatAdapter.java` | Chat message model + RecyclerView adapter. |
| `app/src/main/java/com/duoshield/app/call/IncomingCallWatcher.java`, `CallForegroundService.java`, `CallRecord.java`, `TurnCredentialCache.java` | Call support machinery. Not touched. |
| `app/src/main/java/com/duoshield/app/call/watch/` | All Watch Together production code: `WatchTogetherState`, `YouTubeUrlParser`, `WatchTogetherRepository` (Session 1); `WatchTogetherPlayerView`, `WatchTogetherActivity` (**Session 2, new**). |
| `app/src/main/res/layout/activity_call.xml` | Call screen layout, including the bottom control bar with `btnChatLayout` / `btnChat`. **Session 2: added `btnWatchLayout` / `btnWatch` markup** (`android:visibility="gone"` by default). **Not yet bound in `CallActivity.java` — no click listener, no reveal-on-connect logic.** |
| `app/src/main/res/layout/activity_incall_chat.xml` | In-call chat layout. Reference for styling the Watch Together screen. |
| `app/src/main/java/com/duoshield/app/util/FirebaseCostGuard.java` | Mandatory Firestore cost gate. See §9. |
| `firestore.rules` | Security rules. Modified this session (added `/watch/{docId}`). |
| `firestore-tests/rules.test.js` | Jest + `@firebase/rules-unit-testing` rules tests. Modified this session. |
| `app/src/test/java/com/duoshield/app/call/` | JVM unit tests for call logic (`CallStateTest`, `TurnCredentialCacheTest`, `IncomingCallWatcherDeduplicationTest`). Pure logic, no Firebase. |
| `.agents/memory/duoshield-rules.md` | **Non-negotiable project rules.** Read before editing anything. |
| `.github/workflows/ci.yml` | CI: `:app:lintDebug`, `:app:assembleDebug`, instrumented Espresso tests on emulator. |
| `.github/workflows/firestore-rules-test.yml` | CI: runs the Firestore rules Jest suite against the emulator. |

---

## 3. Existing WebRTC Architecture

Verified by reading the source this session.

- **Engine:** `CallManager` wraps `PeerConnectionFactory` / `PeerConnection` using the
  `org.webrtc` library. It exposes a `CallState` enum
  (`IDLE`, `OUTGOING_RINGING`, `INCOMING_RINGING`, `CONNECTING`, `CONNECTED`, `ENDED`, `FAILED`).
- **Signaling transport: Firestore, not a socket server.** There is no custom signaling
  backend. Everything flows through documents:
  - `calls/{callId}` — the call doc: `callerId`, `calleeId`, `type`, `status`, `chatId`,
    `offer`, `answer`, `restartOffer`, `iceRestartRequested`, `endReason`, timestamps.
  - `calls/{callId}/callerCandidates/{id}` — caller ICE candidates.
  - `calls/{callId}/calleeCandidates/{id}` — callee ICE candidates.
- **Signaling access layer:** `CallSignalRepository` — `createCallDoc`, `writeAnswer`,
  `writeStatus`, `addCallerCandidate` / `addCalleeCandidate`, `listenToCall`,
  `listenToCallerCandidates` / `listenToCalleeCandidates`, ICE-restart helpers, and
  `deleteCallDoc` (which sweeps subcollections).
- **CRITICAL FINDING — there is NO WebRTC data channel in use.**
  `CallManager`'s `onDataChannel(...)` observer callback is an **empty stub**. No
  `DataChannel` is ever created, and `grep -rn "DataChannel"` finds only that stub.
  So "the communication infrastructure already present in the call" means
  **Firestore signaling**, not a data channel. This drove the architecture in §5.
- **Lifecycle:** `CallActivity` receives `EXTRA_CALL_ID`, `EXTRA_CALLER_ID`,
  `EXTRA_CALLEE_ID`, `EXTRA_IS_CALLER`, `EXTRA_MY_UID`, `EXTRA_IS_VIDEO`,
  `EXTRA_PARTNER_NAME`, `EXTRA_CHAT_ID`. It holds `callId`, `myUid`, `partnerId`,
  `isCaller` as fields. On teardown it writes a `CallRecord` and calls `deleteCallDoc`.
- **Auth / authorization:** `firestore.rules` restricts `calls/{callId}` read+write to
  `callerId` and `calleeId` only. Call *creation* additionally requires a valid
  `chatId` proving a bilateral contact relationship (the "F6" gate). Subcollections
  each re-assert the participant check, because **Firestore rules do not cascade**.

---

## 4. Existing Temporary Chat Architecture

- `InCallChatActivity` is a **separate Activity** launched from `CallActivity` via
  `openInCallChat()`, bound to the `btnChat` view in the call control bar.
- Extras passed: `EXTRA_CALL_ID`, `EXTRA_MY_UID`, `EXTRA_PARTNER_NAME`.
- Messages live in **`calls/{callId}/chat`** — a Firestore subcollection, ordered by
  timestamp, read with a single `addSnapshotListener` stored as a `ListenerRegistration`
  and removed in `onDestroy()`.
- Messages are **ephemeral**: never written to Room, and swept when the call document is
  deleted by `CallSignalRepository.deleteCallDoc`.
- It extends `AppCompatActivity`, **not** `BaseActivity` — deliberately, with a documented
  rationale: the user is already authenticated, the data is ephemeral, and triggering the
  app-lock redirect mid-call would disrupt an active call.

**What was reused for Watch Together:** the whole shape — ephemeral Firestore
subcollection under the call doc, one snapshot listener owned by the screen, swept on call
end, `AppCompatActivity` for mid-call screens, and extras-based wiring from `CallActivity`.

---

## 5. Watch Together Architecture

**Chosen design: a single Firestore state document at `calls/{callId}/watch/state`.**

```
calls/{callId}                  <- existing call doc (untouched)
  /callerCandidates/{id}        <- existing ICE (untouched)
  /calleeCandidates/{id}        <- existing ICE (untouched)
  /chat/{msgId}                 <- existing temporary chat (untouched)
  /watch/state                  <- NEW: one Watch Together state doc
```

Media path (unchanged by design):

```
YouTube CDN ──────► Participant A's local player
YouTube CDN ──────► Participant B's local player
Firestore watch/state ◄──► both (only: videoId, playing, positionMs, rate, seq)
WebRTC ───────────► audio/video of the call ONLY — no YouTube bytes
```

**Why this fits the existing codebase:**

- It mirrors the signaling mechanism the call **already** uses, so there is no new
  service, no new protocol stack, and no new failure mode to operate.
- It is the smallest possible integration surface: `CallManager` and the entire
  PeerConnection/SDP/ICE path are **completely untouched**.
- Join/rejoin is free: the newest snapshot **is** the complete session state, so a
  participant who joins late or returns from background just reads the doc and re-syncs.
- Cheapest option under the project's Firestore cost rules: **one** listener and **one**
  write per user action (versus an append-only event log, which multiplies writes,
  grows unbounded, needs sweeping, and requires replay on rejoin).

**Why a WebRTC data channel was rejected:** none exists today (`onDataChannel` is an
empty stub), so adding one means touching working PeerConnection setup and renegotiation
— explicitly against the brief. It would *also* still need a Firestore fallback so a
late-joining or returning participant can obtain current state. Latency is not the
binding constraint here because drift is corrected by projection math (§6) rather than by
message timeliness.

**Why `active: false` instead of deleting the doc on stop:** deleting races with the peer's
snapshot listener and can leave their player open with nothing to reconcile against. The
document is genuinely removed when the call ends, via `deleteCallDoc`.

---

## 6. Synchronization Protocol

### State document: `calls/{callId}/watch/state`

| Field | Type | Meaning |
|---|---|---|
| `active` | boolean | Session running. `false` = ended; players should close. |
| `videoId` | string | YouTube video ID (11 chars). **Only the ID is synced, never a URL.** |
| `hostUid` | string | UID of whoever started the session (informational; control is shared). |
| `playing` | boolean | True = playing, false = paused. |
| `positionMs` | long | Playback position at the moment of the write. |
| `playbackRate` | double | Playback rate; defaults to `1.0`. |
| `updatedAtMs` | long | **Writer's** wall clock. Diagnostics/staleness display ONLY — see clock note. |
| `seq` | long | Monotonic counter, incremented on every write. Ordering + echo suppression. |
| `lastActionBy` | string | UID that performed the action. |
| `lastAction` | string | One of the action labels below. |

### Action labels

`start`, `play`, `pause`, `seek`, `rate`, `stop`, `heartbeat`.
These are diagnostic/UI labels — **followers act on the state fields, not on the label.**

### Ordering rule (echo suppression)

`WatchTogetherState.shouldApply(applied, incoming)`:

- Any first state is applied.
- Afterwards, **only a strictly greater `seq` is applied.**

This drops Firestore's local echo of our own write (which would otherwise fight the local
player) and any out-of-order delivery. `WatchTogetherRepository.observeRemoteSeq(n)` raises
the local counter above anything seen remotely, so this device's next write is never
discarded as stale by the peer.

### Clock safety — IMPORTANT for the next session

`updatedAtMs` is the **writer's** clock and is **NOT comparable** against a reader's clock:
device clocks drift and users can change them. **Do not** compute
`now() - updatedAtMs` to derive elapsed time.

Instead, when a snapshot is applied, the follower records **its own local receipt time**
(use `SystemClock.elapsedRealtime()`, which is monotonic and immune to clock changes), then
projects forward using locally measured elapsed time:

```java
long elapsed = SystemClock.elapsedRealtime() - localReceiptRealtime;
long target  = WatchTogetherState.projectedPositionMs(state, elapsed);
if (WatchTogetherState.shouldSeek(localPlayerPositionMs, target)) {
    player.seekTo(target);
}
```

### Projection rule

`projectedPositionMs(state, elapsedSinceSnapshotMs)`:

- Paused → returns the stored `positionMs` (time does not advance).
- Playing → `positionMs + elapsed * playbackRate`.
- Negative elapsed is clamped to 0; result is never negative.

### Drift rule

`shouldSeek(localMs, targetMs)` seeks only when
`|target - local| > DRIFT_THRESHOLD_MS` (**1500 ms**, exclusive).
Below that, correcting is more jarring than the drift itself.

### Heartbeat

The controlling participant may write `heartbeat` at most once per
`HEARTBEAT_INTERVAL_MS` (**10 s**) so followers can correct slow drift. This bounds write
cost. **Implemented** in `WatchTogetherActivity.maybeWriteHeartbeat()` — single-writer
(only `lastActionBy` writes, only while playing), on a `Handler` tied to the Activity
lifecycle. See §7.

### Control model

**Shared control**, deliberately: either participant may play/pause/seek. `hostUid` records
who started the session but does not lock control. The Firestore rules permit both
participants to write, and there is a rules test asserting the non-host can pause.

---

## 7. Current Implementation Status

> **SESSION 5 HEADLINE — the feature is now COMPILER-VERIFIED.** A real JDK 17 + Android
> SDK 34 toolchain was provisioned in this container and the project was built for the
> first time ever. `:app:compileDebugJavaWithJavac` and `:app:assembleDebug` both
> **SUCCEED** and produce real APKs. `:app:lintDebug` **SUCCEEDS**. All 58 Watch Together
> JVM unit tests **PASS**. The Firestore rules tests **were actually executed** against the
> real emulator — **151/151 pass, including all 7 `watch/state` tests**. The phrase
> "not compiler-verified", which appeared throughout Sessions 1–4, is now obsolete and has
> been removed from the rows below.
>
> Two caveats, stated precisely: (1) **device/emulator verification is still NOT done** —
> it is hard-blocked, no `/dev/kvm` in this container (§10). (2) `:app:testDebugUnitTest`
> as a *whole task* still FAILS, on **13 pre-existing `BackupRoundTripTest` failures that
> have nothing to do with Watch Together** (root cause and proof of pre-existence in §11
> item 8). No Watch Together test fails.

> **SESSION 6 CONFIRMATION — Session 5's build claims were independently reproduced from
> scratch, so they are not a one-off artifact of one container.** Session 6 started in a
> *fresh* container with **no JDK, no Android SDK, no `adb`, and no `emulator`** — the
> Session 5 toolchain did not persist. A JDK 17.0.20 + Android SDK 34 (platform-tools,
> build-tools 34.0.0) toolchain was re-provisioned and every check was re-run:
>
> | Check | Session 6 result |
> |---|---|
> | `:app:compileDebugJavaWithJavac` | **PASS** (warnings only, zero errors) |
> | `:app:assembleDebug` | **PASS** — real APKs emitted |
> | `:app:lintDebug` | **PASS** (see the `abortOnError` caveat in §10) |
> | Watch Together JVM tests | **PASS — 58/58**, via `--rerun-tasks` so this is not a cached result |
> | `scripts/check-watch-together.js` | **PASS — 21/21** |
> | `:app:testDebugUnitTest` (whole task) | **FAIL — 126 tests, 13 failed, all 13 in `BackupRoundTripTest`** (pre-existing, unrelated; §11 item 8) |
> | Device / emulator runtime verification | **BLOCKED** — see §10 |
>
> Session 6 also **re-read `WatchTogetherState`, `WatchTogetherRepository`,
> `YouTubeUrlParser`, and `WatchTogetherActivity`'s lifecycle/heartbeat paths looking for a
> real defect and found none**, so per its instructions it made **no code change**. Three
> specific NPE/leak hypotheses were checked and each is already correctly defended:
> `onCreate` `finish()`es when `callId`/`myUid` is null (so `maybeWriteHeartbeat`'s
> `myUid.equals(...)` cannot NPE); the heartbeat `Handler` is cancelled in **both** `onStop`
> (`removeCallbacks`) and `onDestroy` (`removeCallbacksAndMessages(null)`); and the snapshot
> listener is attached under a `stateListener == null` guard and removed in both `onStop` and
> `onDestroy`, so it can never double-attach. The `repo != null` guard in `onStart` is what
> makes the post-`finish()` `onStart`→`onStop` pass harmless.

| Component | Status | Notes |
|---|---|---|
| `WatchTogetherState` (model + sync math) | **COMPLETE** | Fields, `toMap`/`fromMap`, `projectedPositionMs`, `shouldSeek`, `shouldApply`, `isPlayable`, `copy`. Defensive parsing. |
| `YouTubeUrlParser` | **COMPLETE** | All common URL forms, bare IDs, `t`/`start` offsets, strict rejection. |
| `WatchTogetherRepository` (Firestore transport) | **COMPLETE** | `stateRef`, `listenToState`, `writeState`, `fetchState`, `endSession`, `nextSeq`/`observeRemoteSeq`. **Session 2: now takes a `Context` and calls `FirebaseCostGuard`** — `listenToState` and `fetchState` gate/record one read; `writeState` gates/records one write and returns `boolean` (false = dropped, budget exhausted or null args) instead of `void`. |
| Firestore rules for `/watch/{docId}` | **COMPLETE** | Participant-gated, mirrors the ICE/chat rules. |
| Ephemeral sweep on call end | **COMPLETE** | `"watch"` added to `deleteCallDoc`'s subcollection list. |
| Unit tests — sync model | **COMPLETE + PASSING** | `WatchTogetherStateTest` — **executed Session 5: 26 tests, 26 passed, 0 failed.** |
| Unit tests — URL parser | **COMPLETE + PASSING** | `YouTubeUrlParserTest` — **executed Session 5: 32 tests, 32 passed, 0 failed.** (Sessions 1–4 documented "33 tests"; the real count reported by the JUnit XML is **32**. The doc was wrong, the tests are fine. Total Watch Together unit tests = **58**.) |
| Firestore rules tests for `watch` | **COMPLETE + PASSING (executed Session 5)** | 7 tests in `rules.test.js`. **Actually run against the real Firestore emulator this session: all 7 pass** (`caller can start`, `callee can start`, `callee can read caller's state`, `either participant can control playback`, `outsider cannot read`, `outsider cannot hijack`, `unauthenticated denied`). Whole suite: 151/151. |
| Static validation script | **COMPLETE (Session 3: now covers the player/Activity + UI wiring)** | `scripts/check-watch-together.js` now structurally checks `WatchTogetherPlayerView.java` and `WatchTogetherActivity.java` (tokenizer brace/paren balance + package), and adds an integration-points section: control-bar ids present in `activity_call.xml`; `CallActivity` binds/reveals/listens/launches + imports the Activity; extras passed match constants declared on the Activity; manifest declares `WatchTogetherActivity` as `exported="false"`; `activity_watch_together.xml` exists. **Session 4** added four safety-invariant checks: exactly-one snapshot listener (attach + guard + ≥2 remove sites), single-writer heartbeat (`lastActionBy` guard + `ACTION_HEARTBEAT`), repository cost-guarding of every read/write, and CallActivity awareness being one-shot `fetchState` with no listener and no writes. **All 21 checks pass.** |
| **`WatchTogetherPlayerView`** (WebView host/bridge, `app/.../call/watch/WatchTogetherPlayerView.java`) | **COMPLETE, COMPILES (Session 5)** | Wraps a `WebView` that loads `file:///android_asset/watch_together/player.html` (Session 1 asset). `@JavascriptInterface` bridge (`onReady`, `onStateChange`, `onPlaybackRateChange`, `onCurrentTime`, `onPlayerError`) posts back to the main thread via a `Handler`; Java→JS calls (`loadVideo`, `play`, `pause`, `seekTo`, `setPlaybackRate`) go through `evaluateJavascript`. JS execution is scoped to this WebView only; `setAllowFileAccess`/universal access left at safe defaults. Exposes a `Listener` callback interface consumed by `WatchTogetherActivity`. |
| **`WatchTogetherActivity`** (`app/.../call/watch/WatchTogetherActivity.java`) | **COMPLETE, COMPILES (Session 5)** | Extends `AppCompatActivity` (documented rationale comment repeating the `InCallChatActivity` precedent, per rule §9.1). Reads `EXTRA_CALL_ID`/`EXTRA_MY_UID`/`EXTRA_PARTNER_NAME`. Owns exactly one `listenToState` `ListenerRegistration`, removed in `onDestroy()`. Implements the full sync protocol from §6: `shouldApply` → `observeRemoteSeq` → local `SystemClock.elapsedRealtime()` receipt stamp → `projectedPositionMs` → `shouldSeek` drift gating. Uses an `applyingRemote` flag to suppress write-back feedback loops when reconciling a remote snapshot. Validates both the locally-parsed and the remotely-received video ID through `YouTubeUrlParser`/`isValidVideoId` before ever loading it. **Heartbeat writer IS implemented** (`maybeWriteHeartbeat`, single-writer) — see the Heartbeat writer row below. |
| **`activity_watch_together.xml`** | **COMPLETE** | URL input + Start row, `FrameLayout` player container hosting the `WatchTogetherPlayerView`, play/pause/seek-back/seek-forward controls, status text, minimize/close buttons. Reuses existing drawables (`ic_arrow_down`, `ic_close`, `ic_play_audio`/`ic_pause_audio`, `bg_incall_input`, `bg_call_btn_circle`) — no new drawables except the control-bar icon below. |
| **Watch Together button in the call control bar** | **COMPLETE (Session 3)** | `activity_call.xml` has the `btnWatchLayout`/`btnWatch` block (mirrors `btnChatLayout`/`btnChat`), icon `ic_watch_together.xml`. It is `visibility="gone"` by default and `CallActivity` now reveals it (`View.VISIBLE`) in the `isVideo` block right after `btnChatLayout`, and attaches a click listener. |
| **`CallActivity` wiring** | **COMPLETE (Session 3), COMPILES (Session 5)** | `CallActivity.java`: imports `com.duoshield.app.call.watch.WatchTogetherActivity`; declares `btnWatch`/`btnWatchLayout` fields; binds both via `findViewById`; reveals `btnWatchLayout` for video calls; `btnWatch.setOnClickListener(v -> openWatchTogether())`; new `openWatchTogether()` mirrors `openInCallChat()` (guards on `callId`/`myUid`, then launches `WatchTogetherActivity` with `EXTRA_CALL_ID`/`EXTRA_MY_UID`/`EXTRA_PARTNER_NAME`). Raw brace balance verified (164/164). |
| **Session-invite / "partner started watching" awareness** | **PARTIAL (Session 4)** | `CallActivity.refreshWatchTogetherAwareness()` does a **one-shot** `fetchState` when the watch button is revealed; if a session is already active it sets the button's content description to "Rejoin Watch Together" and `setSelected(true)` (semantic-only cue, no new drawables, no second listener, no writes). This covers the "I can tell a session is live and tapping rejoins" case. **NOT done:** an active push/notification/toast that alerts B in real time the instant A starts a session while B is on the call screen but has not opened Watch Together — deliberately deferred to avoid a second always-on listener on `CallActivity`. |
| **Heartbeat writer** | **COMPLETE, COMPILES (Session 5)** | `WatchTogetherActivity.maybeWriteHeartbeat()` runs on a `Handler` posted every `HEARTBEAT_INTERVAL_MS` (started in `onStart`, cancelled in `onStop`/`onDestroy`). Single-writer: only the participant matching `appliedState.lastActionBy` writes, and only while `playing`, using `ACTION_HEARTBEAT` through the budget-gated `writeState`. This bounds write cost to at most one per interval and never lets both devices write. |
| **FirebaseCostGuard integration** | **COMPLETE** | See `WatchTogetherRepository` row above. Mandatory-before-merge item from Session 1 is now resolved. |
| **Manifest entry for the new Activity** | **COMPLETE (Session 3)** | `WatchTogetherActivity` is declared in `AndroidManifest.xml` directly after `InCallChatActivity`, with identical attributes: `exported="false"`, `screenOrientation="portrait"`, `windowSoftInputMode="adjustResize"`, `theme="@style/Theme.DuoShield.FullScreen"` (theme verified to exist in `themes.xml`). |

---

## 8. Files Changed

### New files from Session 1 (all additive)

| File | Contents |
|---|---|
| `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherState.java` | State model, Firestore field-name constants, action labels, tuning constants (`DRIFT_THRESHOLD_MS`, `HEARTBEAT_INTERVAL_MS`), defensive `toMap`/`fromMap`, and the pure sync math (`projectedPositionMs`, `shouldSeek`, `shouldApply`). No Android/Firebase imports, so it is JVM-unit-testable. |
| `app/src/main/java/com/duoshield/app/call/watch/YouTubeUrlParser.java` | Static YouTube URL → video-ID extraction, validation, and `t`/`start` offset parsing. No Android imports. |
| `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherRepository.java` | Firestore wrapper for `calls/{callId}/watch/state`, shaped after `CallSignalRepository`. Owns the local `seq` counter. All failures logged and non-fatal so Watch Together can never tear down a call. |
| `app/src/test/java/com/duoshield/app/call/watch/WatchTogetherStateTest.java` | 26 JVM tests: seq ordering/echo suppression, projection (paused/playing/rate/negative-elapsed), drift threshold boundaries, serialization round-trip, Firestore number widening, wrong-type tolerance, `isPlayable`, `copy` independence, rejoin scenario. |
| `app/src/test/java/com/duoshield/app/call/watch/YouTubeUrlParserTest.java` | 33 JVM tests: watch/short/embed/nocookie/shorts/live/legacy-v/mobile URLs, bare IDs, whitespace, plus rejection of non-YouTube URLs, `javascript:` URLs, channel URLs, wrong-length and bad-alphabet IDs; timestamp parsing. |
| `scripts/check-watch-together.js` | Toolchain-free static validator: balanced braces/parens (single-pass Java tokenizer), package declarations, test→production symbol existence, rules-file brace balance, `/watch/` nested inside `/calls/`, and the `deleteCallDoc` sweep. **Not yet extended for Session 2 files — see §12 item 1.** |
| `docs/watch-together/IMPLEMENTATION_STATE.md` | This document. |
| `app/src/main/assets/watch_together/player.html` | YouTube IFrame Player API host page, loaded by `WatchTogetherPlayerView` via `file:///android_asset/`. |

Session 1 was merged to `main` via PR #39 (`993acd8`, `62fe765`, merge commit `833a60a`).

### New files from Session 2 (all additive)

| File | Contents |
|---|---|
| `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherPlayerView.java` | `WebView`-hosted YouTube IFrame player bridge. Loads `player.html`; `@JavascriptInterface` methods (`onReady`, `onStateChange`, `onPlaybackRateChange`, `onCurrentTime`, `onPlayerError`) marshal JS callbacks onto the main thread via `Handler`; `loadVideo`/`play`/`pause`/`seekTo`/`setPlaybackRate` drive the player via `evaluateJavascript`. Exposes a `Listener` interface. |
| `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherActivity.java` | The Watch Together screen. `AppCompatActivity` with the `InCallChatActivity` mid-call rationale documented in a class comment. Implements the full §6 sync protocol (echo suppression via `seq`, elapsed-time projection via `SystemClock.elapsedRealtime()`, drift-gated seeking, `applyingRemote` feedback-loop guard). Validates local and remote video IDs through `YouTubeUrlParser` before loading. |
| `app/src/main/res/layout/activity_watch_together.xml` | Layout for the above: URL entry row, player container, transport controls, status line, minimize/close. Reuses existing drawables only. |
| `app/src/main/res/drawable/ic_watch_together.xml` | New vector icon for the control-bar button (only new drawable this session). |

### Modified files, Session 1 (minimal, additive only)

| File | Change |
|---|---|
| `app/src/main/java/com/duoshield/app/call/CallSignalRepository.java` | Added `"watch"` to the subcollection array in `deleteCallDoc` so the state doc is swept with the call; updated the Javadoc from "three" to "four". **No logic change.** |
| `firestore.rules` | Added a `match /watch/{docId}` block inside `match /calls/{callId}`, using the identical participant gate as `callerCandidates` / `calleeCandidates` / `chat`. **No existing rule modified.** |
| `firestore-tests/rules.test.js` | Added a `describe('/calls/{callId}/watch/state')` block with 7 tests. **No existing test modified.** |

`git diff --stat` for the Session 1 modified files: **3 files, 106 insertions, 3 deletions**
(the 3 deletions are the Javadoc line and the array literal that were rewritten).

### Modified files, Session 2 (minimal, additive only)

| File | Change |
|---|---|
| `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherRepository.java` | Constructor now takes a `Context` and constructs a `FirebaseCostGuard`. `listenToState`/`fetchState` gate+record one read each; `writeState` gates+records one write and now returns `boolean` (previously `void`) — `false` means the write was dropped (budget exhausted or null args). **This is a breaking signature change for any future caller** — there are none yet, since `CallActivity` does not call the repository at all (see §12 item 8). |
| `app/src/main/res/layout/activity_call.xml` | Added a `btnWatchLayout`/`btnWatch` block (mirrors `btnChatLayout`/`btnChat`) after the chat button, `android:visibility="gone"` by default. **No existing view modified.** |

Nothing from Session 1's do-not-touch list (`CallManager.java`, `InCallChatActivity.java`
and its model/adapter, existing `firestore.rules` blocks, `IncomingCallWatcher.java`,
`CallForegroundService.java`) was touched in Session 2 either.

### Modified files, Session 3 (minimal, additive only — makes the feature reachable)

| File | Change |
|---|---|
| `app/src/main/java/com/duoshield/app/call/CallActivity.java` | Added `import com.duoshield.app.call.watch.WatchTogetherActivity;`; added `btnWatch` (`ImageView`) and `btnWatchLayout` (`View`) fields next to the chat ones; bound both in the `findViewById` block; revealed `btnWatchLayout` in the `isVideo` block right after `btnChatLayout`; added `btnWatch.setOnClickListener(v -> openWatchTogether())` in `setupButtons`; added `openWatchTogether()` mirroring `openInCallChat()`. **No existing method logic changed** — all insertions alongside the existing chat wiring. |
| `app/src/main/AndroidManifest.xml` | Added the `<activity android:name=".call.watch.WatchTogetherActivity" .../>` declaration after `InCallChatActivity`, with identical attributes. **No existing entry modified.** |
| `scripts/check-watch-together.js` | Added `WatchTogetherPlayerView.java` and `WatchTogetherActivity.java` to the structural `FILES` list; added a CallActivity⇄WatchTogetherActivity integration-points section (control-bar ids, bind/reveal/listen/launch/import in `CallActivity`, extras-match, manifest declaration, watch layout existence). **No existing check modified.** |

Session 3 touched **nothing** on any do-not-touch list. `CallManager.java`,
`CallSignalRepository.java`, `InCallChatActivity.java`, and all `firestore.rules` blocks
are untouched. The only pre-existing production file changed is `CallActivity.java`, and
only by additive insertion mirroring the existing chat-button pattern.

### Modified files, Session 4 (awareness cue + validator invariants — additive only)

| File | Change |
|---|---|
| `app/src/main/java/com/duoshield/app/call/CallActivity.java` | Added imports for `WatchTogetherRepository` and `WatchTogetherState`; added a call to `refreshWatchTogetherAwareness()` right after the button is revealed in the `isVideo` block; added the `refreshWatchTogetherAwareness()` method — a **one-shot** `fetchState` that, if a session is active, sets `btnWatch` content description to "Rejoin Watch Together" and `setSelected(true)`. Uses only guaranteed `View`/`ImageView` methods (no new resources), no second listener, no `writeState`. Raw brace balance 169/169. |
| `scripts/check-watch-together.js` | Added four safety-invariant checks (checks 5–8): exactly-one snapshot listener in `WatchTogetherActivity`; single-writer heartbeat; repository cost-guarding of reads and writes; CallActivity awareness is one-shot `fetchState` with no listener and no writes. Total now 21 checks. **No existing check modified.** |

Session 4 touched **nothing** on any do-not-touch list. The only pre-existing production
file changed is `CallActivity.java`, again by additive insertion. `WatchTogetherActivity.java`
(which contains the merged heartbeat writer) was **not** modified this session — only read
and validated.

### Modified files, Session 5 (build verification — exactly ONE source file changed)

Session 5 was a **verification** session, not a feature session. No Watch Together source
file needed any change: the entire `call/watch/` package compiled correctly on its first
ever compile. Exactly one file was edited, and it was **not** a Watch Together file.

| File | Change |
|---|---|
| `app/src/main/java/com/duoshield/app/BaseActivity.java` | **Fixed a hard compile error that was blocking the entire build** — and therefore blocking all Watch Together verification. `BaseActivity` called `FcmUnregisterWorker.enqueue(getApplicationContext(), uidBeforeSignOut)` (2 args), but `FcmUnregisterWorker` declares `enqueue(Context)` (1 arg), so `javac` failed with *"method enqueue in class FcmUnregisterWorker cannot be applied to given types"*. Removed the now-pointless `userBeforeSignOut`/`uidBeforeSignOut` capture (the worker uses `FirebaseMessaging.deleteToken()`, which needs neither a uid nor a bearer token) and replaced the guard with a plain `FirebaseAuth.getInstance().getCurrentUser() != null` check so the de-registration is still only scheduled when a real session existed. Net −12/+7 lines. **This bug was pre-existing and unrelated to Watch Together** — see §11 item 7 for the proof. |

**No files were created or deleted in Session 5.** `scripts/check-watch-together.js` was
re-run but **not modified** — all 21 checks already passed unchanged, so there was nothing
to fix and no check was weakened.

Untracked, gitignored build scaffolding was generated locally to make the build possible
(`local.properties`, `app/google-services.json` from `app/google-services.json.template`,
`app/build/`). All three are matched by `.gitignore` (verified with `git check-ignore -v`),
so **`git status --porcelain` is empty apart from the one source edit above.** Nothing
secret or environment-specific was committed.

---

## 9. Files That Must NOT Be Modified Without Good Reason

- **`app/src/main/java/com/duoshield/app/call/CallManager.java`** — the working WebRTC
  engine. Watch Together must not require any change here. If a future step seems to need
  one, the design is wrong. (Its `onDataChannel` stub is intentionally left alone.)
- **`app/src/main/java/com/duoshield/app/call/CallSignalRepository.java`** — only the
  subcollection sweep list was touched. Do not alter the SDP/ICE/status methods.
- **`app/src/main/java/com/duoshield/app/call/InCallChatActivity.java`** and the in-call
  chat model/adapter — the temporary chat must keep working exactly as-is. Watch Together
  is a sibling feature, not a replacement.
- **`firestore.rules` existing blocks** — this file encodes many prior security fixes
  (F3, F6, F19, F21, F27, F28, F38, S06-C2 duress latch, BUG-S-CALL01). Only add new
  blocks; never "tidy" existing ones.
- **`IncomingCallWatcher.java`, `CallForegroundService.java`** — call lifecycle
  machinery with existing dedup/notification behavior.

### Project rules that constrain this feature (`.agents/memory/duoshield-rules.md`)

1. **FLAG_SECURE in all activities** — every Activity must extend `BaseActivity`, except
   `SignInActivity`, `LockScreenActivity`, `MainActivity`. **`InCallChatActivity` is a
   documented in-call exception** (uses `AppCompatActivity` to avoid the app-lock redirect
   mid-call). **DONE (Session 2):** `WatchTogetherActivity` follows the `InCallChatActivity`
   precedent and documents the same rationale in its class comment.
2. **`FirebaseCostGuard` before every Firestore op** — `guard.canRead/canWrite/canDelete(n)`
   then `guard.recordReads/recordWrites/recordDeletes(n)`. **DONE (Session 2):** wired into
   `WatchTogetherRepository`'s constructor (now takes a `Context`); `listenToState`,
   `fetchState`, and `writeState` all gate-then-record. `writeState` now returns `boolean`.
3. **One Firestore listener per screen** — attach in `onStart()`/`onCreate()`, keep the
   `ListenerRegistration`, detach in `onStop()`/`onDestroy()`.
4. **Batch deletes only** (`WriteBatch`, max 450 ops) — relevant if sweeping ever changes.
5. **DiffUtil always** for list adapters; never `notifyDataSetChanged()`.
6. **No Cloud Functions** — all logic client-side, enforced by rules.
7. **Room DB version = 10** — Watch Together state is ephemeral and **must not** be
   persisted to Room, so no migration is needed. Keep it that way.

---

## 10. Validation Performed

### Environment limitation — read this before trusting any status above

The dev container used for **both** sessions has **no JDK and no Gradle**
(`which java javac gradle` → nothing). It also has **no `firebase-tools`**, and the
Firestore emulator itself requires Java. Therefore, still in Session 2:

- `./gradlew :app:testDebugUnitTest` — **NOT RUN** (no JDK/Gradle available).
- `./gradlew :app:assembleDebug` — **NOT RUN** (no JDK/Gradle available).
- `./gradlew :app:lintDebug` — **NOT RUN** (no JDK/Gradle available).
- `npm test` in `firestore-tests/` — **NOT RUN** (emulator needs Java).

### What WAS actually executed and passed — Session 1

| Check | Command | Result |
|---|---|---|
| Rules-test JS syntax | `node --check firestore-tests/rules.test.js` | **PASS** — "JS SYNTAX OK" |
| Jest discovers the suite | `npx jest --listTests` | **PASS** — `rules.test.js` listed |
| Rules-test deps install | `npm ci` in `firestore-tests/` | **PASS** |
| New rules tests registered | script-counted `test(` in the new `describe` | **PASS** — 7 tests |
| `firestore.rules` brace balance | `scripts/check-watch-together.js` | **PASS** — depth 0 |
| `/watch/` nested inside `/calls/` | `scripts/check-watch-together.js` | **PASS** — subcollections found: `callerCandidates calleeCandidates chat watch` |
| Java brace/paren balance, all 5 new files | `scripts/check-watch-together.js` | **PASS** |
| Package declarations correct | `scripts/check-watch-together.js` | **PASS** |
| Every production symbol used by tests exists | `scripts/check-watch-together.js` | **PASS** (27 state symbols + 4 parser symbols) |
| `deleteCallDoc` sweeps `"watch"` | `scripts/check-watch-together.js` | **PASS** |
| Change isolation | `git diff --stat` | **PASS** — only 3 existing files touched, +106/−3, `CallManager` and `CallActivity` untouched |

One real defect was found and fixed during Session 1 validation: the first version of the
static checker used chained regexes to strip literals, and an apostrophe inside a Javadoc
comment ("a user might paste") made the char-literal pattern swallow real parentheses,
producing a phantom paren imbalance of 24 in `YouTubeUrlParserTest.java`. The checker was
rewritten with a single-pass tokenizer; the Java file itself was correct.

### What WAS actually executed and passed — Session 2

`scripts/check-watch-together.js` was re-run and still passes, but **it does not inspect
`WatchTogetherPlayerView.java` or `WatchTogetherActivity.java` at all** — extending it was
deferred (see §12 item 1). In its place, the following manual checks were run by hand and
passed:

| Check | Command | Result |
|---|---|---|
| Brace balance, `WatchTogetherActivity.java` | `grep -o '{' \| wc -l` vs `grep -o '}' \| wc -l` | **PASS** — 66/66 |
| Brace balance, `WatchTogetherPlayerView.java` | same | **PASS** — 68/68 |
| Every `R.id`/`R.layout`/`R.drawable` referenced by `WatchTogetherActivity.java` resolves | cross-checked against `activity_watch_together.xml` ids and `res/drawable/` contents | **PASS** — `ic_play_audio.xml`, `ic_pause_audio.xml` exist; every `R.id.*` used has a matching `android:id` in the layout |
| New control-bar ids exist and don't collide | `grep -n btnWatch app/src/main/res/layout/activity_call.xml` | **PASS** — `btnWatchLayout`, `btnWatch` present once each |
| `ic_watch_together.xml` and `bg_call_btn_circle.xml` exist | `ls` | **PASS** |
| Change isolation | `git status` / `git log --stat` on `ab66400` | **PASS** — only the 6 files listed in §8 touched; `CallManager.java`, `InCallChatActivity.java`, `CallActivity.java` untouched |

**Note:** brace-count parity is a much weaker check than the Session 1 tokenizer (it does
not exclude braces inside string/char literals or comments, and both new files happen to
contain none in a way that would trip this up, but that was verified by inspection, not by
the checker). Treat this as a smoke check, not proof of syntactic validity — real
confidence still requires `javac`/Gradle.

### What WAS actually executed and passed — Session 3

Still **no JDK/Gradle** in this container (`which java javac gradle` → nothing), so the
Gradle build/unit-test/lint remain **NOT RUN** for a third session. What was run:

| Check | Command | Result |
|---|---|---|
| Checker JS syntax | `node --check scripts/check-watch-together.js` | **PASS** |
| Full static checker (now 17 checks, incl. Session 2 files + UI wiring) | `node scripts/check-watch-together.js` | **PASS** — all 17 OK, exit 0 |
| `Theme.DuoShield.FullScreen` exists | `grep` in `res/values/themes.xml` | **PASS** — defined line 58 |
| XML well-formedness of `activity_call.xml`, `activity_watch_together.xml`, `AndroidManifest.xml` | `python3 xml.dom.minidom.parse` | **PASS** — all parse without error |
| `CallActivity.java` brace balance (raw) | `node` open/close count | **PASS** — 164/164 |

The new checker section specifically proves, without a compiler, that: the control-bar
ids exist in the layout; `CallActivity` binds `btnWatch`+`btnWatchLayout`, reveals the
layout, sets a click listener, defines `openWatchTogether()`, launches
`WatchTogetherActivity`, and imports it; the extras `CallActivity` passes
(`EXTRA_CALL_ID`/`EXTRA_MY_UID`/`EXTRA_PARTNER_NAME`) are all declared on the Activity;
the manifest declares the Activity as `exported="false"`; and the watch layout exists.

### Manual (device/emulator) verification

**NONE**, in any of the three sessions. No device or emulator has been available in this
container. As of Session 3 the feature IS now wired end-to-end in code, so a device
click-through (start/play/pause/seek/rate/rejoin/end) is finally possible and is the top
outstanding verification — see §13.

### Honest confidence statement

None of the Java code — Session 1's or Session 2's — is compiler-verified. Session 2's new
files (`WatchTogetherPlayerView`, `WatchTogetherActivity`) additionally use `android.webkit.*`
(`WebView`, `WebViewClient`, `JavascriptInterface`), `android.os.Handler`/`Looper`, and
`android.os.SystemClock` — all standard Android APIs already used elsewhere in the app, but
never combined this way before, so the compile-error risk is not lower than Session 1's was.
**The next session must run Gradle before writing any more code.**

---

## 11. Known Issues

Actual, observed issues only:

1. ~~`WatchTogetherRepository` does not call `FirebaseCostGuard`.~~ **RESOLVED in Session 2**
   — see §7/§9.
2. **Nothing is compiler-verified**, Session 1 or Session 2. See §10. No JDK/Gradle has
   been available in this container in either session.
3. **The Firestore rules tests were written but never executed** — the emulator requires
   Java. Their logic is modeled directly on the passing `callerCandidates` tests and uses
   the same `asUser` / `seed` / `testEnv` helpers (signatures verified by reading them), but
   they are unproven. Unchanged from Session 1.
4. ~~The feature is still not reachable from the UI.~~ **RESOLVED in Session 3.**
   `CallActivity` now binds `btnWatch`/`btnWatchLayout`, reveals the button for video calls,
   sets a click listener, and launches `WatchTogetherActivity` via `openWatchTogether()`;
   `WatchTogetherActivity` is declared in `AndroidManifest.xml`. An end user can now open the
   Watch Together screen from an active video call. **Still unproven on a real device** —
   see issue 2 (no compiler) and §13.
5. ~~`scripts/check-watch-together.js` does not cover the Session 2 files.~~ **RESOLVED in
   Session 3.** The checker now runs the tokenizer-based structural checks on
   `WatchTogetherPlayerView.java` and `WatchTogetherActivity.java`, and additionally verifies
   every CallActivity⇄Activity integration point (§10).
6. ~~No heartbeat writer.~~ **RESOLVED** — `WatchTogetherActivity.maybeWriteHeartbeat()`
   writes `ACTION_HEARTBEAT` at most once per `HEARTBEAT_INTERVAL_MS` while playing, and
   only from the last actor, so passive drift is now corrected during uninterrupted
   playback. Still unproven on a real device (issue 2).
7. **Awareness is one-shot only, not a live alert.** As of Session 4, `CallActivity` shows a
   "Rejoin Watch Together" cue on the watch button when a session is already active (via a
   single `fetchState` at reveal time). It does **not** actively notify participant B the
   instant A starts a session while B sits on the call screen — that would need a live
   signal, and we deliberately did not add a second always-on listener to `CallActivity`
   (project rule #3 + cost). If a real-time invite is wanted, the cheapest correct path is to
   piggyback on the call-doc listener `CallActivity` already owns (e.g. a `watchActive` flag
   mirrored onto the call doc), NOT a new Watch Together listener.

Not issues, but deliberate and worth not "fixing" blindly:

- `updatedAtMs` is intentionally not used for elapsed-time math (§6 clock safety).
- Control is intentionally shared rather than host-locked (§6).
- `endSession` intentionally writes `active: false` rather than deleting (§5).

---

## 12. Remaining Work

Concrete, ordered tasks. Items struck through were completed in Session 2.

1. ~~Wire `FirebaseCostGuard` into `WatchTogetherRepository`.~~ **DONE (Session 2).**
2. ~~Create `WatchTogetherActivity`.~~ **DONE (Session 2)** — sync protocol, echo/feedback
   guards, and video-ID validation are all implemented as originally specified.
3. ~~Create `activity_watch_together.xml`.~~ **DONE (Session 2).**
4. ~~Implement the YouTube IFrame player (`WatchTogetherPlayerView` + `player.html`
   bridge).~~ **DONE (Session 2)** — `player.html` was actually already an asset carried
   over from Session 1's later work; the Java host/bridge (`@JavascriptInterface` +
   `evaluateJavascript`) is new this session.
5. ~~Add the control-bar button markup in `activity_call.xml`.~~ **DONE (Session 2)** —
   markup only; see item 8, still open.
6. **Run the real build.** `./gradlew :app:testDebugUnitTest` then
   `./gradlew :app:assembleDebug`. Neither has ever been run, across two sessions, on any
   file in `app/src/main/java/com/duoshield/app/call/watch/`. **Do this before writing any
   more Watch Together code** — fix whatever it finds before extending further.
7. ~~Extend `scripts/check-watch-together.js`~~ **DONE (Session 3)** — the two Session 2
   files are now in the structural `FILES` list, and a new integration-points section guards
   the CallActivity⇄Activity wiring, manifest, and layout.
8. ~~Wire `CallActivity`~~ **DONE (Session 3).** Binds `btnWatch`/`btnWatchLayout`; reveals
   `btnWatchLayout` in the existing `isVideo` block right after `btnChatLayout` (verified:
   that is exactly how `btnChatLayout` is revealed — a video-call gate, not a `CONNECTED`
   state gate; `btnChatLayout` has no separate CONNECTED reveal, so Watch Together mirrors it
   precisely); `openWatchTogether()` launches `WatchTogetherActivity` with the three extras.
   Note: like the chat button, the button becomes visible for video calls; there is no
   additional CONNECTED gate in the existing UI, and `openWatchTogether()` guards on
   `callId`/`myUid` being set (same guard as `openInCallChat()`).
9. ~~Add `WatchTogetherActivity` to `AndroidManifest.xml`~~ **DONE (Session 3)** — declared
   with `InCallChatActivity`'s exact attributes.
10. ~~Add the heartbeat writer~~ **DONE** — `WatchTogetherActivity.maybeWriteHeartbeat()`,
    single-writer (`lastActionBy` guard), `ACTION_HEARTBEAT`, once per `HEARTBEAT_INTERVAL_MS`
    while playing, on a lifecycle-bound `Handler`. Validated by static check 6.
11. ~~Invite/awareness (one-shot)~~ **DONE (Session 4)** — `CallActivity.refreshWatchTogetherAwareness()`
    does a single `fetchState` when the watch button is revealed and sets a "Rejoin Watch
    Together" cue if a session is active. No second listener, no writes (validated by static
    check 8). **Still open (optional):** a *live* real-time invite. If wanted, mirror a
    `watchActive` boolean onto the call doc and read it from the call-doc listener
    `CallActivity` already owns — do NOT add a second always-on Watch Together listener.
12. **Run the Firestore rules tests** where Java is available:
    `cd firestore-tests && npm ci && npx firebase emulators:exec --only firestore "npm test"`.
13. **Manual two-device verification** of: start, play, pause, seek, rate, rejoin after
    backgrounding, session end, call end cleanup, and that call audio/video and in-call
    chat still work throughout. Cannot happen until item 8 makes the feature reachable.

---

## 13. Next Session Instructions (start here)

The core UI wiring (Session 3), the heartbeat writer, and the one-shot awareness cue
(Session 4) are all DONE in code. The ONLY things left are (a) a real compiler/build and
(b) on-device verification — both blocked in every session so far by a missing toolchain.
There is no more code to add for the core feature. Start here, in this order:

1. **Read `.agents/memory/duoshield-rules.md`** before touching anything.
2. **Read this document in full**, especially §6 (clock safety), §9 (do-not-touch),
   §11 (known issues), and §14 (settled decisions — do not relitigate them).
3. **THE TOP PRIORITY: run a real Gradle build.** This has now been deferred for THREE
   sessions and is the single largest risk. The entire `call/watch/` package plus the
   Session 3 `CallActivity` edits have never been compiled.
   ```bash
   ./gradlew :app:testDebugUnitTest      # expect ~59 call/watch unit tests (Sessions 1)
   ./gradlew :app:assembleDebug          # first-ever compile of the watch/ package + wiring
   node scripts/check-watch-together.js  # fast structural smoke check (now 17 checks)
   ```
   If JDK/Gradle is still missing, **say so explicitly and do NOT report the build as
   verified** — fall back to `node scripts/check-watch-together.js` only.
4. **Fix any compile errors** revealed by the build, in `call/watch/` and in the
   `CallActivity` wiring. Likely first suspects if it fails: the `WatchTogetherRepository`
   constructor now needs a `Context` (does anything construct it without one?), and
   `WebView`/`JavascriptInterface`/`SystemClock` imports in the player/Activity.
5. **On-device / emulator click-through** (now possible for the first time): from a video
   call, tap the Watch Together control, paste a YouTube link, and verify start / play /
   pause / seek / rate / rejoin-after-background / stop / call-end cleanup, AND that call
   audio+video and in-call chat still work throughout. See §12 item 13.
6. **Then the optional polish batch:** §12 item 10 (heartbeat writer, at most one write per
   `HEARTBEAT_INTERVAL_MS` while playing) and item 11 (invite/awareness prompt — use a
   one-shot `fetchState`, do NOT add a second always-on listener in `CallActivity`).
7. **Run the Firestore rules tests** where Java is available (§12 item 12).
8. Do **not** modify `CallManager.java`. Do **not** add a WebRTC data channel. Do **not**
   restructure the in-call chat. Do **not** redo the repository audit or rework WebRTC.
9. After each batch: re-run whatever build/validation is available, then **update §7, §8,
   §10, §11, §12, and §15 of this document** to reflect what is actually true.

### Copy-paste prompt for the next session

> Continue the DuoShield Watch Together implementation. This is a NEW AI session. Read
> `docs/watch-together/IMPLEMENTATION_STATE.md` first, then verify the actual code.
> The feature is now wired end-to-end in code (Session 3): `CallActivity` binds and
> launches `WatchTogetherActivity`, and the manifest entry exists. It is NOT compiler-
> verified — no JDK/Gradle has been available in three sessions.
> **CURRENT GOAL:** (1) establish a JDK/Gradle toolchain and run
> `./gradlew :app:testDebugUnitTest :app:assembleDebug`; fix any compile errors in
> `app/src/main/java/com/duoshield/app/call/watch/` and the `CallActivity` wiring first.
> (2) If a device/emulator is available, click through start/play/pause/seek/rate/rejoin/
> stop and confirm call audio+video and in-call chat still work. Then, only if time
> remains, add the heartbeat writer (§12 item 10) and the invite/awareness prompt using a
> one-shot `fetchState` (§12 item 11 — do NOT add a second always-on listener).
> **RULES:** Do not modify `CallManager.java`, do not add a WebRTC data channel, do not
> rework the in-call chat or redo the WebRTC/repository audit, do not refactor unrelated
> code. Work in small verified batches, keep the build green, and update
> `IMPLEMENTATION_STATE.md` after each task. If Gradle is unavailable, say so and rely on
> `node scripts/check-watch-together.js` — do not claim the build is verified.

---

## 14. Important Decisions

Settled. Do not reconsider without a concrete new reason.

| Decision | Rationale | Rejected alternatives |
|---|---|---|
| **Sync over a single Firestore doc `calls/{callId}/watch/state`** | Mirrors the signaling the call already uses; no new service; zero changes to `CallManager`; rejoin is free because the doc *is* the state. | **Event subcollection** — more writes, unbounded growth, needs sweeping, needs replay on rejoin. **New backend/socket service** — explicitly out of scope. |
| **No WebRTC data channel** | None exists today (`onDataChannel` is an empty stub), so adding one means touching working PeerConnection setup/renegotiation — against the brief. It would still need a Firestore fallback for late joiners anyway. | Data channel as primary transport. |
| **YouTube via WebView + IFrame Player API** | No new Gradle dependency; officially supported by YouTube; exposes exactly the controls the protocol needs (`playVideo`, `pauseVideo`, `seekTo`, `setPlaybackRate`) plus JS→Java state callbacks. | `android-youtube-player` third-party library — adds a dependency and is itself a WebView wrapper. Raw `ExoPlayer` on extracted streams — violates YouTube ToS. |
| **YouTube media never crosses WebRTC** | Explicit product requirement; also avoids re-encoding cost and bandwidth. Each client streams from YouTube directly. | Screen-share / re-broadcast of the video through the existing call. |
| **Only the 11-char video ID is synced, never a URL** | Keeps the payload minimal and prevents a participant from pushing an arbitrary URL into the peer's WebView. Remote IDs are re-validated before loading. | Syncing the full pasted URL. |
| **`seq` counter for ordering, not timestamps** | Immune to device clock skew; cleanly suppresses Firestore's local echo of our own write. | `updatedAtMs` comparison, `serverTimestamp()` ordering (null on local echo). |
| **Followers project position from locally measured elapsed time** | Writer and reader clocks are not comparable. `SystemClock.elapsedRealtime()` is monotonic and survives user clock changes. | `now() - updatedAtMs`. |
| **1500 ms drift threshold** | Below this, seeking is more disruptive than the drift. | Continuous correction; a much tighter threshold. |
| **Shared control, `hostUid` informational only** | Two-person calls; either person pausing is natural. Rules already allow both participants to write, and a test asserts it. | Host-locked control requiring transfer. |
| **`active: false` on stop, not doc deletion** | Deletion races with the peer's listener and leaves their player with nothing to reconcile against. Real deletion happens with the call, via `deleteCallDoc`. | Deleting `watch/state` on stop. |
| **`AppCompatActivity` for the Watch Together screen** | Follows the documented `InCallChatActivity` precedent: the user is already authenticated, state is ephemeral, and an app-lock redirect mid-call would disrupt the call. | `BaseActivity` (would risk a mid-call lock redirect). |
| **Watch Together state never enters Room** | It is ephemeral like in-call chat; keeps Room at version 10 with no migration. | Persisting sessions locally. |

---

## 15. Last Known Good State

- **Branch:** `main` (Session 1's `duoshield-watch-together` branch was merged via PR #39;
  Session 2 committed directly on top of `main` — see commit `ab66400`).
- **Working tree at end of Session 1** (merged, for history):
  - Modified: `app/src/main/java/com/duoshield/app/call/CallSignalRepository.java`,
    `firestore.rules`, `firestore-tests/rules.test.js`
  - New: `app/src/main/java/com/duoshield/app/call/watch/` (3 files),
    `app/src/test/java/com/duoshield/app/call/watch/` (2 files),
    `scripts/check-watch-together.js`, `docs/watch-together/IMPLEMENTATION_STATE.md`,
    `app/src/main/assets/watch_together/player.html`
- **Working tree at end of Session 2** (commit `ab66400`, clean tree, no uncommitted
  changes):
  - Modified: `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherRepository.java`
    (constructor + `FirebaseCostGuard` wiring, `writeState` now returns `boolean`),
    `app/src/main/res/layout/activity_call.xml` (new `btnWatchLayout`/`btnWatch` block,
    additive only)
  - New: `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherPlayerView.java`,
    `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherActivity.java`,
    `app/src/main/res/layout/activity_watch_together.xml`,
    `app/src/main/res/drawable/ic_watch_together.xml`
- **Build state:** believed intact but **still not compiler-verified** — no JDK/Gradle in
  this container in either session (see §10). Session 2's edits to existing files are
  narrow and reviewed by hand:
  1. `WatchTogetherRepository` has **no callers yet** (nothing wires `CallActivity` to it),
     so its constructor/`writeState` signature change cannot have broken any existing
     compiled path.
  2. The `activity_call.xml` change is a pure XML addition (`btnWatchLayout`), verified to
     not collide with any existing `id`, `visibility="gone"` by default, unbound in Java.
  All other Session 2 changes are new files in the same new package as Session 1, which
  nothing pre-existing imports, so they cannot break the existing app even if they contained
  an error — same reasoning as Session 1's assessment, still valid.
- **Working tree at end of Session 3** (branch `v0/xojow11866-8151-31108357`):
  - Modified: `app/src/main/java/com/duoshield/app/call/CallActivity.java` (button field +
    bind + reveal + click listener + `openWatchTogether()` + import — all additive),
    `app/src/main/AndroidManifest.xml` (new `WatchTogetherActivity` `<activity>` entry),
    `scripts/check-watch-together.js` (Session 2 files added to structural checks + new
    integration-points section)
  - New: none (all Session 3 work is edits to existing files)
- **Runtime behavior:** Watch Together is now **reachable** from an active **video** call:
  the control-bar button appears, and tapping it opens `WatchTogetherActivity`. Voice-only
  calls are unaffected (the button stays `gone`, exactly like the chat button). Calls,
  in-call chat, and WebRTC are otherwise unchanged — Session 3 only added to `CallActivity`.
- **Feature reachability:** **wired end-to-end in code**, pending compiler + device proof.
- **Build state:** still **NOT compiler-verified** — no JDK/Gradle in this container in any
  of the three sessions. Static checker (17 checks) passes; `CallActivity` raw brace balance
  164/164; all touched XML parses clean; `Theme.DuoShield.FullScreen` confirmed present.
- **Next verification owed (top priority):** `./gradlew :app:testDebugUnitTest` and
  `./gradlew :app:assembleDebug` (see §13) — now owed across THREE sessions — followed by a
  two-device click-through.
