# YouTube Search Implementation

> **Persistent cross-session handoff document.**
> Part 1 (backend) is COMPLETE. Part 2 (Android UI) is NOT STARTED.
> This file describes the ACTUAL state of the code. If it ever disagrees with the
> repository, **the repository wins** — verify, then correct this file.

- **Last updated:** Session 1 of 2 (2026-08-10) — backend/search foundation only.
- **Branch:** `v0/pelawo5952-2703-7fb87f56` (off `main`)
- **Reconciled against commit:** `c82ccee` — merge of PR #44
  ("Enable YouTube Watch Together with playback speed controls"). Working tree
  was clean at session start (`git status --short` empty).
- **Part 1 status:** **COMPLETE AND TEST-VERIFIED** (58/58 server unit tests pass,
  executed — see §15/§16).
- **Part 2 status:** **NOT STARTED.** No Android file was created or modified in
  this session. See §18 and §19.

---

## 1. Objective

Let a user on a DuoShield Watch Together session **search YouTube by keyword**
instead of having to paste a URL, pick a result, and have the **existing** Watch
Together player load that video and sync it as it already does.

Hard constraints, from the feature request:

- The YouTube Data API credential **MUST NOT** reach the Android client in any
  form — not Java/Kotlin, resources, `BuildConfig`, Gradle-generated constants,
  APK assets, or obfuscated client storage.
- Users pay **$0** → must fit inside YouTube's free 10,000 units/day quota.
- Use the **official** YouTube Data API. No scraping, no unofficial endpoints.
- Do **not** redesign the existing Watch Together feature, its synchronization,
  WebRTC calls, or in-call chat.
- Do **not** introduce another backend if existing infrastructure can do the job.
  (It can — see §3.)

---

## 2. Existing Watch Together Integration

The Watch Together feature is already complete and audited across 8 prior
sessions (see `IMPLEMENTATION_STATE.md`). This feature **adds an input method
only**. Nothing about the sync protocol changes.

Search plugs into exactly one place. `WatchTogetherActivity.startFromInput()`
(around **line 299** of
`app/src/main/java/com/duoshield/app/call/watch/WatchTogetherActivity.java`)
currently does:

```java
private void startFromInput() {
    String raw = etUrl.getText().toString().trim();
    String videoId = YouTubeUrlParser.extractVideoId(raw);   // URL → 11-char id
    if (videoId == null) { /* toast */ return; }
    WatchTogetherState s = new WatchTogetherState();
    s.active = true;  s.videoId = videoId;  s.hostUid = myUid;
    s.playing = true; s.positionMs = YouTubeUrlParser.extractStartMs(raw);
    s.playbackRate = WatchTogetherState.DEFAULT_PLAYBACK_RATE;
    performLocalWrite(WatchTogetherState.ACTION_START, s);   // → Firestore → both players
}
```

**Everything after `videoId` is already built and working.** Session 2's job is
to produce a `videoId` from a tapped search result and feed it into this same
`performLocalWrite(ACTION_START, …)` path — *not* to build a second player or a
second write path.

Reference for the client-side HTTP idiom: **`LinkPreviewFetcher.java`** in
`app/src/main/java/com/duoshield/app/util/`. It is the closest existing analogue
— an authenticated POST to this same server, off the main thread, with a bounded
in-memory cache and main-thread callbacks. **Copy its shape.**

---

## 3. Backend Architecture

**Decision: reuse the existing Render push server. No new service was created.**

The repo has three backend-ish directories; only one was the right home:

| Directory | What it is | Verdict |
|---|---|---|
| `server/` | **Node HTTP server on Render.** Already does Firebase ID-token verification, per-UID per-endpoint rate limiting, env-var secrets, and outbound proxy fetches (`/linkPreview`). | **CHOSEN** |
| `worker/` | Cloudflare Worker for **media storage** (R2/B2 tiering, per-object capability tokens). Authorizes object keys, not user queries. | Rejected — wrong domain |
| `functions/` | Firebase Cloud Functions scaffold. `.agents/memory/duoshield-rules.md` **rule 6 says "No Cloud Functions"**. | Rejected — violates project rule |

