# Profile Picture Completion Audit

This file records the profile-picture wiring gaps found after commit `f684659` and the files involved. It is retained as a durable regression and deployment reference.

## Confirmed gaps and intended fixes

| Status | Gap | Affected files | Acceptance criteria |
|---|---|---|---|
| Complete | Existing avatar is not restored after reinstall/new-device sign-in | `app/src/main/java/com/duoshield/app/ui/SettingsActivity.java` | Settings loads locally first, then reads `users/{uid}.photoUrl`, downloads a valid `b2:avatars/...` object, and refreshes the durable local cache. |
| Complete | Local avatar survives canonical local-data wipe | `app/src/main/java/com/duoshield/app/util/WipeHelper.java` | `files/own_avatar.jpg` and avatar temporary files are deleted by `eraseLocalData`. |
| Complete | Replacing a photo leaves the previous B2 object orphaned | `SettingsActivity.java`, existing delete support in `B2StorageHelper.java` | After successful publication, the prior owned avatar object is deleted best-effort. |
| Complete | Success is shown before Firestore publication completes | `SettingsActivity.java` | Success and durable local replacement happen only after the user document and partner propagation writes complete. Failed publication removes the newly uploaded object best-effort and preserves the old local state. |
| Complete | Arbitrary selected bytes are named and uploaded as JPEG | `SettingsActivity.java` | Input is decoded safely, EXIF-oriented, square-cropped, bounded, and encoded as actual JPEG before upload. |
| Complete | Selected images have no decode/dimension/upload-size limits | `SettingsActivity.java` | Bounds-first decode, downsampling, pixel limits, and final byte ceiling prevent unbounded memory/upload use. |
| Complete | Rotation and crop are not normalized | `SettingsActivity.java` | EXIF rotation/flip is honored and output is a centered square avatar. |
| Complete | Avatar key authorization lacked regression coverage | `server/lib/mediaScope.test.js` | Tests cover valid keys, malformed keys, owner-only write/delete, authenticated reads, and fail-closed input. |
| External deployment | Live server/Worker may predate avatar-key authorization | `server/index.js`, `server/lib/mediaScope.js`, `worker/src/index.js` | Both push server and Cloudflare Worker run avatar-aware code. Deployment is a separate explicit operation. |

## Resolved defects

Most rows above were already implemented in code; only two were genuinely outstanding.

1. **Build-breaking call to a non-existent method.** `SettingsActivity.publishUploadedPhoto` called
   `propagatePhotoToConversations(uid, path)`, which was never defined anywhere in the tree — the
   module could not compile. The intended implementation, `publishPhotoReferences(uid, path)`, was
   present but unreferenced (dead code). `publishUploadedPhoto` now calls `publishPhotoReferences`.
   This also removed a correctness bug in the old shape: it paired a standalone `users/{uid}` write
   with a separate propagation task via `Tasks.whenAll`, which can partially succeed and leave
   `users/{uid}.photoUrl` updated while the denormalized `partnerPhotoUrl_<uid>` chat fields are
   stale. `publishPhotoReferences` performs both in a single atomic `WriteBatch`, so step 6 of the
   data flow ("only after all publication writes succeed") now actually holds. The
   `com.google.android.gms.tasks.Tasks` import became unused and was removed.

2. **Local avatar survived the canonical wipe.** `WipeHelper.eraseLocalData` never deleted
   `files/own_avatar.jpg`. That file sits directly in `filesDir`, so neither the `b2_cache` clear
   nor the `getCacheDir()` delete reached it, and it is a plain unencrypted JPEG of the account
   holder's face. A new step 3a deletes both it and the `own_avatar.jpg.tmp` staging sibling left
   by the atomic write-then-rename in `saveOwnAvatarToDisk`. The `my_photo_url` and
   `own_avatar_uid` pointer keys were already covered by the existing `prefs.edit().clear()`.

## Data flow that must remain wired

1. Settings photo button opens the system content picker.
2. Selected image is normalized locally to bounded JPEG bytes.
3. Client uploads `avatars/<uid>_<millis>.jpg` through the media capability-token flow.
4. `users/{uid}.photoUrl` is updated with the `b2:` path.
5. Existing chats receive `partnerPhotoUrl_<uid>` so partner conversation avatars refresh.
6. Only after all publication writes succeed is the local avatar/cache replaced.
7. The superseded owned B2 avatar is deleted best-effort.
8. A new installation restores the avatar from `users/{uid}.photoUrl` through authenticated B2 loading.
9. Canonical wipe deletes local avatar data.

