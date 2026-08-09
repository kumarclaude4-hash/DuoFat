# DuoShield Push Server

Node.js server deployed on Render.com. It watches Firestore with `onSnapshot()`
and sends FCM pushes through the Firebase Admin SDK. Keep the Render service
awake with UptimeRobot by polling `/status`.

## Deploy on Render
1. New Web Service → connect repo → set Root Directory to `server`
2. Build command: `npm install`
3. Start command: `npm start`
4. Add env var: `GOOGLE_APPLICATION_CREDENTIALS_JSON` = `<paste full service account JSON as one line>`
5. In UptimeRobot, monitor `https://<your-render-service>.onrender.com/status`

## Optional environment variables
- `MAX_INITIAL_MESSAGE_AGE_MS` — startup grace window for recently-created
  messages. Default: `300000` (5 minutes). Older documents in Firestore's
  initial listener snapshot are skipped so Render restarts do not resend old
  notifications.
- `YOUTUBE_API_KEY` — YouTube Data API v3 key, used by `POST /youtubeSearch`
  for Watch Together search. When unset, that endpoint returns `503` and search
  is unavailable; nothing else is affected. **Set the value only here, never in
  the Android app** — see below.
- `YOUTUBE_REGION_CODE` — optional ISO 3166-1 alpha-2 code (e.g. `US`) to bias
  search results toward a region. Costs no extra quota.

## Watch Together YouTube search (`POST /youtubeSearch`)

Authenticated with a Firebase ID token, like every other client endpoint:

```
POST /youtubeSearch
Authorization: Bearer <Firebase ID token>
{ "q": "lofi beats", "maxResults": 10 }

200 → { "results": [ { "videoId", "title", "channel", "thumbnail" } ], "cached": false }
```

The key never leaves the server. The Android app sends a query string and gets
back video IDs, which it hands to the existing Watch Together player — so the
APK contains no YouTube credential and cannot be decompiled to recover one.

**Quota is the binding constraint.** A `search.list` call costs 100 units
against a 10,000/day free allowance — roughly **100 searches per day for the
entire deployment**. The endpoint therefore enforces, in this order:
auth → query validation (min 2 / max 100 chars) → 10-minute response cache →
per-user rate limit (6/min) → one YouTube call, never paginated. Cache hits are
served before the rate limiter, so repeating a search costs neither quota nor
budget.

To create the key: Google Cloud console → enable **YouTube Data API v3** →
Credentials → create an API key → restrict it to that single API (and
optionally to the Render egress IP). Add it to Render as `YOUTUBE_API_KEY`.

## Service account
Download from Firebase Console → Project Settings → Service Accounts → Generate
new private key. Paste the entire JSON content as the value of
`GOOGLE_APPLICATION_CREDENTIALS_JSON` on Render. Never commit this to the repo.

The service account needs:
- Firebase Cloud Messaging permission to send FCM messages.
- Firestore read/write permission to watch messages and mark delivery status.