`server/index.js` already had a near-identical endpoint to model on:
`POST /linkPreview` (server-side fetch of a third-party URL so the client's IP
and identity never touch the target). YouTube search is the same shape with a
credential added, so it follows that idiom line for line.

Flow:

```
Android (Watch Together)
   │  POST /youtubeSearch   { q, maxResults }
   │  Authorization: Bearer <Firebase ID token>
   ▼
DuoShield push server (Render)  ← YOUTUBE_API_KEY lives ONLY here
   │  GET https://www.googleapis.com/youtube/v3/search?...&key=<secret>
   ▼
YouTube Data API v3
   │  full response (etag, tokens, descriptions, …)
   ▼
server: allow-list projection → { videoId, title, channel, thumbnail }
   ▼
Android → existing player via existing performLocalWrite(ACTION_START)
```

Video bytes are untouched by this design: each device still streams from YouTube
directly into the existing IFrame player. Only *search text* transits DuoShield.

---

## 4. Endpoint

`POST /youtubeSearch` — `server/index.js` (handler begins at **line ~2657**,
immediately after `/linkPreview` and before `/removeGroupMember`).

**Request**

```json
{ "q": "lofi beats", "maxResults": 10 }
```

- `q` — required, string. Trimmed; control chars stripped; whitespace collapsed.
- `maxResults` — optional. Clamped to `[1, 15]`; defaults to `10`.

**Responses**

| Status | Body | Cause |
|---|---|---|
| `200` | `{ "results": [...], "cached": bool }` | Success (possibly zero results) |
| `400` | `{ "error": "Bad JSON" }` | Body is not JSON |
| `400` | `{ "error": "Query must be a string" }` | `q` missing / wrong type |
| `400` | `{ "error": "Query must be at least 2 characters" }` | Too short |
| `400` | `{ "error": "Query must be at most 100 characters" }` | Too long |
| `400` | `{ "error": "Invalid search query" }` | YouTube rejected it (400) |
| `401` | `{ "error": "Unauthorized" }` | No `Authorization` header |
| `401` | `{ "error": "Invalid token" }` | ID token failed verification |
| `413` | `Request body too large` (text) | >64 KB body (shared `collectBody` guard) |
| `429` | `{ "error": "Too many searches — try again in a minute" }` | Per-user limit |
| `502` | `{ "error": "Search failed. Try again." }` | Upstream 5xx / malformed JSON |
| `503` | `{ "error": "Search is not configured" }` | `YOUTUBE_API_KEY` unset |
| `503` | `{ "error": "Search is temporarily unavailable. Try again later." }` | **Quota exhausted** (upstream 403/429) |
| `504` | `{ "error": "Search timed out. Try again." }` | Network failure / 8 s timeout |
| `500` | `Server error (ref: <id>)` (text) | Unexpected — detail stays in logs |

> **Note for Session 2:** most errors are JSON, but `413` and `500` are
> **plain text** (they come from the shared `collectBody` / `sendServerError`
> helpers used by every endpoint on this server). The Android parser must not
> assume the body is always JSON — parse defensively and fall back to a generic
> message on any status it does not recognise.

**Gate order** — deliberate, and the single most important design property:

```
auth → API-key configured → validate input → CACHE → rate limit → YouTube
```

Everything cheap or rejecting runs before the one step that spends a finite
shared resource. The **cache is checked before the rate limiter** on purpose: a
repeated query costs zero quota, so the user must never be throttled for it.

---

## 5. Authentication

Firebase ID token in `Authorization: Bearer <token>`, verified with
`admin.auth().verifyIdToken(tok)` — byte-identical to `/linkPreview`,
`/mediaToken`, `/turnCredentials`, and every other client endpoint here.

There is **no anonymous access**. An unauthenticated caller is rejected at the
first gate, before any validation, cache lookup, or quota spend. This matters
beyond privacy: quota is a shared exhaustible resource, so an open endpoint
would hand any stranger a one-request-per-100-units lever to break search for
every real user for the rest of the day.

The verified `uid` is the rate-limit bucket key, so limits cannot be evaded by
rotating IPs or client-supplied headers.

