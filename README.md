<div align="center">

<img src="https://raw.githubusercontent.com/kumarclaude4-hash/DuoFat/main/app/src/main/res/drawable/ic_secure.png" width="88" alt="DuoShield"/>

# DuoShield

**Military-grade end-to-end encrypted messaging for Android.**  
No servers read your messages. No metadata sold. No backdoors.

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20%E2%80%93%20Oreo-4285F4?style=flat-square&logo=android)](https://developer.android.com/about/versions/oreo)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35-4285F4?style=flat-square)](https://developer.android.com)
[![Signal Protocol](https://img.shields.io/badge/Signal%20Protocol-libsignal%200.54.1-9A81FF?style=flat-square)](https://signal.org/docs/)
[![License](https://img.shields.io/badge/License-Proprietary-D96A7C?style=flat-square)](#license)
[![Version](https://img.shields.io/badge/Version-1.3-6BBF8A?style=flat-square)](#changelog)

</div>

---

## Why DuoShield?

| Feature | DuoShield | WhatsApp | Signal | Telegram |
|---|:---:|:---:|:---:|:---:|
| Signal Protocol E2EE | ✅ | ✅ | ✅ | ❌ (opt-in) |
| Post-Quantum (PQXDH + Kyber-1024) | ✅ | ❌ | ✅ | ❌ |
| Duress PIN (silent wipe) | ✅ | ❌ | ❌ | ❌ |
| No phone number required | ✅ | ❌ | ❌ | ❌ |
| Encrypted local DB (SQLCipher) | ✅ | ❌ | ✅ | ❌ |
| Encrypted media at rest | ✅ | ❌ | ✅ | ❌ |
| Seed-phrase account restore | ✅ | ❌ | ❌ | ❌ |
| No cloud backup of keys | ✅ | ❌ | ✅ | ❌ |
| Encrypted voice & video calls | ✅ | ✅ | ✅ | ✅ |
| Safety number verification | ✅ | ❌ | ✅ | ❌ |

---

## Features

### 🔐 Cryptography
- **Signal Protocol** — Double Ratchet algorithm with X3DH key agreement (PQXDH with Kyber-1024 for post-quantum resistance)
- **AES-256-GCM** throughout — group keys, media files, voice notes, backups
- **SQLCipher** — database key derived via HKDF-SHA256 from the user's UID
- **PBKDF2-HMAC-SHA256** — 310 000 iterations for PIN/duress PIN hashing
- **Signed pre-keys** — rotated weekly; 25-key batch upload when stock falls below 10

### 💬 Messaging
- **1-to-1 and group chats** — real-time via Firestore + FCM, offline delivery queue
- **Voice messages** — AES-256-GCM encrypted, streamed from Backblaze B2 with waveform scrubbing and 1×/1.5×/2× playback speed
- **Media sharing** — photos and videos (up to 500 MB) encrypted before upload; 50 MB+ videos stream-encrypt to disk (zero full-file allocation)
- **Message reactions** — quick emoji reactions with live sync
- **Reply, forward, edit, star, pin** — full message action sheet
- **Disappearing messages** — per-conversation timer, enforced by scheduled Cloud Function sweep
- **Link previews** — inline domain + title cards

### 🛡 Security
- **Duress PIN** — secondary PIN silently wipes all local data, keys, and Firestore content
- **App lock** — biometric or PIN with configurable auto-lock timeout
- **Safety numbers** — Signal-style identity verification; banner on key change
- **Delivery & read receipts** — FCM-backed with race-condition guard against status downgrade
- **Self-destruct** — scheduled remote wipe of messages, media, and Firestore data
- **No-screenshot mode** — `FLAG_SECURE` on all sensitive screens (pre-release gate)

### 📞 Calls
- **WebRTC E2EE voice + video calls** — DTLS-SRTP, TURN via Cloudflare
- **Adaptive bitrate** — Bandwidth Estimation on 64-bit, hard cap on 32-bit
- **TURN relay** — 100 GB/user/month; usage shown in Settings

### 🔑 Account & Recovery
- **Seed phrase** — 24-word BIP-39 mnemonic; entire identity derived deterministically
- **No phone number or email** — account identified by seed-derived UID (`XXXXX-XXXXX-XXX`)
- **Backup & restore** — PBKDF2-protected encrypted backup, seed-phrase-based account restore

### 🎨 UX
- **Obsidian Dark theme** — deep violet surfaces with Brand Lavender (#9A81FF) accents
- **Skeleton loading** — shimmer placeholders on first load
- **Swipe-to-archive** — conversation archiving with undo Snackbar
- **Search** — real-time conversation and message search
- **QR-code contact add** — ZXing gallery scan, deep-link (`duoshield://add/`), clipboard paste
- **Typing indicators** — animated CipherDots bubble

---

## Architecture

```
app/
├── crypto/
│   ├── signal/
│   │   ├── SignalCipherHelper.java     # Double Ratchet encrypt/decrypt
│   │   ├── SignalKeyManager.java       # Key generation, Kyber, pre-key rotation
│   │   ├── SignalSessionManager.java   # Session bootstrap (PQXDH / X3DH)
│   │   └── SignalPreKeyRefresher.java  # Batch pre-key upload (threshold=10, batch=25)
│   ├── GroupCipherHelper.java          # AES-256-GCM shared group key
│   └── BackupCryptoHelper.java         # PBKDF2 + AES-256-GCM backup encryption
├── db/
│   └── AppDatabase.java               # Room v12 + SQLCipher; UID-scoped DB key
├── models/                            # Contact, Group, GroupMember, Message, Conversation
├── call/
│   ├── CallActivity.java              # WebRTC UI (voice + video)
│   ├── IncomingCallActivity.java
│   └── WebRtcEngine.java              # PeerConnection, ICE, TURN, adaptive bitrate
├── util/
│   ├── FirebaseCostGuard.java         # Singleton — all Firestore I/O gated here
│   ├── B2StorageHelper.java           # SigV4 PUT/DELETE/GET; stream-encrypt for >50 MB
│   ├── AppLockManager.java            # AtomicInteger ref-count lifecycle
│   └── NotificationStyler.java
├── ui/
│   ├── TypingDotsView.java            # Animated typing indicator
│   ├── WaveformView.java              # Voice-note waveform with progress scrubbing
│   └── MatrixRainView.java            # Splash screen background
├── BaseActivity.java                  # All sensitive screens extend this
├── ChatMediaActivity.java             # 1-to-1 messaging (3 100 + lines)
├── GroupChatActivity.java
├── ConversationListActivity.java
└── SplashActivity.java → MainActivity.java → (routing trampoline)
```

### Data flow — sending a message

```
User types → sendMessage()
    └─ SignalCipherHelper.encrypt()           // Double Ratchet
        └─ Firestore.set(conversationId/messages/msgId)
            └─ ConversationMetaUpdater.updateLastMessage()  // plaintext preview ≤80 chars
                └─ FCM push via Push Server (Render)
                    └─ Recipient: DuoShieldMessagingService.onMessageReceived()
                        └─ SignalCipherHelper.decrypt()
                            └─ Room DB insert → RecyclerView DiffUtil update
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 (Android) |
| Encryption | libsignal-android 0.54.1 · AES-256-GCM · Kyber-1024 |
| Database | Room 2.6 + SQLCipher 4.5 |
| Cloud | Firebase Firestore + Firebase Auth |
| Media storage | Backblaze B2 (SigV4 signed) · Cloudflare R2 hot cache |
| Push | Firebase Cloud Messaging + OneSignal 5.x |
| Calls | WebRTC (stream-webrtc-android) + Cloudflare TURN |
| Image loading | Glide 4.16 |
| QR codes | ZXing |
| Build | Gradle 8.11 · JDK 17 |

---

## Building

### Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17 |
| Android SDK | API 35, build-tools 35.0.0 |
| `google-services.json` | From your Firebase project (template at `app/google-services.json.template`) |

### Quick start

```bash
# 1. Firebase config
cp app/google-services.json.template app/google-services.json
# Fill in your Firebase project_id, api_key, app_id, etc.

# 2. local.properties
cat > local.properties << 'EOF'
sdk.dir=/path/to/android-sdk
b2.key.id=YOUR_B2_KEY_ID
b2.application.key=YOUR_B2_SECRET
b2.bucket=your-bucket-name
b2.region=eu-central-003
push.server.url=https://your-fcm-server.com
worker.url=https://your-cloudflare-worker.dev
worker.secret=YOUR_WORKER_SECRET
EOF

# 3. Debug build
./gradlew :app:assembleDebug

# 4. Release build (requires signing env vars)
./gradlew :app:assembleRelease
```

### Signing env vars (release builds)

```
KEY_STORE_PASSWORD=…
KEY_ALIAS=duoshield
KEY_PASSWORD=…
```

---

## CI / GitHub Actions

| Workflow | Trigger | Output |
|---|---|---|
| `ci.yml` | Push to `main`, PRs | Lint report + debug APK |
| `release.yml` | Manual (`workflow_dispatch`) | Signed release APKs + GitHub Release |
| `firestore.yml` | Changes to `firestore.rules` | Firestore security rules test suite |

### Required GitHub repository secrets

| Secret | Description |
|---|---|
| `GOOGLE_SERVICES_JSON` | Full contents of `google-services.json` |
| `KEYSTORE_BASE64` | Release keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (`duoshield`) |
| `KEY_PASSWORD` | Key password |
| `B2_KEY_ID` | Backblaze B2 key ID |
| `B2_APPLICATION_KEY` | Backblaze B2 application key |
| `B2_BUCKET` | B2 bucket name |
| `B2_REGION` | B2 region (e.g. `eu-central-003`) |
| `PUSH_SERVER_URL` | Base URL of the FCM push server |
| `WORKER_URL` | Cloudflare Worker URL |
| `WORKER_SECRET` | Cloudflare Worker shared secret |

---

## Push Notification Server

The FCM dispatch server lives in [`server/`](server/). It is a stateless Node.js relay — it **never** reads message content.

```bash
cd server
npm install
node index.js          # default port 3000
```

**Production:** deployed on [Render](https://render.com).  
Set `GOOGLE_APPLICATION_CREDENTIALS_JSON` (Firebase service-account JSON) in your hosting environment.

---

## Cloudflare Worker (media cache)

The tiered storage worker lives in [`worker/`](worker/). Uploaded media lands in **Cloudflare R2** (hot, 30-day TTL) and is promoted to **Backblaze B2** (cold, permanent).

```bash
cd worker
npm install
npx wrangler deploy    # deploys via Cloudflare Git integration
```

---

## Firestore Rules & Indexes

```bash
# Deploy security rules
firebase deploy --only firestore:rules

# Deploy composite indexes
firebase deploy --only firestore:indexes

# Run rules unit tests locally
cd firestore-tests && npm install
firebase emulators:exec --only firestore "npm test"
```

---

## Security

Vulnerability disclosures → [`SECURITY.md`](SECURITY.md)

### Key design decisions

- **On-device encryption** — plaintext never leaves the device; the server is a stateless FCM relay
- **Post-Quantum** — PQXDH with Kyber-1024 for new sessions; `ensureKyberKeyExists()` upgrades legacy accounts at launch; corrupt-key guard with automatic regeneration
- **Zero-knowledge DB** — SQLCipher key derived via HKDF-SHA256 from the UID; inaccessible without the seed phrase
- **`FirebaseCostGuard` singleton** — all Firestore reads/writes gated; prevents runaway listener cost
- **`AppLockManager` ref-count** — AtomicInteger lifecycle prevents lock bypass on rapid resume/backgrounding
- **Duress PIN two-phase** — wipe guard flag committed to prefs before starting intent; prevents race on force-quit
- **Status downgrade guard** — any `status` write transactions against the `read → delivered` regression

---

## Changelog

See [`CHANGELOG.md`](CHANGELOG.md) for the full release history.

---

## License

**Proprietary — all rights reserved.**  
No part of this source code may be reproduced, distributed, or used in any form without explicit written permission from the author.

---

<div align="center">

Built with 🔐 and ☕ — because privacy is a right, not a feature.

</div>
