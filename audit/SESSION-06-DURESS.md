# Session 06 — Duress & Account Locks

_Audit session 06 of the DuoShield security review. Scope frozen at the code state on branch
`course-curriculum-management`._

**Severity counts: 0 Critical / 3 High / 3 Medium / 4 Low / 3 Informational**

---

## 1. Scope

The duress-PIN ("plausible deniability") subsystem end to end, plus the `accountLock` latch it
drives:

| Surface | Location |
|---|---|
| Duress PIN storage + verification | `app/src/main/java/com/duoshield/app/security/DuressManager.java:56-114` |
| Duress trigger | `app/src/main/java/com/duoshield/app/LockScreenActivity.java:184-196` |
| "Sync then wipe" sequence | `DuressManager.java:154-325` |
| Nonce issuance | `server/index.js:2362-2408` (`POST /requestLockNonce`) |
| Nonce consumption + lock write | `server/index.js:2422-2486` (`POST /duress-lock`) |
| WorkManager retry fallback | `app/src/main/java/com/duoshield/app/security/AccountLockWorker.java` |
| Lock enforcement (the only one) | `app/src/main/java/com/duoshield/app/ui/RestoreFromSeedActivity.java:252-268` |
| Eligibility gate | `DuressManager.java:341-377`, `ui/ManageUnlockCodesActivity.java:83` |
| Rules | `firestore.rules:321-324` (`duressEligibility`), `:341-354` (`accountLock`), `:360-362` (`_duressNonces`) |
| Rules tests | `firestore-tests/rules.test.js:914-1027` |
| Operator unlock | `server/index.js:2676-2719` (`POST /admin/api/locked/unfreeze`) |
| Design intent | `docs/DURESS_PIN_SECURITY_PLAN.md` |

**Threat model applied.** The adversary coerces the user into unlocking the device, then keeps
the device and (in the strong case) also coerces the 12-word seed phrase. The feature's two
advertised guarantees are (a) **the account is locked** so a coerced seed cannot be used to
restore the data elsewhere, and (b) **plausible deniability** — the device presents as
unconfigured and nothing on it reveals that a duress code was entered. Both are evaluated
against an adversary who can read device storage, patch the APK, replay HTTP, and control
network reachability.

---

## 2. Verdict

| Guarantee | Status |
|---|---|
| Nonce is uid-bound, single-use, atomically consumed | ✅ **Correct** — verified, see §4 |
| `accountLock` cannot be cleared or flipped to `false` by a client | ✅ **Correct** — rules + tests |
| `accountLock` cannot be read/written cross-account | ✅ **Correct** — rules + tests |
| `_duressNonces` / `waitlist` unreachable from clients | ✅ **Correct** |
| **The lock actually prevents a restore** | ❌ **NO** — client-side only, post-auth (S06-H1) |
| **The lock is set when the attacker controls the network** | ❌ **NO** — silent no-op offline (S06-H3) |
| **The wipe leaves no evidence a duress code was entered** | ❌ **NO** — WorkManager residue (S06-H2) |
| Eligibility is a server-enforced boundary | ❌ **NO** — cached client bool only (S06-M1) |

The cryptographic and transactional plumbing built in the previous hardening pass is sound. The
**enforcement** of what that plumbing produces is not: the lock is an advisory client-side check,
and the two guarantees the feature exists to provide can each be defeated without touching the
server.

---

## 3. Findings

### S06-H1 — `accountLock` is never enforced server-side; the restore gate is a client-side check that runs *after* authentication

**Severity: High** · **Location:** `RestoreFromSeedActivity.java:252-268`, `server/index.js:1436-1546`

`accountLock` is consulted in exactly one place in the entire codebase:

```
$ grep -rn "accountLock" app/src/main/java | grep -v /security/
RestoreFromSeedActivity.java:256:   db.collection("accountLock").document(derivedUserId).get();
RestoreFromSeedActivity.java:260:   boolean accountLocked = lockDoc.exists() && ...getBoolean("locked"));
RestoreFromSeedActivity.java:261:   if (accountLocked) {
```

