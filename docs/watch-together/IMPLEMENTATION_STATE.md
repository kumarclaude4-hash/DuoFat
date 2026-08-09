# Watch Together — Implementation State

> **Persistent cross-session handoff document.**
> Update this file whenever meaningful implementation progress is made.
> It must describe the ACTUAL state of the code, not the original plan.
> Do not delete prior useful context — amend it.

- **Last updated:** Session 2 (2026-08-09)
- **Branch:** `duoshield-watch-together` (merged to `main` via PR #39; Session 2 work is
  on top of `main`, PR pending)
- **Overall feature status:** **PARTIALLY COMPLETE** — sync foundation, `FirebaseCostGuard`
  wiring, the WebView/IFrame player host, `WatchTogetherActivity`, its layout, and the
  control-bar button markup all landed. **Still NOT reachable from the UI**: `CallActivity`
  does not yet bind the new button or launch the Activity, and the manifest entry is
  missing. See §7 and §13.

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
cost. **Not yet implemented** — see §12.

### Control model

**Shared control**, deliberately: either participant may play/pause/seek. `hostUid` records
who started the session but does not lock control. The Firestore rules permit both
participants to write, and there is a rules test asserting the non-host can pause.

---

## 7. Current Implementation Status

| Component | Status | Notes |
|---|---|---|
| `WatchTogetherState` (model + sync math) | **COMPLETE** | Fields, `toMap`/`fromMap`, `projectedPositionMs`, `shouldSeek`, `shouldApply`, `isPlayable`, `copy`. Defensive parsing. |
| `YouTubeUrlParser` | **COMPLETE** | All common URL forms, bare IDs, `t`/`start` offsets, strict rejection. |
| `WatchTogetherRepository` (Firestore transport) | **COMPLETE** | `stateRef`, `listenToState`, `writeState`, `fetchState`, `endSession`, `nextSeq`/`observeRemoteSeq`. **Session 2: now takes a `Context` and calls `FirebaseCostGuard`** — `listenToState` and `fetchState` gate/record one read; `writeState` gates/records one write and returns `boolean` (false = dropped, budget exhausted or null args) instead of `void`. |
| Firestore rules for `/watch/{docId}` | **COMPLETE** | Participant-gated, mirrors the ICE/chat rules. |
| Ephemeral sweep on call end | **COMPLETE** | `"watch"` added to `deleteCallDoc`'s subcollection list. |
| Unit tests — sync model | **COMPLETE** | `WatchTogetherStateTest`, 26 tests. |
| Unit tests — URL parser | **COMPLETE** | `YouTubeUrlParserTest`, 33 tests. |
| Firestore rules tests for `watch` | **COMPLETE** (written, not executed — see §10) | 7 tests added to `rules.test.js`. |
| Static validation script | **COMPLETE, but does not cover Session 2 files** | `scripts/check-watch-together.js` checks the Session 1 model/repo/rules files only. It has **not** been extended to check `WatchTogetherPlayerView`/`WatchTogetherActivity`. Manual brace-balance and `R.id`/`R.layout`/`R.drawable` cross-reference checks were run by hand instead (see §10) — extending the script is still owed. |
| **`WatchTogetherPlayerView`** (WebView host/bridge, `app/.../call/watch/WatchTogetherPlayerView.java`) | **COMPLETE, not compiler-verified** | Wraps a `WebView` that loads `file:///android_asset/watch_together/player.html` (Session 1 asset). `@JavascriptInterface` bridge (`onReady`, `onStateChange`, `onPlaybackRateChange`, `onCurrentTime`, `onPlayerError`) posts back to the main thread via a `Handler`; Java→JS calls (`loadVideo`, `play`, `pause`, `seekTo`, `setPlaybackRate`) go through `evaluateJavascript`. JS execution is scoped to this WebView only; `setAllowFileAccess`/universal access left at safe defaults. Exposes a `Listener` callback interface consumed by `WatchTogetherActivity`. |
| **`WatchTogetherActivity`** (`app/.../call/watch/WatchTogetherActivity.java`) | **COMPLETE, not compiler-verified** | Extends `AppCompatActivity` (documented rationale comment repeating the `InCallChatActivity` precedent, per rule §9.1). Reads `EXTRA_CALL_ID`/`EXTRA_MY_UID`/`EXTRA_PARTNER_NAME`. Owns exactly one `listenToState` `ListenerRegistration`, removed in `onDestroy()`. Implements the full sync protocol from §6: `shouldApply` → `observeRemoteSeq` → local `SystemClock.elapsedRealtime()` receipt stamp → `projectedPositionMs` → `shouldSeek` drift gating. Uses an `applyingRemote` flag to suppress write-back feedback loops when reconciling a remote snapshot. Validates both the locally-parsed and the remotely-received video ID through `YouTubeUrlParser`/`isValidVideoId` before ever loading it. **Heartbeat writer is NOT implemented** — see below. |
| **`activity_watch_together.xml`** | **COMPLETE** | URL input + Start row, `FrameLayout` player container hosting the `WatchTogetherPlayerView`, play/pause/seek-back/seek-forward controls, status text, minimize/close buttons. Reuses existing drawables (`ic_arrow_down`, `ic_close`, `ic_play_audio`/`ic_pause_audio`, `bg_incall_input`, `bg_call_btn_circle`) — no new drawables except the control-bar icon below. |
| **Watch Together button in the call control bar** | **PARTIAL** | `activity_call.xml` has a new `btnWatchLayout`/`btnWatch` block (mirrors `btnChatLayout`/`btnChat`), using new icon `ic_watch_together.xml`. **`android:visibility="gone"` by default and nothing in `CallActivity.java` ever sets it visible or attaches a click listener** — the button exists in the XML tree but is currently unreachable at runtime. |
| **`CallActivity` wiring** | **NOT STARTED** | `CallActivity.java` has not been modified. This is the single largest remaining gap — see §12 item 8 and §13. |
| **Session-invite / "partner started watching" prompt** | **NOT STARTED** | |
| **Heartbeat writer** | **NOT STARTED** | Constant exists (`WatchTogetherState.HEARTBEAT_INTERVAL_MS`); no writer loop in `WatchTogetherActivity` yet. |
| **FirebaseCostGuard integration** | **COMPLETE** | See `WatchTogetherRepository` row above. Mandatory-before-merge item from Session 1 is now resolved. |
| **Manifest entry for the new Activity** | **NOT STARTED** | `WatchTogetherActivity` is not declared in `AndroidManifest.xml`. Harmless today (nothing launches it yet) but required before the button can be wired. |

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

