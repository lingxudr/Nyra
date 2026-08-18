# Cara push repo ini ke GitHub

Repo lokal sudah siap: `git init` sudah dijalankan, `.gitignore` sudah diatur,
dan commit pertama sudah dibuat di branch `main`. Yang tersisa hanya
menghubungkannya ke GitHub.

Saya **tidak** bisa melakukan push ini untuk Anda, karena butuh kredensial akun
GitHub Anda. Jangan pernah menempelkan token ke chat mana pun, termasuk ke saya.

## 1. Buat repo kosong di GitHub

Buka https://github.com/new — isi nama, misal `cypy-android`.
**Jangan** centang "Add a README", "Add .gitignore", atau "Choose a license";
repo harus benar-benar kosong supaya push pertama tidak bentrok.

## 2. Hubungkan dan push

Dari komputer Anda sendiri (bukan dari sini), setelah menyalin folder
`cypy-android`:

```bash
cd cypy-android
git remote add origin https://github.com/<akun-anda>/cypy-android.git
git push -u origin main
```

GitHub akan meminta login. Password akun biasa **tidak** lagi diterima; pakai
Personal Access Token: Settings -> Developer settings -> Personal access tokens
-> Tokens (classic) -> Generate new token, centang scope `repo`. Tempel token
itu sebagai pengganti password.

## Yang sengaja TIDAK ikut masuk repo

Diatur di `.gitignore`:

| Item | Alasan |
|---|---|
| `*.jks` (`cypy-release.jks`) | Kunci penandatanganan. Kalau bocor, orang lain bisa menerbitkan APK palsu yang dianggap Android sebagai update sah dari aplikasi Anda. **Simpan sendiri dan cadangkan** — kalau hilang, Anda tidak akan pernah bisa merilis update untuk pemasangan yang sudah ada. |
| `apk/` | 73 MB. Git buruk menyimpan berkas biner besar; ukuran repo akan membengkak tiap rilis. Pakai **GitHub Releases** (lihat bawah). |
| `local.properties` | Berisi path SDK khusus mesin Anda. |
| `build/`, `.gradle/` | Hasil build, bisa dibuat ulang. |

`app/src/main/assets/eyecypy.onnx` (12 MB) **ikut** masuk. Di bawah batas
hard 100 MB GitHub, dan tanpa berkas itu aplikasi tidak bisa dibangun sama
sekali. Kalau nanti terasa berat, pindahkan ke Git LFS.

## Melampirkan APK ke rilis

Karena `apk/` diabaikan git, unggah APK sebagai aset rilis:

1. Di GitHub: Releases -> Draft a new release
2. Tag: `v1.25.1.13`
3. Seret keempat berkas dari folder `apk/` ke area lampiran
4. Tempel tabel SHA-256 dari README supaya orang bisa memverifikasi unduhan

## Kalau kunci penandatanganan terlanjur ter-commit

Menghapusnya di commit berikutnya **tidak cukup** — berkas itu tetap ada di
riwayat dan tetap bisa diunduh. Anda harus menulis ulang riwayat
(`git filter-repo`) lalu **buat kunci baru**, karena yang lama harus dianggap
sudah bocor.
