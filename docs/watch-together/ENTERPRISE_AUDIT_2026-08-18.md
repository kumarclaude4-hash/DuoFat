# Watch Together — Enterprise Audit

**Date:** 2026-08-18
**Scope:** The YouTube Watch Together add-on reached from inside a live video call —
`WatchTogetherActivity`, `WatchTogetherPlayerView`, `assets/watch_together/player.html`,
`YouTubeSearchState`, `YouTubeSearchParser`, `YouTubeSearchClient`,
`res/layout/activity_watch_together.xml`, and the audio interaction with `CallActivity`.
**Out of scope:** `InCallChatActivity` (see §5), `CallActivity`'s audio-focus listener
(see §4 — recorded as a standing hazard, deliberately not modified), and any `FLAG_SECURE`
change (see §5 — deferred by request until the screenshot testing pass completes).

**Status:** every confirmed defect below is fixed except the deferred `FLAG_SECURE` item.

---

## 1. Findings

| # | Finding | Severity | File / Line | Root Cause | Impact | Status |
|---|---------|----------|-------------|------------|--------|--------|
| W-01 | Unsafe WebView teardown | **CRITICAL** | `WatchTogetherActivity.onDestroy()` ~L246 (pre-fix); `WatchTogetherPlayerView` | `super.onDestroy()` ran *first*, then `player.destroy()` — destroying a WebView still attached to its parent, with the Activity already releasing its window. No code path ever detached the view. | Documented undefined behaviour and a well-known crash/leak path: the render process holds a reference to a view whose window is going away. Every watch session leaked a WebView at best; at worst it crashed on exit from the feature. | **Fixed** |
| W-02 | Video audio competes with the live call | **HIGH** | Whole feature — no volume/mute handling existed anywhere | The embed plays at YouTube's default volume on top of an active WebRTC voice call. No attenuation, no mute control, no local override. | Both participants' voices are buried under the video at equal loudness. The feature's entire premise — watching *together*, i.e. while talking — does not work. There was also no escape hatch short of pausing playback for both people. | **Fixed** |
| W-03 | Dead `isRetryable()`; Retry offered on non-retryable errors | **MEDIUM** | `YouTubeSearchParser.isRetryable(int)` L170 (zero production callers); `WatchTogetherActivity.renderSearch()` | `renderSearch()` showed Retry for *every* `Phase.ERROR`. `YouTubeSearchState` never retained the status needed to decide, so the fully unit-tested `isRetryable` had nothing to be called from. | `STATUS_NOT_CONFIGURED` (search absent from this APK), `400` (YouTube rejected the terms) and `413` (query too long) each rendered a button whose only possible outcome was the identical error — directly beneath copy that had just told the user to do something else ("Paste a YouTube link instead", "Try fewer words"). Each tap also burned a wasted round trip. | **Fixed** |
| W-04 | `onBackPressed()` deprecated; breaks predictive back | **MEDIUM** | `WatchTogetherActivity` ~L267 (pre-fix) | Deprecated as of API 33 and, more importantly, simply not invoked once predictive back is in effect. | The "dismiss the search panel first" behaviour silently stops working on modern devices. Back from the search panel closes the whole screen instead — which, for the participant who started the session, also drops them out of Watch Together when they only meant to stop browsing. | **Fixed** |
| W-05 | Search cache never cleared | **MEDIUM** (privacy) | `YouTubeSearchClient.clearCache()` L182 — zero callers | The method's own javadoc says "call on sign-out, app wipe, or when a Watch Together session ends". None of those three call sites existed. | A process-wide static cache retains what the user typed plus the titles and channels returned — a record of what they searched for while on a call with a specific person. It survived session end *and* sign-out/wipe, so on a shared device the next session (or the next account) could be served the previous person's results. Contradicts the fresh-install posture every wipe path is built to present. | **Fixed** |
| W-06 | `ENDED` leaves shared session state stale | **MEDIUM** | `WatchTogetherActivity.onPlayerStateChange()` ~L890 (pre-fix) | `YT_STATE_ENDED` updated the play/pause icon and nothing else. | The document stayed `playing = true` forever and the heartbeat kept republishing a position pinned at the end of the video every 10 s — a permanent write trickle describing playback that had already finished, and a state telling any device that joins or returns that a finished video is still playing. | **Fixed** |
| W-07 | No-op listener stubs | **LOW** | `onPlaybackRateChange()`, `onPlayerReady()` | Both empty. | `onPlayerReady` was the natural, and only, hook that fires for *every* way a player comes into existence (first construction and `reset()`'s page reload) — it is now where initial attenuation is applied (W-02). `onPlaybackRateChange` is genuinely informational: rate syncs through the state document, so it is left as-is with its comment intact. | **Fixed / N-A** |
| W-08 | `FLAG_SECURE` never applied to this screen | **HIGH** | `WatchTogetherActivity` L59; `BaseActivity.applyScreenshotSecurity()` L76 | The Activity extends `AppCompatActivity` directly, not `BaseActivity`, so it never routes through the single point that enforces the screenshot preference. | Screenshots, screen recording and the recents thumbnail capture the video surface *and* the search field's terms regardless of the user's "Allow screenshots" setting. See §5 — **deliberately not fixed in this pass.** | **DEFERRED** |
| W-09 | `hostUid` written but never read for any decision | **INFO** | `WatchTogetherActivity.performLocalWrite()` L928-932; `WatchTogetherState` L77 | `hostUid` is populated, persisted, round-tripped and compared in `equals`, but no control-flow anywhere reads it. Every authority decision uses `lastActionBy` instead. | None today — it is a correctly maintained field with no consumer. Recorded rather than changed: removing a persisted field is a schema change, and it is plausibly intended for a future "only the host may end the session" rule. | **Observed, no change** |

### Corrected findings

Two items raised going in did not survive verification and are recorded so they are not
re-litigated:

- **No U+FFFD / replacement-character handling defect exists.** `YouTubeSearchState.normalizeQuery`
  treats C0/C1 control characters as whitespace and leaves every other code point intact;
  U+FFFD is not special-cased anywhere and does not need to be. Parsing goes through the real
  `org.json` on the test classpath (asserted by
  `YouTubeSearchParserTest.orgJsonIsTheRealImplementationNotTheAndroidStub()`), so decoding is
  not silently lossy either.
- **`ENDED` was partially handled, not unhandled.** It did reach `onPlayerStateChange` and did
  flip the play/pause icon. The defect (W-06) is narrower than "ignored": the *local UI*
  responded and the *shared document* did not.

---

## 2. Fixes as implemented

### W-01 — teardown order and an explicit WebView contract

`WatchTogetherPlayerView` now declares `destroy()`, `onPause()` and `onResume()` explicitly:

1. `stopLoading()`, then drop the JS bridge, then `loadUrl("about:blank")` — the blank load is
   what actually tears down the YouTube iframe and therefore stops its audio. A bare
   `destroy()` can leave media audible for a moment.
2. Detach from the parent `ViewGroup`, satisfying the "not attached" precondition.
3. Only then `super.destroy()`.

It is idempotent (`destroy()` on an already-destroyed WebView throws, and both the Activity and
the framework can plausibly reach it), and the `destroyed` flag also short-circuits `eval()` —
including a re-check *inside* the `Handler.post`, since teardown can land between the two.

`onPause()`/`onResume()` are overridden rather than inherited on purpose. The Activity depends on
that contract, and `hasFreshPosition()` plus the heartbeat's stall-handover logic depend on the
fact that a paused page stops ticking. A silently inherited method can change under a dependency
bump with nothing here noticing; an override cannot. Both are guarded against double-application,
which the framework can deliver around a configuration change.

`WatchTogetherActivity.onDestroy()` now releases the listener, both handlers, the player and the
search cache **before** `super.onDestroy()`.

### W-02 — attenuate the video, never request audio focus

`player.html`'s `window.WT` gained `setVolume(v)`, `mute()` and `unMute()`, all routed through
the existing `safe()` wrapper. Pre-ready calls are queued in `pendingVolume` / `pendingMuted` and
replayed on `onReady`, exactly as `cue` already used `pending` — `null` meaning "nothing
requested", distinct from `0` / `false`. `WatchTogetherPlayerView` exposes matching
`setVolume(int)` (clamped to `[0,100]`) and `setMuted(boolean)`.

- Default attenuation is `CALL_DUCK_VOLUME_PERCENT = 35`, applied from `onPlayerReady()` so
  voices sit on top of the video from the first frame rather than after a full-volume blast.
- `btnWatchMute` was added to the `watchControls` row matching sibling button styling, and is
  wired in `bindViews()`.
- Mute is **device-local** and is never written to the session document: one participant
  silencing the video for themselves must not silence it for the other. It is therefore the one
  control in that row that deliberately does *not* go through `performLocalWrite()`.
- It survives recreation via `onSaveInstanceState` (`STATE_VIDEO_MUTED`), so a rotation does not
  blast the video back to full volume mid-conversation.
- `applyVideoAudioSettings()` is re-invoked after every cue/reload, because a newly loaded video
  starts at the embed's default volume — without it, every video change would restart loud.

### W-03 — carry the status into the state machine

`YouTubeSearchState` gained a `retryable` field and an `onError(long, String, int)` overload that
sets it from `YouTubeSearchParser.isRetryable(status)`. The pre-existing two-arg signature
delegates with the new `RETRYABLE_UNKNOWN` sentinel (`Integer.MIN_VALUE`, distinct from every
real status and from both of the parser's negative sentinels), so existing call sites and tests
keep the historical permissive behaviour. `WatchTogetherActivity` passes the real status from the
search callback; `renderSearch()` gates `btnSearchRetry` on `phase == ERROR && isRetryable()`.
`beginSearch`, `onResults`, `markTooShort` and `reset` all restore the default, so one 400 cannot
suppress Retry for every later network failure in the session.

### W-04 — `OnBackPressedCallback`

Registered on `getOnBackPressedDispatcher()` in `onCreate()`; the former `onBackPressed()` body
moved into `dismissSearchPanel()` unchanged. `setEnabled(...)` is driven from
`searchState.isPanelVisible()` inside `renderSearch()`. That gating matters specifically for
predictive back: a permanently enabled callback tells the framework this screen *always*
intercepts Back, which suppresses the OS back-gesture preview animation even when there is
nothing to intercept.

`androidx.activity` was verified present — `appcompat:1.6.1` resolves `androidx.activity:activity:1.6.0`
transitively, and both `OnBackPressedCallback` and `getOnBackPressedDispatcher()` predate it, so
the dispatcher API is available and no behaviour needed downgrading. It is now **also declared
explicitly** in `app/build.gradle` at that same version (so the build graph is unchanged today),
because the Activity depends on it directly and leaning on a transitive edge means a future
appcompat reorganisation breaks this for reasons nothing in the file explains.

### W-05 — two real call sites for `clearCache()`

- `WatchTogetherActivity.onDestroy()` — the documented "when a Watch Together session ends" site.
- `WipeHelper.eraseLocalData()` step 4b — the central sign-out/wipe hook, which all of
  `VOLUNTARY`, `UNPAIR` and `DURESS` share. This is the same class of problem the adjacent
  `SeedPhraseHelper.clearDerivationCache()` call already solves there: a static,
  process-lifetime cache that no on-disk clear reaches.

Note for follow-up: `LinkPreviewFetcher.clearCache()` (`util/LinkPreviewFetcher.java` L93) has the
same shape and still has **zero callers**. Out of scope here, flagged as a likely sibling defect.

### W-06 — settle the document once, on the writer only

`onVideoEnded(positionMs)` writes `playing = false` pinned at the end position, via the existing
`performLocalWrite(ACTION_PAUSE, …)` path. Guards, in order:

1. `appliedState` must exist and be playable.
2. `!appliedState.playing` → return. A second `ENDED`, or one after a pause, must not re-write.
3. `!myUid.equals(appliedState.lastActionBy)` → return.

Guard 3 is the echo-safety guard and the reason this is correct. `ENDED` fires on **both** devices
— it is a property of the video, not of who pressed anything. Restricting the write to the device
that is currently `lastActionBy` is the same single-writer rule the heartbeat already uses, so the
follower's player reaching the end produces no Firestore op at all, preserving the
"remote/echo-driven changes never generate a write" invariant that `onPlayerStateChange` is built
around. The heartbeat then stops as a *consequence*: `maybeWriteHeartbeat()` already returns early
when `!appliedState.playing`, so nothing needed cancelling separately and the heartbeat stays
available for the next real action.

---

## 3. DEFERRED — `FLAG_SECURE`

**Not implemented in this pass, by request: enabling it would block the screenshot-based testing
currently in progress.**

### What is wrong

`FLAG_SECURE` is applied app-wide from `BaseActivity.onCreate()` via
`applyScreenshotSecurity()` (`BaseActivity.java` L76-91), reading the user-facing
`app_screenshot_enabled` preference. Two problems compound:

1. **These screens never reach that code.** `WatchTogetherActivity` (L59) and
   `InCallChatActivity` (L42) both extend `AppCompatActivity` **directly**, not `BaseActivity`.
   They are therefore unprotected *irrespective of the preference*, and will remain unprotected
   even after item 2 below is reverted. This is true of the whole call subsystem —
   `CallActivity` (L78) and `IncomingCallActivity` (L44) bypass `BaseActivity` as well.
2. **The default currently permits capture.** `BaseActivity.SCREENSHOTS_ALLOWED_BY_DEFAULT`
   (L74) is `true` — a temporary testing default, already documented in-file as
   **"REVERT BEFORE RELEASE"**. While it stands, even `BaseActivity`-derived screens allow
   screenshots, screen recording and recents thumbnails on a fresh install.

### Rule #1 violation

The project's secure-by-default posture (S08-H2, `audit/SESSION-08-CLIENT-PLATFORM.md`) requires
`FLAG_SECURE` on any surface rendering conversation content. Watch Together renders shared media
inside a call; `InCallChatActivity` renders message text. Both are in scope for that rule and
neither honours it.

### Exposure specific to this feature

Beyond the video surface, `etWatchUrl` doubles as the **search field**. Its contents, the returned
result titles and channel names, and the thumbnails are all on screen — so a screen recording or a
recents-list thumbnail captures *what the user searched for while on a call with a specific
person*, not merely that they watched something. This is the same data W-05 removes from memory,
so leaving the capture path open partially undoes that fix.

### Re-enable checklist (after the testing pass)

- [ ] Set `BaseActivity.SCREENSHOTS_ALLOWED_BY_DEFAULT = false`.
- [ ] Confirm `SecurityPrivacySettingsActivity` reads that same constant for its switch default,
      so the toggle cannot render out of step with the flag actually enforced (it currently does —
      keep it that way).
- [ ] Route `WatchTogetherActivity` through the enforcement point: either extend `BaseActivity`,
      or call `BaseActivity.applyScreenshotSecurity(this)` from `onCreate()`. Extending
      `BaseActivity` also brings the shake detector and sign-out redirect — **verify that is
      wanted on a screen hosted inside a live call before choosing it**, since an unexpected
      redirect mid-call would be worse than the finding.
- [ ] Do the same for `InCallChatActivity`.
- [ ] Decide explicitly about `CallActivity` / `IncomingCallActivity` (out of scope here, same
      bypass).
- [ ] Verify on-device: attempt a screenshot and a screen recording on each screen, and check the
      recents-list thumbnail is blanked.
- [ ] Re-run `node scripts/check-watch-together.js`.

---

## 4. Standing hazard — never request audio focus from this feature

**Do not "improve" W-02 by switching to `AudioManager` audio focus.** It will silence the user's
own microphone.

`CallActivity` requests `AUDIOFOCUS_GAIN` (`CallActivity.java` L692) and its focus-change listener
(L698-708) reacts to loss by muting the local mic:

```java
if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        || focusChange == AudioManager.AUDIOFOCUS_LOSS) {
    if (callManager != null) callManager.setMuted(true); // localAudioTrack.setEnabled(false)
} else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
    if (callManager != null) callManager.setMuted(isMuted); // restores the user's own choice
}
```

The mic is restored **only** on `AUDIOFOCUS_GAIN` — nothing else in the listener ever re-enables
the audio track. `AUDIOFOCUS_LOSS_TRANSIENT` — including
`..._MAY_DUCK`-style transient requests — is exactly what a "duck the other app" request produces.
So any focus request from `WatchTogetherActivity` kills the local mic for the entire watch
session: **both participants go silent precisely when the video starts**, which is the worst
possible moment and presents as a call bug, not a Watch Together bug.

The chosen fix routes around this entirely by lowering the *player's* volume through the JS bridge.
No focus transaction is performed and no call audio is touched. `CallActivity`'s listener is
deliberately left unmodified — changing live-call audio behaviour to accommodate an optional add-on
is the wrong direction, and the fix does not need it.

This constraint is also documented at both code sites (`WatchTogetherPlayerView.setVolume()` and
`player.html`'s `setVolume`) so it is discoverable from the place someone would edit.

---

## 5. Explicitly out of scope

- **Any `FLAG_SECURE` change** — deferred until the screenshot testing pass completes (§3).
- **`InCallChatActivity`** — untouched. Its only finding here is the shared `FLAG_SECURE` bypass,
  recorded in §3 with a checklist entry, because fixing it in isolation would half-solve a
  subsystem-wide issue while the testing default still permits capture anyway.
- **`CallActivity`'s focus listener** — recorded as a standing hazard in §4 only. It touches live
  call audio and the chosen fix routes around it.

---

## 6. Verification

| Check | Result |
|---|---|
| `node scripts/check-watch-together.js` | **Pass** — all 30 static checks green (single-listener rule, single-writer heartbeat, cost-guard gating, no YouTube API key under `app/`, search reachable only via the add-on). |
| Existing parser / state / URL / WatchTogetherState unit tests | Unchanged and expected to pass. The `onError(long, String)` signature was retained precisely so no existing test needed editing. |
| New unit coverage | `YouTubeSearchStateTest` extended for the retryable state and the status-carrying overload: `terminalFailuresAreNotRetryable`, `transientFailuresAreRetryable`, `legacyOnErrorKeepsOfferingRetry`, `explicitUnknownStatusKeepsOfferingRetry`, `nonRetryableVerdictDoesNotLeakForward`, `freshStateDefaultsToRetryable`, `staleErrorCannotChangeRetryability`. |
| Gradle compile / unit-test run | **Not available in this environment** — no JDK and no Android SDK are installed in the audit sandbox (`java: command not found`, `ANDROID_HOME` unset). Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` for authoritative validation, as `check-watch-together.js` itself advises on completion. |

### Manual test notes for the on-device pass

- Start a video; confirm both participants can be heard over it from the first frame.
- Toggle `btnWatchMute`; confirm the video mutes **on that device only** and the peer is
  unaffected.
- Rotate while muted; confirm it stays muted.
- Change to a different video while muted; confirm the new video does not start at full volume.
- Let a video run to its end; confirm the document settles to `playing = false` once and the
  heartbeat write trickle stops (only the device that acted last should write).
- With the search panel open, press Back; confirm the panel closes and the session survives.
  Press Back again; confirm the screen closes and the back-gesture preview animates normally.
- Trigger a non-retryable search error (e.g. an over-long query); confirm no Retry button appears.
- Leave the feature; confirm no crash on exit and no leaked audio.
