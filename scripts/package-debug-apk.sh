#!/usr/bin/env bash
# Packages the DEBUG APK with a stable name, a SHA-256 checksum and a metadata
# file. Run from the repository root after `./gradlew assembleDebug`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK" ]; then
  echo "ERRORE: $APK non trovato. Esegui prima: ./gradlew assembleDebug" >&2
  exit 1
fi

# The native engine is the whole point of the app: never publish an APK that
# silently lost it (for example because the jniLibs step was skipped).
# Capture the listing first: piping straight into `grep -q` closes the pipe
# early and, with `pipefail`, makes a successful match look like a failure.
APK_ENTRIES="$(unzip -l "$APK")"
if ! grep -q "lib/arm64-v8a/libbigbai.so" <<<"$APK_ENTRIES"; then
  echo "ERRORE: libbigbai.so (arm64-v8a) non è presente nell'APK." >&2
  exit 1
fi

mkdir -p dist
OUT="dist/BIG-B-AI-debug-${VERSION}.apk"
cp "$APK" "$OUT"

SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$OUT")"
SIZE_MB="$(awk -v b="$SIZE" 'BEGIN { printf "%.1f", b/1048576 }')"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

printf '%s  %s\n' "$SHA" "$(basename "$OUT")" > "dist/BIG-B-AI-debug-${VERSION}.apk.sha256"

cat > "dist/BIG-B-AI-debug-${VERSION}.txt" <<EOF
BIG B AI — APK DEBUG
====================
File:          $(basename "$OUT")
Versione:      ${VERSION} (versionCode ${VERSION_CODE}) — variante debug
Dimensione:    ${SIZE} byte (${SIZE_MB} MiB)
SHA-256:       ${SHA}
Data build:    ${BUILD_DATE}
minSdk:        31 (Android 12)
targetSdk:     35
ABI:           arm64-v8a
Firma:         chiave DEBUG Android (CN=Android Debug)
Package:       com.bigbai.app.debug (suffisso .debug, convive con la release)
Motore AI:     llama.cpp v1.0.0, CPU only (nessuna accelerazione GPU in questa build)
Modelli:       NON inclusi nell'APK; si scaricano dall'app e sono verificati con SHA-256.
Test eseguiti: 62 test unitari JVM + build debug. Nessun test su dispositivo fisico o emulatore.
Audit:         docs/AUDIT-1.2.md
EOF

echo "APK:      $OUT"
echo "Size:     ${SIZE} bytes (${SIZE_MB} MiB)"
echo "SHA-256:  ${SHA}"
echo "Metadata: dist/BIG-B-AI-debug-${VERSION}.txt"
