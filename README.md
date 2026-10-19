# FlashingTool (nama kerja — ganti sesuai keinginan)

Aplikasi Android untuk komunikasi langsung dengan perangkat MediaTek dalam mode
BROM/Preloader lewat USB OTG, meniru alur kerja FlashingTool GUI (PC) tapi berjalan
sepenuhnya di Android menggunakan `UsbManager`/`UsbDeviceConnection` — tanpa
root, tanpa PC.

## ⚠️ STATUS: protokol DA sudah berbasis file DA asli kamu, BELUM TERVALIDASI di hardware nyata

Lapisan **BROM** (`BromSession.kt`) sekarang memakai handshake 4-byte standar
MediaTek (`0xA0 0x0A 0x50 0x05` dengan echo complement). Ini adalah urutan yang
sama dipakai mtkclient / SP Flash Tool. Load address default untuk DA diubah ke
`0x200000` (nilai umum untuk MT6789 / Helio G99).

Lapisan **DA** (`DaSession.kt`, `DaCommands.kt`, `DaXml.kt`) sudah ditulis
ulang mengikuti **command string dan struktur XML yang dikonfirmasi langsung
dari strings di dalam `DAMT6789.bin`** milik kamu (MTK_DA_v6, build
2025-11-25, Transsion) — bukan tebakan generik lagi. Yang masih berupa asumsi
dan wajib diverifikasi adalah **framing byte di bawah teks XML** (length
prefix, dll) — lihat `PROTOCOL_NOTES.md` untuk daftar lengkap titik yang
perlu dicek dan cara verifikasinya.

**Jangan pakai ini pada device produksi/harian sebelum protokolnya tervalidasi
di device test.**

**Jangan pakai ini pada device produksi/harian sebelum protokolnya tervalidasi
di device test.** Device test kamu (Infinix X6891 / Helio G99) adalah tempat
yang tepat untuk memvalidasi ini — tapi tetap siapkan jalur recovery lain
(misal: device kedua, atau tahu cara masuk BROM mode manual) sebelum mencoba
operasi WRITE pertama kali.

## Yang SUDAH ada

- `usb/` — lapisan transport USB mentah (`UsbManager`/`UsbDeviceConnection`,
  permission flow, deteksi VID/PID MediaTek)
- `protocol/BromSession.kt` — handshake BROM, identifikasi chip, kirim+jalankan DA
- `protocol/DaCommands.kt` — nama command XML V6, dikonfirmasi dari `DAMT6789.bin`
- `protocol/DaXml.kt` — builder & parser envelope XML (command, partition table)
- `protocol/DaSession.kt` — start session, get DA/HW info, baca tabel partisi,
  baca partisi, tulis partisi (semua lewat command XML V6)
- `protocol/FlashController.kt` — orchestrator yang dipanggil UI
- `model/ScatterParser.kt` — parser scatter file (.txt) ala MediaTek
- `model/PartitionSafety.kt` — flag partisi sensitif (NVRAM/EFS/dll) + pesan
  peringatan sebelum ditulis
- `ui/MainActivity.kt` — UI utama: load DA, load scatter, connect, cek info
  HW/SLA, baca tabel partisi, backup cepat per-partisi
- `ui/PgptManagerActivity.kt` — **PGPT Manager**: tulis/flash per-partisi
  dengan konfirmasi risiko (PartitionSafety), dan **Full Flash** dari scatter
  file (cocokkan nama file di scatter dengan folder firmware via SAF, lalu
  tulis semua partisi berurutan, berhenti di kegagalan pertama)
- `ui/PartitionListAdapter.kt` — list partisi dengan indikator warna risiko
  (merah=CRITICAL, oranye=HIGH, ungu=MEDIUM)
- `protocol/FlashSessionHolder.kt` — singleton sesi aktif, dipakai bersama
  MainActivity dan PgptManagerActivity supaya tidak perlu reconnect USB
  setiap pindah layar
- GitHub Actions (`.github/workflows/build.yml`) — build APK debug otomatis
  setiap push

### Fastboot Terminal (terpisah dari BROM/DA)

