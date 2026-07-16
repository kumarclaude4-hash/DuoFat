---
name: DuoShield B2 Storage
description: B2StorageHelper design, path format, credential injection, and media upload/viewer wiring.
---

## B2 Storage Implementation

**Path format**: `b2:<objectKey>` e.g. `b2:media/<chatId>/<uuid>.jpg`
- Stored in Firestore `path` field (same as Supabase paths)
- `B2StorageHelper.isB2Path()` checks for "b2:" prefix
- `toObjectKey()` strips prefix; `toPublicUrl()` builds full URL

**Endpoint/region** now configurable via BuildConfig (not hardcoded):
- `getEndpoint()` → `BuildConfig.B2_ENDPOINT` → fallback `"https://s3.ca-east-006.backblazeb2.com"`
- `getRegion()` → `BuildConfig.B2_REGION` → fallback `"ca-east-006"`, `SERVICE = "s3"`
- All three methods (uploadFile, downloadFile, deleteFile) derive `host` and `credentialScope` from `getRegion()`
- `getSigningKey(dateStamp, region)` takes region as param (not constant)

**Credentials** come from `BuildConfig.B2_KEY_ID`, `B2_APPLICATION_KEY`, `B2_REGION`, `B2_ENDPOINT`:
- build.gradle reads from local.properties first (`b2.key.id`, `b2.application.key`, `b2.bucket`, `b2.region`)
- Falls back to env vars: `B2_KEY_ID` / `B2_APPLICATION_KEY` / `B2_BUCKET` / `B2_REGION`
- `B2_BUCKET` defaults to `"duoshield-media"`, `B2_REGION` defaults to `"ca-east-006"` if not set
- `B2_ENDPOINT` is derived at build time as `"https://s3.${b2Region}.backblazeb2.com"`

**Connection test**: `B2StorageHelper.testConnection()` — synchronous, call from bg thread, returns null on success or error string on failure. Wired to "Test B2 Connection" button in SettingsActivity (Media Storage section).

**Encryption**: AES-256-GCM with fresh per-file key; wire format `[12-byte IV | ciphertext | GCM auth tag]`
- `encryptForUpload()` → EncryptedMedia{data, keyBase64}
- `decryptAfterDownload(data, keyBase64)` — symmetric inverse
- Public download (no auth) is fine because content is E2EE

**Upload** uses AWS SigV4:
- `SignedHeaders = content-length;content-type;host;x-amz-content-sha256;x-amz-date`
- `deleteFile()` uses `SignedHeaders = host;x-amz-content-sha256;x-amz-date`

**Why:**
- B2's S3-compatible API requires SigV4 auth for PUT/DELETE but allows public GET
- No AWS SDK added (avoids 10+ MB APK bloat) — all signing is native Java crypto

**How to apply:**
- New image/video uploads go through `ChatMediaActivity.uploadMediaWithRetry()` → `B2StorageHelper.uploadFile()`
- On Firestore failure: `deleteFile(storagePath)` is called on executor to clean up orphaned files
- `sendMediaMessage()` uses `B2StorageHelper.isB2Path(storagePath)` guard before cleanup

## Image/Video sizing
- Images: compressed to JPEG quality 75 before encrypt+upload (`compressImage()`)
- Videos: rejected if > 20 MB before any upload work (`getFileSize()` via ContentResolver)

## Viewer wiring
- Tap image bubble → `FullScreenImageActivity` (PhotoView pinch-zoom); decrypts via `B2StorageHelper.loadMedia()`
- Tap video bubble → `MediaViewerActivity` (ExoPlayer); B2/Supabase video decrypted to temp file then played
- `MediaViewerActivity` has `EXTRA_MEDIA_KEY` extra (new); download button saves to Movies/DuoShield

## 24-hour auto-delete
- `B2CleanupWorker` (in `db/`) — one-time WorkManager job, 24h initial delay
- Scheduled from `ChatMediaActivity.sendMediaMessage()` in `addOnSuccessListener` after Firestore write succeeds
- Worker deletes B2 file, then sets `path=null` + `mediaExpired=true` on the Firestore message doc
- `SelfDestructWorker.commitBatchDelete()` also now calls `B2StorageHelper.deleteFile()` for B2 paths (same pattern as Supabase)
- `B2CleanupWorker.schedule()` is a no-op if path is not a b2: prefix → safe to call unconditionally

## B2 credentials from Replit Secrets
- `B2_KEY_ID` and `B2_APPLICATION_KEY` set as Replit Secrets → picked up via `System.getenv()` in build.gradle
- `local.properties` b2.* entries are intentionally blank (env var fallback takes over)

## Android SDK for Replit builds
- SDK was NOT installed at `/home/runner/android-sdk` — required manual setup:
  ```bash
  mkdir -p /home/runner/android-sdk/cmdline-tools
  curl -o /tmp/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip /tmp/cmdtools.zip -d /tmp/cmdtools && mv /tmp/cmdtools/cmdline-tools /home/runner/android-sdk/cmdline-tools/latest
  export ANDROID_HOME=/home/runner/android-sdk && export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
  yes | sdkmanager --licenses && yes | sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
  ```
- `local.properties` must have `sdk.dir=/home/runner/android-sdk` (NOT committed to git; recreate on fresh env)
