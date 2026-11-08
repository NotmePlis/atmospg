#!/usr/bin/env bash
# Membuat kunci rilis SEKALI. Jalankan di komputer sendiri, bukan di repo bersama.
# Hasil: ../atmosphere-release.jks (di luar folder proyek) + keystore.properties (di-gitignore).
set -euo pipefail
cd "$(dirname "$0")/.."
KS="$(cd .. && pwd)/atmosphere-release.jks"
[ -e "$KS" ] && { echo "Sudah ada: $KS (tidak ditimpa)"; exit 1; }
read -rsp "Password keystore (min 8 karakter): " PW; echo
[ ${#PW} -ge 8 ] || { echo "Terlalu pendek"; exit 1; }
keytool -genkeypair -v -keystore "$KS" -storetype PKCS12 -alias atmosphere \
  -keyalg RSA -keysize 4096 -validity 10000 -storepass "$PW" -keypass "$PW" \
  -dname "CN=Atmosphere PG Companion"
umask 077
cat > keystore.properties <<P
storeFile=$KS
storePassword=$PW
keyAlias=atmosphere
keyPassword=$PW
P
echo "Selesai. CADANGKAN $KS dan passwordnya (password manager + salinan offline)."
echo "Kehilangan kunci = tidak bisa memperbarui APK; harus uninstall dan isi ulang pengaturan."
