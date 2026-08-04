# DuoShield Security Review — 2026-08-04

Full-scope review covering: Android app crypto/key management, auth & app-lock,
Firestore rules & Firebase backend, the push server (`server/index.js`), the
Cloudflare Worker media store (`worker/src/index.js`), and Android platform
hardening. Combines two automated scanners (dependency audit, static analysis,
privacy dataflow) with six focused manual reviews. Every Critical/High item
below was independently re-verified against the live source (file:line);
Medium/Low items are as reported by the domain review unless marked verified.

Two items are **explicitly out of scope by product decision** and are not
findings: (1) no contact-consent gate before a chat/call can be created, and
(2) `FLAG_SECURE` screenshot protection is intentionally absent on some screens.

## Automated scans

| Scanner | Result |
|---|---|
| Dependency audit | 2 High: `brace-expansion` DoS (CVE-2026-14257, CVE-2026-69152) — transitive dev dependency of `firestore-tests` only, not shipped in app/server/worker |
| SAST | 0 findings |
| Privacy dataflow (HoundDog) | 1 Medium: client IP logged to stdout, `server/index.js:984` |

## Critical

1. **Cloudflare Worker fails open with no `WORKER_SECRET`** — `worker/src/index.js:73-79`. `isAuthorized()` returns `true` for every request when the secret env var is unset, exposing all photos/voice notes/videos (read/write/delete) with zero authentication. There is no deploy-time check that the secret is actually configured, so a misconfigured deploy fails open silently instead of refusing to serve.
   *Fix:* return `false` (deny) when the secret is missing; add a startup/deploy check that fails the deploy if `WORKER_SECRET` is absent.

## High

2. **Worker has no per-object authorization, only one shared secret embedded in every APK** — `worker/src/index.js:264-267` takes the storage key directly from the request path (`media/<chatId>/<uuid>.jpg`) with no ownership check, format validation, or traversal rejection; the only gate is `WORKER_SECRET`, which is compiled into `BuildConfig` for every install (`app/build.gradle:75`, `B2StorageHelper.java:101`). Since the same secret ships in every user's APK and can be extracted from it, any user can read/overwrite/delete any other user's media if they can learn or guess its object key.
   *Fix:* bind object keys to an authenticated identity/chat membership check (e.g. mint short-lived per-object tokens server-side, the way TURN credentials already work), not a single static shared secret.
3. **Legacy Firestore rules allow room/conversation hijacking** — `firestore.rules:223-248`. `/rooms/{code}` has no creator binding on `create`/`update` beyond `status == "waiting"`, so any signed-in user who finds a waiting room code can claim it. `/conversations/{convId}` lets any existing participant rewrite the whole document including `participants`, so a participant can add an arbitrary UID and gain access to the thread and its messages subcollection.
   *Fix:* bind `create`/`update` to the original creator, make `participants` immutable after creation (mirror the fix already applied to the current `/chats` collection), or deny writes to these legacy paths entirely if they're no longer used.
4. **Worker R2→B2 tiering and upload/delete are not concurrency-safe** — `worker/src/index.js:292-313, 447-477`. PUT and DELETE race independently; a DELETE can land after a PUT completes and leave the file undeleted, and the nightly cold-tier migration can delete a version that a concurrent re-upload just replaced, or leave stale ciphertext servable from B2.
   *Fix:* use conditional/ETag-based writes and treat migration+delete as a single atomic (or idempotent, version-checked) operation instead of independent read/put/delete steps.
5. **`/linkPreview` SSRF guard doesn't cover redirects** — `server/index.js:1651-1691`. The initial hostname is checked against a private/loopback/metadata blocklist, but the subsequent `fetch(..., redirect: "follow")` follows redirects without re-validating each hop. A public URL that 302s to `127.0.0.1` or a cloud metadata address bypasses the filter.
   *Fix:* disable automatic redirects and manually re-validate each `Location` header against the same blocklist before following it (or cap redirect count and re-check every hop).

## Medium

