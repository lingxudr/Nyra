package com.nyra.comic

/**
 * Pencatat durasi per tahap.
 *
 * Alasan keberadaannya: laporan lapangan menyebut 280 s untuk 4 halaman, tetapi
 * satu-satunya rincian yang tersedia hanyalah angka inpaint. Tanpa rincian per
 * tahap, setiap penyetelan berikutnya cuma tebak-tebakan — jadi ini dikerjakan
 * lebih dulu sebelum optimasi apa pun.
 *
 * Sengaja bukan `object`: satu contoh per proses terjemahan supaya angkanya
 * tidak bocor antar-proses, dan supaya bisa diuji tanpa status global.
 * Aman dipanggil dari beberapa utas (terjemahan berjalan paralel per gelombang).
 */
class Durasi {

    /** Nama tahap yang dilaporkan, sekaligus urutan tampilnya di ringkasan. */
    enum class Tahap(val label: String) {
        DEKODE("Dekode gambar"),
        DETEKSI("Deteksi balon"),
        OCR("Deteksi teks lepas"),
        API("Permintaan terjemahan"),
        INPAINT("Inpaint"),
        RENDER("Render teks"),
        SIMPAN("Simpan berkas")
    }

    private val total = HashMap<Tahap, Long>()
    private val jumlah = HashMap<Tahap, Int>()

    /** Catat [ms] milidetik pada [tahap]. Nilai negatif diabaikan. */
    @Synchronized
    fun catat(tahap: Tahap, ms: Long) {
        if (ms < 0) return
        total[tahap] = (total[tahap] ?: 0L) + ms
        jumlah[tahap] = (jumlah[tahap] ?: 0) + 1
    }

    /** Jalankan [blok] sambil mencatat durasinya. Nilai baliknya diteruskan. */
    inline fun <T> ukur(tahap: Tahap, blok: () -> T): T {
        val t0 = System.currentTimeMillis()
        try {
            return blok()
        } finally {
            catat(tahap, System.currentTimeMillis() - t0)
        }
    }

    @Synchronized
    fun totalMs(tahap: Tahap): Long = total[tahap] ?: 0L

    @Synchronized
    fun jumlahPanggilan(tahap: Tahap): Int = jumlah[tahap] ?: 0

    @Synchronized
    fun totalSemuaMs(): Long = total.values.sum()

    @Synchronized
    fun kosong(): Boolean = total.isEmpty()

    /**
     * Lupakan semua catatan.
     *
     * Wajib dipanggil di awal tiap proses: satu objek [Pipeline] bisa dipakai
     * untuk beberapa kali terjemahan, dan tanpa ini angka proses lama akan
     * ikut terjumlah sehingga persentasenya jadi menyesatkan.
     */
    @Synchronized
    fun bersihkan() {
        total.clear()
        jumlah.clear()
    }

    /**
     * Ringkasan siap tulis ke log, terurut dari tahap yang paling mahal.
     *
     * [totalKeseluruhanMs] adalah jam dinding seluruh proses; dipakai untuk
     * menghitung persentase supaya jumlah persennya jujur — tahap yang berjalan
     * paralel bisa membuat jumlah durasi tahap melebihi jam dinding, dan itu
     * memang ditandai apa adanya alih-alih dinormalisasi diam-diam.
     */
    @Synchronized
    fun ringkasan(totalKeseluruhanMs: Long): List<String> {
        if (total.isEmpty()) return emptyList()
        val baris = ArrayList<String>()
        baris.add("[Rincian waktu]")

        val urut = Tahap.entries
            .filter { (total[it] ?: 0L) > 0L }
            .sortedByDescending { total[it] ?: 0L }

        for (t in urut) {
            val ms = total[t] ?: 0L
            val n = jumlah[t] ?: 0
            val detik = ms / 1000.0
            val persen = if (totalKeseluruhanMs > 0) ms * 100.0 / totalKeseluruhanMs else 0.0
            baris.add(
                "  ${t.label.padEnd(20)} ${"%7.1f".format(detik)} s" +
                    " (${"%4.1f".format(persen)} %)" +
                    " dalam $n panggilan"
            )
        }

        val jumlahTahap = total.values.sum()
        if (totalKeseluruhanMs > 0 && jumlahTahap > totalKeseluruhanMs * 1.05) {
            baris.add(
                "  (jumlah tahap melebihi jam dinding karena sebagian" +
                    " berjalan paralel)"
            )
        }
        return baris
    }
}
