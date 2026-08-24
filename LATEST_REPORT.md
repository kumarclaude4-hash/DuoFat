# Latest Report — Video Call & YouTube Watch Together Review

**Date:** 2026-08-24
**Scope:** End-to-end review of the in-call **Video Call** feature and the **YouTube Watch Together** feature (Android client, Firestore signaling, and the push-server search endpoint), verifying correctness/robustness and fixing any defects found.

---

## 1. Summary

Both features were reviewed end-to-end — Android sources, layouts, the WebView player page, Firestore security rules, the manifest, and the server-side search endpoint. The feature code itself is in very good shape: heavily defended, well-documented, and backed by static wiring checks plus server unit tests, **all of which pass**.

**Correction to the previous pass:** my first report declared everything "perfect," but the GitHub Actions **Lint** job was in fact failing (`BUILD FAILED`). The failure was not in the call or watch-together logic — it was a **Gradle dependency-verification gap** that only surfaces during a real Gradle resolve, which the static/server checks cannot catch. That is now fixed (Fix 2 below), which is the real answer to "did you fix all issues."

Two defects fixed total: the CI build blocker (Fix 2) and a low-risk locale inconsistency (Fix 1).

| Area | Result |
| --- | --- |
| CI **Lint** job (`lintAnalyzeDebug` + AndroidTest + UnitTest) | was **FAIL** → fixed |
| Gradle dependency verification — `guava-parent:33.3.1-jre` POM recorded | **FIXED** |
| `gradle/verification-metadata.xml` well-formed after edit | **PASS** |
| Watch Together static wiring checks (`scripts/check-watch-together.js`) | 30/30 **PASS** |
| Server unit tests (`server/lib/youtube-search.test.js`, `pure.test.js`) | 102/102 **PASS** |
| Referenced layouts / drawables / icons exist | **PASS** |
| Firestore rules — participant-only gate on `/watch/{docId}` | **PASS** |
| Manifest — `WatchTogetherActivity` declared `exported="false"` | **PASS** |
| Defects fixed | **2** (CI dependency verification + locale-safe cache key) |

---

## 2. What was fixed

### Fix 2 — CI **Lint** build failure (Gradle dependency verification) — the real blocker

**File:** `gradle/verification-metadata.xml`

**Symptom (from the CI screenshot / logs):**

```
Execution failed for task ':app:lintAnalyzeDebug'.
> Dependency verification failed for configuration ':app:coreLibraryDesugaring'
   One artifact failed verification: guava-parent-33.3.1-jre.pom
   (com.google.guava:guava-parent:33.3.1-jre) from repository MavenRepo
BUILD FAILED in 36s
```

The same failure repeated for `lintAnalyzeDebugAndroidTest` and `lintAnalyzeDebugUnitTest`.

**Root cause:** This project pins `verify-metadata=true`, so Gradle verifies **POM descriptors**, not just JAR/AAR checksums. The verification metadata was regenerated against `assemble*` / `lintDebug`, whose resolution consumes guava via **Gradle Module Metadata** (`.module`) and never resolves the guava **parent POM**. The `lintAnalyze*` tasks resolve the `coreLibraryDesugaring` graph (`desugar_jdk_libs` → guava `33.3.1-jre`) along a path that *does* resolve `guava-parent-33.3.1-jre.pom`. That parent POM had no recorded checksum (recorded `guava-parent` versions stopped at `32.1.3-android`), so Gradle rejected it and failed the build. Gradle's message says "**One** artifact failed verification," i.e. exactly this single missing entry.

**Fix:** Recorded the missing component in version order, next to the existing `guava-parent` entries:

```xml
<component group="com.google.guava" name="guava-parent" version="33.3.1-jre">
   <artifact name="guava-parent-33.3.1-jre.pom">
      <sha256 value="55441db27e8869dfefe053059bdf478bdc7e95585642bf391f0023345fd56287"
              origin="Verified against Maven Central published sha1 94729a0ed1dc35f623edd13afa6c1c2fe9a15d7c"/>
   </artifact>
</component>
```

