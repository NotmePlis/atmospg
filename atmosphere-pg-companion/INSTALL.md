# Pemasangan singkat

## 1. Build APK rilis bertanda tangan (di komputer Anda)
Prasyarat: Android Studio / Android SDK 34, JDK 17.
1. `./scripts/make-keystore.sh` — sekali saja. Kunci dibuat **di luar** folder proyek; `keystore.properties` otomatis di-gitignore.
2. **Cadangkan** `atmosphere-release.jks` + password (password manager dan salinan offline). Jangan commit, jangan kirim lewat chat/email.
3. `./gradlew test assembleRelease`
4. APK: `app/build/outputs/apk/release/app-release.apk`. Publikasikan ke server: `./scripts/publish-apk.sh ../atmosphere-pg`.
   (jika namanya `app-release-unsigned.apk`, `keystore.properties` belum ada/terbaca).
5. Pembaruan berikutnya: naikkan `versionCode` di `app/build.gradle.kts`, build dengan kunci yang **sama**.

## 2. Pasang di HP (yang sama dengan DANA)
1. Salin APK ke HP, izinkan "pasang dari sumber ini", pasang.
2. Android 13+: Info aplikasi → ⋮ → **Izinkan pengaturan terbatas** (agar Akses notifikasi bisa dinyalakan).
3. Buka aplikasi → Pengaturan: URL server + token perangkat `dvc_…` → Simpan & tes koneksi.
4. Panduan izin: Akses notifikasi, izin notifikasi, baterai Tidak dibatasi, notifikasi DANA aktif.
5. Biarkan di **mode diagnostik**; cek Log menunjukkan notifikasi DANA lolos filter.
6. **Matikan/hapus macro pembayaran dan heartbeat MacroDroid**, lalu **Aktifkan live**.
7. Bayar nominal kecil; verifikasi di dashboard. Lalu jalankan skenario di `TESTING.md`.
