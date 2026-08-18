package com.cypy.manga

import kotlin.math.max
import kotlin.math.min

/**
 * Perhitungan murni untuk inpainting: penentuan petak, dilatasi masker, dan
 * pemaduan tepi. Dipisah dari [Inpainter] supaya bisa diuji tanpa onnxruntime,
 * yang native library-nya tidak tersedia di unit test.
 */
object InpaintMath {

    /** Sisi masukan model LaMa. */
    const val TILE = 512

    /**
     * Lebar pita pemaduan di tepi petak, dalam piksel. Tanpa pemaduan, batas
     * antar petak terlihat sebagai garis lurus di tengah artwork.
     */
    const val FEATHER = 16

    /** Kotak (x1,y1,x2,y2) dalam koordinat gambar. */
    data class Tile(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
        val w: Int get() = x2 - x1
        val h: Int get() = y2 - y1
    }

    /**
     * Perbesar kotak sebesar [pad] piksel ke segala arah lalu jepit ke gambar.
     * Masker teks harus sedikit lebih besar dari huruf: sisa antialias di tepi
     * huruf akan dianggap konten sah oleh model dan menghasilkan noda abu-abu.
     */
    fun dilate(box: IntArray, pad: Int, imgW: Int, imgH: Int): IntArray {
        val x1 = max(0, box[0] - pad)
        val y1 = max(0, box[1] - pad)
        val x2 = min(imgW, box[2] + pad)
        val y2 = min(imgH, box[3] + pad)
        return intArrayOf(x1, y1, min(x2, imgW), min(y2, imgH))
    }

    /**
     * Petak 512x512 yang berpusat pada [box], dijepit ke dalam gambar.
     *
     * Model bekerja pada 512x512 tetap. Alih-alih memperkecil seluruh halaman
     * (yang membuat detail hilang pada halaman 2000 px), kita potong jendela
     * seukuran model di sekitar area yang dihapus sehingga resolusi asli
     * dipertahankan. Kalau gambarnya lebih kecil dari petak, seluruh gambar
     * dipakai dan penskalaan diserahkan ke pemanggil.
     */
    fun tileFor(box: IntArray, imgW: Int, imgH: Int): Tile {
        val cx = (box[0] + box[2]) / 2
        val cy = (box[1] + box[3]) / 2
        val half = TILE / 2

        if (imgW <= TILE && imgH <= TILE) return Tile(0, 0, imgW, imgH)

        // SFX besar bisa lebih lebar dari 512 px. Petak harus tetap memuat
        // seluruh kotak - kalau terpotong, sisa hurufnya tertinggal di
        // halaman - jadi petak diperbesar dan penskalaan ke ukuran model
        // diserahkan ke pemanggil.
        val perluW = max(TILE, box[2] - box[0])
        val perluH = max(TILE, box[3] - box[1])
        val tw = min(perluW, imgW)
        val th = min(perluH, imgH)

        var x1 = cx - max(half, tw / 2)
        var y1 = cy - max(half, th / 2)
        x1 = x1.coerceIn(0, max(0, imgW - tw))
        y1 = y1.coerceIn(0, max(0, imgH - th))
        return Tile(x1, y1, x1 + tw, y1 + th)
    }

    /**
     * Kelompokkan kotak yang berdekatan supaya bisa diselesaikan dalam satu
     * petak. Satu inferensi memakan waktu beberapa detik, jadi menggabungkan
     * beberapa SFX yang berdekatan menjadi satu petak adalah penghematan
     * terbesar yang tersedia.
     */
    fun groupIntoTiles(boxes: List<IntArray>, imgW: Int, imgH: Int): List<Pair<Tile, List<Int>>> {
        val out = ArrayList<Pair<Tile, MutableList<Int>>>()
        val terpakai = BooleanArray(boxes.size)

        for (i in boxes.indices) {
            if (terpakai[i]) continue
            val t = tileFor(boxes[i], imgW, imgH)
            val anggota = mutableListOf(i)
            terpakai[i] = true
            for (j in i + 1 until boxes.size) {
                if (terpakai[j]) continue
                val b = boxes[j]
                // Hanya digabung kalau seluruh kotak muat di petak yang sama:
                // kotak yang terpotong akan menghasilkan sisa teks di tepinya.
                if (b[0] >= t.x1 && b[1] >= t.y1 && b[2] <= t.x2 && b[3] <= t.y2) {
                    anggota.add(j)
                    terpakai[j] = true
                }
            }
            out.add(t to anggota)
        }
        return out.map { it.first to it.second.toList() }
    }

    /**
     * Bobot pemaduan 0..1 untuk piksel di dalam kotak masker, mengecil di
     * dekat tepi. Nilai 1 berarti pakai hasil model sepenuhnya.
     */
    fun featherWeight(x: Int, y: Int, box: IntArray): Float {
        val d = min(
            min(x - box[0], box[2] - 1 - x),
            min(y - box[1], box[3] - 1 - y)
        )
        if (d >= FEATHER) return 1f
        if (d < 0) return 0f
        return (d + 1).toFloat() / (FEATHER + 1).toFloat()
    }

    /**
     * Ukuran padding masker menurut ukuran kotak: kotak besar butuh pita lebih
     * lebar karena hurufnya juga lebih tebal.
     */
    fun maskPad(box: IntArray): Int {
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        return max(3, (min(w, h) * 0.08f).toInt().coerceAtMost(24))
    }
}