That check is in the app, and it runs at **step D** — after **step C** has already completed
`signInWithCustomToken()` (`:216-236`). By the time the lock is read, the caller already holds a
live Firebase session whose uid *is* the victim's `derivedUserId`. `rollbackFailedRestore()` at
`:262` signs that session out, but only because the app politely chooses to.

`POST /mintToken` (`server/index.js:1436-1546`) — the endpoint that mints that session — never
reads `accountLock`. Its transaction (`:1483-1519`) checks only the identity-hash binding and,
for brand-new accounts, the waitlist token. A locked account and an unlocked account are
indistinguishable to it.

**Exploit path.** Adversary holds the seed phrase (coerced) and the device:

1. Derive `userId` + identity keypair from the seed off-device (the derivation is in the APK).
2. `POST /mintToken {userId, identityPubKeyHex}` → **200, custom token**. The account being
   locked is irrelevant.
3. `signInWithCustomToken` → full Firebase session as the victim.
4. Read everything the Firestore rules grant that uid: `users/{uid}`, `identities/{uid}`,
   `chats` where they are a participant, `groups` they belong to, `prekeys`, backup blobs.
   Skip step 4 in the app entirely — the lock read never happens because it exists only in
   `RestoreFromSeedActivity`.

No patched APK is even required — steps 1-4 are plain HTTPS plus the Firebase REST API. Patching
out `:261` is simply the lazier variant.

The rules cannot compensate: they gate *access* by uid, and this attacker legitimately holds the
victim's uid. The lock must be enforced where the credential is issued.

**Fix.** Enforce the latch server-side inside the `/mintToken` transaction, before
`createCustomToken`:

```js
// inside the existing db.runTransaction at :1483, alongside the identity check
const lockSnap = await tx.get(db.collection("accountLock").doc(userId));
if (lockSnap.exists && lockSnap.data().locked === true) {
  throw Object.assign(new Error("Access request not approved"), { status: 403 });
}
```

Reuse the **existing** 403 message verbatim so a locked account stays indistinguishable from a
wrong seed / unapproved invite (preserving the deniability property that
`RestoreFromSeedActivity:248-251` is careful about client-side). Read it inside the transaction,
not before it, so a concurrent `/duress-lock` cannot interleave. Keep the client-side check as
defence in depth, but move it *before* step C so no session is ever minted for a locked account.
Also apply the same guard to `/migrateUid` (`:1647`).

---

### S06-H2 — The duress wipe leaves plaintext WorkManager records proving a duress code was entered

**Severity: High** · **Location:** `DuressManager.java:269-322`, `AccountLockWorker.java:66-84`, `util/FcmUnregisterWorker.java:71-77`

The wipe at `DuressManager.java:269-311` is thorough about the things it enumerates — Room DB
(`:274-275`), `SecurePrefs` (`:287`), contact backup (`:293`), MediaStore (`:299`), three named
SharedPreferences files (`:305-310`). It never touches WorkManager's own database
(`androidx.work.workdb`), and nothing else in the codebase does either:

```
$ grep -rn "pruneWork\|cancelAllWork\|androidx.work.workdb" app/src/main/java
SelfDestructScheduler.java:51:  WorkManager.getInstance(ctx).cancelAllWorkByTag(WORK_TAG);
```

Only `SelfDestructScheduler`'s own tag, unrelated to duress. Meanwhile the duress path
deliberately enqueues two jobs that write into that database:

- `FcmUnregisterWorker.enqueue(appCtx, uidBeforeWipe)` (`DuressManager.java:169`) → tag
  `"fcm_unregister_" + uid` (`FcmUnregisterWorker.java:77`), input data `{uid: <plaintext uid>}`.
- `AccountLockWorker.enqueue(appCtx, uidBeforeWipe, nonce)` (`DuressManager.java:248`) → tag
  `"account_lock_" + uid` (`AccountLockWorker.java:81`), input data
  `{uid: <plaintext uid>, nonce: <64-hex>}` (`:71-74`).

