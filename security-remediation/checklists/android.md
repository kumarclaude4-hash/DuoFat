# CHECKLIST — android (client)

- **Purpose:** Kotlin Android client; UI, local secure storage, duress UX, capability presentation.
- **Trust responsibility:** UNTRUSTED to the server. Must never be the sole enforcer of a security decision.
- **Secrets exposure risk:** SecurePrefs / Keystore material; any embedded keys in `google-services.json`; residue in logs/backups/screenshots.
- **Dependency relationships:** Consumes server token-mint + media endpoints; depends on server-authoritative duress/lock (Round 1).
- **Security responsibilities:** Fail-closed secure storage; wipe duress residue; no plaintext fallback; no client-only authz.
- **Files in scope:** SecurePrefs/crypto classes, duress handling, backup/`allowBackup` config, logging.
- **Findings affecting folder:** S06-C1 (client duress), S06-H/M residue, S07-H SecurePrefs fail-open, S08-* client platform residue/verifiable build.
- **Required changes:** SecurePrefs fail-closed (R2); duress residue wipe (R2); disable backups / redact logs (R2/R3); rely on server authz.
- **Verification steps:** Unit test SecurePrefs error path denies; inspect backup manifest; log scan for secrets; confirm no client-only gate.
- **Regression checks:** Normal login/storage still works; duress flow still triggers UX.
