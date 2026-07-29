# DuoShield

Military-grade end-to-end encrypted messaging for Android. Signal Protocol (Double Ratchet + PQXDH + Kyber-1024) with zero-knowledge local database (SQLCipher) and Cloudflare tiered media storage.

## Stack

| Layer | Technology |
|---|---|
| Android app | Java, minSdk 26, targetSdk 34, Room v21, SQLCipher 4.5 |
| Encryption | libsignal-android 0.54.1 (PQXDH + Kyber-1024) |
| Backend | Firebase Firestore + Auth + FCM |
| Push relay | Node.js on Render (`server/`) |
| Media storage | Cloudflare Worker (R2 hot → B2 cold) (`worker/`) |
| Build CI | GitHub Actions (`.github/workflows/`) |

## Project layout

```
app/              Android application source
  src/main/java/com/duoshield/app/
    auth/           Custom-token Firebase auth
    backup/         E2EE cloud backup (AES-256-GCM + GZIP)
    call/           WebRTC voice/video calling
    contacts/       Contact management (QR, deep-link, clipboard)
    crypto/         SeedPhraseHelper, SignalCipherHelper, GroupCipherHelper
    db/             Room database, DAOs, migrations
    models/         Data models (Contact, Conversation, Group, Message)
    notifications/  FCM service, NotificationStyler
    security/       PinManager, AppLockManager
    ui/             Custom views (WaveformView, TypingDotsView, etc.)
    util/           B2StorageHelper, FirebaseCostGuard, WipeHelper, etc.
server/           Node.js push relay (deployed on Render)
worker/           Cloudflare Worker — tiered media storage (R2 → B2)
scripts/          strip_signal_records.py, setup-android-sdk.sh
docs/             Architecture and security docs
firestore.rules   Firestore security rules
```

## Building the APK

APKs are built in GitHub Actions — not on Replit. The release workflow requires these GitHub Secrets:

| Secret | Purpose |
|---|---|
| `GOOGLE_SERVICES_JSON` | Firebase config (full `google-services.json` contents) |
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (`duoshield`) |
| `KEY_PASSWORD` | Key password |
| `WORKER_URL` | Cloudflare Worker URL |
| `WORKER_SECRET` | Shared auth token for Worker requests |
| `PUSH_SERVER_URL` | Push relay URL (default: `https://duofat.onrender.com`) |

The lint workflow uses `app/google-services.json.template` as a stub when `GOOGLE_SERVICES_JSON` is not set.

## Running the push server locally

```bash
cd server
npm install
node index.js   # runs on port 3000
```

Required environment variables for the server (set on Render for production):
- `FIREBASE_SERVICE_ACCOUNT` — service account JSON (stringified)
- `SESSION_SECRET` — express-session secret
- `WORKER_SECRET` — shared token (must match Android BuildConfig)
- `TURN_TOKEN_ID`, `TURN_API_TOKEN` — Cloudflare TURN API credentials

## Cloudflare Worker

```bash
cd worker
npm install
npx wrangler deploy   # deploys to kumarclaude4.workers.dev
```

Set `WORKER_SECRET` via `wrangler secret put WORKER_SECRET` before deploying.

## Key architecture rules (do not violate)

- **FirebaseCostGuard singleton** — all Firestore reads/writes must go through it; prevents quota exhaustion
- **One listener per screen** — detach in `onDestroy()`; never create multiple listeners for the same path
- **BaseActivity** — all sensitive activities must extend `BaseActivity` for app-lock enforcement; only pre-auth onboarding screens extend `AppCompatActivity`
- **libsignal-client must be `implementation`** — it is NOT `compileOnly`; see `app/build.gradle` for the three-line dependency structure and the stripped JAR explanation
- **HKDF off-limits** — use `SeedPhraseHelper.hkdfSha256()` (public, 3 args); never import `org.signal.libsignal.protocol.kdf.HKDF`
- **No Cloud Functions** — all server logic lives in `server/index.js` on Render
- **Room DB current version: 21** — always add a migration when bumping; SQLCipher passphrase derived in `DatabaseKeyProvider`
- **lastMessage is plaintext** — `ConversationMetaUpdater` writes a ≤80-char preview; never encrypt it again
- **Message status downgrade guard** — any `status` write must be transacted to prevent `read → delivered` regression

## User preferences

- Push every change to `origin/main` via `GIT_PAT` secret after completing work
- Never attempt to fix or run the push server on Replit — it lives on Render
- Ask before adding new secrets or environment variables; user controls what's in the Secrets tab
