# Uji akhir (wajib sebelum dipakai untuk uang sungguhan)

Otomatis: `./gradlew test` (filter, backoff) dan `./gradlew connectedAndroidTest` (antrean/dedupe `StoreQueueTest`, butuh perangkat/emulator).
Semua langkah di bawah dilakukan manual di mode **LIVE** dengan nominal kecil. Kriteria lulus selalu sama:
**tiap pembayaran muncul tepat SATU kali di dashboard (satu order Paid, saldo bertambah sekali).**

| # | Skenario | Cara | Hasil yang diharapkan |
|---|---|---|---|
| 1 | Pembayaran normal | Bayar invoice kecil | Log: antre → `server: matched • ORDER`; dashboard Paid 1× |
| 2 | Jaringan putus | Mode pesawat → bayar → tunggu 1 mnt → matikan mode pesawat | Antrean 1; setelah online terkirim otomatis; Paid 1×, tidak ada duplikat |
| 3 | Server error | Matikan/ubah server jadi 5xx, bayar, pulihkan | Log "percobaan N" dengan jeda naik; event_id sama; terkirim setelah pulih; Paid 1× |
| 4 | Respons hilang | Putus jaringan tepat setelah kirim (atau timeout server) | Dikirim ulang dengan event_id sama; server menjawab `duplicate`; saldo tidak ganda |
| 5 | Notifikasi ganda | Pasang ulang/perbarui notifikasi DANA yang sama; matikan-nyalakan akses notifikasi | Log `diabaikan: duplikat`; tidak ada event baru atau Paid ganda |
| 6 | Layar mati | Kunci layar ≥15 mnt (baterai: Tidak dibatasi), bayar | Terkirim saat layar mati |
| 7 | Proses dihentikan sistem | Swipe dari Recent / `adb shell am kill com.atmosphere.companion`, bayar | Listener tersambung ulang (≤ ~15 mnt via WorkManager); notifikasi aktif ≤15 mnt di-replay; Paid 1× |
| 8 | Reboot | Restart HP, **jangan buka aplikasi**, bayar | Setelah boot listener tersambung; terkirim; Log bisa menampilkan peringatan jeda |
| 9 | Antrean tahan restart | Putus jaringan, bayar, matikan paksa proses, online lagi | Event tetap di antrean dan terkirim sekali |
| 10 | Force-stop (batas) | Info aplikasi → Paksa berhenti, bayar, buka aplikasi | Pembayaran saat mati TIDAK terbaca (batas Android); Log mencatat jeda; cocokkan manual di DANA/dashboard |
| 11 | Diagnostik | Mode diagnostik, bayar | Log "TIDAK dikirim"; dashboard tidak berubah |
| 13 | Diagnosis tahap | Putus jaringan lalu bayar; lihat kartu status | Tahap 1–2 ✓, tahap 3 ✓ (percobaan N), tahap 4 ✗ "belum ada pengakuan"; setelah online jadi ✓ |
| 14 | Coba Lagi | Saat #13 tertahan tekan **Coba Lagi** | Event ID yang sama dikirim ulang; Paid/saldo tetap 1× |
| 15 | Ditolak server | Paksa 400 (mis. server menolak payload) lalu perbaiki dan tekan Coba Lagi | Event tidak hilang, antrean lain tetap jalan, event ID sama |
| 16 | Event Uji | Tekan **Kirim Event Uji** | Log "[UJI]", hasil OK; dashboard: tidak ada perubahan invoice/saldo/laporan. Jika muncul PERINGATAN: berhenti, cek backend |
| 17 | Tampilan | Terang & gelap; font terbesar; layar kecil; keyboard terbuka di Pengaturan (field terlihat, nav tersembunyi); wizard 3 langkah pada instal baru; Beranda tidak "Aktif" di mode uji/heartbeat basi | Teks terbaca, tidak terpotong; kartu status & tombol tindakan sesuai: izin dicabut → Perlu Izin; pesawat → Menunggu Koneksi; token salah → Gagal Dikirim |
| 12 | Macro lama mati | Cek MacroDroid: pembayaran + heartbeat nonaktif | Tidak ada kiriman ganda dari dua sumber |

Catatan: teks notifikasi DANA asli belum diketahui; sesuaikan kata kunci di Pengaturan > Lanjutan bila #1 tidak lolos filter.

## Pairing QR (lembar Scan QR)

| # | Skenario | Langkah | Hasil yang diharapkan |
|---|---|---|---|
| P1 | Alur lengkap | Web: Hubungkan Aplikasi → APK: Scan QR → Buka Kamera → pindai → Hubungkan | Lembar: domain + hitung mundur → loading → "Terhubung"; web menampilkan "Pairing berhasil" |
| P2 | QR kedaluwarsa | Tunggu hitung mundur web habis (QR buram "Kedaluwarsa"), pindai | APK: "QR tidak bisa dipakai" + Scan Ulang; konfigurasi lama TIDAK berubah |
| P3 | Pakai ulang | Pindai QR yang sama kedua kali | Ditolak, tombol Scan Ulang; token tetap yang pertama |
| P4 | Bersamaan | Dua HP memindai satu QR hampir bersamaan | Satu sukses, satu ditolak |
| P5 | Jaringan putus | Matikan data sebelum Hubungkan | "Gagal terhubung" + Coba Lagi; nyalakan data, Coba Lagi berhasil selama QR berlaku |
| P6 | Tema/layar | Terang & gelap, layar kecil, font besar | Teks terbaca, tombol tidak terpotong, lembar bisa digulir |
| P7 | Manual | Tempel tautan / isi URL+token | Tetap berfungsi |

Backend: `cd atmosphere-pg && npm test` (kedaluwarsa, pakai ulang, klaim bersamaan, batas salah tebak).
