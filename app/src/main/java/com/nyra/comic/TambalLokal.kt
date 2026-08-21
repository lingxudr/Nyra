package com.nyra.comic

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Penambal gambar tanpa model: mengisi area yang dihapus dengan menyambung
 * piksel di sekelilingnya.
 *
 * Dipakai untuk menghapus watermark ketika model LaMa 93 MB belum diunduh.
 * Algoritmanya Fast Marching Method (Telea 2004), yang sama dengan
 * `cv::INPAINT_TELEA` di OpenCV: area diisi dari tepi ke dalam, urut menurut
 * jarak ke piksel yang masih utuh, dan setiap piksel baru adalah rata-rata
 * berbobot tetangga yang sudah diketahui.
 *
 * Alasan memilih ini, bukan sekadar rata-rata kotak:
 *
 * 1. Urutan pengisian mengikuti jarak, jadi isian tumbuh rapi dari tepi dan
 *    tidak meninggalkan tepi kotak yang kentara.
 * 2. Bobot arah (`dir`) membuat garis yang masuk ke area terhapus diteruskan
 *    ke dalam, bukan diratakan jadi bercak. Untuk komik ini penting: hampir
 *    semua halaman berisi garis tinta tegas dan raster screentone.
 *
 * Seluruh berkas ini aritmetika murni pada `IntArray` piksel ARGB, tanpa
 * `android.graphics`. Alasannya sama dengan [BubbleContour]: di unit test JVM
 * biasa seluruh metode `Color` adalah stub yang mengembalikan 0, sehingga
 * pengujian tambal akan lulus tanpa arti. Dengan aritmetika langsung, kelas ini
 * benar-benar teruji.
 */
object TambalLokal {

    /** Piksel yang nilainya sudah pasti dan boleh dipakai sebagai sumber. */
    private const val KNOWN = 0

    /** Piksel di tepi area terhapus, sedang menunggu giliran diisi. */
    private const val BAND = 1

    /** Piksel di dalam area terhapus yang belum diisi. */
    private const val INSIDE = 2

    /**
     * Radius tetangga yang dipakai untuk menghitung satu piksel, dalam piksel.
     *
     * Terlalu kecil membuat isian jadi bergaris; terlalu besar membuat isian
     * kabur dan lambat (biayanya kuadratik). 6 px adalah kompromi yang sama
     * dengan bawaan OpenCV.
     */
    const val RADIUS = 6

    private const val JAUH = 1.0e6f

    /**
     * Isi ulang seluruh [boxes] pada [pix] dari piksel di sekitarnya.
     *
     * [pix] adalah piksel ARGB baris-per-baris ukuran [w] x [h], dan ditulis
     * di tempat. Kotak dilebarkan lebih dulu memakai [InpaintMath.maskPad]
     * dengan alasan yang sama seperti pada model: sisa antialias di tepi huruf
     * akan dianggap konten sah dan menyisakan bayangan abu-abu.
     *
     * Mengembalikan jumlah piksel yang benar-benar diisi ulang.
     */
    fun tambal(
        pix: IntArray,
        w: Int,
        h: Int,
        boxes: List<IntArray>,
        radius: Int = RADIUS,
    ): Int = tambal(pix, w, h, boxes, radius, Mode.OTOMATIS)

    /** Cara mengisi lubang. */
    enum class Mode {
        /** Pilih sendiri: cermin bila ada margin bersih, kalau tidak difusi. */
        OTOMATIS,

        /** Paksa Telea (difusi). Dipakai untuk pengujian pembanding. */
        DIFUSI,

        /** Paksa salin-cermin. Dipakai untuk pengujian pembanding. */
        CERMIN,
    }

    /**
     * Versi [tambal] dengan pemilihan mesin eksplisit.
     *
     * Pemisahan ini ada supaya kedua mesin bisa diadu di unit test dengan
     * gambar yang sama; tanpa itu, klaim "cermin lebih baik" hanya jadi
     * komentar yang tidak pernah diperiksa ulang.
     */
    fun tambal(
        pix: IntArray,
        w: Int,
        h: Int,
        boxes: List<IntArray>,
        radius: Int,
        mode: Mode,
    ): Int {
        if (w <= 0 || h <= 0 || boxes.isEmpty() || pix.size < w * h) return 0

        if (mode != Mode.DIFUSI) {
            val n = TambalCermin.tambal(pix, w, h, boxes, mode == Mode.CERMIN)
            if (n > 0) return n
            // Cermin menolak (mis. lubang terlalu dekat tepi gambar): lanjut
            // ke difusi, yang selalu bisa mengisi asalkan ada satu piksel utuh.
        }
        return difusi(pix, w, h, boxes, radius)
    }

