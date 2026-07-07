---
name: DuoShield release signing
description: Release keystore location, alias, and workflow setup for assembleRelease
---

# DuoShield Release Signing

## Rule
The release keystore is committed to the repo at `app/duoshield-release.keystore`. The `assembleRelease` workflow references it directly via `$PWD/app/duoshield-release.keystore` — no KEYSTORE_BASE64 env var is decoded at build time.

**Why:** The base64 was too long to paste into the Replit Secrets UI. Storing the file directly in the repo is simpler and the passwords are still kept as secrets.

## Keystore details
- File: `app/duoshield-release.keystore`
- Alias: `duoshield` (lowercase)
- Algorithm: RSA 2048, validity 10 000 days
- Passwords: stored as `KEYSTORE_PASSWORD` and `KEY_PASSWORD` Replit secrets (same value)

## assembleRelease workflow command (current)
```
bash setup-android-sdk.sh 2>/dev/null
&& ANDROID_HOME=/home/runner/android-sdk ./gradlew --stop 2>/dev/null
; python3 -c "import os; open('app/google-services.json','w').write(os.environ['GOOGLE_SERVICES_JSON'])"
&& python3 -c "import os; open('app/src/main/assets/service-account.json','w').write(os.environ['GOOGLE_APPLICATION_CREDENTIALS_JSON'])"
&& ANDROID_HOME=/home/runner/android-sdk ./gradlew :app:assembleRelease --no-daemon
  "-Pandroid.injected.signing.store.file=$PWD/app/duoshield-release.keystore"
  "-Pandroid.injected.signing.store.password=$KEYSTORE_PASSWORD"
  "-Pandroid.injected.signing.key.alias=$KEY_ALIAS"
  "-Pandroid.injected.signing.key.password=$KEY_PASSWORD"
2>&1 && echo RELEASE_APK_DONE
```

## How to apply
- Always stop Gradle daemons (`./gradlew --stop`) before a release build to avoid daemon-timeout failures when multiple builds ran previously in parallel.
- Do NOT run assembleDebug and assembleRelease in the same parallel workflow — they share the Gradle daemon and will race/timeout.
- Output APK: `app/build/outputs/apk/release/app-release.apk`

## Required Replit secrets
| Secret | Purpose |
|---|---|
| `GOOGLE_SERVICES_JSON` | google-services.json content |
| `GOOGLE_APPLICATION_CREDENTIALS_JSON` | Firebase service account JSON |
| `KEYSTORE_PASSWORD` | Keystore store password |
| `KEY_ALIAS` | `duoshield` |
| `KEY_PASSWORD` | Key password |
| `B2_KEY_ID` | Backblaze B2 key ID |
| `B2_APPLICATION_KEY` | Backblaze B2 application key |

## Required Replit env vars (shared)
| Var | Value |
|---|---|
| `B2_REGION` | `ca-east-006` |
| `PUSH_SERVER_URL` | Push server URL |
