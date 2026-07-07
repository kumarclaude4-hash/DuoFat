---
name: DuoShield libsignal explicit load
description: libsignal-android 0.54.x no longer auto-loads its native library; apps must call System.loadLibrary("signal_jni") explicitly or every JNI call throws UnsatisfiedLinkError.
---

## Rule
Call `System.loadLibrary("signal_jni")` explicitly in `DuoShieldApp.onCreate()`, synchronously, before any other code that might touch libsignal.

## Why
In libsignal-android 0.54.x, the automatic `System.loadLibrary("signal_jni")` call was removed from the Java static initializers. Without the explicit call, every JNI-backed libsignal method (`Curve.decodePrivatePoint`, `Curve.generateKeyPair`, `SessionCipher.encrypt`, etc.) throws `UnsatisfiedLinkError`. The `friendlyError()` handler in `SeedPhraseDisplayActivity` maps this to "Encryption library failed to load, please reinstall the app." The `signal_jni` string literal was absent from every DEX file until the explicit load was added — confirmed by raw DEX byte search.

## How to apply
- The explicit load is in `DuoShieldApp.onCreate()` (very first block, before Firestore init).
- Wrapped in `try/catch (UnsatisfiedLinkError)` so a clear log is emitted if the .so is missing.
- The `.so` is stored uncompressed ("Stored", 0% compression) in the APK for all 4 ABIs (arm64-v8a, armeabi-v7a, x86, x86_64) — confirmed correct.
- Do NOT remove the explicit load or move it after any code that accesses libsignal classes.
- `libsignal_jni_testing.so` is also in the APK (from the debug AAR); this is normal and does not need to be loaded explicitly.