`workdb` is an ordinary unencrypted Room database in the app's data directory. WorkManager
retains completed `OneTimeWorkRequest` rows — including tags, input data, and state — until
pruned; nothing prunes them here. `AccountLockWorker` is also enqueued with a 5-40 s initial
delay (`:54-55`, `:70`), so on a fast wipe the row is frequently still `ENQUEUED`.

**Why this is the whole ballgame.** The wipe's stated purpose (`DuressManager.java:140-145`) is
that "device presents as unconfigured/factory-reset" and the sign-out is "indistinguishable from
a voluntary sign-out". A tag literally reading `account_lock_<uid>` is the opposite of
indistinguishable. An adversary who ran the duress PIN and is now suspicious pulls
`/data/data/<pkg>/databases/androidx.work.workdb` (root, ADB backup, or a forensic image) and
recovers:

- proof the app was configured and by which account (`uid` — the app otherwise leaves no uid),
- proof a **security-triggered** teardown ran, not a voluntary sign-out (a voluntary sign-out
  enqueues no `account_lock_` job),
- the live 24-hour lock nonce, and
- a wipe timestamp correlating to the moment the coerced PIN was entered.

Note the ordering makes this the *normal* case, not an edge case: step 1b enqueues the worker
whenever the nonce fetch succeeds (`:247-250`) — it is not conditional on step 1a having failed —
so an online duress trigger always creates the `account_lock_` row.

This is also the one duress finding that a server-side fix cannot reach.

**Fix.**

1. Stop putting identifiers in tags and input data. Tag jobs with a constant
   (`"pending_teardown"`), and pass the uid/nonce via a value the wipe destroys — or accept an
   opaque server-issued handle only.
2. Have each worker `WorkManager.pruneWork()` and cancel its own tag as its final act in
   `doWork()`, on every terminal path (success *and* the `Result.success()` drops at
   `AccountLockWorker.java:126-138`).
3. In `DuressManager`, only enqueue `AccountLockWorker` when step 1a actually failed (see
   S06-I2) — the fallback should be the exception, not the default.
4. Add a startup sweep: on a fresh launch with no identity in `SecurePrefs`, call
   `pruneWork()` before anything else, so residue from an interrupted wipe is cleared the next
   time the app opens.

Also reconsider `FcmUnregisterWorker` on the duress path for the same reason — it too records
the uid in cleartext.

---

### S06-H3 — Offline duress trigger silently fails to lock the account; the attacker controls whether the lock happens

**Severity: High** · **Location:** `DuressManager.java:192-256`

Two paths can set the lock, and **both** require network at, or shortly after, trigger time:

- **1a, synchronous write** (`:197-219`): a Firestore `set()` with a 5 s cap. Offline, the SDK
  queues it locally — and then step 3a-3e destroys the app's storage and step 4 signs out, so the
  queued mutation is deleted before it can ever flush. It is not "eventually consistent"; it is
  gone.
- **1b, nonce + WorkManager fallback** (`:227-256`): requires an ID token fetch (5 s,
  `:239-241`) *and* a successful `POST /requestLockNonce` (`:246`). Offline both fail, so
  `AccountLockWorker` is never enqueued at all (`:247`, and the explicit note at `:164-166`).

So when the device has no connectivity, the duress PIN performs the full destructive local wipe
and locks nothing. The user sees exactly the same silent, deniable behaviour either way — there
is no indication, by design (`:152`), and by this point the UI is already gone.

**Exploit path.** This is directly attacker-controlled and needs no tooling:

1. Adversary seizes the device (already unlocked, or unlocks it under coercion).
2. Enables airplane mode / pulls the SIM / uses a Faraday bag — a routine, unremarkable step
   for anyone handling a seized phone, and one that also defeats remote-wipe, so it is standard
   practice.
3. Coerces the PIN. The user gives the **duress** PIN believing it protects the account.
4. The app wipes locally and looks compliant. `accountLock` is never written.
5. Adversary coerces the seed phrase (or already has it) and restores the account at leisure —
   and even the client-side gate at `RestoreFromSeedActivity:260` now passes, because the lock
   document does not exist.

