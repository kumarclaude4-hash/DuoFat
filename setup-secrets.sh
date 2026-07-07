#!/bin/bash
# Writes Replit secrets to the file paths expected by the Android build.
# Run this once before every `./gradlew assembleDebug`.

set -e

if [ -z "$GOOGLE_SERVICES_JSON" ]; then
  echo "ERROR: GOOGLE_SERVICES_JSON secret is not set."
  echo "  → Go to the Replit Secrets tab and add GOOGLE_SERVICES_JSON"
  exit 1
fi

echo "$GOOGLE_SERVICES_JSON" > app/google-services.json
echo "✓ app/google-services.json written"