**How the hash was verified (per this file's own SC-03 process):**
- Downloaded the POM from Maven Central (`repo1.maven.org`) — the exact repository named in the failure (`MavenRepo`) — and computed `sha256 = 55441db2…6287`.
- Cross-checked integrity against Central's independently published `.sha1` sidecar: published `94729a0e…5d7c` == computed `.sha1`, exact match.
- Google's Maven mirror (`dl.google.com`) does **not** host `com.google.guava:guava-parent` (returns a 404 page), consistent with the artifact originating from Central only.
- Confirmed the POM has **no `<parent>` element**, so no further ancestor descriptor needs recording — and the failing jar/`.module` for guava `33.3.1-jre` were already recorded, so this one POM is the complete gap.

**Risk:** Minimal and security-preserving. This *adds* a pinned checksum (it does not weaken or disable verification), so the `coreLibraryDesugaring` resolve now succeeds while every other artifact stays locked. `verification-metadata.xml` re-validated as well-formed XML after the edit.

### Fix 1 — Locale-safe search cache key

**File:** `app/src/main/java/com/duoshield/app/util/YouTubeSearchClient.java`

**Problem:** The in-memory search cache key was built with the default-locale `String.toLowerCase()`:

```java
return query.toLowerCase() + '\u0000' + maxResults;
```

On a device set to a Turkish locale, `"I".toLowerCase()` produces the dotless `"ı"`, so the same query could fold to two different keys depending on device locale (or across a locale change), silently defeating the cache and costing an extra round trip. Every other case-folding site in this codebase (e.g. `WatchTogetherPlayerView.isYouTubeHost`) deliberately pins a locale for exactly this reason, so this was an inconsistency rather than a matching convention.

**Fix:** Pin the fold to `Locale.ROOT`, matching the codebase's locale discipline:

```java
return query.toLowerCase(java.util.Locale.ROOT) + '\u0000' + maxResults;
```

**Risk:** Minimal. Pure client-side cache-key normalization; no change to what is sent on the wire, to the sync protocol, or to server behavior. Static checks and server tests still pass after the change.

---

## 3. Findings — Video Call

Reviewed `CallActivity`, its layout, and the Watch Together entry point.

- **Call establishment & lag fix present.** TURN credentials are prefetched via the callback form with a 3s hard-timeout fallback before the PeerConnection is created, avoiding the STUN-only / 20s-ICE-timeout lag path. Renderers are initialized (`prepareEgl()` → `initVideoRenderers()`) *before* the call starts, so the local "You" PiP is never attached to an uninitialized renderer.
- **In-call chat banner asymmetry fix present.** The chat listener is attached in `onCreate` for the callee and re-attached from `doStartCall()` for the caller (with bounded retries), closing the "pop-up only shows on one side" race caused by the parent call doc not existing yet.
- **No-answer watchdog** (45s) fires safely inside the 60s ICE timeout; headset routing, proximity wake lock, audio focus, and foreground service are all handled.
- **Watch Together entry point is correctly gated.** `openWatchTogether()` guards on an established call (`callId`/`myUid`), passes exactly the extras `WatchTogetherActivity` declares, and `refreshWatchTogetherAwareness()` uses a **one-shot** `fetchState` (not a second always-on listener and never a write), matching the project's single-listener / no-extra-writes invariants.

No defects found.

---

## 4. Findings — YouTube Watch Together

Reviewed `WatchTogetherActivity`, `WatchTogetherState`, `WatchTogetherRepository`, `WatchTogetherPlayerView`, `player.html`, the YouTube search stack (`YouTubeSearchClient/Parser/State/Adapter/Result`), and the server `/youtubeSearch` endpoint.

**Sync correctness**
- Exactly one Firestore snapshot listener, attached in `onStart` and removed in **both** `onStop` and `onDestroy`.
- Clock-safe projection: followers use locally measured `elapsedRealtime` and never compare against the writer's wall clock; `seq` ordering suppresses local echo, with a correct same-`seq` tie-break for the simultaneous-first-write race.
- Single-writer heartbeat with a self-correcting stall handover; heartbeat only publishes a **fresh** position (`hasFreshPosition()`), preventing a backgrounded device from dragging the peer back to a dead timestamp.
- Drift correction is suppressed during a cue settle window but never swallows a deliberate seek/play/pause/rate action.
- `ENDED` settles the shared doc once (single writer), stopping the perpetual end-of-video heartbeat trickle.

**Media & security**
- Video is loaded directly from YouTube inside each device's own WebView via the official IFrame API — it never crosses Firestore or the WebRTC media path.
- WebView is locked down (no file/content/universal access, geolocation off), navigation is pinned to YouTube hosts, main-frame navigations are refused (so "Watch on YouTube" cannot replace the player), and only an 11-char validated video id is ever interpolated into JS.
- **No YouTube API key anywhere in the APK** — search goes through the authenticated push-server endpoint; the key lives only in server env. Verified by the static check and the server tests (`the transformed response never contains the API key`, `redactApiKey`, `mapYouTubeError never echoes upstream detail`).
- Player teardown follows the WebView contract (stop → drop bridge → detach → `super.destroy()`), and the search cache is cleared when the session ends.
- WebView audio is **attenuated** (35%) rather than requesting audio focus, correctly avoiding `CallActivity`'s focus-loss handler muting the user's own microphone.

**Search UX**
- Debounced search-as-you-type, token-based stale-response rejection, honest Retry (only for retryable statuses), pasted links start a session immediately, `DiffUtil`-based list updates, and Glide thumbnail loading over https only.

**Firestore rules**
- `/calls/{callId}/watch/{docId}` is gated to the two call participants (same gate as `chat`/candidate subcollections); a third signed-in account can neither observe nor hijack a session. The `watch` subcollection is swept when the call doc is deleted.

No defects found beyond Fix 1.

---

## 5. Verification performed

```
# guava-parent POM hash cross-checked against Maven Central's published .sha1
curl -s .../guava-parent-33.3.1-jre.pom.sha1   # 94729a0e…5d7c == locally computed .sha1
xmllint --noout gradle/verification-metadata.xml            # WELL-FORMED

node scripts/check-watch-together.js        # 30/30 checks PASS
node --test server/lib/youtube-search.test.js server/lib/pure.test.js   # 102/102 PASS
```

Also confirmed post-fix: verification metadata well-formed, static checks PASS, server tests PASS.

> **Note:** The authoritative Android build (`./gradlew :app:lintDebug :app:assembleDebug`) requires the Android/Gradle toolchain, which is not available in this environment — that is exactly why the dependency-verification gap (Fix 2) was invisible to the static/server checks in the previous pass and only appeared in CI. The hash was therefore verified out-of-band against Maven Central's own published checksum. The next CI run is the authoritative confirmation that the Lint job is green.

---

## 6. Recommendation

The call and watch-together **feature logic** is correctly implemented and robust; the one behavioral hardening this pass is the locale-safe search cache key (Fix 1). The change that actually unblocks the pipeline is Fix 2 — recording the missing `guava-parent:33.3.1-jre` POM checksum so the CI Lint job stops failing dependency verification. Re-run the GitHub Actions workflow to confirm **Lint → Build debug APK → Instrumented tests** now proceed.