The user is left believing the account was protected. That is worse than an honest failure.

**Fix.** The lock must not depend on connectivity at duress time.

- **Primary:** make the lock decision *durable before the wipe*. Write a lock intent into a
  location the wipe deliberately preserves (a dedicated one-key file outside the three cleared
  prefs files, mirroring how `SecurePrefs.getDeviceGate()` is intentionally kept isolated from
  the wipe per `:280-285`), then have a boot-completed / next-launch worker drain it. This
  survives reboots and >24 h offline, which the current nonce cannot (`AccountLockWorker.java:130-136`
  drops the job outright once the nonce expires).
- **Secondary:** move the nonce fetch off the duress path entirely. Fetch and rotate a lock
  nonce during normal foreground operation while online, keep the current one in the
  wipe-preserved file, and the duress path then always has a usable credential.
- Consider an inverted design so offline fails *closed*: a server-side heartbeat, where the
  account auto-locks if a duress-flagged device stops checking in. That removes the attacker's
  ability to win by cutting the network.
- Independently, S06-I2: step 1a currently cannot tell success from failure, so it cannot even
  detect that it needs to fall back.

---

### S06-M1 — `duressEligibility` is enforced nowhere; it is a cached client boolean that only hides UI

**Severity: Medium** · **Location:** `DuressManager.java:341-377`, `ui/ManageUnlockCodesActivity.java:83`, `firestore.rules:321-324`

The rules for `duressEligibility` are exactly right (owner-read, `write: if false`, tested at
`rules.test.js:984-1027`), and the design intent at `DuressManager.java:327-335` is explicit: "A
generic account created by anyone probing the app is never enrolled and never sees the option."

But the flag's only consumer is a cached boolean gating UI visibility:

```
$ grep -rn "isDuressEligibleCached" app/src/main/java
ManageUnlockCodesActivity.java:83:  boolean eligible = DuressManager.isDuressEligibleCached(this);
```

`isDuressEligibleCached` (`:347-351`) reads `SecurePrefs` with a `false` default, populated by
`refreshEligibility` (`:360-377`). Nothing consults eligibility when a duress PIN is *set*
(`setDuressPin`, `:56-69`), when one is *matched* (`isDuressPin`, `:71-89`), when
`performLogout` runs (`:154`), or on the server in `/requestLockNonce` (`server/index.js:2385-2396`)
or `/duress-lock` (`:2446-2466`). `accountLock` create is granted to any authenticated user for
their own doc (`firestore.rules:344-345`) with no eligibility predicate.

**Exploit path (feature-existence disclosure).** An adversary who wants to know whether this
build has a duress feature — the question the eligibility gate exists to keep unanswerable —
signs up an account of their own, patches the `eligible` check at
`ManageUnlockCodesActivity.java:83` (or writes `duress_eligible_<uid>=true` into their own
`SecurePrefs`), sets a duress PIN, enters it, and observes the local wipe plus their own
`accountLock/<uid>` doc appear. They now know the feature exists, how it is triggered, what it
does, and which collection to look for. Every real user's deniability degrades accordingly:
denying that a duress code exists stops being credible once the mechanism is publicly known.

Additionally, since a non-eligible account can complete the whole flow, `/requestLockNonce` and
`/duress-lock` will happily issue and consume nonces for accounts the operator never enrolled
(feeding S06-M2).

**Fix.** Treat eligibility as a server-side boundary, not a UI hint. Check
`duressEligibility/{uid}.eligible === true` in `/requestLockNonce` before issuing a nonce, and add
`&& exists(/databases/$(database)/documents/duressEligibility/$(request.auth.uid)).data.eligible == true`
to the `accountLock` create rule at `firestore.rules:344`. Accept that the client-side PIN check
cannot be enforced remotely (the PIN never leaves the device, correctly) — but the observable
*consequences* can be, and those are what leak the feature's existence.

---

### S06-M2 — `_duressNonces` grows without bound; any authenticated user can inflate it indefinitely

**Severity: Medium** · **Location:** `server/index.js:2394-2396`, `2446-2466`

