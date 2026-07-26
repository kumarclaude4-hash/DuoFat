# Developer Setup Guide

This guide covers everything you need to build DuoShield locally and understand the infrastructure.

---

## Android App

### Requirements

| Tool | Version | Install |
|---|---|---|
| JDK | 17 (Temurin recommended) | [Adoptium](https://adoptium.net) |
| Android SDK | API 34, build-tools 34.0.0 | Android Studio or `sdkmanager` |
| Gradle | 8.7 | Included via wrapper (`./gradlew`) |

### First-time setup

```bash
# 1. Point Gradle at your SDK
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
# On macOS: echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 2. Create stub google-services.json (uses template — no Firebase calls will work)
cp app/google-services.json.template app/google-services.json

# 3. Create stub service-account.json
mkdir -p app/src/main/assets
echo '{"type":"service_account","project_id":"duoshield-8caf1"}' \
  > app/src/main/assets/service-account.json
```

### Build commands

```bash
# Compile check (~15 s with warm Gradle, ~2 min cold)
./gradlew :app:compileDebugJavaWithJavac --no-daemon

# Lint (static analysis)
./gradlew :app:lintDebug --no-daemon --continue

# Debug APK (not signed for release)
./gradlew :app:assembleDebug --no-daemon

# Signed release APK (requires real secrets — see below)
bash build-release.sh
```

### Secrets for a real build

Set these as environment variables (or in `local.properties`) before running `build-release.sh`:

| Variable | Source |
|---|---|
| `GOOGLE_SERVICES_JSON` | Firebase Console → Project Settings → google-services.json |
| `GOOGLE_APPLICATION_CREDENTIALS_JSON` | Google Cloud → IAM → Service Account JSON |
| `KEYSTORE_BASE64` | `base64 app/duoshield-release.keystore` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | `duoshield` |
| `KEY_PASSWORD` | Key password |
| `WORKER_URL` | Cloudflare Worker URL |
| `WORKER_SECRET` | Shared auth token |

---

## Push Server (Render)

The push server lives in `/server` and is deployed on **Render.com**.

```bash
cd server
npm install
node index.js
# Requires: GOOGLE_APPLICATION_CREDENTIALS_JSON env var
```

Endpoints:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/mintToken` | Mint Firebase custom auth token; rate-limited 1/60 s per userId |
| `POST` | `/turnCredentials` | Return Cloudflare TURN credentials to the app |
| `GET` | `/status` | Health check — returns uptime and delivery stats |

**Deploying:** Push to `main` → Render auto-deploys via Git integration.  
**Logs:** [Render Dashboard](https://dashboard.render.com) → DuoFat service → Logs tab.

---

## Cloudflare Worker (Tiered Media Storage)

The Worker lives in `/worker` and implements R2 hot → B2 cold tiered storage.

```bash
cd worker
npm install

# Local dev (uses .dev.vars for secrets)
cp .dev.vars.template .dev.vars
# Edit .dev.vars with your credentials
npx wrangler dev

# Deploy to production
npx wrangler deploy
```

### Worker secrets (set once via wrangler)

```bash
npx wrangler secret put B2_ACCESS_KEY_ID       # Backblaze key ID
npx wrangler secret put B2_SECRET_ACCESS_KEY   # Backblaze application key
npx wrangler secret put WORKER_SECRET          # shared auth token (must match BuildConfig.WORKER_SECRET)
```

### Storage tiers

| Tier | Service | Retention | Use |
|---|---|---|---|
| Hot | Cloudflare R2 (`duoshield-hot`) | 0–30 days | Recent media, fast access, free egress |
| Cold | Backblaze B2 (`duoshield-cold`, `eu-central-003`) | Permanent | Older media, low cost |

Daily cron at 02:00 UTC migrates objects older than 30 days from R2 → B2.

---

## Firestore

```bash
# Install Firebase CLI
npm install -g firebase-tools
firebase login

# Deploy security rules
firebase deploy --only firestore:rules

# Deploy composite indexes
firebase deploy --only firestore:indexes

# Run rules unit tests
cd firestore-tests
npm install
firebase emulators:exec --only firestore --project duoshield-test "npm test"
```

---

## CI / GitHub Actions

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | push / PR to `main` | Lint + debug APK |
| `release.yml` | push to `main` | Signed release APK → GitHub Release |
| `firestore-rules-test.yml` | changes to `firestore.rules` or `firestore-tests/` | Emulator-based rules tests |
| `firestore.yml` | push to `main` | Deploy rules + indexes |

Lint reports and APKs are uploaded as GitHub Actions artifacts (14-day retention).
