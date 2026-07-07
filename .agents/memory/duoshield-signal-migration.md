---
name: DuoShield Signal Protocol migration
description: ECDH/CryptoHelper stack fully removed; all encryption now via Signal Protocol (libsignal-android 0.54.1). Room DB v11.
---

# Signal Protocol Migration

## What changed
- `CryptoHelper`, `ECDHHelper`, `CryptoInitializer`, `KeyManager`, `EncryptionHelper`, `MediaHelper`, `VoiceNoteHelper`, `MessageViewModel`, `ConversationViewModel` all **deleted**.
- All message encryption/decryption goes through `SignalCipherHelper` which wraps `SessionCipher`.
- `ChatMediaActivity.ensureSignalSession()` replaced `reEnsureEcdhKey()`.
- Media files (`SupabaseStorageHelper`) are encrypted AES-256-GCM per-file; key stored in Firestore `mediaKey` field and Room `messages.mediaKey` column.
- Pairing still uses Firestore + 6-digit code but ECDH derivation removed; Signal pre-key bundle exchanged instead.

## Room DB
- Version: **11** (MIGRATION_10_11 adds `messages.mediaKey TEXT`)
- Version chain: MIGRATION_8_9 → signal_sessions table; MIGRATION_9_10 → messages.sigType; MIGRATION_10_11 → messages.mediaKey
- Next migration (if needed): v11 → v12. Write `MIGRATION_11_12` and add to `addMigrations()`.

**sigType semantics:**
- 0 = legacy ECDH message (no Signal; shown as "[Legacy message — not decryptable]")
- 1 = `SignalMessage` (WHISPER_TYPE, normal Double Ratchet)
- 3 = `PreKeySignalMessage` (PREKEY_TYPE, session initiation)

**Why:** `sigType` lets `SignalCipherHelper.decrypt()` choose the right message parser; old rows default to 0 so the legacy path still handles them gracefully.

## Key storage
- Signal identity key pair + pre-keys → `SignalKeyManager` (EncryptedSharedPreferences).
- Mnemonic seed phrase → `SeedPhraseHelper` (derives identity key deterministically; never logged/stored after display).
- No ECDH shared key in SharedPreferences anymore (`ecdh_shared_key` key is obsolete).

## Architecture rules carried forward
- One Firestore listener per screen; null-guard before attaching.
- DiffUtil always for RecyclerView adapters.
- No fallbackToDestructiveMigration on shipped builds.
- Mnemonic must never be logged or persisted after first display.
