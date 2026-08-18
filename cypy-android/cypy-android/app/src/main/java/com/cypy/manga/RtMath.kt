package com.cypy.manga

import kotlin.math.max
import kotlin.math.min

/**
 * Fungsi murni untuk memadukan keluaran tiga-kelas RT-DETR ke dalam pipeline.
 * Dipisah dari RtDetector supaya bisa diuji tanpa ONNX Runtime (pustaka native
 * onnxruntime tidak tersedia di unit test JVM).
 */
object RtMath {

    /** text_free dianggap sudah tercakup balon bila tumpang tindihnya sebesar ini. */
    const val COVERED_BY_BUBBLE = 0.50f

    /** Ambang IoU untuk membuang kotak kembar antar sumber detektor. */
    const val DUP_IOU = 0.55f

    /** text_bubble dianggap milik sebuah balon bila sebesar ini berada di dalamnya. */
    const val INSIDE_FRAC = 0.60f

    fun area(b: IntArray): Int = max(0, b[2] - b[0]) * max(0, b[3] - b[1])

    fun intersection(a: IntArray, b: IntArray): Int {
        val x1 = max(a[0], b[0]); val y1 = max(a[1], b[1])
        val x2 = min(a[2], b[2]); val y2 = min(a[3], b[3])
        return max(0, x2 - x1) * max(0, y2 - y1)
    }

    fun iou(a: IntArray, b: IntArray): Float {
        val i = intersection(a, b)
        val u = area(a) + area(b) - i
        return if (u <= 0) 0f else i.toFloat() / u
    }

    /** Apakah [inner] pada dasarnya berada di dalam [outer]. */
    fun isInside(inner: IntArray, outer: IntArray, frac: Float = INSIDE_FRAC): Boolean {
        val a = area(inner)
        return a > 0 && intersection(inner, outer) >= frac * a
    }

    /**
     * Mencarikan kotak text_bubble untuk setiap balon. Hasilnya sejajar indeks
     * dengan [bubbles]; null bila balon itu tak punya kotak teks (mis. balon
     * kosong, atau teksnya tidak terdeteksi).
     *
     * Bila satu balon memuat beberapa kotak teks, semuanya digabung menjadi
     * satu kotak pembungkus supaya seluruh teks lama ikut tertutup.
     */
    fun pairTextToBubbles(
        bubbles: List<IntArray>,
        textInBubble: List<IntArray>
    ): List<IntArray?> = bubbles.map { bub ->
        var acc: IntArray? = null
        for (t in textInBubble) {
            if (!isInside(t, bub)) continue
            acc = if (acc == null) t.copyOf() else intArrayOf(
                min(acc[0], t[0]), min(acc[1], t[1]),
                max(acc[2], t[2]), max(acc[3], t[3])
            )
        }
        acc
    }

    /**
     * Menyaring text_free: buang yang sebenarnya sudah berada di dalam balon
     * (biar tidak diterjemahkan dua kali), lalu buang duplikat antar sesama.
     */
    fun refineFreeText(
        freeText: List<IntArray>,
        bubbles: List<IntArray>,
        imgW: Int,
        imgH: Int
    ): List<IntArray> {
        val hasil = ArrayList<IntArray>()
        for (t in freeText) {
            val a = area(t)
            if (a <= 0) continue
            // Terlalu lebar = kemungkinan besar bukan blok teks komik.
            if (t[2] - t[0] >= imgW * TextRegionMath.MAX_WIDTH_RATIO) continue
            if (a < imgW.toLong() * imgH * TextRegionMath.MIN_AREA_RATIO) continue
            val tertutup = bubbles.any { b ->
                intersection(t, b) >= COVERED_BY_BUBBLE * a
            }
            if (tertutup) continue
            if (hasil.any { iou(it, t) >= DUP_IOU }) continue
            hasil.add(t)
        }
        return hasil
    }

    /**
     * Menggabungkan kotak dari detektor lama (YOLO) dengan kotak RT-DETR.
     * RT-DETR diprioritaskan; kotak YOLO hanya ditambahkan bila tidak ada
     * padanannya di RT-DETR. Dipakai pada mode "gabungan".
     */
    fun union(utama: List<IntArray>, tambahan: List<IntArray>): List<IntArray> {
        val hasil = ArrayList(utama)
        for (b in tambahan) {
            if (area(b) <= 0) continue
            val ada = hasil.any { iou(it, b) >= DUP_IOU || isInside(b, it, 0.80f) }
            if (!ada) hasil.add(b)
        }
        return hasil
    }
}
