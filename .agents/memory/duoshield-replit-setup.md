---
name: DuoShield Replit environment setup
description: Current workflow configuration and Replit migration state
---

# DuoShield Replit Environment Setup

## Current workflow configuration
| Workflow | Purpose | Run button? |
|---|---|---|
| `Push Server` | Node.js FCM push + mintToken server, port 3000 | ✅ Yes |
| `assembleRelease` | Signed release APK build | No (trigger manually) |

`assembleDebug` was removed — the user only wants release builds.

## Push Server
- Command: `cd server && npm install --prefer-offline 2>/dev/null; node index.js`
- Port: 3000
- Requires: `GOOGLE_APPLICATION_CREDENTIALS_JSON` secret (Firebase service account)
- Health check: `GET /status` returns JSON with uptime + stats
- Mint endpoint: `POST /mintToken` — verifies identity key hash, rate-limits 1/60s per userId

## assembleRelease
- Uses `app/duoshield-release.keystore` committed to repo (no KEYSTORE_BASE64 decoding)
- Stops existing Gradle daemons first to avoid race conditions
- Output: `app/build/outputs/apk/release/app-release.apk` (~103 MB)
- Build time: ~4–5 min (dominated by R8 minification)

## Secrets state (as of June 2026)
All required secrets are configured in Replit Secrets:
- `GOOGLE_APPLICATION_CREDENTIALS_JSON` ✅
- `GOOGLE_SERVICES_JSON` ✅
- `KEYSTORE_PASSWORD` ✅
- `KEY_ALIAS` ✅ (`duoshield`)
- `KEY_PASSWORD` ✅
- `B2_KEY_ID` ✅
- `B2_APPLICATION_KEY` ✅

Env vars (shared):
- `B2_REGION` = `ca-east-006`
- `PUSH_SERVER_URL` = `https://duoshield.onrender.com` (Render.com; update if server moves)

## GitHub Actions secrets (for CI)
Same set as above, plus `KEYSTORE_BASE64` (base64 of `app/duoshield-release.keystore`).
In Replit the file is used directly, so KEYSTORE_BASE64 is not needed in Replit itself.
