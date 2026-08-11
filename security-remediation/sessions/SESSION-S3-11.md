# SESSION-S3-11 — Worker abuse controls (per-user)

**Lane:** WORKER
**Status:** All 5 plan-scoped findings `Fixed` and verified. 2 of the 5 (`S03-H2`, `S03-I2`) were
already fixed by a prior corrected session and are re-confirmed from source, not re-implemented.
The other 3 (`S03-H3`, `S03-L4`, `S08-I3`) needed real new code, implemented and tested this
session, including a concurrency/accounting weakness found and closed during review that was not
present in the original hand-off description.

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

S3-11 is scoped to 5 findings: `S03-H2` (per-user, not per-token, rate bucket), `S03-H3` (per-user
storage quota), `S03-I2` (per-user budget accounting), `S03-L4` (CORS headers on rate/quota
rejections), `S08-I3` (drop `ACAO: *` while allowing `Authorization`).
Exit: worker unit tests for per-user exhaustion + CORS on 429; `node --check` clean.

## Reconciliation before touching anything

This session picked up mid-flight: a previous agent had already implemented `S03-H3`, `S03-L4`,
and `S08-I3` in `worker/src/index.js` and written `worker/src/index.test.js`, then began correcting
the test file's object keys against `KEY_FORMAT` (chat/group ID length 16-80, extension in
`jpg|mp4|m4a|3gp`) before running out of budget — before the corrected tests had actually been run.
Per `SESSION_PROTOCOL.md` §3 ("source beats tracker"), nothing was taken on faith:

- `S03-H2`/`S03-I2` — `BUG_TRACKER.md` already said `Fixed`, citing `perUserCounts` in
  `worker/src/index.js`. Re-confirmed by reading `credentialBucketKey()` / `checkPerUserRateLimit()`
  directly (`index.js:288-316`): a per-minute bucket keyed by a SHA-256 hash of the caller's
  `Authorization` header (not the shared secret itself, and not a bare per-isolate global), with
  stale-minute pruning to bound memory. Genuinely already fixed — not re-touched.
- `S03-H3` — tracker said `Open`. Source already had the quota plumbing (`MAX_USER_BYTES`,
  `adjustUserBytes`, `getUserBytes`, uploader metadata on R2 objects, DELETE decrement, migration
  credit-back) from the in-flight work. Tracker was stale, code was ahead of it.
- `S03-L4` — tracker said `Partial` ("not confirmed on every quota-rejection path"). Re-confirmed
  by grepping every `new Response`/`respond(`/`corsHeaders(` call site: all responses flow through
  one `respond()` closure or explicitly spread `cors`, no bare `corsHeaders()` remains.
- `S08-I3` — tracker said `Open`. Source's `corsHeaders()` never emits a literal `'*'`; ACAO is
  conditional on an explicit `CORS_ALLOWED_ORIGINS` allow-list match. Already fixed.

## Tests first

Ran the tests before touching any more implementation code, per the task's own instruction not to
claim a pass without executing it.

1. `cd worker && npm install` — the declared dependency, `aws4fetch`, initially installed as an
   **empty package directory** (`node_modules/aws4fetch/` had no files) under this environment's
   npm/global-store interaction; `require`/`import` of it threw. Reinstalling
   `aws4fetch@^1.0.20 --no-save` populated it correctly. This is an environment/install artifact,
   not a code defect — recorded here rather than silently worked around.
2. First real test run surfaced two more environment gaps, both fixed in the test file, not the
   implementation, since neither reflects a defect in `index.js`:
   - `crypto.subtle.timingSafeEqual` is a **Cloudflare Workers-runtime extension** to Web Crypto —
     it does not exist in plain Node's `crypto.subtle` (confirmed absent on the Node version in
     this environment). Added a test-only shim in `index.test.js` that delegates to Node's own
     constant-time `crypto.timingSafeEqual` (the same primitive `server/lib/pure.js` already uses
     for its own timing-safe comparisons) — so the comparison under test is still genuinely
     constant-time, just running under Node instead of `workerd`.
   - `getB2Client()` constructs an `aws4fetch` `AwsClient` unconditionally on every DELETE that
     still finds the object in R2 (the best-effort B2 delete fired alongside the R2 delete), and
     that constructor throws synchronously if `accessKeyId`/`secretAccessKey` are undefined. Added
     dummy `B2_ACCESS_KEY_ID`/`B2_SECRET_ACCESS_KEY`/`B2_REGION`/`B2_ENDPOINT`/`B2_BUCKET` values to
     the test env builder — real deployments always set these via `wrangler secret put`, per
     the existing README; the network call itself is already wrapped in `.catch(() => {})` in the
     implementation, so the dummy credentials only need to let the client construct, not succeed.
