package com.nyra.comic

/**
 * Ringkasan proyek untuk perpustakaan, plus aturan-aturan kecil di sekitarnya.
 *
 * Kenapa kelas ini ada terpisah dari [Project]: sampai ronde 24 proyek memang
 * tersimpan — sampai sepuluh buah — tetapi satu-satunya pintu menuju editor
 * adalah tombol di layar Hasil yang selalu membuka `Project.list().first()`,
 * yaitu proyek TERBARU saja. Begitu pengguna menerjemahkan bab berikutnya,
 * bab sebelumnya masih utuh di disk namun mustahil dibuka lagi, lalu diam-diam
 * dibuang oleh `Project.prune()`. Yang hilang bukan berkas sembarangan: di
 * dalamnya ada hasil deteksi tiap halaman dan seluruh terjemahan LLM yang
 * sudah dibayar. Perpustakaan adalah pintu yang hilang itu.
 *
 * Logikanya sengaja ditaruh di kelas biasa tanpa Context supaya bisa diuji
 * sebagai aritmetika murni, bukan lewat instrumentasi Android.
 */
object Library {

    /** Satu baris di daftar perpustakaan. */
    data class Entri(
        val id: String,
        val nama: String,
        val bahasa: String,
        val jumlahHalaman: Int,
        val jumlahBalon: Int,
        val jumlahTerjemahan: Int,
        val diperbaruiPada: Long,
        /** Byte yang dipakai folder proyek ini. */
        val ukuranByte: Long
    ) {
        /**
         * Berapa bagian balon yang sudah punya terjemahan.
         *
         * Dipakai untuk memberi tahu pengguna bahwa sebuah bab berhenti di
         * tengah jalan — biasanya karena kuota API habis atau proses dihentikan
         * — sehingga ia tahu bab itu layak dilanjutkan, bukan diulang.
         */
        val kelengkapan: Float
            get() = if (jumlahBalon <= 0) 1f
            else (jumlahTerjemahan.toFloat() / jumlahBalon).coerceIn(0f, 1f)

        val lengkap: Boolean get() = kelengkapan >= 0.999f
    }

    /**
     * Ringkas sebuah proyek jadi satu entri.
     *
     * Balon dihitung dari kotak, bukan dari peta terjemahan: kotak tanpa
     * terjemahan justru inti informasinya — itulah yang belum selesai.
     */
    fun ringkas(p: Project, ukuranByte: Long): Entri {
        var balon = 0
        var terjemah = 0
        for (hal in p.pages) {
            balon += hal.boxes.size
            for ((_, t) in hal.translations) {
                // "SKIP" adalah keputusan sadar model untuk tidak menerjemahkan
                // (misalnya efek suara latar), jadi ia dihitung selesai. Teks
                // kosong bukan keputusan, melainkan lubang.
                if (t.isNotBlank()) terjemah++
            }
        }
        return Entri(
            id = p.id,
            nama = p.name,
            bahasa = p.targetLanguage,
            jumlahHalaman = p.pages.size,
            jumlahBalon = balon,
            jumlahTerjemahan = terjemah,
            diperbaruiPada = p.updatedAt,
            ukuranByte = ukuranByte
        )
    }

    /** Urutan tampil: terbaru dulu, seperti daftar bacaan yang wajar. */
    fun urutkan(daftar: List<Entri>): List<Entri> =
        daftar.sortedByDescending { it.diperbaruiPada }

    /**
     * Saring berdasarkan kata kunci, tidak peduli huruf besar-kecil.
     *
     * Kueri kosong mengembalikan semuanya — bukan daftar kosong.
     */
    fun saring(daftar: List<Entri>, kueri: String): List<Entri> {
        val q = kueri.trim().lowercase()
        if (q.isEmpty()) return daftar
        return daftar.filter {
            it.nama.lowercase().contains(q) || it.bahasa.lowercase().contains(q)
        }
    }

    /**
     * Bersihkan nama yang diketik pengguna saat mengganti nama proyek.
     *
     * Nama ini akhirnya ikut menjadi nama berkas CBZ hasil ekspor, jadi
     * pemisah jalur dan karakter kendali harus dibuang di sini — bukan nanti
     * saat menulis berkas, ketika kegagalannya sudah terlambat dan membingungkan.
     */
    fun rapikanNama(mentah: String, cadangan: String = "Proyek"): String {
        val bersih = buildString {
            for (c in mentah) {
                when {
                    c == '/' || c == '\\' -> append(' ')
                    c.code < 0x20 || c.code == 0x7F -> append(' ')
                    else -> append(c)
                }
            }
        }.replace(Regex("\\s+"), " ").trim()
        if (bersih.isEmpty()) return cadangan
        return if (bersih.length > MAKS_NAMA) bersih.take(MAKS_NAMA).trim() else bersih
    }

    const val MAKS_NAMA = 80

    /** Ukuran yang enak dibaca manusia. */
    fun ukuranRingkas(byte: Long): String {
        if (byte < 1024) return "$byte B"
        val kb = byte / 1024.0
        if (kb < 1024) return String.format("%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    /**
     * Umur dalam kata-kata, relatif terhadap [sekarang].
     *
     * Waktu diberikan sebagai parameter, tidak diambil dari jam sistem di
     * dalam fungsi, supaya hasilnya bisa diuji tanpa bergantung pada saat
     * pengujian dijalankan.
     */
    fun umurRingkas(waktu: Long, sekarang: Long): String {
        val d = sekarang - waktu
        if (waktu <= 0L || d < 0) return "baru saja"
        val menit = d / 60_000L
        if (menit < 1) return "baru saja"
        if (menit < 60) return "$menit menit lalu"
        val jam = menit / 60
        if (jam < 24) return "$jam jam lalu"
        val hari = jam / 24
        if (hari < 30) return "$hari hari lalu"
        val bulan = hari / 30
        if (bulan < 12) return "$bulan bulan lalu"
        return "${bulan / 12} tahun lalu"
    }
}
