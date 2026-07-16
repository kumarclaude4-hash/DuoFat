---
name: DuoShield SecurePrefs singleton
description: SecurePrefs.get() is now a cached singleton with isAvailable() guard
---

## Rule
`SecurePrefs.get(ctx)` returns a cached `SharedPreferences` instance (thread-safe double-checked locking). After the first call, `SecurePrefs.isAvailable()` reliably reports whether the backing store is actually encrypted.

## Why
The old code re-created `EncryptedSharedPreferences` on every call and silently fell back to plain `SharedPreferences` on failure. This meant EC keys and PIN hashes could be stored in plaintext without any indication — directly undermining all the security bug fixes (7, 8, 11, 12, 14, 15, 20).

## How to apply
- `CryptoInitializer.getSharedKey()` and `getMyPrivateKey()` return `null` when `!SecurePrefs.isAvailable()` → forces re-pairing rather than reading potentially-plaintext key material.
- `SecurePrefs.reset()` exists for `WipeHelper` / test use — clears the cache so next `get()` re-attempts EncryptedSharedPreferences creation.
- `ensureKeyExists(Context)` (called at startup from `MainActivity.route()`) is always the first call to `SecurePrefs.get()`, so `isAvailable()` is reliable by the time any key getter runs.
- `isAvailable()` returns `false` if `initialized == false` (before first `get()` call) — safe default: treats keys as absent.