`/requestLockNonce` writes a `_duressNonces/{nonce}` doc per call (`:2396`). Documents are removed
only when `/duress-lock` consumes one (`:2464`) or when an expired one is presented (`:2456`).
There is no TTL policy, no scheduled sweep, and no cleanup anywhere:

```
$ grep -rn "_duressNonces" server/index.js firestore.rules
server/index.js:2396   .doc(nonce).set({ uid, expiresAt });
server/index.js:2447   const nonceRef = db.collection("_duressNonces").doc(nonce);
firestore.rules:360    match /_duressNonces/{nonce} {
```

An expired nonce that is never presented is never deleted — and the common case is precisely
that, because `AccountLockWorker` drops the job without calling the endpoint on expiry
(`AccountLockWorker.java:130-136`) and because step 1a usually succeeds first, leaving the
fallback nonce unused. Every successful duress trigger therefore leaks one permanent document.

The rate limit is `checkAuthRateLimit(uid, "requestLockNonce")` (`:2385`), a per-minute bucket. It
bounds the rate, not the total. Any authenticated user can loop at the permitted rate and add
documents indefinitely — unbounded Firestore storage and write-quota consumption on the
operator's bill, with no admin visibility (the collection is not surfaced anywhere in `/admin`).

There is also a **deniability** angle: an undeleted `_duressNonces` doc holds `{uid, expiresAt}`.
Anyone with read access to the project (operator, a compromised service account, a subpoena) can
enumerate exactly which uids triggered a duress wipe and when — a permanent server-side record
of the event the feature is designed to make undetectable.

**Fix.** Add a Firestore TTL policy on `_duressNonces.expiresAt` so Google deletes expired docs
automatically (this is the intended mechanism and costs nothing). Delete the doc on the
`AccountLockWorker` drop paths too, via a `DELETE`/discard call, rather than abandoning it. Cap
outstanding nonces per uid (delete the previous nonce for that uid before issuing a new one —
there is never a reason for a uid to hold two). Shorten the 24-hour window (`:2395`) once the
durable-intent fix from S06-H3 removes the need for a long retry runway.

---

### S06-M3 — Raw uids logged on both duress endpoints, violating the codebase's own `uidTag` policy

**Severity: Medium** · **Location:** `server/index.js:2398`, `:2476`

```js
2398:  console.log(`[requestLockNonce] nonce issued for uid=${uid}`);
2476:  console.log(`[duress-lock] accountLock written for uid=${uid}`);
```

Both interpolate the raw uid. The codebase has a `uidTag()` helper used consistently elsewhere
(`server/index.js:674`; e.g. `mintToken` at `:1525`, `migrateUid` at `:1810`) precisely so uids are
not written to logs in cleartext.

These two are the worst possible places to break that rule. A log line pairing a raw uid with
`[duress-lock]` is a durable, plaintext, timestamped record that **this specific account
triggered a duress wipe** — sitting in whatever log aggregator the push server ships to, outside
Firestore's access controls and outside the operator's deletion workflow. Server logs are
routinely retained longer than app data, replicated to third-party providers, and reachable by
support staff who have no business knowing this.

**Fix.** Use `uidTag(uid)` in both. Better: for `/duress-lock`, drop the uid from the message
entirely and log only that a lock was written — the operator's legitimate view of who is locked
is `/admin/api/locked` (`:2654-2674`), which reads live Firestore state and is covered by the
audit log. Sweep the client side too: `DuressManager.java:214` and `AccountLockWorker.java:81`
have the analogous problem in logcat and in the WorkManager tag (S06-H2).

---

### S06-L1 — Nonce expiry check fails open on a malformed `expiresAt`

**Severity: Low** · **Location:** `server/index.js:2454`

```js
if (!nonceUid || new Date() > new Date(expiresAt.toDate ? expiresAt.toDate() : expiresAt)) {
```