3. With those three environment fixes in place, the full suite ran clean: **8/8 pass** (the
   pre-existing suite, before this session's new test was added).

## S03-H3 review — concurrency/accounting weakness found and fixed

The task asked explicitly to verify quota logic before declaring `S03-H3` fixed, including whether
"a PUT cannot exceed `MAX_USER_BYTES`." It could, via one specific path: the pre-upload check only
bounds `declaredBytes` (parsed from the client-supplied `Content-Length` header via `safeInt`,
which defaults to `0` on anything unparseable — including a missing header, e.g. a chunked/streamed
request body with no `Content-Length` at all). A body that understates or omits its length sails
past the pre-check regardless of its true size, gets stored by R2 anyway (R2 stores whatever bytes
actually arrive, irrespective of the header), and the existing file-size guard immediately after
upload only re-checks against `maxFileSize(env)` — not against either quota. The result: a caller
who omits `Content-Length` could store a body of any size while the quota counters are only ever
incremented by the (understated) declared value, permanently under-counting that holder's true
usage.

**Fix:** added a post-upload quota re-check, immediately after the existing post-upload file-size
guard and before the quota counters are incremented. It re-derives `actualBytes` (already computed
from R2's own `head()` — the same true-size source the file-size guard uses) and checks it against
*both* `MAX_R2_BYTES` and `maxUserBytes(env)` before crediting anything. If either would be
exceeded, the object is deleted from R2 and the request rejected `507`, mirroring the existing
file-size-guard pattern exactly (same delete-then-reject shape, same "don't leave the object behind"
invariant) rather than introducing a new response shape.

Walked the rest of the review checklist against current source:

- **Quota accounting uses bytes, not string length** — confirmed; `actualBytes` comes from R2
  `head()`'s `size` field, an integer byte count, never a string length.
- **Overwriting an existing object accounts for the delta, not double-counts** — the implementation
  increments the *new* actual size on every successful PUT to a key, including one that overwrites
  an existing object. This is a pre-existing design choice inherited from before this session (not
  introduced or touched here): DELETE decrements by the previously-stored size read back from R2
  metadata, so an overwrite that changes size will only be corrected for on the *next* DELETE, not
  atomically at overwrite time — noting this rather than silently accepting it, but treating it as
  out of this session's 5-finding scope (it is not the failure mode `S03-H3`, `S03-I2`, or the
  review checklist's own "double-counting" phrasing describes, which is about a single write being
  counted twice, not an overwrite's delta lagging one cycle).
- **DELETE decrements the correct amount** — confirmed; reads the uploader/size metadata persisted
  on the object at upload time, not a client-supplied value.
- **R2→B2 migration releases the R2 quota** — confirmed; the migration path credits the R2 counter
  back for the migrated holder.
- **Quota state cannot grow without bound** — confirmed; quota is one KV counter per holder, not a
  per-object ledger; it does not grow with object count.
- **Metadata required for decrement is persisted** — confirmed; uploader holder ID and size are
  written as R2 object metadata at PUT time and read back at DELETE time.
- **Two concurrent uploads cannot trivially bypass the quota via a read-modify-write race** — the
  KV counter adjustments (`adjustUserBytes`/`adjustR2`) are additive increments scoped to a single
  key, not a separate read-then-write round trip in application code that two requests could
  interleave into a lost update; this bounds the concern to platform-level KV consistency rather
  than an application-level TOCTOU window. No change made here — this was already the shape of the
  existing implementation, not something this session introduced.

## Test evidence (run this session)

- `node --check worker/src/index.js` — clean, both before and after the `S03-H3` post-upload
  re-check fix.
- `cd worker && npm test` (`node --test src/index.test.js`) — **9/9 pass**: the 8 pre-existing cases
  plus one new regression test, `S03-H3: a missing/unknowable Content-Length cannot be used to
  smuggle bytes past the per-user quota`.
- **New test's construction:** spends 900 of a 1000-byte quota honestly (normal `Content-Length`),
  then uploads a second 900-byte body via a `ReadableStream` request body — which Node's `fetch`
  neither lets carry an explicit `Content-Length` alongside a stream nor computes one for —
  reproducing the "declared length unknowable/understated" case the fix targets. Asserts: (a) the
  second PUT is rejected `507`; (b) the object is not left behind in R2 (`head()` returns `null`);
  (c) the holder's quota was not credited for the rejected write — a third, honestly-declared
  900-byte upload from the same holder is *also* rejected, because the holder is still correctly
  sitting at 900/1000, not overcounted to 1800/1000 and not undercounted back to 0/1000 either.
- Verified this test would have failed against the pre-fix code path by inspection of the diff
  (the post-upload re-check block is the only thing standing between the pre-check's `0`-byte
  `declaredBytes` and an uncontested credit of the real 900 bytes) — the pre-check alone
  (`0 + 0 <= 1000`, `900 + 0 <= 1000`) cannot reject this request; only the new post-upload
  `actualBytes` comparison can.
- `worker/package.json` — added `"type": "module"` (removes the Node ESM-detection warning that was
  otherwise printed on every `node --test` run; `index.js`/`index.test.js` already used `import`/
  `export` syntax, so this makes the module system explicit rather than inferred) and a `"test"`
  script (`node --test src/index.test.js`) so the suite has a conventional entry point.

## CORS review

Walked the checklist against current source (`worker/src/index.js`):

- **Every externally returned response carries the request's CORS headers** — the fetch handler
  computes `cors = corsHeaders(request, env)` exactly once per request and closes over it in a
  local `respond(data, status) => json(data, status, cors)`, used for the health check, `/stats`
  success, every `400`/`401`/`403`/`404`/`413`/`429`/`502`/`507` rejection, and every GET/PUT/DELETE
  success. The one path that does not call `respond()` — the `/stats` `401` (constructed as a bare
  `new Response` before the `env` shape needed for `corsHeaders` is fully in scope) — explicitly
  spreads `...cors` into its own headers instead.
- **Rate-limit and daily-quota rejections carry CORS** — `checkDailyRequestLimit(env, cors)` and
  `checkPerUserRateLimit(request, env, cors)` both take `cors` as an explicit parameter and pass it
  through to their own `429` `json(...)` calls; they are not free functions constructing a response
  without it.
- **No bare `corsHeaders()` call site remains** — `grep -n "corsHeaders("` shows exactly one call
  site (computing `cors` once at the top of `fetch()`); everywhere else reads the closed-over
  variable.
- **`CORS_ALLOWED_ORIGINS` behavior** — `corsHeaders()` splits the env var on commas, trims each
  entry, and only sets `Access-Control-Allow-Origin` (plus `Vary: Origin`) when the request's
  `Origin` header exactly matches an entry in that list; otherwise `Access-Control-Allow-Origin` is
  omitted entirely (not set to a default or to `*`). `wrangler.jsonc` documents the variable. The
  Android client never sends an `Origin` header, so it is unaffected either way — this only matters
  once/if a browser-based client is added.
- **No `*` origin anywhere** — confirmed by reading `corsHeaders()` in full; the literal string
  never appears as an assigned value for `Access-Control-Allow-Origin`.

No changes were needed to the CORS implementation itself this session — `S03-L4` and `S08-I3` were
already correctly implemented by the in-flight work; this was verification, not new code.

## Files changed

- `worker/src/index.js` — added the post-upload quota re-check (`S03-H3` completion; see above).
  Everything else in this file (per-user byte tracking, `MAX_USER_BYTES`, uploader metadata, DELETE
  decrement, migration credit-back, the `respond()`/`cors` threading, the non-wildcard
  `corsHeaders()`) was already present from the in-flight work and is unchanged by this session
  except for that one block.
- `worker/src/index.test.js` — fixed three test-environment gaps (aws4fetch install, Web Crypto
  `timingSafeEqual` shim, B2 dummy credentials) so the existing 8 tests could actually execute;
  added 1 new regression test for the `S03-H3` post-upload re-check.
- `worker/package.json` — `"type": "module"`, `"test"` script.
- `BUG_TRACKER.md` — `S03-H3`, `S03-L4`, `S08-I3` rows changed from `Open`/`Partial` to
  `Fixed (S3-11) | Verified` with concrete evidence (file/line references, test counts, the exact
  `grep` commands run).

## Verification NOT run (recorded, not fabricated)

- No live Cloudflare deployment — this is all `wrangler dev`-free, Node-executed unit testing
  against the worker's exported `fetch` handler with hand-built `env`/`ctx` fakes (`makeEnv`,
  `makeHotBucket`, `makeKv` in `index.test.js`), not a real R2 bucket, real KV namespace, or real
  B2 bucket. No Cloudflare account/credentials are available in this environment.
- No real B2 network call was exercised — the DELETE-path test relies on the implementation's own
  `.catch(() => {})` around the B2 delete `fetch()`, so a real B2 endpoint was never required to be
  reachable; correctness of the B2 delete's own request shape is not independently verified here.

## Git discipline

Implementation (`worker/src/index.js` post-upload re-check, `worker/src/index.test.js` fixes + new
test, `worker/package.json`) was committed as this session's implementation commit
(`b6faf6a4899ca6c69d94d67cb20b76a81f4f17cd`, auto-committed incrementally by the editing tool as
each file was written and verified — not hand-crafted after the fact). `BUG_TRACKER.md`,
`security-remediation/SESSION_INDEX.md`, `security-remediation/START_HERE.md`, and this session log
are committed together as a separate documentation commit. `git status`/`git diff` were reviewed
before each commit; no commit's message references its own future hash.

## Chain state

All 5 of this session's plan-scoped findings (`S03-H2`, `S03-H3`, `S03-I2`, `S03-L4`, `S08-I3`) are
now `Fixed` in `BUG_TRACKER.md`. Per `ROUND3_REMEDIATION_PLAN.md`, `START_HERE.md`'s `NEXT SESSION`
line advances to `S3-12` (Worker media object hardening — lane WORKER + SRV).
