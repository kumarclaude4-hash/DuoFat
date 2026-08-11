#!/bin/bash
set -e

echo "=== DuoShield Signed Release Build ==="

# Write google-services.json safely via Python (avoids shell escaping issues)
python3 -c "import os; open('app/google-services.json','w').write(os.environ['GOOGLE_SERVICES_JSON'])"
echo "google-services.json OK"

# S08-C1: Do NOT write service-account.json into app/src/main/assets. The FCM
# Admin private key must never be packaged into the APK — anyone can unzip a
# released build and extract assets, giving them full push/admin authority.
# FCM sends are performed server-side (server/index.js + the notifyOnMessage
# Cloud Function), which is the only component that holds
# GOOGLE_APPLICATION_CREDENTIALS_JSON. The Android client only writes to
# Firestore and lets the server deliver the push; it never reads
# service-account.json (see ChatMediaActivity.notifyPartner).
#
# Guard: fail the build if the file was reintroduced by any other path.
if [ -f app/src/main/assets/service-account.json ]; then
  echo "❌ app/src/main/assets/service-account.json exists — admin credentials must never be packaged into the APK (S08-C1)." >&2
  exit 1
fi
echo "no admin service-account.json packaged (S08-C1) OK"

# Decode keystore from secret
python3 -c "import os,base64; open('/tmp/duoshield-release.jks','wb').write(base64.b64decode(os.environ['KEYSTORE_BASE64']))"
echo "Keystore decoded OK"

# Build signed release APK
ANDROID_HOME=/home/runner/android-sdk ./gradlew :app:assembleRelease --no-daemon \
  -Pandroid.injected.signing.store.file=/tmp/duoshield-release.jks \
  -Pandroid.injected.signing.store.password="$KEYSTORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
  -Pandroid.injected.signing.key.password="$KEY_PASSWORD" \
  2>&1

echo ""
echo "✅ Signed APK ready at: app/build/outputs/apk/release/app-release.apk"