6. `server/index.js:998-1059` — `/mintToken`'s per-UID 60s cooldown is read outside the Firestore transaction and written only after the token is minted; concurrent requests for the same `userId` can each pass the stale check and mint multiple tokens. The identity-claim transaction still prevents binding to someone else's account, so this is an abuse-control gap, not an auth bypass. *Fix:* make the cooldown check-and-set part of the same transaction.
7. `server/index.js` (multiple routes, e.g. `976-980, 1361-1364, 1631-1634, 1811-1814, 1917-1920, 2001-2004, 2081-2084, 2128-2131`) — several JSON routes read the request body directly instead of through the shared `MAX_BODY_BYTES`-guarded reader, so a large or content-length-less body isn't capped before parsing. *Fix:* route every body-bearing endpoint through the shared bounded reader.
8. `server/index.js:1830-1858` — `/duress-lock` reads the nonce and deletes it in separate, non-transactional steps; concurrent requests could both pass the nonce check before either delete commits, replaying a single-use credential. *Fix:* wrap nonce verification + lock write + nonce delete in one transaction.
9. `worker/src/index.js:280-287` — the 9.5 GB R2 cap check uses a non-atomic KV counter and pre-upload `Content-Length` (a real post-upload HEAD check does catch the true size afterwards, so this is a soft rather than hard cap). Many concurrent uploads could transiently push total usage past the intended limit. *Fix:* move to an atomic reservation (Durable Object or equivalent) if the free-tier ceiling needs to be a hard guarantee.
10. `firestore.rules:253-264` — `identities/{userId}` is readable by any authenticated user and permits arbitrary extra fields on update (only `identityPubKeyHash` is pinned). This may be intentional — `firestore-tests/rules.test.js:581-582` explicitly tests for and expects public readability — but it doubles as a full UID-mapping enumeration oracle for anyone with an account. *Worth an explicit product decision either way; flagging for confirmation rather than assuming a fix.*
11. `firestore.rules:7-9, 16-28, 283-313` — no field-level schema validation on user documents, public-key bundles, or backup metadata; an owner (or a client acting on their behalf) can overwrite their own key material or backup record with malformed data. Confidentiality stays owner-scoped, but integrity/availability of a user's own account state isn't enforced. *Fix:* add field allowlists/type checks and mark identity/signed-key fields immutable after creation.
12. `server/index.js:984` and `504-507` — client IP addresses are written to logs (HoundDog-flagged, GDPR/CCPA-relevant), and the rate limiter trusts the first `X-Forwarded-For` value without validating it's from a trusted proxy, so it can be spoofed to bypass IP-based limits or pollute admin audit records. *Fix:* stop logging raw IPs (or hash/truncate them), and read the client IP from Render's trusted proxy chain instead of an unvalidated header.
13. `server/index.js` (~10 error-handling call sites, e.g. `1692, 1732-1733, 1791-1793, 1863-1866`) — error responses return `"Server error: " + e.message`, leaking internal exception detail to the client. *Fix:* return a generic message and keep detail in server-side logs only.
14. `app/src/main/java/com/duoshield/app/db/DatabaseKeyProvider.java:27-37` — the generated SQLCipher key is persisted with async `apply()` and the get-or-create path isn't synchronized. A crash between key generation and disk flush can leave the database permanently unopenable; two racing first-time callers could theoretically generate different keys. *Fix:* synchronize initialization and use a synchronous, checked write before opening the database.
15. `app/src/main/java/com/duoshield/app/util/SecurePrefs.java:170-174` — if Android Keystore/`EncryptedSharedPreferences` initialization fails, storage silently falls back to plaintext `SharedPreferences`, and `DatabaseKeyProvider` doesn't check `SecurePrefs.isAvailable()` before trusting it as encrypted. *Fix:* fail closed (block DB/key material access and surface the degraded state) rather than silently downgrading confidentiality.
16. Signal TOFU identity-change handling (`DuoShieldSignalStore.java:113-147`) — investigated: this is intentional Trust-On-First-Use (same model Signal/WhatsApp use), with an existing safety-number banner (`safety_num_changed_<uid>`) as the out-of-band mitigation. The one real gap: dismissing the banner (✕) only hides it for the current session — a user can keep messaging an unverified, changed identity indefinitely without ever confirming it's expected. *Optional hardening, not a defect*: consider re-surfacing the banner every session (not just once) until the user actually taps Verify.

## Low / Info

