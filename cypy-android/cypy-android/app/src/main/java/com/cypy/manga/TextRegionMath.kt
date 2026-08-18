package com.cypy.manga

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

    /** Jarak penggabungan, relatif terhadap tinggi kotak. */
    const val GROUP_GAP_RATIO = 0.80f

    /** Blok teks lebih kecil dari ini (relatif luas halaman) dianggap derau. */
    const val MIN_AREA_RATIO = 0.0004f

    /** Blok selebar ini hampir pasti bukan teks, melainkan panel. */
    const val MAX_WIDTH_RATIO = 0.90f

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
     */
    fun groupNearby(boxes: List<IntArray>, gapRatio: Float = GROUP_GAP_RATIO): List<IntArray> {
        val sisa = boxes.map { it.copyOf() }.toMutableList()
        val hasil = ArrayList<IntArray>()
        while (sisa.isNotEmpty()) {
            var a = sisa.removeAt(0)
            var bergabung = true
            while (bergabung) {
                bergabung = false
                var i = 0
                while (i < sisa.size) {
                    val b = sisa[i]
                    val toleransi = max(a[3] - a[1], b[3] - b[1]) * gapRatio
                    val dekatX = (min(a[2], b[2]) - max(a[0], b[0])) > -toleransi
                    val dekatY = (min(a[3], b[3]) - max(a[1], b[1])) > -toleransi
                    if (dekatX && dekatY) {
                        a = intArrayOf(
                            min(a[0], b[0]), min(a[1], b[1]),
                            max(a[2], b[2]), max(a[3], b[3])
                        )
                        sisa.removeAt(i)
                        bergabung = true
                    } else i++
                }
            }
            hasil.add(a)
        }
        return hasil
    }

    /** Singkirkan derau dan blok selebar panel. */
    fun dropNoise(boxes: List<IntArray>, imgW: Int, imgH: Int): List<IntArray> {
        val minArea = imgW.toFloat() * imgH.toFloat() * MIN_AREA_RATIO
        val maxW = imgW * MAX_WIDTH_RATIO
        return boxes.filter { b ->
            val w = (b[2] - b[0]).toFloat()
            val h = (b[3] - b[1]).toFloat()
            w > 0 && h > 0 && w * h >= minArea && w < maxW
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
        val blok = groupNearby(luar)
        // Menggabungkan bisa membuat blok baru menutupi balon; saring lagi.
        val bersih = dropNoise(dropInsideBubbles(blok, bubbles), imgW, imgH)
        return bersih.sortedWith(compareBy({ it[1] }, { it[0] }))
    }
}