### Manual (device/emulator) verification

**NONE**, in either session. No device or emulator has been available in this container in
any session. The feature also still cannot be launched from the UI (§7), so there is
nothing to click through yet even if a device were available.

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
4. **The feature is still not reachable from the UI.** Narrower than Session 1's version of
   this issue: the Activity, layout, player, and control-bar button *markup* now all exist,
   but (a) `CallActivity.java` never binds `btnWatch`, sets its visibility, or launches
   `WatchTogetherActivity`, and (b) `WatchTogetherActivity` is not declared in
   `AndroidManifest.xml`. An end user still cannot start a session. This is the single
   largest remaining gap — see §12 item 3 and §13.
5. **`scripts/check-watch-together.js` does not cover the Session 2 files.** It still only
   checks `WatchTogetherState`, `YouTubeUrlParser`, `WatchTogetherRepository`, the two test
   files, and the rules file. `WatchTogetherPlayerView.java` and `WatchTogetherActivity.java`
   were checked manually instead (§10) — the manual check is weaker than the tokenizer-based
   one and should be replaced by extending the real script.
6. **No heartbeat writer.** `WatchTogetherState.HEARTBEAT_INTERVAL_MS` exists but nothing
   calls it on an interval. Drift correction currently only happens on state-changing
   actions (play/pause/seek/rate), not passively during uninterrupted playback. Not a
   regression — this was already true and already tracked in Session 1's remaining work.
7. **No invite/awareness prompt.** If participant A starts a session, participant B has no
   in-call indication that a Watch Together session is running until they themselves open
   the screen. Unchanged from Session 1.

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
7. **Extend `scripts/check-watch-together.js`** to cover `WatchTogetherPlayerView.java` and
   `WatchTogetherActivity.java` with the same tokenizer-based brace/paren balance and
   package-declaration checks it already applies to the Session 1 files. Currently these
   two files are excluded from the automated checker entirely (§10, §11 item 5).
