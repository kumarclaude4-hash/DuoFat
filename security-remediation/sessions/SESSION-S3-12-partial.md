# SESSION-S3-12 (partial) — Worker per-object hardening: S03-M2 only

**Status:** Partial. This session fixed exactly one of the six findings the
`ROUND3_REMEDIATION_PLAN.md` S3-12 entry bundles together. The other five —
`S03-M1`, `S03-M3`, `S03-L2`, `S03-L3`/`S04-I2`, `S10-N3` — are **not** touched
and remain `Open`/queued under a future full run of S3-12.

Plan scope for reference (`ROUND3_REMEDIATION_PLAN.md`):
> Findings: `S03-M1` (`nosniff` + `Content-Disposition` + validated
> `Content-Type`), `S03-M2` (uploader-bound, not just scope-bound, tokens),
> `S03-M3` (cut token TTL + bound reuse), `S03-L2` (guard
> `decodeURIComponent`), `S03-L3`/`S04-I2` (remove dead B2 presign surface;
> **revoke B2 key** = runbook), `S10-N3` (cold-tier delete/migration race).
> Exit: worker + server tests green; dead B2 path gone by grep.

This session was explicitly scoped by the user to "one more bug." `S03-M2`
was selected (see the options offered — S03-M1/M3/L2/L3+I2/S10-N3 were the
alternatives) because it is a distinct authorization gap with a clear,
self-contained fix, independent of the other five findings.

## S03-M2 — Tokens scope-bound, not uploader-bound

### The gap

`verifyMediaToken()` (worker/src/index.js) authorizes a request against
exactly one `(op, key, holder, expiresAt)` tuple, signed by the push server.
The push server (`POST /mediaToken` in `server/index.js`) only checks that
the calling uid **participates in the chat/group** the key's middle segment
names (`callerMayAccessScope()`) — it has no way to know, and never asks,
whether the caller is the object's original uploader.

Consequence: in a 1:1 chat or group, every participant can legitimately
obtain a correctly-signed `write` or `delete` token for **any** key in that
conversation, including keys another participant already uploaded to. Before
this fix, the Worker had no additional check once the token verified — so a
participant could:
- **overwrite** (PUT) another participant's media at the same key, silently
  swapping the bytes behind a URL/reference the other participant already
  sent, or
- **delete** (DELETE) another participant's media outright.

Both actions used a *genuine* token — correctly signed, correct op, correct
key, unexpired. This is not a token-forgery bug; it's a missing per-object
ownership check layered on top of the (correct) per-scope check.

The gap was already flagged in the code from the S3-11 session — the S03-H3
comments at the PUT/DELETE sites explicitly said "see S03-M2, out of scope
here" / "not that they are the original uploader" — this session closes what
those comments deferred.

### The fix (`worker/src/index.js`)

