# Session 10 — Synthesis, Regression & Final Report

_Final session of the DuoShield security audit. Scope frozen at commit `1c2fc03` on branch
`v0/dejel83126-6899-d9a568fe`._

**This session's own new findings: 0 Critical / 0 High / 1 Medium / 2 Low.**
**Audit total: 4 Critical / 28 High / 28 Medium / 34 Low / 23 Informational — 117 findings.**

> **Bottom line.** The cryptography is good and the engineering is careful. Neither of those is
> the problem. The problem is that **the private key that bypasses all of it ships inside every
> published APK** (S08-C1), and that **account authentication accepts a public value as proof of
> ownership** (S07-C1). Until those two are closed, the other 115 findings are secondary: an
> attacker does not need them. Sessions 01–06 audited controls that S08-C1 renders moot.

---

## 1. What this session did

Three jobs, per the plan in `AUDIT_PROGRESS.md`:

1. **Regression pass** — re-verified all 23 items of `docs/SECURITY_REVIEW_2026-08-04.md`
   against live source, since that document's status was carried as *"claimed fixed — re-verify"*
   and never confirmed (§4).
2. **Synthesis** — aggregated 114 findings from Sessions 01–09 into cross-cutting themes,
   reconciled the re-ratings sessions issued against each other, and measured the product's
   advertised guarantees against what the code actually enforces (§5, §6).
3. **Gap sweep** — checked the surfaces that fell between session scopes and therefore belonged
   to nobody. This produced 3 original findings (§3), one of which (S10-N3, Firebase App Check)
   is systemic and was not mentioned in any of the nine prior sessions.

Also corrected: `AUDIT_PROGRESS.md` was **stale** — it recorded Session 07 as `NEXT` even though
Sessions 07, 08 and 09 were complete on disk. Sessions 07–09 never had their rows or severity
counts recorded. Fixed as part of this session.

**Verification note.** Session 09's central empirical claim was independently re-confirmed here:
the vendored `libsignal-client-0.54.1-stripped.jar` still hashes to
`fa7d3afe9376ee83b0370bd16aff3083ea61a9ce131ee62773b48b35e6bd89e3`, matching SC-01's recorded
value byte-for-byte, and `ChatService.class`, `Svr3.class` and `GroupSendEndorsementsResponse.class`
are confirmed absent from the archive while `scripts/strip_signal_records.py` still lists only 6
strip entries. SC-01's reproducibility gap is real and unchanged.

---

## 2. Aggregate findings

| Session | Scope | C | H | M | L | I | Total |
|---|---|---|---|---|---|---|---|
| 01 | Firestore rules | 0 | 3 | 4 | 2 | 2 | 11 |
| 02 | Server auth core | 0 | 1 | 1 | 4 | 3 | 9 |
| 03 | Media pipeline | 0 | 3 | 3 | 4 | 3 | 13 |
| 04 | Server egress & limits | 0 | 3 | 3 | 4 | 3 | 13 |
| 05 | Admin surface | 0 | 3 | 3 | 4 | 3 | 13 |
| 06 | Duress & locks | 0 | 3 | 3 | 4 | 3 | 13 |
| 07 | Client crypto | 1 | 3 | 3 | 4 | 3 | 14 |
| 08 | Client platform | 1 | 5 | 3 | 4 | 3 | 16 |
| 09 | Supply chain & CI | 2 | 4 | 4 | 2 | 0 | 12 |
| 10 | Synthesis (new) | 0 | 0 | 1 | 2 | 0 | 3 |
| **Total** | | **4** | **28** | **28** | **34** | **23** | **117** |

**After applying the re-ratings sessions issued against each other** (§7): 4 Critical / 29 High /
27 Medium / 34 Low / 23 Informational.

### The four Criticals

| ID | Session | Finding |
|---|---|---|
| **S08-C1** | 08 | Firebase Admin service-account private key is written to `app/src/main/assets/` and packaged into every published release APK. Admin SDK credentials bypass Firestore rules entirely and can mint a token for any uid. |
| **S07-C1** | 07 | `/mintToken` authenticates an account using a value the client publishes publicly — account takeover without the seed phrase. |
| **SC-01** | 09 | The vendored libsignal JAR — the entire confidentiality guarantee — is not reproducible from the committed build script, has no recorded hash, and is not validated in CI. The current blob was verified clean; nothing can detect the next one. |
| **SC-02** | 09 | The release workflow bakes the full backend credential set (GCP service account, B2 key pair, `WORKER_SECRET`) into the shipped APK. |

S08-C1 and SC-02 are the same exposure seen from two ends — Session 08 found it in the packaged
artifact, Session 09 found it in the workflow that puts it there. They should be remediated as one
piece of work but are counted separately because they need different fixes (stop writing
`assets/`; stop injecting the secrets).

---

## 3. New findings from this session

Three surfaces fell between session boundaries.

### S10-N1 — Firebase App Check is absent entirely, so every Firestore rule is reachable by a scripted client

**Severity: Medium** · `app/build.gradle`, `app/src/main/**`, `firestore.rules` (absence)

Grepping the entire repository for `appcheck` / `app-check` / `App Check` returns **nothing**: no
`firebase-appcheck` dependency, no Play Integrity or SafetyNet provider registration, no
`enforceAppCheck` on any callable, and no mention in any of the nine prior session reports.