---

## 6. YouTube Data API Usage

Official **YouTube Data API v3**, `search.list` endpoint:
`https://www.googleapis.com/youtube/v3/search`. No scraping, no unofficial API.

Parameters sent (built by `pure.buildYouTubeSearchUrl`):

| Param | Value | Why |
|---|---|---|
| `part` | `snippet` | Minimum that returns titles/channels/thumbnails. `id` comes back regardless. |
| `fields` | `items(id/videoId,snippet(title,channelTitle,thumbnails(medium/url,default/url)))` | Narrows the payload to exactly what the UI renders. |
| `q` | validated query | — |
| `type` | `video` | Excludes channels/playlists, which have **no `videoId`** and would break the player. |
| `videoEmbeddable` | `true` | Excludes videos the IFrame player legally cannot play — otherwise a result silently fails on tap. |
| `safeSearch` | `moderate` | YouTube's own filter; free. |
| `maxResults` | 1–15 (default 10) | Bounds payload/render cost. Does **not** reduce quota cost. |
| `regionCode` | `YOUTUBE_REGION_CODE`, if set | Result relevance; free. |
| `key` | `YOUTUBE_API_KEY` (server env) | Only auth the Data API accepts for public data. |

**`pageToken` is never sent.** Pagination would multiply a 100-unit cost by
however many pages a user idly scrolls. One page per query, by design.

---

## 7. Requested Fields

Exactly four fields per result, because that is what the UI needs:

- `videoId` — the only field that is functionally required (feeds the player).
- `title` — primary row text.
- `channel` — disambiguates identical titles.
- `thumbnail` — recognisability.

**`duration` is deliberately NOT returned.** `search.list` does not include it;
it requires a second `videos.list?part=contentDetails` call. That would add
another quota-costing request per search for a cosmetic label. The instruction
was "duration if genuinely needed" — it is not needed, so it is omitted.
If a future session decides it is needed: `videos.list` costs only ~1 unit and
can batch up to 50 ids in **one** call, so fetch it for the whole result page at
once, never per row.

---

## 8. Response Schema

```json
{
  "results": [
    {
      "videoId":   "dQw4w9WgXcQ",
      "title":     "Rick Astley - Never Gonna Give You Up",
      "channel":   "Rick Astley",
      "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
    }
  ],
  "cached": false
}
```

Guarantees Session 2 can rely on:

- `results` is **always an array** (empty on no matches). Never null/absent on 200.
- `videoId` always matches `^[A-Za-z0-9_-]{11}$` — validated server-side; items
  failing it are dropped, not forwarded.
- `title` is always non-empty — items without one are dropped.
- `channel` and `thumbnail` may be `""` (empty), never null. The UI must tolerate
  an empty thumbnail (show a placeholder) rather than crashing.
- `thumbnail`, when non-empty, is always `https://`. Cleartext URLs are stripped.
- `cached` is informational only; it does not change the shape of `results`.

The transform (`pure.transformYouTubeSearchResponse`) is an **allow-list**: it
copies only those four keys out of the upstream body. Any field YouTube adds in
future — quota metadata, etags, page tokens, descriptions — cannot reach the
client, because nothing copies it.

---

## 9. Quota Strategy

This is the constraint that shaped the whole design.

**A `search.list` call costs 100 quota units. The free allowance is 10,000
units/day. That is ~100 searches per day for the ENTIRE deployment**, shared
across all users — not per user. Controls, in the order they take effect:

1. **Authentication required** — no anonymous quota burn (§5).
2. **Minimum query length 2** — a 1-char query costs a full 100 units for
   near-random results. Rejected before any outbound call, at zero cost.
3. **Maximum query length 100** — bounds abuse and cache-key size.
4. **10-minute response cache** (§11) — absorbs the dominant real-world pattern:
   two people on a call searching the same thing seconds apart, and retries after
   a network blip. Empty result sets are cached too, since "no matches for this
   typo" is a stable answer worth not re-asking.
5. **Per-user rate limit 6/min** (§10) — bounds how fast one account can drain
   the shared daily budget.
