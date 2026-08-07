# MIGRATION PLAN

Covers deployment/rollback ordering, credential rotation, and handling of
already-exposed secrets. Referenced by DECISION-LOG DL-001..DL-004.

## 1. Current architecture (from audit SESSION-00 + SYNTHESIS)

- Android client (Kotlin) holding capability logic and SecurePrefs.
- Firebase: Firestore (rules), Cloud Functions (`/mintToken`, media, admin), Storage.
- CI/CD: GitHub Actions (`ci.yml`, `release.yml`) building and signing the APK.
- Secrets currently in-repo: release keystore + `key.properties` (S09-C1), `serviceAccount.json` (S09-C2).

## 2. Target architecture (see architecture/TARGET_STATE.md)

- Server-authoritative token minting with proof-of-seed + fail-closed + account lock/duress.
- Membership-bound media authorization.
- Secrets only in CI secret store / KMS, never in VCS; push-time secret scanning gate.
- Verifiable/reproducible signed build with provenance.

## 3. Migration order (dependency-first)

1. **Secrets first (Round 1).** Rotate exposed keystore + service account BEFORE any code deploy, because the rest of the program assumes the backend and signing identity are trustworthy.
2. **Server trust boundary (Round 1).** Deploy `/mintToken` proof-of-seed + fail-closed + lock behind a feature flag; enroll seeds; enable enforcement.
3. **Media + duress + client residue (Round 2).** Deploy membership-bound media checks; server-side duress enforcement; SecurePrefs fail-closed; SSRF/link-preview egress controls.
4. **Rules + quotas + supply chain (Round 3).** Tighten Firestore rules, add quotas/limits, lock CI supply chain.

## 4. Safe deployment order (per change)

Deploy server/Functions and rules changes to **staging** → verify with tests/evidence → canary → production. Client (APK) ships only AFTER the server enforces the new contract, so an old client never depends on a not-yet-deployed server path, and a new server never rejects a not-yet-migrated legitimate client (dual-accept window governed by feature flag).

## 5. Rollback order (reverse of deploy)

Production → canary → staging. Feature flags allow disabling a new enforcement path in staging without reverting code. **Fail-open is never a rollback target in production** (DL-002): if the new path fails, deny-closed remains.

## 6. Credential rotation order (S09-C1, S09-C2, SC-2)

1. Generate NEW service account; grant least-privilege; deploy to CI secret store.
2. Swap Functions/back-end to new service account; verify; then **disable/delete old** service account key.
3. Generate NEW upload key; enroll with Play App Signing; verify a signed build; then retire old upload key (keep offline until Play confirms, then destroy).
4. Purge both from working tree + history-rewrite plan; add `.gitignore` + secret-scan gate.
5. Rotate any other credentials that touched the repo (API keys in `google-services.json` scope, etc.) per RISK_REGISTER.

## 7. Zero-downtime strategy

- `/mintToken` runs a **dual-accept window**: server accepts both legacy and proof-of-seed requests while clients migrate, with metrics on legacy usage. Flag flips to proof-of-seed-only once legacy usage reaches zero. At no point is fail-open enabled.
- Media authorization ships deny-closed with a short dual-read compatibility shim for in-flight tokens (bounded by token TTL), then the shim is removed.

## 8. Handling already-exposed secrets

Treat every historically committed secret as compromised (DL-001). Rotation (not deletion) is the remediation. History rewrite (`git filter-repo`) is scheduled AFTER rotation so that even recovered history yields only dead credentials. Document exposure window and rotation timestamp in evidence/round-1/.
