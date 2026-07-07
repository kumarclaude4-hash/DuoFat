---
name: DuoShield libsignal D8 desugaring fix
description: libsignal 0.54.1 dependency config — libsignal-client MUST be compileOnly to avoid D8 Records crash; all runtime classes come from libsignal-android
---

## The Rule

`app/build.gradle` must have ALL of the following — removing any one breaks the build:

```groovy
compileOptions {
    coreLibraryDesugaringEnabled true   // REQUIRED — libsignal-android declares requiresCoreLibraryDesugaring=true
    sourceCompatibility JavaVersion.VERSION_1_8
    targetCompatibility JavaVersion.VERSION_1_8
}
```

```groovy
coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.4'

implementation('org.signal:libsignal-android:0.54.1') {
    exclude group: 'org.signal', module: 'libsignal-client'
}
compileOnly 'org.signal:libsignal-client:0.54.1'
```

And in `DuoShieldSignalStore.java`, `loadSenderKey` must return `null` (not `new SenderKeyRecord()`):
```java
public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
    return null; // no-arg constructor removed in 0.54.1; null = "no record exists"
}
```

**Why (three interlocking constraints):**

1. **`coreLibraryDesugaringEnabled` cannot be removed**: `libsignal-android:0.54.1` embeds `requiresCoreLibraryDesugaring=true` in its AAR metadata. AGP's `checkAarMetadata` task enforces this.

2. **`libsignal-client` MUST be `compileOnly`, NEVER `implementation`**: `libsignal-client-0.54.1.jar` contains Java Records. With `coreLibraryDesugaringEnabled=true`, external plain JARs are processed by `DexingNoClasspathTransform` (enableGlobalSynthetics=false). Records desugaring requires global synthetics → D8 crashes with "Attempt to create a global synthetic for 'Record desugaring' without a consumer". Making it `compileOnly` removes the JAR from D8's pipeline entirely. Runtime classes are provided by `libsignal-android` AAR's bundled `classes.jar`, processed by AGP's AAR pipeline (which does support global synthetics).

3. **The `exclude` is mandatory**: Without excluding `libsignal-client` from `libsignal-android`'s transitive deps, Gradle pulls it in both ways and D8 hits duplicate-class errors.

4. **`SenderKeyRecord()` no-arg constructor was removed in 0.54.1**: stub `loadSenderKey` must return `null`.

5. **`org.signal.libsignal.protocol.kdf.HKDF` is NOT called anywhere in DuoShield source**: `SeedPhraseHelper` implements RFC 5869 HKDF via `javax.crypto.Mac`. This avoids any runtime missing-class issue for HKDF, which may or may not be present in `libsignal-android` (its presence there is uncertain). Do NOT change this to call libsignal's HKDF directly.

**Failed attempts to avoid (permanent reference):**
- `implementation 'org.signal:libsignal-client:0.54.1'` → D8 build crash: "Attempt to create a global synthetic for 'Record desugaring' without a consumer"
- Remove `exclude` → D8 duplicate-class error
- Remove `coreLibraryDesugaringEnabled` → `checkAarMetadata` fails

**How to apply when upgrading libsignal:**
Check if the new version still embeds `requiresCoreLibraryDesugaring=true`. If so, keep all four components. If that flag is removed in a future release, the exclude+compileOnly pattern can be revisited.
