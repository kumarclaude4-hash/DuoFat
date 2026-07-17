---
name: DuoShield Push Server — Render deployment
description: Push server is live on Render, not run locally on Replit
---

The push server runs on **Render.com**, not as a Replit workflow.

- URL: `https://duoshield.onrender.com` (stored as `PUSH_SERVER_URL` env var)
- Endpoints: `POST /mintToken`, `POST /turnCredentials`, `GET /status`
- Firebase service-account credential is configured on Render's side — no action needed in Replit
- The `Push Server` workflow in Replit (if it still exists) is unused; the production server is Render

**Why:** Server was migrated to Render for always-on hosting. Render handles the `GOOGLE_APPLICATION_CREDENTIALS_JSON` secret independently.

**How to apply:** When debugging TURN credential failures or push notification issues, check the Render dashboard logs first — not the Replit workflow. `PUSH_SERVER_URL` in local.properties / Replit env vars must point to the Render URL.
