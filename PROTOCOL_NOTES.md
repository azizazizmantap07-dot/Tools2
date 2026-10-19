# Catatan Verifikasi Protokol

**UPDATE:** setelah menganalisis file DA asli kamu (`DAMT6789.bin`, MTK_DA_v6
build 2025-11-25, Transsion vendor), ternyata DA ini memakai **protokol XML
command (V6)**, bukan command-byte legacy sederhana yang diasumsikan di
versi awal `DaSession.kt`. Kode sudah ditulis ulang mengikuti command
string dan struktur argumen yang **terkonfirmasi langsung dari strings di
dalam binary kamu** — bukan tebakan lagi untuk bagian ini. Yang masih berupa
asumsi (dan wajib diverifikasi) adalah **framing level byte di bawah XML
itu sendiri** — lihat daftar prioritas di bawah.

## Apa yang SUDAH terkonfirmasi dari binary (tidak perlu ditebak lagi)

- Command envelope: `<?xml version="1.0" encoding="utf-8"?><host><version>1.0</version><command>CMD:XXX</command><arg>...</arg></host>`
- Command set lengkap (lihat `DaCommands.kt`): `CMD:START`, `CMD:SET-HOST-INFO`,
  `CMD:GET-DA-INFO`, `CMD:GET-HW-INFO`, `CMD:READ-PARTITION-TABLE`,
  `CMD:READ-PARTITION`, `CMD:WRITE-PARTITION`, `CMD:DOWNLOAD-FILE`,
  `CMD:UPLOAD-FILE`, `CMD:END`, dll.
- Respons tabel partisi: `<partition_table version="1.0"><pt><name>%s</name><start>0x%llx</start><size>0x%llx</size></pt>...</partition_table>`
- Field argumen yang dipakai berbagai command (`da/arg/...` di binary):
  `partition`, `start_address`, `length`, `offset`, `packet_length`, `checksum`,
  `pre_erase`, `source_file`, `target_file`, dll.
- Status sukses: literal string `SUCCESSED` (ejaan ini memang ada di DA-nya,
  bukan typo saya).
- DA ini punya dukungan SLA v3.0 penuh dan membaca status `sla_en`/`daa_en`
  langsung dari EFUSE chip saat runtime (bukan ditentukan oleh DA itu
  sendiri) — lihat bagian "Cek status SLA" di bawah.

## Perubahan penting (BROM handshake)

BROM sync sekarang menggunakan urutan standar 4-byte yang dipakai semua tool publik:

```
Host → Device:  0xA0  →  Device → Host:  0x5F
Host → Device:  0x0A  →  Device → Host:  0xF5
Host → Device:  0x50  →  Device → Host:  0xAF
Host → Device:  0x05  →  Device → Host:  0xFA
```

Implementasi lama hanya mengirim 0xA0 dan menerima apa saja; itu tidak cukup untuk memicu BootROM secara andal. Load address default juga diganti ke `0x200000` (nilai yang paling sering bekerja untuk DA v6 di MT6789).

## Yang MASIH asumsi dan WAJIB diverifikasi — urutan prioritas

### 1. Length-prefix framing di bawah XML — HIGHEST PRIORITY
`DaSession.sendXmlCommand()`/`readXmlResponse()` saat ini mengasumsikan setiap
blob XML didahului **4 byte little-endian length prefix** sebelum teks XML-nya
sendiri. Ini pola umum di protokol V6-era MTK, tapi **belum terkonfirmasi**
dari binary kamu secara langsung (strings tidak menunjukkan framing level
byte, cuma isi textual XML-nya).

**Cara verifikasi**: jalankan `connectAndBoostrapDa()` sampai titik
`da.start()`, lihat log mentah `[OUT-XML]`/`[IN-XML]` di layar app. Kalau DA
tidak pernah membalas atau membalas dengan data yang tidak bisa diparse XML,
kemungkinan besar framing length-prefix ini yang salah — coba varian lain
seperti length big-endian, atau tanpa prefix sama sekali (null-terminated).

### 2. Urutan command awal (CMD:START → CMD:SET-HOST-INFO → CMD:GET-DA-INFO) — MEDIUM
`FlashController.connectAndBoostrapDa()` saat ini memanggil tiga command ini
berurutan setelah BROM jump ke DA. Urutan dan keharusan tiap command ini
adalah **asumsi berdasarkan pola umum DA V6**, bukan konfirmasi eksplisit dari
binary. Ada kemungkinan `CMD:SET-HOST-INFO` tidak wajib, atau field yang
diminta berbeda dari yang saya isi (`system_os`, `host_capability`).

**Cara verifikasi**: kalau `da.start()` sukses tapi `da.setHostInfo()` gagal
dengan pesan field tidak dikenali, baca pesan error dari DA (biasanya DA
sendiri cukup verbose, ada string "Required XML node path not found. Check
command string." di binary — DA ini akan memberi tahu kalau field yang kamu
kirim salah) dan sesuaikan.