17. `app/src/main/java/com/duoshield/app/ui/AddContactActivity.java:159-171` — the `duoshield://add/...` deep link copies its path verbatim into the account-ID field with no grammar/length validation before the add-contact flow runs. Low impact since adding a contact still requires explicit user confirmation, but malformed/stale links shouldn't reach that flow unvalidated.
18. `SignInActivity.java:69-75, 161-223`, `MainActivity.java:130` — Firebase UID values are written to logcat in release builds. Minor account-metadata leakage; guard with `BuildConfig.DEBUG` or log a redacted identifier.
19. FCM notification payload — verified `server/index.js`'s `notificationBody()` (line 91) only ever sends fixed generic strings ("New encrypted message", "Sent a photo 🖼", etc.), never real plaintext, so there is **no current leak**. `DuoShieldMessagingService.java:103-119` does display whatever `body`/`senderName` arrives verbatim, though, with no client-side allowlist — a defense-in-depth gap if the server were ever misconfigured or compromised, not an active issue today.
20. `worker/src/index.js:54-63` — CORS is wildcard (`Access-Control-Allow-Origin: *`), intentionally, since only the Android HTTP client (not a browser) calls it today. Fine as-is; revisit if a web client is ever added.
21. `SignalPreKeyRefresher.java:109-144`, `SignalKeyManager.java:89-113` — failed prekey-bundle uploads aren't durably queued/retried beyond the latest batch, which can cause session-establishment failures (denial, not compromise) under repeated network failures.
22. `security-crypto:1.1.0-alpha06` (`app/build.gradle`) — years-old alpha dependency backing `EncryptedSharedPreferences`/`SecurePrefs`; no maintained stable release exists in the same shape from Google. Flagging for awareness, not an immediate action.
23. Two High npm advisories (`brace-expansion` DoS, CVE-2026-14257/CVE-2026-69152) are a transitive dev-only dependency of `firestore-tests` — never shipped to users. Trivial to clear with `npm audit fix` in that workspace.

## Investigated and cleared (subagent flagged, verified not exploitable)

- **CallManager relay-only calling** (`app/src/main/java/com/duoshield/app/call/CallManager.java:320-403`) — when "Relay-only calls" is enabled but TURN credentials aren't cached, the peer connection is still configured with `IceTransportsType.RELAY` and an ICE server list containing only STUN entries. STUN servers cannot produce relay candidates, so the call simply fails to connect — it does **not** silently fall back to direct P2P and does **not** leak the user's IP. No fix needed; this is a reliability rough edge (an unclear failure) at worst.

## Verified solid (no finding)

- AES-GCM usage (`BackupCryptoHelper`, `GroupCipherHelper`) uses 12-byte `SecureRandom` IVs and 128-bit tags; no IV reuse found.
- Seed phrase generation uses 128-bit `SecureRandom` entropy (BIP39); HKDF follows RFC 5869 salt/info separation; no ECB/DES/MD5/SHA-1 or hardcoded keys anywhere in the crypto package.
- PIN and duress-PIN hashing: per-record 16-byte random salts, PBKDF2-HMAC-SHA256 at 310,000 iterations, constant-time comparison; duress PIN cannot reveal real data (`real && !duress` gate).
- `/createChat`, `/removeGroupMember`, `/turnCredentials`, `/migrateUid` all verify Firebase ID tokens and enforce UID/creator checks server-side.
- Admin panel: constant-time token comparison, Firestore values rendered via `textContent` (no stored XSS found), state-changing actions gated by the admin token.
- AndroidManifest: `allowBackup=false`, cleartext traffic disabled, no unprotected exported components, FileProvider non-exported.
- Message status downgrade guard, Firestore create/delete rules on `messages` (sender==auth.uid, encrypted-only), and group key rotation on member removal all still hold as previously verified.

## Priority if remediating

If you want fixes, this is the order that matters most:
1. Critical #1 (Worker fail-closed) — one line, near-zero risk to change, closes a total-exposure footgun.
2. High #3 (Firestore legacy rules) — bounded, well-understood change (mirror the pattern already used on `/chats`).
3. High #5 (`/linkPreview` redirect SSRF) — bounded, additive change to existing guard.
4. High #2 (Worker per-object auth) and #4 (tiering race) — larger, touch the media pipeline; worth scoping as dedicated work rather than a quick patch.
5. Medium items — mostly small, independent fixes; can be batched.
