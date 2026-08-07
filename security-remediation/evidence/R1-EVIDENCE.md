# Round 1 Evidence Log

**Date:** 2026-08-07  
**Remediation Round:** 1  
**Status:** COMPLETE

---

## Findings Addressed

| Finding | Severity | Title | Disposition |
|---------|----------|-------|-------------|
| S08-C1 | Critical | Firebase Admin key shipped in APK via `service-account.json` | FIXED |
| SC-02  | Critical | B2/Worker secrets in CI `local.properties` baked into APK | FIXED |
| S07-C1 | Critical | `/mintToken` accepts public key as proof-of-identity (no PoP) | FIXED |
| S07-H1 | High | Existing-account check in mintToken fails open when `identityPubKeyHash` absent | FIXED |
| S06-H1 | High | `accountLock` not enforced server-side inside the mint transaction | FIXED |
| S08-H1 | High | `WORKER_SECRET` compiled into APK `BuildConfig` | FIXED |
| S02-M1 | Medium | Cooldown timestamp stamped before authentication succeeds | FIXED |
| S02-L1 | Low | mintToken audit log not emitted on key-mismatch 403 | FIXED |
| S03-L1 | Low | `WORKER_SECRET` written to `local.properties` and read into APK | FIXED |

---

## File Changes

### 1. `.github/workflows/release.yml`
- **Removed:** `Write service-account.json` step — Firebase Admin private key no longer written to `app/src/main/assets/` during CI build.
- **Removed:** `B2_KEY_ID`, `B2_APPLICATION_KEY`, `WORKER_SECRET` from the `Write local.properties` step.
- **Kept:** Non-secret endpoint URLs (`PUSH_SERVER_URL`, `WORKER_URL`).
- **Evidence:** Git commit `ad5176d` — `refactor: remove sensitive credentials from APK and local.properties`

### 2. `app/build.gradle`
- **Removed:** `workerSecret` read from `local.properties`/env; `WORKER_SECRET` BuildConfig field now hardcoded `""`.
- **Removed:** `b2KeyId`, `b2AppKey` reads from `local.properties`/env (already `""` fields, stale read logic removed).
- **Kept:** Non-secret `B2_BUCKET`, `B2_REGION`, `B2_ENDPOINT` fields (config, not credentials).
- **Evidence:** Git commit `ad5176d`

### 3. `server/index.js`
- **Added:** `mintChallenges` Map + `CHALLENGE_TTL_MS = 5 min` + 10-min purge interval (S07-C1).
- **Added:** `POST /mintChallenge` endpoint — issues per-userId nonce, IP-rate-limited.
- **Rewrote:** `POST /mintToken` handler:
  - Requires `nonce` + `signatureHex` (S07-C1 PoP).
  - Verifies XEd25519 signature via `xed25519.verifySignature()` before any Firestore access.
  - Reads `accountLock/{userId}` atomically inside the same Firestore transaction (S06-H1).
  - Fails CLOSED when `identityPubKeyHash` is absent/null — explicit deny (S07-H1 / S02-L1).
  - Stamps per-userId cooldown AFTER authentication succeeds, not before (S02-M1).
  - Opportunistically upgrades old records by persisting full `identityPubKeyHex`.
- **Evidence:** This commit.

### 4. `server/lib/xed25519.js` (new file)
- Pure-JS XEd25519 verification: converts Curve25519 (Montgomery) public key to Edwards form, constructs DER SPKI, verifies Ed25519 signature with Node `crypto.verify`.
- Applies Signal's 32-byte `0xFE` domain-separation prefix per XEd25519 spec §2.
- No new npm dependencies.
- **Validation:** Node syntax check: PASS. Self-test (invalid sig → false, direct Ed25519 verify control): PASS.

### 5. `app/src/main/java/com/duoshield/app/auth/AuthTokenHelper.java`
- **Added imports:** `Curve`, `ECPrivateKey` from `org.signal.libsignal.protocol.ecc`.
- **Updated:** `signInWithSeed` signatures now require `ECPrivateKey identityPrivKey`.
- **Added:** `fetchChallenge(userId)` — POST `/mintChallenge`, returns nonce.
- **Updated:** `fetchCustomToken` now takes `nonce` + `signatureHex`; removed old 3-param overload.
- **Flow:** challenge → `Curve.calculateSignature(privKey, nonce.getBytes)` → token.

### 6. `app/src/main/java/com/duoshield/app/DisplayNameActivity.java`
- Passes `identityKeyPair.getPrivateKey()` to `signInWithSeed`.

### 7. `app/src/main/java/com/duoshield/app/ui/RestoreFromSeedActivity.java`
- Passes `identityKeyPair.getPrivateKey()` to `signInWithSeed`.

---

## Validation Results

| Check | Result |
|-------|--------|
| `node --check server/index.js` | PASS |
| `node --check server/lib/xed25519.js` | PASS |
| `node server/lib/pure.test.js` (27 tests) | 27 PASS, 0 FAIL |
| xed25519 self-test (invalid sig → false) | PASS |
| Ed25519 verify control test | PASS |
| Build: `WORKER_SECRET` now `""` in BuildConfig | VERIFIED |
| Build: no B2 credential env reads | VERIFIED |
| Git: no `service-account.json` in source tree | VERIFIED |

---

## Residual Risk

- **XEd25519 conversion** uses pure-JS bigint arithmetic — correct but not constant-time. Acceptable for nonce-signature verification (nonce is public; timing leak does not expose key material).
- **In-memory `mintChallenges` store** — a server restart clears all pending challenges. Client retries `/mintChallenge` transparently. Acceptable for the current single-instance Render deployment.
- **Legacy accounts** with only `identityPubKeyHash` (no `identityPubKeyHex`) still authenticate by providing `identityPubKeyHex` in the request; the server checks `sha256(provided_hex) == storedHash` AND verifies the XEd25519 signature. The opportunistic upgrade writes `identityPubKeyHex` to Firestore on success.

---

## SC-12 (Branch Protection) — Runbook Item

SC-12 requires that the `main` branch has required-review and status-check protections in GitHub. This cannot be implemented via code changes. **Required manual action:**

1. Go to GitHub → Settings → Branches → `main` branch protection rule.
2. Enable: "Require a pull request before merging" (at least 1 approver).
3. Enable: "Require status checks to pass before merging" — add the `Build` and `Test` CI jobs.
4. Enable: "Restrict who can push to matching branches" — remove direct-push permission for non-admins.