## Security invariants

- Avatar writes and deletes are restricted to the UID embedded in the key.
- Avatar reads require an authenticated caller.
- Only `b2:avatars/<currentUid>_<timestamp>.jpg` is eligible for superseded-object deletion.
- Publication failures never discard the previously working local avatar.
- Input decoding is bounded and invalid image content is rejected.

## Verification checklist

- [x] Android debug Java compilation passes. Verified with JDK 17 (Corretto 17.0.19) + Android SDK platform-34 / build-tools 34.0.0: `./gradlew :app:compileDebugJavaWithJavac` → BUILD SUCCESSFUL, 11 pre-existing Room/Glide warnings, 0 errors. `./gradlew :app:assembleDebug` also succeeds, producing `app-arm64-v8a-debug.apk` and `app-armeabi-v7a-debug.apk` (so D8 dexing and the libsignal Record-desugaring path are exercised too). Counterfactual confirmed: recompiling the pre-fix commit reproduces `error: cannot find symbol: method propagatePhotoToConversations(String,String)` at line 455 → BUILD FAILED.
- [ ] Relevant Android tests pass or unrelated failures are recorded. **Blocked by a pre-existing, unrelated repo defect:** `:app:testDebugUnitTest` fails during dependency verification because commit `674d4d0` ("enable Robolectric for Android unit tests") added `org.robolectric:robolectric:4.14.1` without regenerating `gradle/verification-metadata.xml`. ~30 Robolectric/sqlite4java/testparameterinjector artifacts have no trusted checksums, so the test classpath cannot resolve on any machine. This is not caused by the avatar changes and was deliberately left unfixed — adding those hashes is a supply-chain trust decision that belongs in its own reviewed change, not silently bundled into an avatar fix. See "Known unrelated blocker" below.
- [x] `server/lib/mediaScope.test.js` covers avatar authorization.
- [x] Targeted media-scope tests pass. `node --test server/lib/mediaScope.test.js` → 24 passed, 0 failed.
- [ ] Manual device check: choose rotated PNG/HEIC/JPEG and observe a square correctly oriented avatar.
- [ ] Manual device check: reinstall/sign in and observe remote avatar restoration.
- [ ] Manual device check: replace avatar and confirm previous B2 object is removed.
- [ ] Manual device check: force publication failure and confirm old avatar remains.
- [ ] Manual device check: wipe/unpair and confirm no local avatar remains.
- [ ] Push server and Cloudflare Worker deployment confirmed separately.

## Known unrelated blocker: unit tests cannot resolve their classpath

`:app:compileDebugJavaWithJavac` and `:app:assembleDebug` both pass, but
`:app:testDebugUnitTest` fails before a single test runs:

```
Dependency verification failed for configuration ':app:debugUnitTestRuntimeClasspath'
  - robolectric-4.14.1.jar (org.robolectric:robolectric:4.14.1) ... no trusted checksum
  (plus ~30 more: sandbox, shadows-framework, sqlite4java, test-parameter-injector, …)
```

`gradle/verification-metadata.xml` contains zero `org.robolectric` entries, while
`app/build.gradle` has declared Robolectric since commit `674d4d0`. The metadata file
was last touched in `d7db0b2`, before that dependency was added, so the JVM unit-test
suite has been unrunnable for everyone since — this is independent of the profile
picture work and reproduces on a clean checkout.

Fixing it means asserting that ~30 new third-party artifacts are trustworthy, which is
exactly the decision Gradle dependency verification exists to force someone to make
explicitly. It should be its own reviewed commit, not a side effect of an avatar fix.
To regenerate (and then review the diff before trusting it):

```
./gradlew --write-verification-metadata sha256 :app:testDebugUnitTest
```

### Reproducing the build verification

The sandbox used here had no JDK or Android SDK; both were installed to verify the
compile. On Amazon Linux 2023:

```
sudo dnf install -y java-17-amazon-corretto-devel unzip
export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
./gradlew :app:assembleDebug
```

`local.properties` and `app/google-services.json` are both gitignored. The latter is
absent from the repo by design (it holds API keys); a syntactically valid placeholder
derived from `app/google-services.json.template` is enough to satisfy the
`com.google.gms.google-services` plugin for a compile check, but **not** for a build
that must actually reach Firebase at runtime.
