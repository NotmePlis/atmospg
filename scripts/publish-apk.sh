#!/usr/bin/env bash
# Salin APK rilis ke server Atmosphere PG + tulis metadata versi. Pemakaian: ./scripts/publish-apk.sh [../atmosphere-pg]
set -euo pipefail
cd "$(dirname "$0")/.."
SRV="${1:-../atmosphere-pg}"; APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "APK tidak ditemukan: $APK (jalankan ./gradlew assembleRelease dulu; bila bernama -unsigned, buat keystore dulu)"; exit 1; }
G=app/build.gradle.kts
VN=$(sed -n 's/.*versionName *= *"\(.*\)".*/\1/p' "$G" | head -1); VC=$(sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p' "$G" | head -1)
mkdir -p "$SRV/public/downloads"
cp "$APK" "$SRV/public/downloads/atmosphere-pg-companion.apk"
printf '{"versionName":"%s","versionCode":%s}\n' "$VN" "$VC" > "$SRV/public/downloads/atmosphere-pg-companion.json"
echo "Terpasang di server: v$VN (kode $VC)"
