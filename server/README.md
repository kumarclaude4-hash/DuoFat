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

## Service account
Download from Firebase Console → Project Settings → Service Accounts → Generate
new private key. Paste the entire JSON content as the value of
`GOOGLE_APPLICATION_CREDENTIALS_JSON` on Render. Never commit this to the repo.

The service account needs:
- Firebase Cloud Messaging permission to send FCM messages.
- Firestore read/write permission to watch messages and mark delivery status.
