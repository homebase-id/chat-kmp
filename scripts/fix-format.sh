#!/bin/bash

# Fix formatting using Gradle Lint Fix
echo "Running Gradle Lint Fix..."
./gradlew lintFixDebug

if [ $? -eq 0 ]; then
  echo "✅ Formatting fixed successfully."
else
  echo "❌ Failed to fix formatting."
  exit 1
fi
