# Watch Together — Implementation State

> **Persistent cross-session handoff document.**
> Update this file whenever meaningful implementation progress is made.
> It must describe the ACTUAL state of the code, not the original plan.
> Do not delete prior useful context — amend it.

- **Last updated:** Session 1 (2026-08-09)
- **Branch:** `duoshield-watch-together`
- **Overall feature status:** **PARTIALLY COMPLETE** — sync foundation (model, transport, rules, tests) landed; player UI and CallActivity wiring NOT STARTED.

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
| `app/src/main/java/com/duoshield/app/call/watch/` | **NEW this session.** All Watch Together production code. |
| `app/src/main/res/layout/activity_call.xml` | Call screen layout, including the bottom control bar with `btnChatLayout` / `btnChat`. Watch Together button goes here. **Not yet modified.** |
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
| `WatchTogetherRepository` (Firestore transport) | **COMPLETE** | `stateRef`, `listenToState`, `writeState`, `fetchState`, `endSession`, `nextSeq`/`observeRemoteSeq`. **Note: does not yet call FirebaseCostGuard — see §11.** |
| Firestore rules for `/watch/{docId}` | **COMPLETE** | Participant-gated, mirrors the ICE/chat rules. |
| Ephemeral sweep on call end | **COMPLETE** | `"watch"` added to `deleteCallDoc`'s subcollection list. |
| Unit tests — sync model | **COMPLETE** | `WatchTogetherStateTest`, 26 tests. |
| Unit tests — URL parser | **COMPLETE** | `YouTubeUrlParserTest`, 33 tests. |
| Firestore rules tests for `watch` | **COMPLETE** (written, not executed — see §10) | 7 tests added to `rules.test.js`. |
| Static validation script | **COMPLETE** | `scripts/check-watch-together.js`. |
| **YouTube player UI (WebView + IFrame API)** | **NOT STARTED** | The chosen mechanism, decided and recorded in §14. No code written yet. |
| **`WatchTogetherActivity`** | **NOT STARTED** | |
| **`activity_watch_together.xml`** | **NOT STARTED** | |
| **Watch Together button in the call control bar** | **NOT STARTED** | `activity_call.xml` not modified. |
| **`CallActivity` wiring** | **NOT STARTED** | `CallActivity.java` not modified at all this session. |
| **Session-invite / "partner started watching" prompt** | **NOT STARTED** | |
| **Heartbeat writer** | **NOT STARTED** | Constant exists; no writer loop. |
| **FirebaseCostGuard integration** | **NOT STARTED** | Mandatory before merge. See §11 / §12. |
| **Manifest entry for the new Activity** | **NOT STARTED** | |

---

## 8. Files Changed

### New files (all additive)

| File | Contents |
|---|---|
| `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherState.java` | State model, Firestore field-name constants, action labels, tuning constants (`DRIFT_THRESHOLD_MS`, `HEARTBEAT_INTERVAL_MS`), defensive `toMap`/`fromMap`, and the pure sync math (`projectedPositionMs`, `shouldSeek`, `shouldApply`). No Android/Firebase imports, so it is JVM-unit-testable. |
| `app/src/main/java/com/duoshield/app/call/watch/YouTubeUrlParser.java` | Static YouTube URL → video-ID extraction, validation, and `t`/`start` offset parsing. No Android imports. |
| `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherRepository.java` | Firestore wrapper for `calls/{callId}/watch/state`, shaped after `CallSignalRepository`. Owns the local `seq` counter. All failures logged and non-fatal so Watch Together can never tear down a call. |
| `app/src/test/java/com/duoshield/app/call/watch/WatchTogetherStateTest.java` | 26 JVM tests: seq ordering/echo suppression, projection (paused/playing/rate/negative-elapsed), drift threshold boundaries, serialization round-trip, Firestore number widening, wrong-type tolerance, `isPlayable`, `copy` independence, rejoin scenario. |
| `app/src/test/java/com/duoshield/app/call/watch/YouTubeUrlParserTest.java` | 33 JVM tests: watch/short/embed/nocookie/shorts/live/legacy-v/mobile URLs, bare IDs, whitespace, plus rejection of non-YouTube URLs, `javascript:` URLs, channel URLs, wrong-length and bad-alphabet IDs; timestamp parsing. |
| `scripts/check-watch-together.js` | Toolchain-free static validator: balanced braces/parens (single-pass Java tokenizer), package declarations, test→production symbol existence, rules-file brace balance, `/watch/` nested inside `/calls/`, and the `deleteCallDoc` sweep. |
| `docs/watch-together/IMPLEMENTATION_STATE.md` | This document. |

### Modified files (minimal, additive only)

| File | Change |
|---|---|
| `app/src/main/java/com/duoshield/app/call/CallSignalRepository.java` | Added `"watch"` to the subcollection array in `deleteCallDoc` so the state doc is swept with the call; updated the Javadoc from "three" to "four". **No logic change.** |
| `firestore.rules` | Added a `match /watch/{docId}` block inside `match /calls/{callId}`, using the identical participant gate as `callerCandidates` / `calleeCandidates` / `chat`. **No existing rule modified.** |
| `firestore-tests/rules.test.js` | Added a `describe('/calls/{callId}/watch/state')` block with 7 tests. **No existing test modified.** |

