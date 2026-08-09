DO NOT PUT CREDENTIALS IN THIS DIRECTORY.

Everything under app/src/main/assets/ is packaged verbatim into the APK. An APK
is a public artifact: it is attached to GitHub releases and can be unpacked by
anyone with `unzip -p app-release.apk assets/<name>`. There is no such thing as
a secret stored in an asset — only a secret published in one.

This file previously instructed the opposite. It told the reader to place the
Firebase service-account JSON here as `service-account.json`, and the release
workflow did exactly that (finding S08-C1). Any downloaded release therefore
carried the project's Firebase *admin* private key, which grants Admin SDK
authority over every user's Firestore documents and the ability to mint auth
tokens for any uid. No application code ever read the file, so it leaked without
providing any function. The workflow step is deleted, and the release build now
fails if the file reappears.

Where credentials actually belong:
  - Firebase admin credentials -> push server only, via the Render environment.
  - B2 / storage credentials   -> push server and the Cloudflare Worker only.
                                 Clients receive short-lived, per-object
                                 capability tokens instead (SEC-A01).
  - WORKER_SECRET              -> the Worker's own Wrangler secret store.

If a new feature seems to need a secret on the device, it does not. Add a server
endpoint that authenticates the caller and returns a narrowly scoped, short-lived
token for that one operation.
