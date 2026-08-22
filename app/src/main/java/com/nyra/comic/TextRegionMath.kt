package com.nyra.comic

import kotlin.math.max
import kotlin.math.min

/**
 * Matematika murni untuk detektor wilayah teks (PP-OCRv5 det).
 *
 * Dipisah dari [TextRegionDetector] supaya bisa diuji tanpa ONNX Runtime dan
 * tanpa Bitmap — sama seperti pemisahan [DetectMath] dari [YoloDetector].
 */
object TextRegionMath {

    /** Sisi terpanjang saat inferensi. PP-OCR butuh kelipatan 32. */
    const val OCR_LIMIT = 960

    /** Ambang peta probabilitas piksel. */
    const val PROB_THRESHOLD = 0.30f

    /** Rata-rata probabilitas minimum di dalam sebuah kotak. */
    const val BOX_THRESHOLD = 0.60f

    /**
     * Sebuah wilayah teks dibuang kalau bagian sebesar ini sudah tertutup
     * balon yang terdeteksi YOLO — biar tidak diterjemahkan dua kali.
     */
    const val COVERED_BY_BUBBLE = 0.50f

    /** Jarak penggabungan, relatif terhadap sisi pendek serpihan. */
    const val GROUP_GAP_RATIO = 0.80f

    /**
     * Blok hasil penggabungan tidak boleh melebihi sebesar ini terhadap sisi
     * halaman.
     *
     * Pagar ini menutup mode gagal yang nyata: satu blok raksasa menelan
     * separuh halaman, seluruh dialognya dikirim sebagai SATU permintaan
     * terjemahan (jadi semua kalimat tergabung di satu balon), lalu area
     * sebesar itu diserahkan ke inpainting dan kembali sebagai tambalan
     * kelabu-kehijauan. Balon terpanjang di halaman manga normal jarang
     * melewati sepertiga sisi halaman.
     */
    const val MAX_BLOCK_SIDE_RATIO = 0.55f

    /** Blok teks lebih kecil dari ini (relatif luas halaman) dianggap derau. */
    const val MIN_AREA_RATIO = 0.0004f

    /** Blok selebar ini hampir pasti bukan teks, melainkan panel. */
    const val MAX_WIDTH_RATIO = 0.90f

    /**
     * Blok seluas ini terhadap halaman bukan teks lepas.
     *
     * Penjaga lebar saja tidak cukup: blok 945x1400 pada halaman 1127x1600
     * lolos karena lebarnya masih di bawah 90 %, padahal luasnya 73 % halaman.
     */
    const val MAX_AREA_RATIO = 0.25f

    /** Bulatkan ke kelipatan 32, minimal 32 — syarat bentuk masukan PP-OCR. */
    fun snapTo32(value: Int): Int {
        val snapped = Math.round(value / 32f) * 32
        return max(32, snapped)
    }

    /** Ukuran masukan inferensi yang menjaga rasio aspek halaman. */
    fun inputSizeFor(width: Int, height: Int, limit: Int = OCR_LIMIT): Pair<Int, Int> {
        val longest = max(width, height)
        val scale = if (longest > limit) limit.toFloat() / longest else 1f
        return snapTo32((width * scale).toInt()) to snapTo32((height * scale).toInt())
    }

    /** Bagian dari [a] yang tertutup oleh [b], 0..1. */
    fun coveredFraction(a: IntArray, b: IntArray): Float {
        val ix = min(a[2], b[2]) - max(a[0], b[0])
        val iy = min(a[3], b[3]) - max(a[1], b[1])
        if (ix <= 0 || iy <= 0) return 0f
        val area = (a[2] - a[0]).toFloat() * (a[3] - a[1]).toFloat()
        return if (area <= 0f) 0f else (ix.toFloat() * iy.toFloat()) / area
    }

    /**
     * Buang wilayah teks yang sudah berada di dalam balon.
     *
     * Balon punya jalur sendiri yang lebih baik (potongan longgar, masker,
     * tipografi). OCR hanya bertugas menemukan teks yang TIDAK berbalon.
     */
    fun dropInsideBubbles(regions: List<IntArray>, bubbles: List<IntArray>): List<IntArray> =
        regions.filter { r -> bubbles.none { b -> coveredFraction(r, b) >= COVERED_BY_BUBBLE } }

