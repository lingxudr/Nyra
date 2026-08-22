package com.nyra.comic

import android.app.ActivityManager
import android.content.Context

/**
 * Pembatas jumlah request paralel berdasarkan memori yang benar-benar tersedia.
 *
 * Kenapa ini ada: setelan "Request paralel" dulu dipakai apa adanya (1..8).
 * Masalahnya biaya sebuah request bukan hanya jaringan — tiap request menyusun
 * bitmap mosaik yang bisa mencapai puluhan megabita (lebar mosaik x
 * maxTinggiMosaik x 4 byte ARGB_8888), dan dalam satu gelombang semuanya hidup
 * BERSAMAAN. Dengan 4 request paralel, puncaknya ratusan megabita sekali
 * hentak. Di HP dengan heap kecil proses langsung dibunuh sistem, dan karena
 * yang mati adalah prosesnya, tidak ada exception, tidak ada baris log: dari
 * sisi pengguna aplikasi "FC" begitu saja di tengah bab.
 *
 * Solusinya bukan menurunkan setelan pengguna secara permanen, melainkan
 * memakainya sebagai BATAS ATAS lalu menurunkannya bila perangkat memang tidak
 * sanggup. Perhitungannya sengaja murni ([izinkan]) supaya bisa diuji tanpa
 * perangkat, dan pembacaan nilai perangkat dipisah di [untukPerangkat].
 */
object Memori {

    /**
     * Perkiraan puncak memori (byte) satu request, dengan marjin.
     *
     * Satu request menahan, pada saat bersamaan: bitmap mosaik hasil rakitan,
     * potongan-potongan sumbernya, dan salinan base64 JPEG saat dikirim.
     * Angka ini diambil dari kasus terburuk yang realistis, bukan rata-rata,
     * karena kesalahan ke arah terlalu optimistis berakhir dengan proses mati.
     */
    fun biayaPerRequest(tinggiMosaik: Int): Long {
        val tinggi = tinggiMosaik.coerceAtLeast(1).toLong()
        val lebar = LEBAR_PERKIRAAN.toLong()
        val mosaik = lebar * tinggi * 4L
        // Potongan sumber + payload base64 ditaksir sebagai pecahan mosaik.
        return mosaik + mosaik / 2L
    }

    /**
     * Berapa request yang boleh jalan bersamaan.
     *
     * @param diminta nilai dari setelan pengguna (batas atas, tidak pernah dinaikkan)
     * @param sisaHeapByte memori heap yang masih bisa dipakai proses ini
     * @param biayaByte perkiraan biaya satu request, dari [biayaPerRequest]
     *
     * Hanya [PERSEN_AMAN] persen dari sisa heap yang boleh dipakai gelombang request:
     * sisanya untuk decoding halaman, sesi ONNX, dan UI. Hasilnya selalu
     * minimal 1 — kalau satu request saja sudah tidak muat, menolak semuanya
     * berarti pekerjaan tidak akan pernah selesai, jadi lebih baik dicoba satu
     * per satu (dan bila memang gagal, pesannya kini jelas di log).
     */
    fun izinkan(diminta: Int, sisaHeapByte: Long, biayaByte: Long): Int {
        val atas = diminta.coerceIn(1, 8)
        if (biayaByte <= 0L) return atas
        // Aritmetika Long murni: sisaHeapByte * Float akan dipromosikan ke
        // Float yang mantissanya hanya 24 bit, sehingga nilai byte berukuran
        // gigabita mulai dibulatkan.
        val anggaran = sisaHeapByte / 100L * PERSEN_AMAN
        // Dijepit sebagai Long SEBELUM jadi Int. Heap besar dibagi biaya kecil
        // menghasilkan angka di atas Int.MAX_VALUE, dan toInt() akan
        // membungkusnya jadi negatif — hasilnya justru 1, kebalikan dari yang
        // dimaksud. Dijepit dulu ke [1, atas] baru dipersempit tipenya.
        val muat = (anggaran / biayaByte).coerceIn(1L, atas.toLong())
        return muat.toInt()
    }

    /** Sisa heap (byte) yang masih boleh dialokasikan proses ini. */
    fun sisaHeap(): Long {
        val rt = Runtime.getRuntime()
        val terpakai = rt.totalMemory() - rt.freeMemory()
        return (rt.maxMemory() - terpakai).coerceAtLeast(0L)
    }

    /**
     * Apakah sistem sedang dalam tekanan memori rendah.
     *
     * Dipakai sebagai rem tambahan: walau heap Java terlihat lega, bitmap di
     * Android modern dialokasikan di luar heap tersebut, jadi status low-memory
     * milik sistem sering lebih jujur.
     */
    fun sistemSempit(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.lowMemory
    }.getOrDefault(false)

    /** Jumlah request paralel yang aman untuk perangkat yang sedang berjalan. */
    fun untukPerangkat(ctx: Context, cfg: Config): Int {
        val aman = izinkan(cfg.requestParalel, sisaHeap(), biayaPerRequest(cfg.maxTinggiMosaik))
        return if (sistemSempit(ctx)) 1 else aman
    }

    /** Lebar mosaik yang diasumsikan saat menaksir biaya (piksel). */
    private const val LEBAR_PERKIRAAN = 900

    /** Persen sisa heap yang boleh dipakai satu gelombang request. */
    private const val PERSEN_AMAN = 45L
}
