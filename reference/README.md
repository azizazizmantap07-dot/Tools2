# Reference

- `MT6789_Android_scatter.txt` — scatter file asli untuk device test (platform
  MT6789, project `s666ln_ma66b`). Dipakai untuk memvalidasi `ScatterParser.kt`
  dan daftar partisi sensitif di `PartitionSafety.kt` — bukan data tebakan,
  ini layout partisi nyata device kamu.

Partisi yang dikonfirmasi ada (dari file ini): preloader, pgpt/sgpt, boot_a/b,
init_boot_a/b, dtbo_a/b, vbmeta* (a/b), tkv_a/b, md1img_a/b (modem), spmfw_a/b,
dpm_a/b, scp_a/b, sspm_a/b, mcupm_a/b, gz_a/b, lk_a/b, logo_a/b, dram_para,
connsys_wifi/bt/gnss (a/b), tee_a/b, super, userdata, metadata, misc, frp,
nvram, nvcfg, nvdata, otp, efuse, sec1, seccfg, proinfo, protect1/2, persist,
expdb, flashinfo, para, boot_para, preloader_backup.

Kalau layout device kamu berubah (ganti ROM/vendor build), ganti file ini
dengan scatter yang sesuai dan jalankan ulang cek yang sama.