`git diff --stat` for modified files: **3 files, 106 insertions, 3 deletions** (the 3
deletions are the Javadoc line and the array literal that were rewritten).

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
   mid-call). The new `WatchTogetherActivity` should follow the `InCallChatActivity`
   precedent **and document the same rationale in a class comment**.
2. **`FirebaseCostGuard` before every Firestore op** — `guard.canRead/canWrite/canDelete(n)`
   then `guard.recordReads/recordWrites/recordDeletes(n)`. **Currently NOT wired into
   `WatchTogetherRepository`. This is required before merge.**
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

The dev container used for Session 1 has **no JDK and no Gradle**
(`which java javac gradle` → nothing). It also has **no `firebase-tools`**, and the
Firestore emulator itself requires Java. Therefore:

- `./gradlew :app:testDebugUnitTest` — **NOT RUN** (no JDK/Gradle available).
- `./gradlew :app:assembleDebug` — **NOT RUN** (no JDK/Gradle available).
- `./gradlew :app:lintDebug` — **NOT RUN** (no JDK/Gradle available).
- `npm test` in `firestore-tests/` — **NOT RUN** (emulator needs Java).

### What WAS actually executed and passed

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

One real defect was found and fixed during validation: the first version of the static
checker used chained regexes to strip literals, and an apostrophe inside a Javadoc comment
("a user might paste") made the char-literal pattern swallow real parentheses, producing a
phantom paren imbalance of 24 in `YouTubeUrlParserTest.java`. The checker was rewritten
with a single-pass tokenizer; the Java file itself was correct.

### Manual verification

**NONE.** No device or emulator was available. No Watch Together UI exists yet, so there
is nothing to verify by hand.

### Honest confidence statement

The new Java code is **not compiler-verified**. It is plain Java 8-compatible source using
only `java.util`, `java.util.regex`, `android.util.Log`, and Firestore types that are
already used identically in `CallSignalRepository` (`DocumentReference`, `DocumentSnapshot`,
`EventListener`, `FirebaseFirestore`, `ListenerRegistration`). The risk of a compile error
is low but **has not been eliminated**. The next session must run Gradle first.

---

## 11. Known Issues

Actual, observed issues only:

1. **`WatchTogetherRepository` does not call `FirebaseCostGuard`.** This violates project
   rule #2, which is non-negotiable. Must be fixed before merge. It was deferred only
   because the guard needs an Android `Context`, and the decision about whether the
   repository takes a `Context` in its constructor or the caller performs the guard check
   is better made together with the Activity that owns it.
2. **Nothing is compiler-verified.** See §10. No JDK/Gradle was available in Session 1.
3. **The Firestore rules tests were written but never executed** — the emulator requires
   Java. Their logic is modeled directly on the passing `callerCandidates` tests and uses
   the same `asUser` / `seed` / `testEnv` helpers (signatures verified by reading them), but
   they are unproven.
4. **The feature is not reachable from the UI.** There is no button, no Activity, and no
   manifest entry, so an end user cannot start a Watch Together session yet. The foundation
   is inert library code.

Not issues, but deliberate and worth not "fixing" blindly:

- `updatedAtMs` is intentionally not used for elapsed-time math (§6 clock safety).
- Control is intentionally shared rather than host-locked (§6).
- `endSession` intentionally writes `active: false` rather than deleting (§5).

---

## 12. Remaining Work

Concrete, ordered tasks:

1. **Run the real build first.** `./gradlew :app:testDebugUnitTest` then
   `./gradlew :app:assembleDebug`. Fix any compile errors in the new `watch/` package
   before writing new code. Do not skip this.