6. **No pagination, ever** — one upstream call per uncached query, maximum.
7. **No search-on-every-keystroke** — the backend cannot control client typing,
   so it protects itself with 2–6 above regardless of what any client does. The
   Android client must *also* debounce or use explicit submit (§18) — but that is
   a UX optimisation layered on top of enforcement, not the enforcement itself.
8. **Fails closed** — if `YOUTUBE_API_KEY` is unset the endpoint returns 503
   without attempting a call.

Worst case with these limits: one authenticated user sustaining 6/min could
still exhaust the daily budget in ~17 minutes of deliberate abuse. That is
**accepted and documented**, not solved: this is a two-person-call app with a
small, invite-gated user base (see the waitlist flow), and the alternative —
per-user daily quota accounting — needs durable cross-restart storage that this
free-tier Render instance does not have. **If the user base grows, add a
per-UID daily counter in Firestore.** See §17.

---

## 10. Rate Limiting

Reuses the existing `checkAuthRateLimit(uid, endpoint)` mechanism in
`server/index.js` — no new limiter was written.

Added one entry to the existing `AUTH_RATE_LIMITS` table:

```js
youtubeSearch: 6,   // 6 searches/min per user
```

It is deliberately the **tightest limit in that table** (others range 2–120).
Every other endpoint's cost is CPU or Firestore ops; this one spends a shared,
exhaustible, resets-only-at-midnight resource. 6/min still feels instant to a
human typing queries.

Implementation notes:
- Fixed 60-second window, per `uid`, per endpoint — bucketed on the **verified**
  uid, so it cannot be evaded by rotating IPs or spoofing headers.
- In-memory (`Map`), purged every 5 minutes by the existing sweeper. It resets
  on a Render restart; acceptable, and identical to every other endpoint here.
- **Cache hits never reach this gate**, so repeated identical searches are free
  and unthrottled.

---

## 11. Caching

Server-side, in `server/index.js`:

- `searchCache` — `Map` of `cacheKey → { results, expiresAt }`.
- **TTL 10 minutes** — fresh enough that a new upload appears within minutes,
  long enough to absorb bursts.
- **Max 300 entries**, evicting oldest-inserted first (`Map` iteration order).
  Bounded so a long-lived instance cannot grow without limit.
- A periodic sweep drops expired entries so an idle instance stops holding
  result titles in memory. The interval is `.unref()`'d so it never keeps the
  process alive.
- **Cache key** = lowercased query + `\0` + `maxResults`
  (`pure.searchCacheKey`). Case-insensitive, so "Lofi Beats" and "lofi beats"
  share one entry. `maxResults` is part of the key so a 5-row answer is never
  served to a 15-row request. The `\0` delimiter prevents `("a", 11)` colliding
  with `("a1", 1)` — there is a test for exactly that.

Whitespace normalisation happens earlier, in `validateSearchQuery`, so
`"  lofi   beats "` and `"lofi beats"` also share one entry.

---

## 12. Secret Configuration

| Name | Where it lives | Notes |
|---|---|---|
| `YOUTUBE_API_KEY` | Render env var, `server/` service **only** | YouTube Data API v3 key. Read once at startup into a module constant. |
| `YOUTUBE_REGION_CODE` | Render env var (optional) | Not a secret. ISO 3166-1 alpha-2, e.g. `US`. |

**Only the NAME is documented here. The value appears in no file in this
repository, and must never be committed to one.**

To provision: Google Cloud console → enable **YouTube Data API v3** →
Credentials → create API key → **restrict it to that single API**, and
optionally to the Render egress IP. Add as `YOUTUBE_API_KEY` on the Render
service. Also documented in `server/README.md`.

Deliberately **NOT** used, because each would put the credential in the APK:
Gradle `buildConfigField`, `local.properties`, string resources, raw assets, or
any client-side encryption of the key (which only obfuscates — the app must
decrypt it at runtime, so it remains extractable).

---

## 13. Security Considerations

1. **Credential containment.** The key exists only in server env. Verified:
   `grep -rn "YOUTUBE_API_KEY" app/ gradle.properties build.gradle
   local.properties.template` → **no matches**. Only `server/index.js` and docs
   reference the *name*.