Two problems. If `expiresAt` is absent, `expiresAt.toDate` throws `TypeError` inside the
transaction; the error has no `.status`, so `:2473` rethrows into the outer catch and the caller
gets a **500** instead of a 4xx — and `AccountLockWorker` treats 5xx as retryable
(`AccountLockWorker.java:139-140`), so it retries a permanently broken nonce forever. If
`expiresAt` is a string that `Date` cannot parse, the comparison against `Invalid Date` is
`false`, so the nonce is treated as **not expired** — fail-open, valid indefinitely.

Only reachable via corrupted or hand-edited data today, since `:2395` always writes a real `Date`.
Recorded because the surrounding code is otherwise carefully fail-closed.

**Fix.** Validate the shape explicitly and fail closed:

```js
const exp = expiresAt?.toDate?.() ?? (expiresAt instanceof Date ? expiresAt : null);
if (!nonceUid || !exp || Number.isNaN(exp.getTime()) || Date.now() > exp.getTime()) {
  tx.delete(nonceRef);
  throw Object.assign(new Error("Nonce expired"), { status: 401 });
}
```

---

### S06-L2 — `/duress-lock` is unauthenticated with no rate limit of any kind

**Severity: Low** · **Location:** `server/index.js:2422-2486` _(inherited from S04-L2, confirmed)_

`/duress-lock` is the only mutating endpoint on the server with **no** `checkIpRateLimit` and no
`checkAuthRateLimit` — compare `/requestLockNonce` at `:2385`, `/mintToken` at `:1441`,
`/migrateUid` at `:1667`. By necessity it is unauthenticated (the caller has been wiped and
signed out), so nothing throttles it.

Not a credential-guessing risk: the nonce is 32 bytes of `crypto.randomBytes` (`:2394`) checked
for exactly 64 hex chars (`:2433`), so brute force is out of reach. The real cost is that every
request with a well-formed 64-char nonce triggers an unauthenticated Firestore **transaction**
(`:2446-2448`), letting an anonymous attacker drive read quota and billing from a single host,
and drown the signal in `[duress-lock] error` noise.

**Fix.** Apply `checkIpRateLimit(getClientIp(req))` before the transaction. Keep it generous
(the legitimate client retries with exponential backoff from `AccountLockWorker.java:79`), and note
S04-M1: the IP buckets key on the full address, so an IPv6 /64 sidesteps them — fix that keying
alongside this.

---

### S06-L3 — `_duressNonces` has no rules-test coverage

**Severity: Low** · **Location:** `firestore-tests/rules.test.js`

`accountLock` (`:914-981`) and `duressEligibility` (`:984-1027`) are covered thoroughly — the
one-way latch, cross-account denial, anonymous denial, and delete denial all have explicit cases.
`_duressNonces` (`firestore.rules:360-362`) has **none**, and neither does `waitlist`
(`:368-370`). Both are `allow read, write: if false`, so they are correct today, but the deny-all
is untested and a future edit could relax it silently. A leaked nonce read would let an attacker
consume another account's pending lock (turning S06-H3's failure mode into an active attack).

**Fix.** Add the four-case block used for the other server-only collections — authed read denied,
authed write denied, anonymous read denied, anonymous write denied — for both `_duressNonces` and
`waitlist`.

---

### S06-L4 — `AccountLockWorker` reports failure as success and retries 5xx without a cap

**Severity: Low** · **Location:** `AccountLockWorker.java:88-141`

`Result.success()` is returned on: missing input data (`:92`), unset `PUSH_SERVER_URL` (`:99`),
HTTP 400/403 (`:126-129`), and HTTP 401 (`:130-137`). Every one of those means **the account was
not locked**. `Result.success()` also permanently retires the job, so the last retry path is
gone. Nothing surfaces this anywhere — no telemetry, and by design no UI. Meanwhile any 5xx
returns `Result.retry()` (`:139-140`) with exponential backoff from 30 s (`:79`) and no attempt
ceiling, so a persistently failing server produces indefinite retries — each one re-reading the
plaintext nonce out of `workdb` and refreshing the forensic artifact from S06-H2.

The comments are candid that no recovery exists post-wipe (`:132-136`), which is accurate given
the current design and is exactly what S06-H3's durable-intent fix removes.

