---
name: DuoShield key storage
description: Where crypto keys live and how to access them after the HANDOFF_v2 refactor
---

All crypto material is stored in EncryptedSharedPreferences (not plain SharedPreferences).

**Where:** `SecurePrefs.get(context)` returns the encrypted prefs instance.

**Keys:**
- `CryptoInitializer.KEY_EC_PUBLIC`         → my ECDSA/ECDH P-256 public key (Base64)
- `CryptoInitializer.KEY_EC_PRIVATE`        → my private key (PKCS8 Base64)
- `CryptoInitializer.KEY_SHARED_AES`        → 32-byte AES-256 shared key derived via ECDH (Base64)
- `CryptoInitializer.KEY_PARTNER_EC_PUBLIC` → partner's EC public key (Base64), stored by PairingManager

**Why:** Original code used plain SharedPreferences for private key material — plaintext on disk.

**How to apply:** Any new feature that reads or writes crypto keys must go through `SecurePrefs.get(context)`, never `context.getSharedPreferences(...)` directly for these fields.