    /** Telea Fast Marching Method. */
    private fun difusi(
        pix: IntArray,
        w: Int,
        h: Int,
        boxes: List<IntArray>,
        radius: Int,
    ): Int {
        if (w <= 0 || h <= 0 || boxes.isEmpty() || pix.size < w * h) return 0

        val flag = ByteArray(w * h) { KNOWN.toByte() }
        var jumlah = 0

        for (b in boxes) {
            val d = InpaintMath.dilate(b, InpaintMath.maskPad(b), w, h)
            for (y in max(0, d[1]) until min(h, d[3])) {
                val baris = y * w
                for (x in max(0, d[0]) until min(w, d[2])) {
                    if (flag[baris + x] == KNOWN.toByte()) {
                        flag[baris + x] = INSIDE.toByte()
                        jumlah++
                    }
                }
            }
        }
        if (jumlah == 0) return 0

        val t = FloatArray(w * h)
        val antrean = PriorityQueue<Long>()

        // Pita awal: piksel INSIDE yang bersentuhan dengan piksel utuh.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (flag[i] != INSIDE.toByte()) continue
                if (adaTetanggaUtuh(flag, x, y, w, h)) {
                    flag[i] = BAND.toByte()
                    t[i] = 0f
                    antrean.add(kunci(0f, i))
                }
            }
        }
        // Kotak menutupi seluruh gambar: tidak ada sumber untuk menambal.
        if (antrean.isEmpty()) return 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (flag[i] == INSIDE.toByte()) t[i] = JAUH
            }
        }

        while (true) {
            val teratas = antrean.poll() ?: break
            val i = (teratas and 0xFFFFFFFFL).toInt()
            if (flag[i] == KNOWN.toByte()) continue
            flag[i] = KNOWN.toByte()

            val x = i % w
            val y = i / w
            for (k in 0 until 4) {
                val nx = x + DX[k]
                val ny = y + DY[k]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (flag[j] == KNOWN.toByte()) continue

                if (flag[j] == INSIDE.toByte()) {
                    isiPiksel(pix, t, flag, nx, ny, w, h, radius)
                    flag[j] = BAND.toByte()
                }

                val baru = min(
                    min(
                        eikonal(nx - 1, ny, nx, ny - 1, t, flag, w, h),
                        eikonal(nx + 1, ny, nx, ny - 1, t, flag, w, h),
                    ),
                    min(
                        eikonal(nx - 1, ny, nx, ny + 1, t, flag, w, h),
                        eikonal(nx + 1, ny, nx, ny + 1, t, flag, w, h),
                    ),
                )
                if (baru < t[j]) {
                    t[j] = baru
                    antrean.add(kunci(baru, j))
                }
            }
        }
        return jumlah
    }

    /**
     * Satu entri antrean: jarak di 32 bit atas, indeks piksel di 32 bit bawah.
     *
     * Digabung jadi `Long` supaya [PriorityQueue] mengurutkannya langsung tanpa
     * comparator dan tanpa membuat objek pembungkus per piksel. Jarak selalu
     * >= 0 sehingga pola bit `Float` yang sudah di-`toRawIntBits` tetap urut
     * naik ketika dibandingkan sebagai bilangan bulat.
     */
    private fun kunci(jarak: Float, indeks: Int): Long =
        (java.lang.Float.floatToRawIntBits(jarak).toLong() shl 32) or
            (indeks.toLong() and 0xFFFFFFFFL)

    private val DX = intArrayOf(-1, 0, 1, 0)
    private val DY = intArrayOf(0, -1, 0, 1)

    private fun adaTetanggaUtuh(flag: ByteArray, x: Int, y: Int, w: Int, h: Int): Boolean {
        for (k in 0 until 4) {
            val nx = x + DX[k]
            val ny = y + DY[k]
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            if (flag[ny * w + nx] == KNOWN.toByte()) return true
        }
        return false
    }

    /**
     * Penyelesai persamaan eikonal |grad T| = 1 pada dua tetangga bersudut.
     * Menghasilkan perkiraan jarak titik (x2,y2)/(x1,y1) ke tepi area terhapus.
     */
    private fun eikonal(
        x1: Int, y1: Int, x2: Int, y2: Int,
        t: FloatArray, flag: ByteArray, w: Int, h: Int,
    ): Float {
        var sol = JAUH
        val ada1 = x1 in 0 until w && y1 in 0 until h
        val ada2 = x2 in 0 until w && y2 in 0 until h
        val f1 = if (ada1) flag[y1 * w + x1] else (-1).toByte()
        val f2 = if (ada2) flag[y2 * w + x2] else (-1).toByte()

        if (f1 == KNOWN.toByte()) {
            val t1 = t[y1 * w + x1]
            if (f2 == KNOWN.toByte()) {
                val t2 = t[y2 * w + x2]
                val d = 2f - (t1 - t2) * (t1 - t2)
                if (d > 0f) {
                    val r = sqrt(d)
                    var s = (t1 + t2 - r) / 2f
                    if (s >= t1 && s >= t2) {
                        sol = s
                    } else {
                        s += r
                        if (s >= t1 && s >= t2) sol = s
                    }
                } else {
                    sol = 1f + min(t1, t2)
                }
            } else {
                sol = 1f + t1
            }
        } else if (f2 == KNOWN.toByte()) {
            sol = 1f + t[y2 * w + x2]
        }
        return sol
    }

    /**
     * Hitung satu piksel dari tetangga yang sudah diketahui.
     *
     * Bobot tiap tetangga adalah hasil kali tiga faktor Telea:
     * - `dir`  : searah gradien T, meneruskan garis yang masuk ke area hapus;
     * - `jarak`: tetangga dekat lebih menentukan daripada yang jauh;
     * - `level`: tetangga yang sejajar tepi lebih dipercaya.
     */
    private fun isiPiksel(
        pix: IntArray, t: FloatArray, flag: ByteArray,
        x: Int, y: Int, w: Int, h: Int, radius: Int,
    ) {
        val i = y * w + x
        var gx = turunan(t, flag, x + 1, y, x - 1, y, w, h)
        var gy = turunan(t, flag, x, y + 1, x, y - 1, w, h)
        val panjang = sqrt(gx * gx + gy * gy)
        if (panjang > 1e-6f) {
            gx /= panjang
            gy /= panjang
        }

        var totalR = 0f
        var totalG = 0f
        var totalB = 0f
        var bobotTotal = 0f

        val r2 = (radius * radius).toFloat()
        for (dy in -radius..radius) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -radius..radius) {
                val nx = x + dx
                if (nx < 0 || nx >= w) continue
                val j = ny * w + nx
                if (flag[j] != KNOWN.toByte()) continue

                val d2 = (dx * dx + dy * dy).toFloat()
                if (d2 == 0f || d2 > r2) continue
                val d = sqrt(d2)

                // Searah gradien T. Lantai kecil supaya tetangga yang tegak
                // lurus tetap ikut sedikit, jika tidak isian bisa bergaris.
                val dir = max(0.000001f, abs((-dx / d) * gx + (-dy / d) * gy))
                val jarak = 1f / (d2 * d)
                val level = 1f / (1f + abs(t[j] - t[i]))
                val bobot = dir * jarak * level

                val p = pix[j]
                totalR += bobot * ((p shr 16) and 0xFF)
                totalG += bobot * ((p shr 8) and 0xFF)
                totalB += bobot * (p and 0xFF)
                bobotTotal += bobot
            }
        }

        if (bobotTotal <= 0f) return
        val rr = (totalR / bobotTotal + 0.5f).toInt().coerceIn(0, 255)
        val gg = (totalG / bobotTotal + 0.5f).toInt().coerceIn(0, 255)
        val bb = (totalB / bobotTotal + 0.5f).toInt().coerceIn(0, 255)
        pix[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    /** Beda tengah pada T, memakai sisi yang tersedia saja. */
    private fun turunan(
        t: FloatArray, flag: ByteArray,
        xa: Int, ya: Int, xb: Int, yb: Int, w: Int, h: Int,
    ): Float {
        val adaA = xa in 0 until w && ya in 0 until h && flag[ya * w + xa] == KNOWN.toByte()
        val adaB = xb in 0 until w && yb in 0 until h && flag[yb * w + xb] == KNOWN.toByte()
        return when {
            adaA && adaB -> (t[ya * w + xa] - t[yb * w + xb]) / 2f
            adaA -> t[ya * w + xa]
            adaB -> -t[yb * w + xb]
            else -> 0f
        }
    }
}
