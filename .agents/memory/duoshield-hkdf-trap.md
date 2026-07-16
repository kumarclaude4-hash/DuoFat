---
name: DuoShield libsignal dependency structure
description: libsignal-android 0.54.x AAR has NO Java classes — they live in libsignal-client JAR which must be `implementation` not `compileOnly`; HKDF class is still off-limits at runtime
---

# DuoShield libsignal dependency structure

## The rule
`libsignal-client` MUST be declared `implementation`, not `compileOnly`.
Never import or call `org.signal.libsignal.protocol.kdf.HKDF` in any runtime code path (see below).

**Why:** `libsignal-android` 0.54.x AAR contains ONLY the native `.so` file. All Java protocol classes (`Curve`, `IdentityKeyPair`, `SessionCipher`, `PreKeyBundle`, etc.) live exclusively in the `libsignal-client` JAR. Using `compileOnly` means the classes are absent from the APK DEX at runtime → `NoClassDefFoundError: Failed resolution of: Lorg/signal/libsignal/protocol/ecc/Curve;` → "Encryption library failed to load. Please reinstall the app."

The earlier `compileOnly` workaround avoided a D8 Records desugaring crash ("Attempt to create global synthetic without consumer") that affected AGP < 8.x. AGP 8.6.0 handles Records desugaring in external JARs correctly so `implementation` is safe.

**How to apply:** Keep the build.gradle wiring as:
```groovy
implementation('org.signal:libsignal-android:0.54.1') {
    exclude group: 'org.signal', module: 'libsignal-client'
}
implementation 'org.signal:libsignal-client:0.54.1'
```
The exclude prevents Gradle pulling `libsignal-client` twice (once via transitive, once via the explicit line), which would cause duplicate-class dexing errors.

## HKDF still off-limits
Never call `org.signal.libsignal.protocol.kdf.HKDF` directly — it is a libsignal-internal class not part of the public API surface. Use `SeedPhraseHelper.hkdfSha256(ikm, info, length)` — a private static RFC 5869 HKDF-SHA256 built on `javax.crypto.Mac`.

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(new byte[32], "HmacSHA256")); // salt = zeroes
byte[] prk = mac.doFinal(ikm);
mac.init(new SecretKeySpec(prk, "HmacSHA256"));
mac.update(info);
mac.update((byte) 0x01);
return Arrays.copyOf(mac.doFinal(), length);
```
