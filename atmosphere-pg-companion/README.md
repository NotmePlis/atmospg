# Atmosphere PG Companion

Aplikasi Android pengganti MacroDroid untuk Atmosphere PG. Membaca notifikasi pembayaran DANA
lewat `NotificationListenerService` (tanpa root, tanpa klik otomatis) lalu meneruskannya ke backend.
**Backend tidak diubah**: kontraknya sama dengan `docs/MACRODROID.md`.

Pencocokan invoice dan penetapan **Paid tetap dilakukan backend**. Aplikasi ini hanya menyaring dan mengirim.

## Status verifikasi
Kode ditulis mengikuti `server/routes/hook.js`, tetapi **belum dikompilasi dan belum diuji di perangkat**
(lingkungan penulisan tidak punya Android SDK). Langkah pertama: build di Android Studio, jalankan
`NotificationFilterTest`, lalu uji dengan pembayaran DANA nyata bernominal kecil.
Teks notifikasi DANA asli masih belum diketahui; lihat bagian "Menyesuaikan filter".

## Build
- Android Studio Koala+ (JDK 17), buka folder ini, tunggu Gradle sync, Run. Atau: `./gradlew assembleDebug`
  → `app/build/outputs/apk/debug/app-debug.apk`. `assembleRelease` memakai kunci dari `keystore.properties` (lihat `INSTALL.md`).
- minSdk 26 (Android 8.0), targetSdk 34. Tanpa root.

## Setup di HP (yang sama dengan DANA)
1. Dashboard → QRIS & Perangkat → buat **token perangkat** (`dvc_…`). Jangan pakai API key bot/website; aplikasi menolak token tanpa awalan `dvc_`.
2. Buka aplikasi → Pengaturan: isi URL server (mis. `https://pg.contoh.com`) dan token → *Simpan & tes koneksi*.
   URL boleh ditempel lengkap (`…/hook/payment`); bagian `/hook` dibuang otomatis. HTTP polos ditolak kecuali dicentang di Lanjutan.
3. Wizard izin (atau Pengaturan → Izin): aktifkan akses notifikasi (Android 13+ sideload: *Izinkan pengaturan terbatas* di Info aplikasi), izin notifikasi, kecualikan baterai, cek notifikasi DANA aktif.
4. **Matikan semua makro MacroDroid** agar tidak ada pengiriman ganda.
5. Lakukan pembayaran nyata kecil; cek Log di aplikasi dan hasilnya di dashboard.

## Mode diagnostik dan perpindahan dari MacroDroid
APK baru **mulai di mode DIAGNOSTIK** (default): notifikasi DANA dibaca dan disaring, hasilnya hanya masuk Log lokal
(`diagnostik: lolos filter, TIDAK dikirim`). Tidak ada heartbeat berkala, tidak ada `/hook/payment`, invoice tidak tersentuh.
**Tes Koneksi** di halaman utama hanya mengirim satu heartbeat (aman di mode diagnostik).

Urutan pindah:
1. Pasang APK, isi Pengaturan, beri semua izin (Panduan izin). Biarkan MacroDroid tetap berjalan.
2. Di mode diagnostik, tunggu/lakukan notifikasi DANA; pastikan Log menampilkan baris "lolos filter" yang benar. Tekan Tes Koneksi.
3. **Matikan atau hapus macro pembayaran DAN macro heartbeat di MacroDroid** (dan jangan biarkan MacroDroid dioptimalkan hidup lagi).
4. Tekan **Aktifkan live**, centang ketiga konfirmasi. Sejak ini hanya APK yang mengirim.
5. Bayar nominal kecil, cek Log + dashboard. Untuk kembali: **Kembali ke diagnostik** (antrean yang tersisa ditahan, tidak dibuang).

Halaman utama: mode, status listener/izin/server, notifikasi DANA terakhir, antrean, terakhir terkirim, Tes Koneksi, Pengaturan, dan 30 baris log
(token tidak pernah masuk log/URL; isi notifikasi sensitif OTP/PIN diganti `[isi disembunyikan]`).

## Tampilan
Satu Activity dengan **navigasi bawah: Beranda · Riwayat · Pengaturan** (transisi fade ±180 ms; tombol Kembali dari tab lain ke Beranda;
navigasi disembunyikan saat keyboard terbuka; lebar konten dibatasi 640dp di layar lebar; tombol ≥48dp, teks selalu turun baris).
- **Setup pertama (Beranda, bertahap)**: 1 Hubungkan server → 2 Aktifkan izin (status ✓/✗ langsung; lanjut hanya bila Akses notifikasi aktif) → 3 Tes koneksi → *Selesai*. Instalasi lama yang sudah terkonfigurasi melewati wizard.
- **Beranda**: kartu status utama (satu kata + satu tindakan), empat kartu (Pembaca notifikasi, Koneksi server, Pengiriman terakhir, Antrean), Tes Koneksi, Coba Lagi, dan **Detail teknis** (tertutup; diagnosis, izin, baterai, error).
- **Riwayat**: daftar log lengkap (200 terakhir) dengan warna hasil dan kondisi kosong.
- **Pengaturan**: server/token, mode uji/live, izin, dan **Lanjutan** (tertutup: ID perangkat, package, kata kunci, Mode andal, HTTP, Event Uji, hapus token).
- **Umpan balik**: indikator loading, banner hasil (✓/✗/!) + Snackbar untuk berhasil/gagal, dan empty state.
- **Status jujur**: **Aktif** hanya jika izin ada, listener tersambung, mode live, server menjawab heartbeat ≤3 menit lalu, token tidak ditolak, dan tidak ada kiriman gagal. Selain itu: Perlu Izin, Menunggu Koneksi, Gagal Dikirim, atau Mode Uji. Kartu koneksi memakai "Terhubung", bukan "Aktif".
Warna/tema: `res/values/colors.xml` dan `values-night/colors.xml` (token `brand`, `surface`, `status_*`, `container_*`).

