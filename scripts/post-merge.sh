#!/bin/bash
set -e

if [ -f functions/package.json ]; then
  echo "==> Installing Cloud Functions dependencies..."
  cd functions && npm install --prefer-offline 2>&1 && cd ..
fi

echo "==> Post-merge setup complete."