2. **Never echoed to clients.** Upstream error bodies are **never forwarded** —
   they can embed the request URL (which carries the key), the project number,
   and internal reason codes. `pure.mapYouTubeError` returns static strings only,
   and a test asserts those strings contain no `key`/`quota`/`project`/
   `googleapis`/`http` substrings.
3. **Never written to logs.** Node's fetch/URL errors embed the full request URL.
   Every YouTube-related log line passes through `pure.redactApiKey`, which
   rewrites `key=<value>` to `key=[REDACTED]` in both query-string and JSON-ish
   forms. Without this, a transient network error would print the credential
   into Render's persistent logs — the exact leak this architecture prevents.
4. **Search terms are not logged.** Only `uid` (already pseudonymised via
   `uidTag`), query **length**, and result count are logged. In a privacy tool,
   what someone searched for is sensitive, and logs are in the threat model
   (see the `SEC-L01` rationale already in this server).
5. **Allow-list response projection** (§8) — a new upstream field cannot leak.
6. **Input sanitisation.** Control characters are stripped before the query is
   placed in a URL; `URLSearchParams` percent-encodes it, so a query containing
   `&key=` or `#` cannot inject or truncate parameters. Tested.
7. **No SSRF surface.** Unlike `/linkPreview`, the client supplies no URL — the
   destination is a hardcoded constant. The `isBlockedPreviewHost` guard is not
   needed and is not used here.
8. **Body size** capped at 64 KB by the shared `collectBody` helper.
9. **Timeout** of 8 s via `AbortController`, so a hung upstream cannot pin a
   request slot open.
10. **Fails closed** when unconfigured (503, no call attempted).
11. **Cleartext downgrade prevented** — non-`https` thumbnail URLs are dropped
    rather than forwarded (tested).
12. **No new Firestore reads/writes**, so `FirebaseCostGuard` (rule 2) is not
    implicated on the backend. Nothing in this session touches Firestore.

---

## 14. Files Changed

**Modified (3) — all additive:**

| File | Change |
|---|---|
| `server/lib/pure.js` | Added 7 pure helpers + 4 constants: `validateSearchQuery`, `clampMaxResults`, `searchCacheKey`, `buildYouTubeSearchUrl`, `transformYouTubeSearchResponse`, `redactApiKey`, `mapYouTubeError`; exported all. **No existing function modified.** |
| `server/index.js` | Added the `POST /youtubeSearch` handler (after `/linkPreview`); the `YOUTUBE_API_KEY`/`YOUTUBE_REGION_CODE` constants + startup warning; the `searchCache` + get/put/sweep helpers; and one line to the existing `AUTH_RATE_LIMITS` table (`youtubeSearch: 6`). **No existing handler, listener, or limiter modified.** |
| `server/README.md` | Documented the two env vars and the endpoint (name only, never a value). |

**Created (2):**

| File | Contents |
|---|---|
| `server/lib/youtube-search.test.js` | 26 unit tests for the pure helpers (new file so existing tests stay untouched). |
| `docs/watch-together/YOUTUBE_SEARCH_IMPLEMENTATION.md` | This document. |

**NOT touched — confirm this is still true before Session 2 changes anything:**
no file under `app/` (no Android change at all), no `firestore.rules`,
no `firestore-tests/`, no `worker/`, no `functions/`, no `CallManager.java`,
no `CallActivity.java`, no `WatchTogetherActivity.java`,
no `WatchTogetherState.java`, no `WatchTogetherRepository.java`,
no `scripts/check-watch-together.js`.

---

## 15. Tests

`server/lib/youtube-search.test.js` — 26 tests, Node's built-in runner
(`cd server && npm test`), matching the existing `pure.test.js` convention.

