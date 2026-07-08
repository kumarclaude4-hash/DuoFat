---
name: DuoShield Push Server broken (missing credential)
description: Push Server workflow fails to start due to missing Firebase service-account credential
---

`server/index.js` requires `GOOGLE_APPLICATION_CREDENTIALS_JSON` (a full Firebase
service-account JSON) to call `admin.credential.cert()`. Without a valid value it
fails with `FirebaseAppError: Service account object must contain a string
"project_id" property` and the "Push Server" workflow exits immediately.

**Why:** this is a pre-existing environment/config gap, not something introduced
by app code changes — `npm install` alone gets the workflow to start, but it
still needs a real service-account secret to actually run.

**How to apply:** the user was asked (2026-07-08) whether to fix it and declined
for now ("leave it for later"). Don't attempt to silently fix or ignore this the
next time push notifications need debugging — surface it again if it's blocking
new work, and get a valid service-account JSON via the environment-secrets flow
before touching `server/index.js` credential logic further.