2. **Wire `FirebaseCostGuard` into `WatchTogetherRepository`** (project rule #2).
   Recommended: pass a `Context` into the constructor, then guard `writeState`
   (`canWrite(1)` / `recordWrites(1)`), `fetchState` (`canRead(1)` / `recordReads(1)`),
   and the listener attach (`canRead(1)` / `recordReads(1)`). If `canWrite` returns false,
   drop the sync write and log — never throw, and never let it end the call.
3. **Create `WatchTogetherActivity`** (`app/.../call/watch/WatchTogetherActivity.java`):
   - Extras: `EXTRA_CALL_ID`, `EXTRA_MY_UID`, `EXTRA_PARTNER_NAME` (mirror
     `InCallChatActivity`).
   - Extend `AppCompatActivity`, following the documented `InCallChatActivity` precedent,
     and **repeat the rationale comment**.
   - Attach exactly one `listenToState` registration; remove it in `onDestroy()`.
   - On every snapshot: `shouldApply` → `observeRemoteSeq` → record
     `SystemClock.elapsedRealtime()` as the local receipt time → reconcile the player.
   - Guard against feedback loops: when applying a remote state, set a
     `applyingRemote` flag so the player's own state-change callbacks do not immediately
     write back.
4. **Create `activity_watch_together.xml`**: a `WebView` for the player, a URL input +
   "Start" affordance, play/pause/seek controls, an "ended" state, and a visible
   indication of who is watching. Match the existing call/chat visual language.
5. **Implement the YouTube IFrame player** — decided mechanism, see §14. Load a small
   local HTML page from `assets/` into the `WebView` that hosts the IFrame Player API, and
   bridge with `@JavascriptInterface` + `evaluateJavascript`:
   - Java → JS: `loadVideoById`, `playVideo`, `pauseVideo`, `seekTo`, `setPlaybackRate`.
   - JS → Java: `onReady`, `onStateChange`, `onPlaybackRateChange`, current time.
   - **Security:** enable JS only for this WebView; never build the page from a raw pasted
     URL. Pass only a `YouTubeUrlParser.isValidVideoId`-validated 11-char ID, and validate
     the **remote** `videoId` the same way before loading it.
   - Set an `origin`/`widget_referrer` consistent with the embed and keep
     `setAllowFileAccess(false)` / no universal file access.
6. **Add the heartbeat writer**: while playing, the acting participant writes
   `ACTION_HEARTBEAT` at most once per `HEARTBEAT_INTERVAL_MS`.
7. **Add the control-bar button** in `activity_call.xml` next to `btnChatLayout`, plus an
   icon drawable and a string resource. Follow the existing button structure exactly.
8. **Wire `CallActivity`**: find the button, add an `openWatchTogether()` method mirroring
   `openInCallChat()`, and pass `callId` / `myUid` / `partnerName`. Only reveal the button
   when the call is connected, consistent with how the chat button is revealed.
9. **Add `WatchTogetherActivity` to `AndroidManifest.xml`** (`exported="false"`,
   matching `InCallChatActivity`'s attributes).
10. **Invite/awareness**: when a participant starts a session, the peer should learn about
    it. Cheapest path with zero extra cost — `CallActivity` already listens to the call
    doc; instead of a second listener, do a one-shot `fetchState` when the Watch Together
    button is pressed and show a badge/prompt driven by the state doc the Activity already
    reads. **Avoid adding a second always-on listener in `CallActivity`** (project rule #3
    and cost).
11. **Run the Firestore rules tests** where Java is available:
    `cd firestore-tests && npm ci && npx firebase emulators:exec --only firestore "npm test"`.
12. **Manual two-device verification** of: start, play, pause, seek, rate, rejoin after
    backgrounding, session end, call end cleanup, and that call audio/video and in-call
    chat still work throughout.

---

## 13. Next Session Instructions

Start here, in this exact order:

1. **Read `.agents/memory/duoshield-rules.md`** before touching anything.
2. **Read this document in full**, especially §6 (clock safety), §9 (do-not-touch), and
   §14 (settled decisions — do not relitigate them).
3. **Establish a working toolchain and build before writing code:**
   ```bash
   ./gradlew :app:testDebugUnitTest      # must pass: 59 new tests in call/watch
   ./gradlew :app:assembleDebug          # must pass
   node scripts/check-watch-together.js  # fast structural smoke check
   ```
   If the JDK/Gradle is missing again, say so explicitly and rely on
   `scripts/check-watch-together.js`, but **do not report the build as verified.**
4. **Fix any compile errors in `app/src/main/java/com/duoshield/app/call/watch/` first.**
   That package is new and has never been compiled.
5. **Then do Remaining Work item 2 (FirebaseCostGuard), and only then items 3–5**
   (Activity + layout + IFrame player). That is the next coherent batch.
6. Do **not** modify `CallManager.java`. Do **not** add a WebRTC data channel. Do **not**
   restructure the in-call chat.
7. After each batch: re-run the build, then **update §7, §8, §10, §11, §12, and §15 of this
   document** to reflect what is actually true.

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

- **Branch:** `duoshield-watch-together`
- **Working tree at end of Session 1:**
  - Modified: `app/src/main/java/com/duoshield/app/call/CallSignalRepository.java`,
    `firestore.rules`, `firestore-tests/rules.test.js`
  - New: `app/src/main/java/com/duoshield/app/call/watch/` (3 files),
    `app/src/test/java/com/duoshield/app/call/watch/` (2 files),
    `scripts/check-watch-together.js`, `docs/watch-together/IMPLEMENTATION_STATE.md`
- **Build state:** believed intact, **not compiler-verified** (no JDK/Gradle in the
  container — see §10). The three edits to existing files are the only way this session
  could have affected the existing build, and they are:
  1. one string added to a `String[]` literal plus a Javadoc wording change,
  2. a new `match` block appended inside an existing rules block (brace balance verified),
  3. a new `describe` block appended to a Jest file (`node --check` verified).
  All other changes are new files in a new package that nothing existing imports yet, so
  they cannot break the existing app even if they contained an error.
- **Runtime behavior:** **unchanged from before this session.** Watch Together is not
  reachable from any UI, so calls, in-call chat, and WebRTC behave exactly as they did.
- **Feature reachability:** none — foundation only.
- **Next verification owed:** `./gradlew :app:testDebugUnitTest` and
  `./gradlew :app:assembleDebug` (see §13 step 3).