**Fix.** Once a wipe-surviving lock intent exists (S06-H3), the terminal paths should surface a
failure rather than swallow it: keep the intent on disk, report via
`Result.failure()`/`Result.retry()` as appropriate, and cap retries with `getRunAttemptCount()`.
Prune the WorkManager record on every terminal path regardless of outcome (S06-H2).

---

## 4. What is implemented correctly

Verified against the code, not just the comments — worth recording so a later pass does not
"re-fix" these.

- **Nonce design.** The choice of a uid-bound single-use nonce over the APK-embedded
  `WORKER_SECRET` (`server/index.js:2416-2420`) is right, and the reasoning at `:2350-2361` holds:
  a leaked nonce can lock exactly one account, the one that requested it, and cannot authenticate
  to anything.
- **Atomic consume-and-lock.** `:2446-2466` performs the nonce read, the `accountLock` write, and
  the nonce delete in one `runTransaction`. The get-then-batch race called out at `:2439-2443` is
  genuinely fixed — two concurrent requests with the same nonce cannot both succeed.
- **Nonce entropy and validation.** 32 bytes from `crypto.randomBytes` (`:2394`), length-and-shape
  checked at `:2433` before any Firestore access.
- **`/requestLockNonce` auth.** Requires a real Firebase ID token via `verifyIdToken` (`:2378`),
  is rate-limited per uid (`:2385`), and binds the nonce to the *verified* token uid — not to any
  client-supplied value. A caller cannot request a nonce for someone else.
- **`collectBody` on both endpoints** (`:2366`, `:2423`), including `/requestLockNonce` where the
  body is unused — the comment at `:2363-2365` correctly identifies that a bare `req.on("data")`
  drain would bypass `MAX_BODY_BYTES`.
- **The one-way latch in the rules.** `firestore.rules:341-354` permits create/update only when
  `request.resource.data.locked == true` and denies delete unconditionally. A malicious APK cannot
  unlock. Tests cover flip-to-false on both create and update, field removal, cross-account, and
  anonymous (`rules.test.js:930-976`).
- **`duressEligibility` write-lockout.** `write: if false` with owner-only read (`:321-324`) means
  eligibility cannot be self-granted in Firestore or probed for other accounts — the S06-M1
  problem is that nothing *reads* it authoritatively, not that the rule is wrong.
- **Timing parity on the restore gate.** `RestoreFromSeedActivity:240-259` dispatches the
  `identities` and `accountLock` reads concurrently and awaits both, so a locked account is not
  distinguishable by latency, and `:248-251` returns the identical `GENERIC_RESTORE_FAILURE` string
  used for a wrong seed. Good deniability engineering — undermined only by S06-H1 placing the
  check after authentication.
- **Duress PIN hashing.** PBKDF2-HMAC-SHA256, 310 000 iterations, 256-bit output, per-PIN 16-byte
  random salt, `constantTimeEquals` comparison (`DuressManager.java:60-63`, `:87`, `:438-441`,
  `:453-458`).
- **UID-scoped PIN storage.** `duressKey()` (`:51-54`) namespaces the hash per uid with a
  documented rationale (`:45-49`) — a new account on the same device cannot inherit the previous
  account's duress PIN — and `hasDuressPin` migrates the legacy global key (`:97-102`).
- **Device-gate isolation preserved.** The wipe at `:286-289` clears only account-scoped
  `SecurePrefs`; the warning at `:280-285` not to re-add device-gate keys to that `clear()` is
  correct and should be left alone.
- **Wipe breadth and ordering.** `.commit()` rather than `.apply()` for synchronous destruction
  (`:287`, `:306-310`), `AppDatabase.clearInstance()` before `deleteDatabase` (`:274-275`), and
  MediaStore wipe sequenced *before* the prefs clear because the URI list lives in those prefs
  (`:295-300`).
- **Routing guard.** `duress_wipe_in_progress` is written with `.commit()` before
  `startActivity` (`:177-178`) and cleared last with a safety-net remove (`:319-322`), so
  `SignInActivity` cannot auto-route a returning user mid-wipe.