    /**
     * Satukan potongan berdekatan menjadi satu blok teks.
     *
     * PP-OCR mengembalikan per baris, kadang per beberapa aksara. Teks Jepang
     * vertikal pecah jadi banyak serpihan; tanpa penggabungan, satu kalimat
     * jadi belasan permintaan terjemahan yang saling lepas konteks.
     *
     * Dua hal yang WAJIB dijaga di sini, keduanya pernah gagal:
     *
     * 1. Toleransi jarak dihitung dari ukuran **serpihan**, bukan dari
     *    akumulator yang sedang tumbuh. Versi lama memakai tinggi akumulator,
     *    sehingga tiap serapan memperbesar blok, blok yang membesar
     *    memperbesar toleransi, dan toleransi yang membesar menyerap lebih
     *    banyak lagi. Pada halaman 1127x1600 nyata, toleransi meledak dari
     *    80 px menjadi 1146 px dan 44 serpihan runtuh jadi satu kotak
     *    seluas 73 % halaman.
     * 2. Jarak diukur sebagai celah kosong sungguhan pada kedua sumbu.
     *    Rumus lama `(min(a2,b2) - max(a1,b1)) > -toleransi` bernilai benar
     *    untuk kotak yang tumpang tindih pada sumbu itu berapa pun jauhnya
     *    pada sumbu lain, jadi dua balon di sisi berlawanan halaman yang
     *    kebetulan sebaris tetap digabung.
     *
     * Sisi pendek dipakai sebagai satuan karena itulah tinggi baris untuk
     * teks mendatar sekaligus lebar kolom untuk teks Jepang tegak.
     */
    fun groupNearby(
        boxes: List<IntArray>,
        gapRatio: Float = GROUP_GAP_RATIO,
        imgW: Int = Int.MAX_VALUE,
        imgH: Int = Int.MAX_VALUE
    ): List<IntArray> {
        val batasW = if (imgW == Int.MAX_VALUE) Int.MAX_VALUE
        else (imgW * MAX_BLOCK_SIDE_RATIO).toInt()
        val batasH = if (imgH == Int.MAX_VALUE) Int.MAX_VALUE
        else (imgH * MAX_BLOCK_SIDE_RATIO).toInt()

        val sisa = boxes.map { it.copyOf() }.toMutableList()
        val hasil = ArrayList<IntArray>()
        while (sisa.isNotEmpty()) {
            var a = sisa.removeAt(0)
            // Satuan jarak milik blok ini: sisi pendek serpihan terbesar yang
            // sudah tergabung. Tidak ikut membesar saat kotak pembungkus
            // memanjang.
            var satuan = min(a[2] - a[0], a[3] - a[1])
            var bergabung = true
            while (bergabung) {
                bergabung = false
                var i = 0
                while (i < sisa.size) {
                    val b = sisa[i]
                    val satuanB = min(b[2] - b[0], b[3] - b[1])
                    val toleransi = max(satuan, satuanB) * gapRatio
                    val celahX = max(0, max(a[0], b[0]) - min(a[2], b[2]))
                    val celahY = max(0, max(a[1], b[1]) - min(a[3], b[3]))
                    if (celahX <= toleransi && celahY <= toleransi) {
                        val gabung = intArrayOf(
                            min(a[0], b[0]), min(a[1], b[1]),
                            max(a[2], b[2]), max(a[3], b[3])
                        )
                        if (gabung[2] - gabung[0] <= batasW &&
                            gabung[3] - gabung[1] <= batasH
                        ) {
                            a = gabung
                            satuan = max(satuan, satuanB)
                            sisa.removeAt(i)
                            bergabung = true
                            continue
                        }
                    }
                    i++
                }
            }
            hasil.add(a)
        }
        return hasil
    }

    /** Singkirkan derau dan blok selebar panel. */
    fun dropNoise(boxes: List<IntArray>, imgW: Int, imgH: Int): List<IntArray> {
        val luasHalaman = imgW.toFloat() * imgH.toFloat()
        val minArea = luasHalaman * MIN_AREA_RATIO
        val maxArea = luasHalaman * MAX_AREA_RATIO
        val maxW = imgW * MAX_WIDTH_RATIO
        return boxes.filter { b ->
            val w = (b[2] - b[0]).toFloat()
            val h = (b[3] - b[1]).toFloat()
            w > 0 && h > 0 && w * h >= minArea && w < maxW && w * h <= maxArea
        }
    }

    /**
     * Rangkaian penuh: buang yang sudah di dalam balon, gabungkan, bersihkan,
     * lalu urutkan mengikuti urutan baca halaman (atas ke bawah).
     */
    fun refine(
        regions: List<IntArray>,
        bubbles: List<IntArray>,
        imgW: Int,
        imgH: Int
    ): List<IntArray> {
        val luar = dropInsideBubbles(regions, bubbles)
        val blok = groupNearby(luar, GROUP_GAP_RATIO, imgW, imgH)
        // Menggabungkan bisa membuat blok baru menutupi balon; saring lagi.
        val bersih = dropNoise(dropInsideBubbles(blok, bubbles), imgW, imgH)
        return bersih.sortedWith(compareBy({ it[1] }, { it[0] }))
    }
}
