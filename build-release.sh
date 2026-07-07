#!/bin/bash
set -e

echo "=== DuoShield Signed Release Build ==="

# Write google-services.json safely via Python (avoids shell escaping issues)
python3 -c "import os; open('app/google-services.json','w').write(os.environ['GOOGLE_SERVICES_JSON'])"
echo "google-services.json OK"

# Write service-account.json (FCM HTTP v1) from the credentials secret
python3 -c "import os; open('app/src/main/assets/service-account.json','w').write(os.environ['GOOGLE_APPLICATION_CREDENTIALS_JSON'])"
echo "service-account.json OK"

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