Firebase App Check is the control that makes Firestore and the Realtime Database reachable only
from an attested build of your app. Without it, `firestore.rules` is a **public HTTP API**: anyone
with an anonymous-to-them Firebase Web SDK, a project ID (shipped in `google-services.json`, which
is inside the APK by necessity) and one authenticated session can drive every rule directly from a
script, at machine speed, with no app involved.

The threat model in `README.md` correctly says a compromised client bypasses client-side checks, so
App Check is **not** a control of record and cannot be one — a rooted device passes attestation.
That is why this is Medium and not High. What it changes is *cost*, and several existing findings
are rated on the assumption that cost is non-trivial:

- **S03-H1** requires creating `groups/{id}` documents whose ID collides with a target chat. With
  App Check absent this is a `for` loop, not a reverse-engineering exercise.
- **S03-H2 / S03-H3** (exhaust the Worker's 90 K/day budget; fill the 9.5 GB R2 cap with ~19
  uploads) are volume attacks. App Check is the standard first line against exactly this.
- **S01-H1** (wipe another user's one-time prekeys) becomes trivially scriptable against every uid
  returned by the **S01-I1** global read oracle on `users`/`identities` — the two compose into
  automated, project-wide prekey denial.
- **S04-M1** (a single IPv6 /64 defeats every IP-keyed limit) removes the rate-limiting backstop
  that might otherwise bound the above.

**Recommendation:** add `firebase-appcheck-playintegrity`, register the provider before any
Firestore access, and turn on enforcement for Firestore, Storage and any callable — but roll it out
in monitoring mode first and read the metrics, because enforcement will break sideloaded installs
that are not Play-signed, and DuoShield is distributed as a sideloaded APK from GitHub Releases
(SC-04). That distribution model genuinely limits what App Check can deliver here, which is worth
recording as the reason if the team decides to accept this. Note that accepting it means accepting
the four amplifications above at their scripted cost, and S03-H1/S03-H2/S03-H3 should be
prioritized accordingly.

### S10-N2 — A peer's raw uid is written to release logcat from the Signal session store, violating the project's own stated log policy

**Severity: Low** · `app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java:307,320`

`app/proguard-rules.pro:149-154` strips `Log.v/d/i` from release builds via
`-assumenosideeffects`, which correctly resolves the prior review's item #18. The same block keeps
`Log.w` and `Log.e` deliberately, and states the rule that goes with that decision:

```
# When adding a warn/error log, do not interpolate a raw user
# id, phone number, message body or object key into it.
```

**Five** surviving `Log.w`/`Log.e` call sites in `DuoShieldSignalStore.java` break it:

```java
private static String toKey(SignalProtocolAddress address) {
    return address.getName() + "." + address.getDeviceId();   // :293 — name IS the peer uid
}
...
Log.w(TAG, "Identity key changed for " + address + " — storing new key (TOFU).");    // :133
Log.w(TAG, "Identity key changed for " + address.getName() ...);                     // :172
Log.e(TAG, "Failed to deserialise stored identity for " + address, e);               // :190
Log.e(TAG, "Session deserialisation failed for " + key + " — returning fresh.", e);  // :307
Log.e(TAG, "Failed to store session for " + key, e);                                 // :320
```

`key` is `<peer uid>.<deviceId>`, and `SignalProtocolAddress.toString()` likewise embeds
`getName()` — so `:133` and `:190`, which interpolate the address object directly, leak the same
uid as the explicit `getName()` at `:172`. All five are `Log.w`/`Log.e`, so all five survive R8's
`assumenosideeffects` (which strips only `v/d/i`) and run in release builds. Any app holding
`READ_LOGS`, any ADB-connected host, and any crash-report path that captures logcat therefore
receives the identity of a conversation partner — precisely the metadata the proguard comment says
"none of it belongs in a shipped build."

Worth noting *what* leaks at `:133`/`:172`: those fire on **identity-key change**, so the log does
not merely name a peer, it records that a peer's key rotated and when — the exact event a
key-substitution attack (`S01-H2`) produces. The `Log.d` sites in the same file (`:247`, `:285`,
`:439`) are correctly stripped and carry only key IDs.

Every other kept `Log.e` in the codebase that touches a uid routes it through `LogRedact.uid()`
(e.g. `SignalKeyManager.java:931`), so the helper and the convention both already exist; this one
file misses them.

Severity is Low because it needs local access and leaks metadata rather than content. It compounds
**S06-H2** (plaintext `account_lock_<uid>` WorkManager records defeating duress deniability) — both
are on-device residue that proves who the user was talking to — and note that line 307 is the same
call site as **S07-L4** (`loadSession` silently substituting a fresh session), so one edit fixes
both the silent-substitution log and the leak.

**Recommendation:** route all five through `LogRedact.uid(...)` — for `:133` and `:190`, log
`LogRedact.uid(address.getName()) + "." + address.getDeviceId()` rather than the address object,
since passing the object is what makes the leak easy to miss on review. Then add a CI grep
asserting that no `Log.w`/`Log.e` interpolates a bare uid, a bare `key`, or a
`SignalProtocolAddress`, so the policy in the proguard file is enforced rather than merely written
down. Without that grep this recurs: the policy comment predates these five sites.

### S10-N3 — Media deleted by a user can survive forever in the B2 cold tier when the delete races the nightly migration

**Severity: Low** · `worker/src/index.js:530-548` (DELETE), `:646-665` (scheduled migration guard)

Prior review item #4 (Worker tiering not concurrency-safe) is now **substantially** fixed, and the
fix is good: the migration re-`HEAD`s R2 at `:654` and compares `httpEtag` against the value read
before the B2 PUT, deleting from R2 only when unchanged (`:655-657`), and the client DELETE fires an
unconditional best-effort B2 delete at `:546-548` alongside its R2 delete specifically to catch the
both-tiers window. Both race guards are deliberate and documented in comments that name the exact
interleaving each defends against. This finding is *not* a claim that those guards are wrong.

One ordering survives. The migration's steps are `get R2 → PUT B2 → HEAD R2 → delete R2`. A client
DELETE arriving after the migration's `get` but before its `PUT` sees the object still present in
R2, takes the R2 branch, deletes from R2, and fires its B2 delete — which finds nothing and 404s
harmlessly. The migration's `PUT` then lands, **recreating the object in B2**. The migration's
subsequent `HEAD` at `:654` finds R2 empty and falls to the `else` branch at `:663` ("deleted
concurrently — nothing left in R2, nothing to count"), which is correct about the byte counter but
never revisits the B2 copy it just wrote. The object is now in cold storage, unreferenced, and no
later cron run will look at it again: Step 1 iterates `HOT_BUCKET`, and Step 3 only totals B2 bytes
for the counter.

The DELETE handler's B2 cleanup cannot help here, precisely because it is correct: it fires
*before* the migration's PUT, so there is nothing yet to delete. The two guards are individually
sound and each closes the window the other opens — except in this one ordering, where the DELETE's
compensating write happens too early and the migration's happens not at all.

This is also observable, not merely theoretical residue: `GET` checks R2 first and falls back to B2
at `:505`, so a request for the deleted key **still serves the media** from cold tier with
`X-Storage-Tier: cold`.

Consequence: ciphertext the user asked to delete persists indefinitely and remains retrievable.

**Why this stays Low rather than Medium**, despite content the user deleted still being served: an
attacker cannot *force* the interleaving. The window is one B2 PUT's duration, on the single night
an object crosses the 30-day boundary, and hitting it requires the victim to delete in that exact
window. Retrieval afterwards still requires both the object key and a valid media token, so this
does not widen who can reach the data — it only means one specific object was not actually deleted.
It is a durability/promise failure, not an access-control failure.

It is still worth fixing: "delete for everyone" is a user-facing promise in a product built on
metadata resistance, and the residue is **silent** — nothing logs it, and the Step 3 reconciliation
at `:679` will count the orphan as legitimate usage, so it quietly consumes the B2 budget tracked
against **S03-H3** while making the leak invisible to the very metric that would reveal it.

**Recommendation:** one line, in the `else` branch at `:663`. It has already correctly *detected*
the concurrent delete — it just needs to act on it:

```js
} else {
  // Deleted concurrently. We already wrote the B2 copy above, and the
  // client's DELETE ran before that PUT, so its B2 cleanup was a no-op.
  // Undo our own write, or the object survives deletion in cold tier.
  ctx.waitUntil(b2.fetch(b2Url(env, obj.key), { method: 'DELETE' }).catch(() => {}));
}
```

Cost is one subrequest on a branch that is rare by construction. The alternative — re-`HEAD`ing
before the PUT as well as after — narrows the window but does not close it, since the object can
always be deleted between that check and the PUT. Compensating after the fact is the correct shape
here because the migration is the party that created the orphan and is the only party that knows it
did.

---

## 4. Regression pass — `docs/SECURITY_REVIEW_2026-08-04.md`

All 23 items re-verified against live source. **8 genuinely fixed, 4 partially fixed, 11 still
open.** The prior review's own Critical is fixed; two of its four Highs are not.

| # | Prior item | Status now | Evidence / successor finding |
|---|---|---|---|
| 1 | **C:** Worker fails open with no `WORKER_SECRET` | ✅ **Fixed** | `worker/src/index.js:76-95` returns `false`; `:147-148` fails closed on missing `MEDIA_TOKEN_SECRET` with an explicit log. Confirmed in S03. |
| 2 | **H:** No per-object authz, one shared secret in every APK | ⚠️ **Partial** | Per-object capability tokens are real and the crypto is correct (S03), but the token's *authorization input* is forgeable → **S03-H1**. And the old secret is still compiled in and still accepted on `/stats` → **S03-L1**, **S08-H1**. |
| 3 | **H:** Legacy `/rooms` + `/conversations` hijacking | ✅ **Fixed** | `firestore.rules:231-247` — both collections and the `messages` subcollection are now `allow read, write: if false`, with the reasoning recorded inline. Denied outright rather than patched, which is the stronger choice. |
| 4 | **H:** R2→B2 tiering / upload-delete races | ⚠️ **Partial** | Etag-guarded migration delete + compensating B2 delete on the client path. One ordering remains → **S10-N3**. |
| 5 | **H:** `/linkPreview` SSRF guard misses redirects | ⚠️ **Partial** | Redirect revalidation is correct and fails closed (S04). The *predicate* never resolves DNS and misses most of IPv6 → **S04-H1**. |
| 6 | **M:** `/mintToken` cooldown read outside the transaction | ⚠️ **Partial** | Now set *before* minting (`server/index.js:1463,1469`, txn at `:1483`), which fixes the double-mint. But setting it pre-authentication created a targeted re-auth DoS → **S02-M1**. Fixed one way, broke another. |
| 7 | **M:** Routes bypass the `MAX_BODY_BYTES` reader | ⚠️ **Partial** | Routes now use the shared bounded reader, but it measures string length, not bytes → ~192 KB admitted against a 64 KB cap → **S02-L4**, **S04-L1**. |
| 8 | **M:** `/duress-lock` nonce check/delete non-transactional | ✅ **Fixed** | Verify + lock + delete are one transaction; S06 confirmed the consume-and-lock is atomic and the latch is one-way. Among the best-built things in the codebase. |
| 9 | **M:** R2 cap counter non-atomic | 🔻 **Open, re-rated up** | Still advisory by design (**S03-I2**), and S03 showed ~19 uploads from one account fill the global cap → **S03-H3** (High). |
| 10 | **M:** `identities` readable by any authed user | 🔻 **Open — product decision still not made** | `firestore.rules:253` unchanged → **S01-I1**. Still awaiting the explicit decision the prior review asked for; two audits have now flagged it. Composes with S10-N1. |
| 11 | **M:** No field-level schema validation | 🔻 **Open** | **S01-M2**, **S01-L2**, **S07-M3** (backup metadata outside the AEAD), **S01-M3**. |
| 12 | **M:** Client IPs logged / `X-Forwarded-For` trusted | 🔻 **Open** | Raw operator IPs and raw uids now *persisted to Firestore forever* → **S05-M1**; XFF trust hard-coded to one proxy → **S04-M3**. Broader than when first reported. |
| 13 | **M:** `"Server error: " + e.message` leaks internals | ✅ **Fixed** | `server/index.js:679` documents the SEC-L02 fix. Remaining `e.message` uses are in the admin panel's client-side JS and one server-side `console.warn` — both correct. |
| 14 | **M:** SQLCipher key persisted with `apply()`, unsynchronized | ✅ **Fixed** | `DatabaseKeyProvider.java` now uses `commit()` with read-back before returning, and raises `KeyUnavailableException` rather than minting a fresh key when a database exists. The durability contract is documented in the class Javadoc. Genuinely well done. |
| 15 | **M:** `SecurePrefs` silently falls back to plaintext | 🔻 **Open, re-rated up** | Unchanged, and worse than described: the fallback holds the identity key, the backup key **and** the SQLCipher passphrase → **S07-M1** → re-rated **S08-H5** (High). Also the reason **S06-I3**'s deniability question answers "no". |
| 16 | **M:** Safety-number banner dismissal only hides for a session | ✅ **Fixed** | The F23 fix: `ChatMediaActivity.java:2317` explicitly does *not* clear the flag on dismiss; `KeyFingerprintActivity.java:188-192` clears `safety_num_changed_<uid>` only after a successful QR match. Exactly the hardening suggested. |
| 17 | **L:** Deep link copies its path in unvalidated | 🔻 **Open** | **S08-L1** — and the clipboard path *does* validate, so the exported surface is the weaker of the two. |
| 18 | **L:** Firebase UIDs written to release logcat | ✅ **Fixed** (with a residue) | `proguard-rules.pro:149-154` strips `Log.v/d/i` in release, removing every cited site. Two kept `Log.e` sites still leak a peer uid → **S10-N2**. |
| 19 | **L:** FCM body rendered verbatim by the client | 🔻 **Open** | `DuoShieldMessagingService.java:105-119` still displays `data.body` / `senderName` with no allowlist. Server-side `notificationBody()` is still safe (`server/index.js:92`), so no live leak — the defense-in-depth gap is unchanged. |
| 20 | **L:** Worker CORS wildcard | 🔻 **Open, by decision** | Unchanged → **S08-I3**, which notes it now coexists with an allowed `Authorization` header. |
| 21 | **L:** Prekey upload failures not durably retried | 🔻 **Open** | `SignalPreKeyRefresher.java:144` — still "will retry on next replenishment cycle", no durable queue. Denial, not compromise, as before. |
| 22 | **L:** `security-crypto:1.1.0-alpha06` | 🔻 **Open** | Unchanged → **SC-11**, now compounded by the absence of hash pinning (**SC-03**). |
| 23 | **L:** `brace-expansion` DoS advisories | ✅ **Fixed** | `firestore-tests/package-lock.json:1913` resolves `brace-expansion@1.1.16`, above the patched threshold for CVE-2026-14257 / CVE-2026-69152. Dev-only regardless. |

**Reading of the regression pass.** Where the prior review named a *specific, local* defect, it was
fixed, and fixed properly — items 1, 3, 8, 13, 14, 16 are all clean, several with the reasoning
recorded inline for the next reviewer. Where it named a *class* of defect ("no field validation",
"IPs are logged", "storage falls back to plaintext"), the specific instance was addressed and the
class was not, so the finding reappeared this audit with a larger blast radius (items 11, 12, 15).
Item 6 is the one to learn from: the fix was correct in isolation and introduced a new denial
vector, which is what happens when a check moves without re-deriving what the check was ordering
against.

Also worth stating plainly: **two of the prior review's "Verified solid (no finding)" entries were
wrong.** It verified that `/mintToken` "verif[ies] Firebase ID tokens and enforce[s] UID/creator
checks server-side" — S07-C1 is a Critical in exactly that code. And it verified the admin panel's
"state-changing actions gated by the admin token" — true, but S05-H1 shows that token has no
entropy floor, no rotation, no expiry and no working brute-force ceiling. A clean bill on a surface
is only as good as the question that was asked of it.

---

## 5. Cross-cutting themes

The 117 findings are not 117 independent mistakes. They are roughly eight recurring patterns. Fixing
the pattern is cheaper than fixing the instances, and the instances will otherwise regrow.

### Theme A — Enforcement in the wrong place: features whose control lives on the client
**The single most common root cause in this audit, and the one that produces the highest severities.**

`S07-C1` (authentication accepts a published value) · `S06-H1` (`accountLock` never checked by
`/mintToken` — the lock runs *after* the session is minted) · `S06-H3` (offline duress wipes
locally but never locks, and the attacker controls connectivity) · `S05-M2` + `S06-M1`
(`duressEligibility` enforced nowhere; the admin panel's enroll/revoke is cosmetic) · `S05-M3`
(admin inactivity timeout is client-side only) · `S03-H1` (membership self-asserted in a
client-writable document).

Every one of these ships a UI that tells the user a guarantee is in force, backed by a check the
attacker executes. The README's own rule — *"the client validates X" is never a control* — is
stated correctly and then violated by six shipped features. **This theme alone accounts for
2 of the 4 advertised guarantees being broken (§6).**

### Theme B — Identity asserted rather than proven
`S07-C1` · `S03-H1` (create `groups/{id}` with a target chat's ID, self-assert membership) ·
`S07-H3` (group messages have no AAD; sender attribution is rules-only) · `S01-H1` (any account
can rewrite another user's one-time prekeys) · `S01-L1` (`createdBy` unvalidated) · `S02-H1`
(`migrateUid` copies attacker-controlled fields onto the new uid).

The system repeatedly derives authorization from a *document field* instead of from a *key
operation*. Documents are client-writable; keys are not. S03-H1 and S07-H3 are the same bug at two
layers — the media tier and the message tier both trust the `groups` collection as an identity
source.

### Theme C — Fail-open error handling
`S07-H1` + `S02-L1` (key check passes when the stored hash is absent) · `S08-H5` + `S07-M1`
(`SecurePrefs` degrades to plaintext and `isInitialized()` deliberately ignores it) · `S07-L1`
(creator check passes on a null cached `creatorUid`) · `S06-L1` (nonce expiry passes on a malformed
`expiresAt`) · `S07-L4` (`loadSession` substitutes a fresh session on deserialization failure) ·
`S06-L4` (`AccountLockWorker` reports failure as success).

Notably, the two places the codebase *does* fail closed — the Worker's missing-secret check and the
duress-lock transaction — are the two places a previous review told it to. Fail-closed appears to be
applied on request rather than by default.

### Theme D — Secrets in publicly distributed artifacts
`S08-C1` (Admin service-account private key in `assets/`) · `SC-02` (the entire backend credential
set injected at build time) · `S03-L1` + `S08-H1` (`WORKER_SECRET` still in `BuildConfig` and still
accepted server-side) · `S08-L3` (PIN length stored beside the PIN hash) · `S08-I1` (R8 keeps every
`crypto.**` member name, easing extraction).

`assets/` is the important detail: R8 and `shrinkResources` never touch it, so the one mitigation
the build does apply cannot help. And because releases carry no checksums and the tag rolls
(`SC-04`, `SC-05`), **the team cannot determine which credential generation is in which
user's hands** — which makes the rotation this requires hard to even scope.

### Theme E — Advisory limits presented as quotas; no durable state
`S03-H2` (one account exhausts the Worker's global 90 K/day budget) · `S03-H3` (~19 uploads fill
the 9.5 GB global cap) · `S03-I2` (all Worker accounting advisory by design) · `S04-M1` (a single
IPv6 /64 defeats every IP-keyed limit, including the admin lockout) · `S04-L3` + `S02-L3`
(all limiter state per-process, in-memory, never purged, lost on restart, not shared across
instances) · `S06-M2` (`_duressNonces` grows without bound) · `S05-L4` (unbounded `.get()` queries)
· `S10-N1` (no App Check, so abuse is scriptable at machine speed).

Every limit in the system is *per-process and best-effort*. On a platform that scales out, this
means the effective limit is `configured_limit × instance_count`, and after any deploy it is
`configured_limit` again from zero. The rate limits are real code and they document themselves as
best-effort; the problem is that findings elsewhere (S05-H1's brute-force ceiling, S04-M1's
lockout) are rated as though they were durable.

### Theme F — On-device and in-log residue defeating metadata resistance
`S06-H2` (plaintext `account_lock_<uid>` WorkManager records prove a duress code was entered) ·
`S08-H2` (`FLAG_SECURE` actively *cleared*, so the OS persists snapshots of plaintext chats) ·
`S08-H3` (150 MB plaintext Glide disk cache plus four unswept prefixes, indefinitely) ·
`S07-L2` (static `derivationCache` retains the identity key pair across a duress wipe) ·
`S08-L2` (clipboard writes without `EXTRA_IS_SENSITIVE`) · `S08-L4` (lock screen layered over an
already-rendered activity, neither excluded from recents) · `S05-M1` + `S06-M3` (raw uids and
operator IPs persisted and logged) · `S10-N2` (peer uid in release logcat).

This is the theme that decides whether the duress feature means anything. The subsystem's
internals are genuinely well built (S06 says so) and its guarantee still fails, because the wipe
does not sweep what the rest of the app left behind.

### Theme G — Server and client as confused deputies for outbound fetches
`S04-H1` (SSRF predicate never resolves DNS) · `S04-H3` + `S08-H4` (link-preview `og:image` fetched
directly by *both* devices, handing any sender the recipient's IP address and read timestamp) ·
`S04-M3` (XFF trust hard-coded to one proxy — putting any CDN in front collapses every client into
one rate-limit bucket) · `S04-M2` (24-hour redistributable TURN credentials, no aggregate cap) ·
`S08-I2` (no certificate pinning).

S04-H3/S08-H4 is the one to note: it is an IP-disclosure primitive in a product whose value
proposition is metadata resistance, it requires no compromise of anything, and it was found
independently from both the server side and the client side.

### Theme H — An unverifiable build
`SC-01` (vendored crypto JAR not reproducible, no recorded hash, not validated in CI) · `SC-03`
(no dependency verification — ~30 Maven coordinates unpinned by hash) · `SC-04` (no checksums, no
signature record, no provenance on releases) · `SC-05` (every release and tag deleted on each push
to `main`) · `SC-06` (JitPack builds from mutable Git refs, inside the process that renders
decrypted media) · `SC-07` (committed `gradle-wrapper.jar`, unvalidated) · `SC-08` (actions on
mutable tags) · `SC-09` (no scanning of any kind) · `SC-10` (rules deployed by an unpinned
`firebase-tools`).

The audit verified the *source*. This theme is why that verification has a shelf life: nothing in
the pipeline can detect a substituted binary, and `SC-05` deletes the record of which binary
shipped when.

### How the themes compose
The two Criticals are not isolated either. **S08-C1 (Admin key in the APK) invalidates Sessions
01–06 wholesale** — Admin SDK credentials bypass Firestore rules, so every rule finding in Session
01, every server authorization finding in Sessions 02–05, and the duress latch in Session 06 are
all reachable around rather than through the controls those sessions assessed. **S07-C1** is the
same conclusion by a cheaper route that needs no APK at all. Both must be closed before any other
finding's severity is meaningful.

---

## 6. Advertised guarantees vs. what the code enforces

Measured against what the product tells users, the README, and the code comments.

| Guarantee | Verdict | Why |
|---|---|---|
| "The server only ever sees ciphertext and metadata" | ❌ **Broken** | `S08-C1` — the Admin private key ships in the APK; Admin SDK bypasses rules and can mint a token for any uid. `SC-02` compounds it. |
| Only the seed-phrase holder can access an account | ❌ **Broken** | `S07-C1` — a public value is accepted as proof of ownership; takeover needs only a target's Account ID. `S07-H1` provides a second route when `identityPubKeyHash` is absent. |
| A duress code wipes the device **and locks the account** | ❌ **Broken** | `S06-H1` (lock never enforced server-side) and `S06-H3` (offline trigger never locks, attacker controls connectivity). Two independent defeats. |
| Duress use is plausibly deniable | ❌ **Broken** | `S06-H2` (plaintext `account_lock_<uid>` WorkManager records) and `S08-H5`/`S07-M1` (`SecurePrefs` may be plaintext, answering `S06-I3` with "no"). |
| Media is private to its conversation | ❌ **Broken** | `S03-H1` — capability-token scope is forgeable via a self-created `groups/{id}`. This is why SEC-A01 is *partially* remediated. |
| Message content is E2E encrypted between honest peers | ✅ **Holds at the protocol layer** | Session 07 confirmed HKDF domain separation, BIP39 parameters, prekey signing, per-address session locking, TOFU integrity, and group-key rotation on member removal. The primitives are right. Undermined only by attackers *becoming* the peer (S07-C1, S08-C1), not by the crypto. |
| Metadata resistance | ⚠️ **Weak** | `S04-H3`/`S08-H4` hand any sender the recipient's IP and read timestamp; `S05-M1` retains raw uids and operator IPs in Firestore permanently; `S10-N2`, `S06-M3` leak uids to logs. |
| Access is invite-only and manually approved | ⚠️ **Unreviewable by construction** | `S05-H2` — the queue carries no information about the requester, and there is no deny, expire or revoke path. |
| Admin actions leave a tamper-evident record | ❌ **False as documented** | `S05-H3` — actions are not durably audited and admin *authentication* is not audited at all. |
| Screenshot / recents protection | ⚠️ **Out of scope by product decision — but** | The prior review recorded this as an accepted decision. `S08-H2` shows `FLAG_SECURE` is not merely absent but *actively cleared on every activity*, and `S08-L4` adds the lock screen to recents. Worth re-confirming the decision covers that. |

**Six of ten broken, three weak, one holding.** The one that holds is the hardest one to get right,
which is genuinely to the team's credit. What fails is almost never the cryptography — it is the
authorization and enforcement placed around it (Themes A and B).

---

## 7. Reconciling the cross-session re-ratings

Sessions issued four re-ratings against each other. Adjudicated for the final numbers:

| Item | Original | Final | Reasoning |
|---|---|---|---|
| `S04-M1` (IPv6 /64 defeats IP-keyed limits) | Medium | **High** | Session 04 rated it Medium *conditional on `ADMIN_TOKEN` being high-entropy*. Session 05 (`S05-H1`, `S05-I1`) found nothing validates, enforces or documents its entropy, and there is no global failure counter and no logging of admin auth failures at all. The condition fails, so the rating moves. **Accepted.** |
| `S07-M1` (`SecurePrefs` plaintext fallback) | Medium | **High as `S08-H5`** | Session 07 scored it on key metadata. Session 08 established the fallback also holds the SQLCipher passphrase — i.e. the whole message database, not just key material. **Accepted; counted once, at High.** |
| `S03-H3` / prior #9 (R2 cap non-atomic) | Medium (prior review) | **High** | Prior review called it a soft-cap concurrency concern. S03 showed ~19 uploads from one account deny uploads platform-wide. **Accepted.** |
| `SEC-A01` (per-object capability tokens) | "Fixed" | **Partially remediated** | Cryptography and fail-closed posture confirmed correct; the shared data-plane secret is genuinely gone from the app's runtime path. But the token's authorization *input* is forgeable (`S03-H1`), and the secret still exists in `BuildConfig` and is still accepted on `/stats` (`S03-L1`, `S08-H1`). **Do not close.** |

One correction to a Session 06 note: it grouped `S06-H1` with the S01/S03 `groups` gap as "the same
enforcement-location bug class." That is right, and §5 keeps them together under Theme A — but
`S06-H1` is specifically an *ordering* failure (the lock is checked after the session is minted),
while `S03-H1` is an *input-trust* failure (the authorization source is client-writable). They share
a theme and need different fixes. Session 07's note that fixing `S06-H1` is "necessary but not
sufficient" without also fixing `S07-C1` is correct and should drive them into one change.

---

## 8. Remediation roadmap

Ordered by what actually reduces risk, not by severity label.

### P0 — before the next release. Nothing else matters until these are done.

1. **Revoke and rotate every credential that has ever been in a published APK.** The GCP
   service-account key (delete the key, do not just rotate — and audit its Cloud Audit Logs for
   use), the B2 key pair, and `WORKER_SECRET`. `S08-C1`, `SC-02`. Assume disclosure; the artifacts
   are public.
2. **Stop writing secrets into the client build.** Remove the `assets/` service-account write and
   the B2 keys from `release.yml` entirely. Media access belongs behind short-lived per-object
   presigned URLs the Worker issues after authenticating the user. `S08-C1`, `SC-02`.
3. **Add a CI gate that greps the built APK for each high-entropy secret and fails the release.**
   Without this, #1 and #2 regress silently. `SC-02`.
4. **Fix `/mintToken` in one change:** require an actual proof of seed possession (a signature over
   a server challenge, not a published value), check `accountLock` *before* minting, and close the
   fail-open path when `identityPubKeyHash` is absent. `S07-C1` + `S06-H1` + `S07-H1`/`S02-L1`.
   These are one code path and must not be fixed separately.
5. **Confirm branch protection on `main`** requires PRs, code-owner review and passing checks, and
   blocks force-push. `SC-12`. Cheap, and `SC-01`/`SC-05` mitigations assume it.

### P1 — the guarantees the product advertises

6. **Bind media capability tokens to a membership source the client cannot write.** Closes `S03-H1`
   and finally resolves SEC-A01. Same change should stop `/stats` accepting `WORKER_SECRET` and drop
   it from `BuildConfig` (`S08-H1`, `S03-L1`).
7. **Make duress mean what it says:** enforce the lock server-side (done in #4), queue the lock
   durably for offline triggers (`S06-H3`), and sweep the WorkManager residue (`S06-H2`).
8. **Make `SecurePrefs` fail closed.** It currently holds the identity key, backup key and SQLCipher
   passphrase in plaintext when Keystore init fails. `S08-H5`. This also decides `S06-I3`.
9. **Resolve DNS in the SSRF predicate and cover IPv6.** `S04-H1`.
10. **Proxy link-preview images through the server** instead of having both devices fetch the
    sender's chosen host. `S04-H3`, `S08-H4`.
11. **Admin surface:** enforce an `ADMIN_TOKEN` entropy floor at startup, log auth failures, and
    make the audit log durable. `S05-H1`, `S05-H3`, `S05-I1`.
12. **Client-side plaintext residue:** set `FLAG_SECURE`, bound and sweep the media cache.
    `S08-H2`, `S08-H3`. (Re-confirm the `FLAG_SECURE` product decision first — see §6.)
13. **Make the build verifiable:** stop deleting releases and tags (`SC-05` — a one-line deletion),
    publish `SHA256SUMS` and the signing-certificate fingerprint (`SC-04`), make the vendored JAR
    reproducible and hash-assert it in CI (`SC-01`).

### P2 — batch

14. Rules hardening: `S01-H1` (cross-user prekey writes), `S01-H2`/`S01-H3` (message and chat field
    protection), `S01-M4`, `S01-M1`, field-schema validation across the board (prior #11).
15. `S02-H1` (`migrateUid` verbatim field copy), `S07-H2` (backup checksum oracle), `S07-H3` (group
    message AAD), `S07-M2` (trust keyed on mutable uid).
16. Quotas and limits: `S03-H2`, `S03-H3`, `S04-M1`, durable limiter state (`S04-L3`, `S02-L3`),
    `S06-M2`.
17. `S05-H2` (waitlist reviewability — needs a product decision, not just code).
18. Supply chain: `SC-03`, `SC-06`, `SC-07`, `SC-08`, `SC-09`, `SC-10`.
19. Decide `S01-I1` explicitly (two audits have now asked). Consider `S10-N1` App Check with the
    sideloading caveat in mind. Then the Lows.

---

## 9. What this audit could not determine

Stated so the next reviewer does not mistake silence for a clean result:

- **No dynamic testing.** Every finding is from source reading, with two exceptions: Session 09's
  JAR diff and this session's hash re-verification. No exploit was executed against live
  infrastructure, and the `firestore-tests/` suite was **not run** in any session — including
  Session 01, which reviewed the rules it tests.
- **Repository configuration is invisible from the tree.** Branch protection (`SC-12`), GitHub
  Actions secret scoping, and environment protection rules could not be verified.
- **Firebase and Cloudflare console configuration is out of band.** App Check enforcement state
  (`S10-N1`), the actual IAM scope of the leaked service account, the actual B2 key permissions,
  Firestore backup/PITR settings, and the Worker's deployed secret set were all inferred from code.
  **The real severity of `S08-C1` depends on the service account's IAM roles**, which must be
  checked in the console.
- **Deployed-vs-committed drift.** The Firestore rules, Worker and server reviewed here are the
  committed versions. Nothing confirms production matches — and `SC-05`/`SC-10` mean there is no
  reliable record of what was deployed when.
- **The vendored JAR was verified once, at one commit.** `SC-01` exists precisely because that
  verification does not carry forward.

---

## 10. What is genuinely good

An audit that lists 117 findings will read as a condemnation, and that would be the wrong
conclusion to draw from it.

- **The cryptographic core is correct.** HKDF with proper domain separation, BIP39 parameters to
  spec, no raw `copyOf` key derivation, 12-byte `SecureRandom` IVs with 128-bit tags and no reuse,
  PBKDF2-HMAC-SHA256 at 310,000 iterations with per-record salts and constant-time comparison,
  prekeys signed and verified, per-address session locking preventing ratchet clobbering, correct
  TOFU with no app-code path that clears the trust store, group keys rotated on member removal, and
  a rules-enforced creator-only group-key slot. Session 07 went looking for crypto bugs and mostly
  found correct crypto. **This is the hard part, and it is right.**
- **The duress subsystem's internals are well engineered:** a uid-bound single-use nonce, an atomic
  consume-and-lock transaction, a correct one-way latch in the rules, and deliberate timing parity
  on the restore gate. Its guarantees fail for reasons outside itself.
- **Several prior-review fixes are exemplary, not minimal.** The legacy-rules fix denied the
  collections outright instead of patching field rules. `DatabaseKeyProvider` gained a documented
  durability contract and a recoverable-error type rather than a `commit()` swap. The F23
  safety-number fix moved the flag clear to the point of actual verification. Each records *why* in
  the code, which is what made this audit's regression pass possible at all.
- **Fail-closed where it was implemented is implemented properly:** the Worker denies on a missing
  secret with an explicit log, and the capability-token verification has no bypass.
- **The keystore handling in `release.yml`** fails loudly on a missing `KEYSTORE_BASE64` rather than
  silently shipping unsigned, and wipes the decoded keystore with `if: always()`.
- **Log hygiene is a real, documented policy** with a `LogRedact` helper and an R8 rule that strips
  `Log.v/d/i` from release builds. `S10-N2` is two call sites missing the helper, not an absent
  practice.
- **`firestore-rules-test.yml` exists at all.** Testing security rules is a step most projects skip.
- **The code explains its own security reasoning.** `scripts/strip_signal_records.py`, the
  three-line libsignal arrangement in `app/build.gradle`, the legacy-rules denial comments, the
  Worker's race-guard comments, and the SEC-* markers throughout made a ten-session audit tractable.
  That documentation is why findings like `S10-N2` and `S10-N3` are precise rather than speculative
  — in both cases the code stated the intended invariant and the deviation was visible against it.

The pattern across all ten sessions: **DuoShield builds mechanisms carefully and places them
incorrectly.** The nonce is atomic but nothing checks the lock. The capability token is
cryptographically sound but its input is forgeable. The Signal integration is correct but a public
value mints the session. The JAR is clean but unverifiable. Theme A and Theme B are one problem
stated twice, and it is a *design-review* problem rather than a coding problem — which is more
tractable than 117 separate bugs, and is where remediation effort will pay back the most.

---

## 11. Audit complete

All ten sessions are done. `AUDIT_PROGRESS.md` reflects the final state.

**Recommended next steps, in order:** (1) work P0 — items 1–5 are the whole story; (2) re-run the
regression method in §4 against the P0 fixes rather than trusting commit titles, which is the lesson
of prior items 6, 11, 12 and 15; (3) obtain the console-side facts listed in §9 before finalizing
`S08-C1`'s severity; (4) run `firestore-tests/` and treat any gap between it and Session 01 as a
finding in its own right.