8. **Wire `CallActivity`** — the biggest remaining gap:
   - Bind `btnWatch`/`btnWatchLayout` (`findViewById`, alongside the existing `btnChat`
     binding).
   - Add an `openWatchTogether()` method mirroring `openInCallChat()`, launching
     `WatchTogetherActivity` with `EXTRA_CALL_ID` / `EXTRA_MY_UID` / `EXTRA_PARTNER_NAME`.
   - Reveal `btnWatchLayout` (currently `visibility="gone"`) only when the call is
     `CONNECTED`, consistent with how `btnChatLayout` is revealed — check exactly how/where
     `CallActivity` currently shows/hides `btnChatLayout` and mirror that logic precisely.
   - Only show the button for video calls if that turns out to already be how the existing
     UI distinguishes call types (verify; do not assume).
9. **Add `WatchTogetherActivity` to `AndroidManifest.xml`** (`exported="false"`, matching
   `InCallChatActivity`'s attributes exactly).
10. **Add the heartbeat writer**: while playing, the acting participant writes
    `ACTION_HEARTBEAT` at most once per `HEARTBEAT_INTERVAL_MS`. Not started in either
    session.
11. **Invite/awareness**: when a participant starts a session, the peer should learn about
    it. Cheapest path with zero extra cost — `CallActivity` already listens to the call
    doc; instead of a second listener, do a one-shot `fetchState` when the Watch Together
    button is pressed and show a badge/prompt driven by the state doc the Activity already
    reads. **Avoid adding a second always-on listener in `CallActivity`** (project rule #3
    and cost). Not started in either session.
12. **Run the Firestore rules tests** where Java is available:
    `cd firestore-tests && npm ci && npx firebase emulators:exec --only firestore "npm test"`.
13. **Manual two-device verification** of: start, play, pause, seek, rate, rejoin after
    backgrounding, session end, call end cleanup, and that call audio/video and in-call
    chat still work throughout. Cannot happen until item 8 makes the feature reachable.

---

## 13. Next Session Instructions (start here)

Start here, in this exact order:

1. **Read `.agents/memory/duoshield-rules.md`** before touching anything.
2. **Read this document in full**, especially §6 (clock safety), §7 (current status —
   most of the player/Activity layer is now built), §9 (do-not-touch), §11 (known issues),
   and §14 (settled decisions — do not relitigate them).
3. **Verify the actual code before changing anything** — this doc is a summary, not a
   substitute for reading:
   - `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherActivity.java`
   - `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherPlayerView.java`
   - `app/src/main/res/layout/activity_watch_together.xml`
   - `app/src/main/res/layout/activity_call.xml` (the new `btnWatchLayout` block, and how
     `btnChatLayout` is bound/shown in `CallActivity.java` — that is the pattern to mirror)
4. **Establish a working toolchain and build before writing more code:**
   ```bash
   ./gradlew :app:testDebugUnitTest      # must pass: 59 tests in call/watch (Session 1)
   ./gradlew :app:assembleDebug          # must pass — first-ever compile of the watch/ package
   node scripts/check-watch-together.js  # fast structural smoke check (Session 1 files only)
   ```
   If the JDK/Gradle is missing again, say so explicitly and rely on
   `scripts/check-watch-together.js`, but **do not report the build as verified.** This has
   now been deferred for two sessions in a row — treat it as the top risk, not a formality.
5. **Fix any compile errors in `app/src/main/java/com/duoshield/app/call/watch/` first**,
   including the two new Session 2 files. That whole package has never been compiled.
6. **Then do Remaining Work items 7–9** (extend the checker, wire `CallActivity`, add the
   manifest entry) — that is the next coherent batch, and it is what makes the feature
   reachable from the UI for the first time.
7. After that, item 10 (heartbeat) and item 11 (invite/awareness) are the next batch.
8. Do **not** modify `CallManager.java`. Do **not** add a WebRTC data channel. Do **not**
   restructure the in-call chat. Do **not** redo the repository audit or rework WebRTC —
   both are explicitly out of scope again for this feature.
9. After each batch: re-run whatever build/validation is available, then **update §7, §8,
   §10, §11, §12, and §15 of this document** to reflect what is actually true — not what was
   planned.

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
- **Runtime behavior:** **unchanged from before Session 1.** Watch Together is still not
  reachable from any UI (see §11 item 4), so calls, in-call chat, and WebRTC behave exactly
  as they did before this feature existed.
- **Feature reachability:** none — the player/Activity layer is built but not wired into
  `CallActivity`, and the manifest entry is missing.
- **Next verification owed:** `./gradlew :app:testDebugUnitTest` and
  `./gradlew :app:assembleDebug` (see §13 step 4) — now owed across two sessions.
