package com.nyra.comic

/**
 * Penentu jumlah utas untuk inferensi ONNX.
 *
 * Kenapa dipisah jadi satu tempat: keempat model dulu menyalin rumus yang sama
 * (`max(2, jumlahInti / 2)`), jadi menyetelnya berarti mengedit empat berkas
 * dan gampang tertinggal satu.
 *
 * Kenapa angkanya begini: HP Android hampir selalu big.LITTLE — beberapa inti
 * cepat dan beberapa inti hemat daya. Menyebar pekerjaan ke SEMUA inti membuat
 * inti cepat menunggu inti lambat menyelesaikan bagiannya, sehingga sering
 * lebih lambat daripada memakai inti cepat saja. Karena itu jumlah utas
 * dibatasi 4, bukan sebanyak-banyaknya inti.
 *
 * Batas 4 ini adalah tebakan terdidik, BUKAN hasil pengukuran di perangkat
 * nyata — sandbox tempat kode ini dikembangkan hanya punya 2 inti sehingga
 * angkanya tidak bisa digeneralisasi. Karena itu nilainya bisa ditimpa lewat
 * setelan, dan durasi tiap tahap dicatat (lihat [Durasi]) supaya penyetelan
 * berikutnya berdasar angka dari HP pengguna.
 */
object Utas {

    /** Batas atas bawaan; lihat catatan big.LITTLE di atas. */
    const val MAKS_BAWAAN = 4

    /**
     * Jumlah utas untuk [inti] buah prosesor logis.
     *
     * Selalu minimal 1, dan tidak pernah melebihi jumlah inti yang ada.
     */
    fun hitung(inti: Int, maks: Int = MAKS_BAWAAN): Int {
        if (inti <= 1) return 1
        val batas = if (maks < 1) 1 else maks
        return minOf(inti, batas)
    }

    /** Jumlah utas untuk perangkat yang sedang berjalan. */
    fun untukPerangkat(maks: Int = MAKS_BAWAAN): Int =
        hitung(Runtime.getRuntime().availableProcessors(), maks)
}