| Requirement | Covered by |
|---|---|
| Valid search | `validateSearchQuery` accepts + normalises; `buildYouTubeSearchUrl` param assertions; `transformYouTubeSearchResponse` happy path |
| Empty/short query | rejects `""`, `" "`, `"a"`; boundary: `"ab"` accepted |
| Malformed input | non-strings (`null`/`undefined`/number/object/array/bool/function) rejected without throwing |
| Empty results | `{items: []}` and a body with no `items` → `[]` |
| YouTube API errors | `mapYouTubeError` 403/429→503, 400→400, 5xx/unknown→502 |
| Authentication failure | Endpoint-level; see the BLOCKED note below |
| Rate limiting | Existing `evaluateFixedWindow` tests in `pure.test.js` cover the shared limiter |
| Response transformation | allow-list keys; medium→default thumbnail fallback; drops invalid/missing `videoId` (incl. channel results), empty titles; defaults missing channel; malformed bodies |
| **Credential never in client-facing response** | transform output never contains the key even when the upstream body echoes it; `redactApiKey` strips it from log strings; error messages asserted free of leaky substrings |
| Over-long query | boundary at 100 / 101 chars |
| Injection via query | `"cats & dogs?=#"` cannot inject extra URL params |
| Quota-shape guards | `pageToken` never sent; `maxResults` clamped `[1,15]`; `type=video`; `videoEmbeddable=true` |
| Cache-key correctness | case-insensitivity; `maxResults` in key; `\0` collision guard |
| Non-Latin queries | CJK + Arabic preserved (justifies the min length of 2) |

**A real bug was found and fixed by these tests, not by review:**
`clampMaxResults(null)` returned `1` instead of the default `10`, because
`Number(null)`, `Number("")`, `Number([])` and `Number(false)` are all `0`,
which then hit the lower clamp. A client omitting `maxResults` would have
silently received **one** result. Fixed by requiring a number or numeric string
*before* coercion; regression tests added for all five coercion traps.

**Not unit-tested (deliberate, with reason):** the handler's own glue —
`verifyIdToken`, `checkAuthRateLimit`, `fetch`. Exercising it in-process needs
real Firebase credentials and network egress, and this server has no HTTP-level
test harness to extend. Instead, the gate order and every status code were
verified by a **throwaway in-process probe** that replayed the handler's exact
gate sequence against a stubbed upstream (results in §16). The probe was deleted
rather than committed, because a copy of the handler's logic would drift from the
handler. **Session 2 should treat endpoint-level integration testing as an open
gap** — see §17.

---

## 16. Validation

Everything below was **actually executed** in this session. Distinguishing
PASS / BLOCKED / NOT RUN as instructed.

| Check | Result | Detail |
|---|---|---|
| `cd server && npm test` | **PASS — 58/58** | 32 pre-existing + 26 new. Zero failures. |
| `node --check server/index.js` | **PASS** | Syntax valid. |
| `node --check server/lib/pure.js` | **PASS** | |
| `node --check server/lib/youtube-search.test.js` | **PASS** | |
| `node scripts/check-watch-together.js` | **PASS — 21/21** | Confirms no Watch Together invariant was disturbed. |
| Credential-absence grep over `app/`, `gradle.properties`, `build.gradle`, `local.properties.template` | **PASS** | Zero matches for `YOUTUBE_API_KEY` / `youtube.api.key` / `youtubeApiKey`. |
| Endpoint gate-order probe (stubbed upstream, in-process) | **PASS** | 401 no-header, 401 bad token, 400 bad JSON, 400 short query, 400 missing query, 200 cache miss, 200 cache **hit** on differing case, 200 empty results, 503 on upstream 403 (quota), 502 on upstream 500, 504 on timeout, 502 on malformed JSON. Rate limit: attempts 1–6 → 200, attempts 7–8 → **429**. Key present in outbound URL, **absent** from response. |
| `:app:compileDebugJavaWithJavac` | **BLOCKED** | No JDK in this container (`java` not on PATH) — same toolchain gap as Sessions 7–8, not a regression. **No Android file was changed**, so nothing new to compile. |
| `:app:assembleDebug` / `:app:lintDebug` | **BLOCKED** | Same. |
| Watch Together JVM tests | **BLOCKED** | Same (needs JDK). Untouched by this session. |
| Firestore rules tests | **NOT RUN** | Requires the Firebase emulator (`firebase` binary absent). This session changed **no** `.rules` file and adds no Firestore access. |
| Runtime / on-device verification | **BLOCKED** | No device or emulator. Also **not meaningful yet** — there is no UI to exercise until Session 2. |

