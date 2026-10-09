#!/usr/bin/env bash
set -e

# Run gradle bumpVersion task to increment version in version.properties
./gradlew :app:bumpVersion

# Re-build debug APK
./gradlew assembleDebug

# Read new version from version.properties
VERSION_MAJOR=$(grep "versionMajor" version.properties | cut -d'=' -f2 | tr -d ' \r\n')
VERSION_MINOR=$(grep "versionMinor" version.properties | cut -d'=' -f2 | tr -d ' \r\n')
VERSION_PATCH=$(grep "versionPatch" version.properties | cut -d'=' -f2 | tr -d ' \r\n')
FULL_VERSION="${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_PATCH}"

# Copy to release folder with version name and latest alias
mkdir -p release
cp app/build/outputs/apk/debug/app-debug.apk "release/ALPHA-NEW-v${FULL_VERSION}.apk"
cp app/build/outputs/apk/debug/app-debug.apk release/ALPHA-NEW-latest.apk
cp app/build/outputs/apk/debug/app-debug.apk release/ALPHA-NEW-v1.0.0-debug.apk

echo "=========================================================="
echo " ALPHA NEW v${FULL_VERSION} built successfully!"
echo " Files ready in release/ folder:"
echo "   - release/ALPHA-NEW-v${FULL_VERSION}.apk"
echo "   - release/ALPHA-NEW-latest.apk"
echo "=========================================================="