- **Trigger has no guess-count fallback.** `LockScreenActivity:184-196` fires only on an exact
  duress-PIN match; the removal of a wrong-guess threshold is deliberate and documented at `:30`.

---

## 5. Cross-session notes

- **S05-M2 confirmed and upgraded to S06-M1.** Session 05 flagged that `duressEligibility`
  appeared to be enforced nowhere server-side. Confirmed: its sole consumer is a cached client
  boolean controlling UI visibility (`ManageUnlockCodesActivity.java:83`).
- **S04-L2 confirmed as S06-L2.** `/duress-lock` has no rate limiting of any kind. Real impact is
  unauthenticated Firestore transaction cost, not nonce brute force.
- **S04-M1 (IPv6 /64 defeats IP-keyed limits) applies here too.** The `checkIpRateLimit` fix
  proposed in S06-L2 inherits that weakness; fix the keying once, centrally.
- **S05-H1 interacts with the latch.** `POST /admin/api/locked/unfreeze` (`server/index.js:2681`)
  deletes `accountLock/{uid}` — so anyone holding `ADMIN_TOKEN` can unlock a duressed account.
  The rules comment at `firestore.rules:337-340` claims only the Firebase console / Admin SDK can
  clear the doc, which is now doc/reality drift (see S06-I1). The strength of the entire duress
  guarantee is bounded by `ADMIN_TOKEN`'s unvalidated entropy.
- **S06-H1 is the same class of bug as S01/S03's `groups` gap:** a control that is correct in the
  rules but unenforced at the point where the credential or the write actually happens. Group
  these in the final report as *enforcement-location* failures.
- **For Session 10 (synthesis):** S06-H1, S06-H2, and S06-H3 each independently defeat the duress
  feature, by three different routes (server-side non-enforcement, on-device forensic residue,
  attacker-controlled connectivity). The feature should be described as **not currently providing
  either of its two advertised guarantees**, notwithstanding that its cryptographic and
  transactional internals are well built.

---

## 6. Informational

### S06-I1 — Rules comment contradicts the shipped admin unfreeze endpoint

`firestore.rules:337-340` states that clearing `accountLock` is possible only via "the Firebase
console / Admin SDK" and frames this as enforcing a "manual-only unlock decision". Since Session
05, `POST /admin/api/locked/unfreeze` (`server/index.js:2676-2719`) exposes exactly that deletion
over HTTP behind a single static bearer token. The security property is now "whoever holds
`ADMIN_TOKEN`", which is materially weaker than what the comment promises. Update the comment to
reference the endpoint and its audit-log entry (`:2883`), so the next reader does not over-trust
the latch.

### S06-I2 — Step 1a cannot distinguish a successful lock write from a failed one

`DuressManager.java:197-219` uses `addOnCompleteListener` (`:208`) and sets `written[0] = true`
regardless of `task.isSuccessful()`, then logs "Synchronous account-lock write complete."
(`:214`) whether or not anything was written. The `catch` at `:215` only fires on a synchronous
throw, never on an async task failure. So the code cannot tell it needs the fallback — which is
why step 1b enqueues `AccountLockWorker` unconditionally (S06-H2 item 3) and why the offline case
in S06-H3 passes silently. Check `task.isSuccessful()` and drive the fallback decision from it.

### S06-I3 — Duress PIN strength is bounded by the PIN space, not the KDF

The 310 000-iteration PBKDF2 at `DuressManager.java:438-441` is strong, but the input is a short
numeric PIN, so an attacker with the stored `salt:hash` blob can exhaust the keyspace regardless
of iteration count — recovering not the account, but the knowledge that a *second, distinct* code
exists, which is itself the deniability-breaking fact. The mitigation is that the blob lives in
hardware-backed `SecurePrefs` and is destroyed by the wipe. Recorded as an accepted design
trade-off, not a defect; it does mean `SecurePrefs`' hardware backing is load-bearing for
deniability and should be verified in the client-side session that covers key storage.

---

_End of Session 06. Next: Session 07 per `AUDIT_PROGRESS.md`._