## Diagnosis, Coba Lagi, Event Uji
Kartu status menampilkan tahap event pembayaran terakhir: 1 notifikasi diterima → 2 masuk antrean → 3 dikirim → 4 diterima server
(✓/✗/…). Tahap 3 ✓ + tahap 4 ✗ berarti server belum mengakui; bisa jadi sudah menerima, jadi **Coba Lagi** selalu memakai
`event_id` dan payload yang sama (server membuang duplikat). Event yang ditolak server (400) kini **disimpan**, tidak dihapus, dan
tidak memblokir antrean; Coba Lagi mengantrekannya lagi dengan event ID yang sama.

**Kirim Event Uji**: sekali kirim ke `/hook/payment`, `event_id` berawalan `uji-`, tanpa nominal, tidak masuk antrean/`seen`, tidak
dicoba ulang, hanya tercatat di Log lokal. Backend tidak ada di paket ini, jadi saya **tidak bisa memastikan** server mengabaikannya
dari invoice/saldo/laporan: aplikasi memberi PERINGATAN jika respons berisi `order_id`/matched. Saran: di `server/routes/hook.js`
tolak/tandai `event_id` berawalan `uji-` sebelum pencocokan invoice. **Tes Koneksi** hanya heartbeat dan tidak menyentuh pembayaran.

## Kontrak ke backend (tidak berubah)
| | |
|---|---|
| Heartbeat | `POST {URL}/hook/heartbeat` tiap 60 dtk, body `{"device_id","queue"}` |
| Pembayaran | `POST {URL}/hook/payment`, body `{"event_id","device_id","timestamp","package","title","text"}` |
| Auth | header `x-device-token` (tidak pernah di URL) |

Aturan antrean (sama dengan MacroDroid): event dihapus **hanya** jika HTTP 200 dan `stored:true`;
timeout/5xx/429/jaringan → kirim ulang dengan `event_id` yang sama (backoff 5 dtk → 5 mnt, urutan terjaga);
401 → berhenti sampai token baru; 400 → event dibuang dan dicatat di log. Antrean disimpan di SQLite sehingga selamat dari restart/offline (maks 1000 event).

Pencegahan duplikat: `event_id` dan hash isi notifikasi dicatat di tabel SQLite `seen` (tahan restart, simpan 2 hari), diperiksa atomik bersama enqueue; replay notifikasi aktif (≤15 mnt) setelah listener tersambung ulang tidak diantrekan dua kali. Backoff memakai jitter 0–20%.

Pemulihan: listener diminta `requestRebind` saat terputus, saat boot/update, tiap siklus mesin (maks 1× / 2 mnt), dan lewat WorkManager 15 mnt. Status dipisah: server (heartbeat), izin notifikasi aplikasi, listener (izin vs tersambung), dan "notifikasi DANA terakhir terbaca". Aplikasi mencatat jeda >5 mnt sejak terakhir hidup (reboot/dimatikan/Paksa berhenti) sebagai peringatan di Log; setelah Paksa berhenti Android tidak menjalankan komponen apa pun sampai pengguna membuka aplikasi.

`event_id` = `{device_id}-{postTime}-{hash8}` (deterministik), sehingga replay notifikasi yang sama idempoten di server.

## Notifikasi apa yang dikirim
Paket harus sama dengan `id.dana` (bisa diubah, samakan dengan `DANA_PACKAGE`). Paket lain tidak pernah dibaca isinya.
Dikirim hanya jika: bukan ringkasan grup/ongoing, **tidak** mengandung OTP/PIN/kode verifikasi/sandi/CVV/frasa "jangan bagikan",
memuat nominal `Rp…`, dan cocok kata kunci pembayaran (default sama dengan `PAYMENT_KEYWORDS`). Yang dikirim hanya `title` dan `text`.
Notifikasi DANA yang dibuang tampil di Log lokal beserta alasannya (yang sensitif disembunyikan, tidak disimpan).

## Menyesuaikan filter
Jika pembayaran asli tidak terkirim, lihat Log: `diabaikan: bukan notifikasi pembayaran` berarti kata kunci perlu disesuaikan
(Pengaturan → Lanjutan; ubah juga `PAYMENT_KEYWORDS` di `.env` server). Hasil `server: ignored_*` atau `needs_review` berasal dari backend.

## Agar tidak macet
Listener resmi disambung sistem; `RelayService` (Mode andal, foreground) menjaga proses; WorkManager tiap ~15 menit
menyalakan ulang pengirim dan meminta rebind listener; BootReceiver memulai ulang setelah reboot/update;
listener memutar ulang notifikasi DANA yang masih tampil (≤ 15 menit) saat tersambung kembali.
Jangan "Paksa berhenti" aplikasi: itu mematikan akses notifikasi sampai aplikasi dibuka lagi.

## Keamanan
URL dan token disimpan di `EncryptedSharedPreferences` (Android Keystore), tanpa backup/transfer. Token tidak pernah masuk log atau URL.

## Batas
Heartbeat hanya membuktikan server terjangkau; bukti notifikasi DANA sampai hanya pembayaran nyata.
Aplikasi tidak memicu atau memverifikasi pembayaran sendiri.

## Struktur
`NotificationFilter` (saring) · `DanaNotificationListener` (baca) · `Store` (antrean/log SQLite) · `ApiClient` (HTTP) ·
`RelayEngine` (heartbeat + kirim + retry) · `RelayService`/`SyncWorker`/`BootReceiver` (ketahanan) · `AppConfig` (penyimpanan aman) · `MainActivity` + `LogAdapter` (UI: tiga tab + wizard).