1. **PUT** — before writing, `HOT_BUCKET.head(key)` the existing object (if
   any). If it exists and its `customMetadata.uploader` (set at PUT time,
   already tracked for S03-H3's quota accounting) differs from the calling
   token's `holder`, reject with `403` before the write ever happens. If
   nothing exists at that key yet, there is no prior uploader to protect —
   the very first write to a key remains unrestricted, exactly as before.

2. **DELETE, R2 (hot) tier** — the handler already performed a `head()` on
   the key to read `customMetadata.uploader` for the S03-H3 quota-credit
   logic. This session adds the ownership check on that same read, before
   the delete executes: uploader mismatch → `403`, no delete, no quota
   credit.

3. **DELETE, B2 (cold) tier** — objects that have already tiered off R2 have
   no R2 metadata to read. Added a `HEAD` request to B2 (via the same
   `AwsClient` used for the DELETE) before the delete, reading the
   `x-amz-meta-uploader` response header. Mismatch → `403`, and the DELETE
   is never issued. A `404` on the HEAD (object doesn't exist anywhere) falls
   through unchanged to the existing DELETE-and-treat-404-as-success path.
   An object with **no** `x-amz-meta-uploader` header at all (any object
   migrated before this fix shipped) is treated as unprotected and left
   deletable by any holder with a valid scope-bound token — the alternative
   (denying everyone, forever) would make every pre-existing cold object
   permanently stuck.

4. **`scheduled()` — R2→B2 migration** — the nightly tiering cron now copies
   `customMetadata.uploader` from the R2 object into an `x-amz-meta-uploader`
   header on the B2 PUT, so the binding added in (3) actually has something
   to check for objects migrated after this fix ships. Existing already-cold
   objects predate this and fall into the "no tag → unprotected" case in (3)
   until they're re-touched.

GET (read) is deliberately **not** uploader-bound — every participant of a
conversation is expected to be able to read every message's media, uploader
or not. Uploader binding only applies to the destructive/mutating verbs.

### Evidence

- `node --check worker/src/index.js` — clean.
- `cd worker && npm test` (`node --test src/index.test.js`) — **17/17 pass**
  (9 pre-existing from S3-11 + 8 new for this fix):
  - a non-uploader's write token is rejected (403) on an existing R2 object;
    the object's bytes are unchanged afterward.
  - the genuine uploader can still overwrite their own object.
  - a brand-new key (nothing exists yet) can be written by any holder with a
    valid scope-bound token — confirms the fix does not regress normal
    first-time uploads.
  - a non-uploader's delete token is rejected (403) on an existing R2
    object; the object still exists afterward.
  - the genuine uploader can still delete their own R2-tier object.
  - a non-uploader's delete token is rejected (403) on a B2 (cold-tier)
    object (via a stubbed `globalThis.fetch` simulating the B2 HEAD
    response's `x-amz-meta-uploader` header) — and the DELETE call is
    asserted to never fire.
  - the genuine uploader can still delete their own B2-tier object — the
    DELETE call is asserted to fire.
  - a B2 object with no `x-amz-meta-uploader` header at all (simulating a
    pre-fix migration) remains deletable by any holder — confirms legacy
    objects are not left permanently stuck.

No server-side (`server/index.js`) changes were needed for this finding —
`signMediaToken()` already binds `holder` into the signed payload (added in
an earlier round for SEC-A01/rate-limiting purposes); the missing half was
purely the Worker never *reading* that binding as an ownership check on
write/delete. This session only touched `worker/src/index.js` and
`worker/src/index.test.js`.

### Deliberately not touched

- `S03-M1`, `S03-M3`, `S03-L2`, `S03-L3`/`S04-I2`, `S10-N3` — all still
  `Open`, queued for a future full S3-12 session. `S03-L3`/`S04-I2` in
  particular has a manual runbook step (B2 key revocation) that cannot be
  completed by code alone.
- The pre-existing, unrelated latent bug noticed while reading the PUT
  overwrite path: on a same-holder overwrite (the one case this fix leaves
  legal), the post-upload accounting at the bottom of the PUT handler adds
  `actualBytes` to both the R2 and per-user counters without first
  subtracting the *old* object's size — a genuine overwrite double-counts
  the old bytes forever. In practice the Android client always generates a
  fresh UUID filename per upload, so same-key overwrites are rare/never
  observed in the wild, but it is a real latent accounting gap. Flagging it
  here rather than fixing it silently in this session — it belongs to S03-H3
  (already marked Fixed in S3-11), not S03-M2, and touching it wasn't part
  of what this session was scoped to do. **Follow-up:** a future session
  should re-open S03-H3 to subtract the pre-existing object's size before
  crediting the new size on same-holder overwrite.

## Commits

- Implementation: `9b53f24` — `worker/src/index.js`, `worker/src/index.test.js`.
- Documentation: (this session's doc commit — see `git log` for the hash).

## Next remediation session

A full **S3-12** pass covering the remaining five findings (`S03-M1`,
`S03-M3`, `S03-L2`, `S03-L3`/`S04-I2`, `S10-N3`), or continue on to **S3-13**
(Admin surface, part 1) if S3-12's remainder is deferred — `START_HERE.md`
and `SESSION_INDEX.md` both point to finishing S3-12 next since it is only
partially done.