### 3. Binary transfer sinkronisasi (CMD:DOWNLOAD-FILE / CMD:UPLOAD-FILE) — HIGH, PALING BERISIKO
Asumsi saat ini: setelah XML command dikirim dan ack diterima, byte binary
mentah langsung mengikuti dalam chunk `packet_length`, tanpa framing
tambahan per-chunk. Ini titik paling kritis untuk operasi **WRITE** — kalau
asumsi salah, data yang ditulis ke flash bisa korup atau device DA bisa
hang menunggu format yang tidak pernah dikirim.

**WAJIB**: uji ini dulu lewat **READ**, bukan WRITE. Baca partisi kecil
(misal `para` atau partisi test ~beberapa MB), simpan hasilnya, lalu buka
filenya — kalau isinya masuk akal (bukan garbage/kosong), framing
CMD:UPLOAD-FILE benar, dan kemungkinan besar CMD:DOWNLOAD-FILE (yang
strukturnya simetris) juga benar.

### 4. Field tambahan yang mungkin wajib tapi belum diisi — LOW-MEDIUM
Dari daftar `da/arg/*` yang ditemukan di binary, ada field yang belum dipakai
di kode saat ini tapi mungkin wajib untuk device/kondisi tertentu:
`da/arg/checksum_level`, `da/arg/connect_type`, `da/arg/bit_width`,
`da/arg/pre_erase`. Kalau DA menolak command dengan pesan field hilang,
tambahkan field yang relevan ke `args` map di command terkait.

## Cek status SLA/DAA device kamu lewat app ini

Tombol **"Cek Info HW / SLA"** di UI memanggil `CMD:GET-HW-INFO` dan
menampilkan seluruh field mentah yang dibalas DA. Ini tempat paling mungkin
status `sla_en`/`daa_en` (atau representasi serupa) muncul, tapi **nama
field persisnya di respons XML belum dikonfirmasi** — jadi lihat apa pun
yang muncul di dialog itu dan cocokkan dengan log DA yang sama saat dijalankan
lewat mtkclient (ground truth) untuk tahu field mana yang relevan.

## Catatan tambahan: PGPT Manager & Full Flash (UI baru)

PgptManagerActivity dan FlashController.flashAll() sudah dibangun di atas
DaSession yang sama — jadi **semua TODO verify di atas berlaku sama persis**
untuk fitur ini. Tidak ada protokol baru yang ditambahkan di layer ini,
cuma orkestrasi UI di atas writePartition()/readPartition() yang sudah ada.

Implikasinya: **jangan coba Full Flash sebelum write single-partition
terbukti benar lewat PGPT Manager untuk satu partisi kecil non-sensitif**.
Full Flash cuma mengulang writePartition() berkali-kali secara berurutan —
kalau framing-nya salah, Full Flash akan merusak lebih banyak partisi
sekaligus sebelum berhenti di kegagalan pertama.

Urutan validasi yang disarankan untuk fitur baru ini:
1. Validasi `writePartition()` dulu lewat PGPT Manager pada SATU partisi
   kecil, NORMAL risk (bukan HIGH/CRITICAL) — misal partisi `para` kalau
   ukurannya kecil, atau buat partisi test sendiri kalau memungkinkan.
2. Baca ulang partisi itu, bandingkan dengan file yang ditulis — harus
   identik byte-per-byte.
3. Baru setelah itu terbukti berkali-kali konsisten, coba Full Flash dengan
   scatter yang HANYA mencakup partisi non-sensitif (filter manual dulu
   folder firmware-nya sebelum pakai Full Flash) sebagai uji coba lanjutan.
4. Partisi CRITICAL (seccfg/otp/efuse/sec1) sengaja tidak punya tombol
   "Flash" langsung di dialog PGPT Manager — kalau benar-benar perlu
   menulisnya, itu butuh modifikasi kode eksplisit, bukan lewat UI biasa.

## Prinsip kerja saat testing

- Selalu mulai dari `CMD:GET-DA-INFO`/`CMD:GET-HW-INFO` (read-only, tanpa
  risiko) untuk memvalidasi framing dasar sebelum mencoba apa pun yang
  menyentuh partition table.
- Baru lanjut ke `CMD:READ-PARTITION-TABLE` setelah dua command di atas
  terbukti jalan — kalau tabel partisi yang terbaca masuk akal (nama partisi
  umum seperti `boot`, `recovery`, `super` muncul dengan ukuran yang wajar),
  framing XML secara keseluruhan sudah benar.
- Jangan coba WRITE ke partisi apa pun — apalagi yang di-flag
  `PartitionSafety.isSensitive()` — sampai READ terbukti konsisten berkali-kali
  dengan ukuran file yang masuk akal.
