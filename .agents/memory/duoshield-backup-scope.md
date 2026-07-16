---
name: DuoShield backup system improvement scope
description: Prioritised list of backup system improvements from the scope doc (June 2026)
---

# DuoShield Backup System — Improvement Scope

Source: `attached_assets/DuoShield_Backup_System_-_Final_Scope_of_Improveme_(2)_1782572302804.md`

## Auth model reminder
- 12-word BIP39 mnemonic → 64-byte seed (PBKDF2-SHA512, 2048 iters)
- Seed → deterministic User ID (SHA-256 → first 8 bytes → Base32 XXXXX-XXXXX-XXX)
- Seed → Signal identity keypair (HKDF-SHA256)
- Seed → Backup encryption key (HKDF-SHA256, info="DUOSHIELD_BACKUP_V1", 32 bytes)
- Mnemonic is NEVER stored — ephemeral only

## Critical (before production)
1. **Backup integrity checksums** — SHA-256 of plaintext stored alongside `enc` field in Firestore; verified on restore; mismatch = permanent data loss (no account recovery possible)
2. **Firestore security rules** — `match /backups/{userId}` allow only `request.auth.uid == userId`; hard-deny delete on message sub-docs; identities collection write-blocked (server-side only)
3. **Backup key derivation verification** — confirm `BackupCryptoHelper.deriveBackupKey()` uses `SeedPhraseHelper.mnemonicToSeed()` → `SeedPhraseHelper.hkdfSha256(seed, "DUOSHIELD_BACKUP_V1", 32)` exactly
4. **Seed phrase storage audit** — grep for `mnemonic` in SharedPrefs/Firestore/Room/Log writes; must find ZERO

## High priority (after production)
5. **Compression** — GZIP before AES-GCM encrypt; expected 40–60% size reduction; wire format `Base64(IV):Base64(ciphertext||tag)`
6. **Incremental backup** — track `last_backup_timestamp` in SharedPrefs; only fetch `getMessagesSince(lastBackupTime)` from Room
7. **Backup monitoring** — log events (backup_started / backup_complete / backup_failed) to Firestore `backup_logs` collection

## Medium / optional
8. Size limits — warn at > 10 000 messages
9. Retention policy — delete docs with `ts < now - 90 days` via batch
10. Key rotation — complex (requires re-encrypting all backups); skip for MVP

## Testing plan (2-person app)
- Quick: create account → send 10 msgs → backup → wipe → restore → verify all 10 present
- Security: try to decrypt User B's backup with User A's key (must fail); verify Firestore rules deny cross-user access; verify checksum detects corruption

**Why:** Seed-phrase auth = zero account recovery options. Backup integrity failures = permanent data loss.