Termasuk **Force Fastboot** (teknik FastbootForge): mengirim `FASTBOOT` ke
Preloader MediaTek (softbrick/bootloop) sampai perangkat masuk mode fastboot,
lalu otomatis menghubungkan terminal perintah di layar yang sama.

Dukungan **fastbootd**: `reboot-fastboot`, deteksi `is-logical`, 
`resize-logical-partition` otomatis sebelum flash, dan `snapshot-update cancel`.


Modul **Fastboot Terminal** (`FastbootActivity`) berbicara protokol fastboot
standar lewat USB OTG — independen dari sesi BROM/DA. Perangkat harus sudah
berada di mode fastboot/bootloader.

Fitur:
- Input perintah bebas **gaya platform-tools** (spasi maupun titik dua, prefiks opsional)
- Tombol cepat: getvar all, reboot, reboot-bootloader, -w, flashing unlock/lock
- Flash file ke partisi, erase partisi
- Perintah yang dikenali (contoh):
  - `getvar all` / `getvar:product` / `fastboot getvar all`
  - `erase userdata` / `erase:cache`
  - `flash boot` / `fastboot --disable-verification flash vbmeta` (lalu pilih file)
  - `reboot`, `reboot-bootloader`, `reboot-fastboot`, `continue`, `-w`
  - `flashing unlock|lock|…`, `oem …`, `set_active a`
  - `snapshot-update cancel`, `resize-logical-partition …`, `is-logical system`
  - `devices` / `adb devices` / `fastboot devices`
  - `adb reboot bootloader|fastboot|recovery`
  - `help` — daftar perintah
  - perintah lain dikirim mentah ke perangkat

**ADB shell penuh** belum disertakan (memerlukan binary native / protokol yang
lebih kompleks). Untuk oprek bootloader, jalur fastboot di atas sudah mencakup
erase, flash, unlock, dan wipe.


## Yang SENGAJA TIDAK dibuat

- Bypass SLA/DAA (kamakiri, exploit auth, dll)
- FRP eraser
- Lock/unlock bootloader (`seccfg`)

Kalau kamu butuh salah satu dari ini untuk device kamu sendiri yang memang sah
kamu modifikasi, itu keputusan dan risiko kamu untuk implementasikan sendiri —
saya tidak akan menambahkannya ke project ini.

## Cara build

### Lewat GitHub Actions (otomatis)
1. Push repo ini ke GitHub.
2. Actions akan otomatis build `assembleDebug` setiap push ke `main`.
3. Ambil APK dari tab **Actions → run terbaru → Artifacts → penumbra-android-debug**.

### Build lokal
```bash
./gradlew assembleDebug
```
APK akan ada di `app/build/outputs/apk/debug/`.

## Yang WAJIB kamu siapkan sendiri

1. **File DA (Download Agent)** yang sah untuk chipset Helio G99 — kamu sudah
   punya ini. Load lewat tombol "Pilih File DA" di app.
2. **`loadAddress` DA yang benar** — saat ini `MainActivity.kt` memakai nilai
   placeholder (`0x40000000L`). Cari nilai yang benar dari dokumentasi/metadata
   DA kamu dan ganti sebelum testing (lihat komentar `TODO` di
   `onConnectClicked()`).
3. **Kabel USB OTG** (device host ke device target BROM mode).
4. **Verifikasi framing protokol** terhadap device nyata — lihat
   `PROTOCOL_NOTES.md` untuk daftar titik yang perlu dicek.

## Struktur alur

```
UsbPermissionHelper → UsbTransport (raw bulk transfer)
        ↓
BromSession (handshake, identifikasi chip, kirim+jump ke DA)
        ↓
DaSession (baca tabel partisi, read/write flash)
        ↓
FlashController (orchestrator dipanggil dari UI)
```

## Lisensi & atribusi

Pemahaman protokol BROM/DA yang dipakai di sini merujuk pada dokumentasi
publik yang sama yang mendasari proyek-proyek open-source komunitas MTK
(mis. `bkerler/mtkclient`, GPL-3.0). Tidak ada kode dari proyek closed-source
manapun (termasuk FlashingTool GUI / Rafflink) yang disalin ke repo ini.