**No runtime result is claimed or fabricated anywhere in this document.**

---

## 17. Known Issues

1. **No per-user DAILY quota cap.** The 6/min limit bounds burst rate, not daily
   total: a determined authenticated user could exhaust the shared 10,000-unit
   budget in ~17 minutes. Accepted for a small invite-gated user base; fix with
   a per-UID daily counter in Firestore if the base grows. (§9)
2. **Cache and rate-limit state are in-memory.** Both reset when Render restarts
   or scales out. Consistent with every other endpoint on this server; a restart
   simply means a few extra upstream calls.
3. **No endpoint-level integration test.** The pure helpers are well covered and
   the gate order was probe-verified, but nothing in CI exercises the live
   handler with auth + fetch. Adding an HTTP-level harness would be a
   cross-cutting change to a 3,500-line server, out of scope here. (§15)
4. **`YOUTUBE_API_KEY` must be provisioned before the feature works.** Until it
   is set on Render, `/youtubeSearch` returns `503 {"error":"Search is not
   configured"}`. Session 2's UI must surface this as a normal "search
   unavailable" state, not a crash.
5. **No `duration`** in results, by deliberate quota trade-off. (§7)
6. **Pre-existing, unrelated, still unfixed:** the stray U+FFFD character in a
   `WatchTogetherActivity.java` comment divider (~line 420) noted in Session 8.
   Cosmetic, valid UTF-8, does not block compilation. Left alone per the
   "no unrelated fixes" rule — but Session 2 **will** be editing that file, so
   it may as well clean it up then.

---

## 18. Remaining Android Work

None of this exists yet. All of it is Session 2.

1. **Search UI in the existing `WatchTogetherActivity`** — do not create a new
   screen and do not redesign the existing one. The layout
   `app/src/main/res/layout/activity_watch_together.xml` already has a URL input
   row (`etUrl`) + start button; the natural move is to let that same field
   accept free text and show results beneath it, keeping paste-a-URL working via
   the existing `YouTubeUrlParser.extractVideoId` check first.
2. **A `YouTubeSearchClient`** in `app/src/main/java/com/duoshield/app/util/`,
   modelled directly on `LinkPreviewFetcher.java`: background executor, Firebase
   ID token via `getIdTokenSync()`, `POST` to
   `BuildConfig.PUSH_SERVER_URL + "/youtubeSearch"`, main-thread callback,
   bounded client-side cache. **No API key anywhere.**
3. **Result list** — `RecyclerView` + adapter with thumbnail/title/channel.
   Per project rule 5, use **DiffUtil** (`setResults(list)`), never
   `notifyDataSetChanged()`.
4. **Client-side debounce (~350–500 ms) or explicit submit.** The backend is
   already protected, but every wasted call costs 100 shared quota units.
   Explicit submit is the cheaper, more predictable choice.
5. **All the states**: loading, empty results, query too short, network failure,
   401, 429, 503-unconfigured, 503-quota, 502, 504, malformed response,
   unavailable video. Note §4's warning that `413`/`500` are plain text.
6. **Selection → existing player**: on tap, take `videoId` and route it through
   the **existing** `performLocalWrite(WatchTogetherState.ACTION_START, s)` path
   exactly as `startFromInput()` does (§2). Do **not** add a second player, a
   second write path, or any change to the sync protocol.
7. **Android tests** for search state, response parsing, empty/malformed
   responses, result→`videoId` mapping, error states, duplicate-search handling.
   Prefer pure/JVM-testable logic (the `WatchTogetherState` package is the model
   to follow — it has no Android imports precisely so it can be unit-tested).
8. **Thumbnail loading** — check which image library the project already uses
   (`MessageAdapter` loads link-preview images; reuse that, do not add a new
   dependency).
9. Extend `scripts/check-watch-together.js` with search invariants if useful.

---

## 19. EXACT NEXT SESSION INSTRUCTIONS

You are Session 2. You have **no** conversation history. The repository and this
file are your only context. Assume nothing below is still true until you check.

**Step 1 — Recover state.**
```bash
cd /vercel/share/v0-project
git status --short
git log -1 --stat
git show --format=fuller --stat HEAD
```
Read, in order:
1. `.agents/memory/duoshield-rules.md` — non-negotiable project rules.
2. `docs/watch-together/IMPLEMENTATION_STATE.md` — Watch Together state (§ "Part 2" at the end covers this feature).
3. This file, `docs/watch-together/YOUTUBE_SEARCH_IMPLEMENTATION.md`.

**Step 2 — Verify Session 1 against the actual code. Do not trust this doc.**
```bash
cd server && npm test                      # expect 58/58 pass
node --check index.js
cd .. && node scripts/check-watch-together.js   # expect 21/21 pass
grep -rn "YOUTUBE_API_KEY" app/ gradle.properties build.gradle   # expect NO matches
```
Then read the real handler — `POST /youtubeSearch` in `server/index.js`
(~line 2657) — and confirm §4's request/response contract and §8's schema match
the code. **If they disagree, the code wins: fix this document first, then
continue.**

**Step 3 — Read before writing.** These four files are the templates you need:
- `app/src/main/java/com/duoshield/app/util/LinkPreviewFetcher.java` — copy this HTTP/auth/cache shape.
- `app/src/main/java/com/duoshield/app/call/watch/WatchTogetherActivity.java` — especially `startFromInput()` (~line 299) and `performLocalWrite`.
- `app/src/main/res/layout/activity_watch_together.xml` — the existing input row you are extending.
- `app/src/main/java/com/duoshield/app/ui/MessageAdapter.java` — the existing image-loading and DiffUtil patterns.

**Step 4 — Build it.** Work through §18 in small verified batches. Rules that
will bite you if ignored:
- `FirebaseCostGuard` before **every** Firestore op (rule 2). Search itself does
  no Firestore I/O — but the existing `performLocalWrite` already handles the
  session write, so **reuse it rather than writing your own**.
- **DiffUtil**, never `notifyDataSetChanged()` (rule 5).
- One Firestore listener per screen (rule 3) — `WatchTogetherActivity` already
  owns exactly one, and the static checker enforces this. Do not add another.
- No API key in the client, in any form.
- Do not modify `CallManager`, WebRTC, in-call chat, or the sync protocol.

**Step 5 — Validate, and label results honestly.**
```bash
cd server && npm test
cd .. && node scripts/check-watch-together.js
./gradlew :app:compileDebugJavaWithJavac :app:assembleDebug :app:lintDebug
./gradlew :app:testDebugUnitTest --tests '*WatchTogether*' --tests '*YouTube*'
```
`java` was **absent** in Sessions 7, 8, and 1-of-2. If it is still absent, mark
Gradle checks **BLOCKED** and say so plainly — do not write "PASS" for anything
you did not run. If a JDK is available, Session 6's recipe
(`scripts/setup-android-sdk.sh`, JDK 17 + Android SDK 34) is the known-good
toolchain. Pre-existing unrelated failure to expect and **not** fix:
13 `BackupRoundTripTest` failures.

**Step 6 — Runtime.** If a device/emulator exists, run the 12-step two-participant
script in the original request (search → results → select → both players load →
play/pause/seek/rate → call audio/video → in-call chat → end session → end call →
fresh call has no stale state). If not, mark runtime verification **BLOCKED**.
**Never fabricate runtime results.**

**Step 7 — Security review.** Answer all 10 questions from the original request
explicitly, with evidence (the §13 items and the greps in Step 2 give you most).

**Step 8 — Document.** Update **§13–§19 of this file** with the final
architecture, client flow, tests, build/lint/runtime results, and final security
assessment. Then update `docs/watch-together/IMPLEMENTATION_STATE.md` so it
represents the FINAL combined state of Watch Together + YouTube Search. Never
put a secret **value** in either file. Do not call the feature
production-ready unless the evidence you actually gathered supports it —
in particular, runtime verification being BLOCKED means **not** production-ready.
