package com.cypy.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Penghapus teks berbasis LaMa (ONNX), pengganti isi-putih untuk teks yang
 * berada di ATAS artwork.
 *
 * Kenapa hanya teks lepas: di dalam balon yang memang putih polos, isi-putih
 * sudah sempurna dan gratis, sedangkan LaMa justru menambah galat kecil dan
 * biaya beberapa detik. Di atas artwork keadaannya terbalik - isi-putih
 * meninggalkan kotak putih yang merusak screentone dan siluet.
 *
 * Model: opencv/inpainting_lama (LaMa, Apache-2.0), 512x512 tetap,
 * masukan image [1,3,512,512] RGB 0..1 dan mask [1,1,512,512] dengan 1 = area
 * yang harus dilukis ulang.
 */
class Inpainter(ctx: Context) : AutoCloseable {

    companion object {
        const val NAMA = "lama.onnx"

        /** Ukuran pasti model resmi; dipakai memvalidasi unduhan separuh jalan. */
        const val UKURAN: Long = 92_591_623L

        /**
         * SHA-256 model resmi opencv/inpainting_lama.
         *
         * Diverifikasi dengan mengunduh berkasnya sungguhan lalu menghitung
         * hash-nya, bukan disalin dari header: nilai ini juga sama persis
         * dengan `x-linked-etag` (hash LFS) yang dikirim Hugging Face.
         *
         * Ukuran saja tidak cukup. Model ini diunduh lewat HTTP dari CDN
         * pihak ketiga, dan hasilnya langsung dijalankan sebagai graf ONNX -
         * berkas yang ditukar di tengah jalan berarti menjalankan model yang
         * bukan kita pilih. Menyamakan 92.591.623 byte itu sepele bagi
         * penyerang; menyamakan hash-nya tidak.
         */
        const val SHA256 =
            "7df918ac3921d3daf0aae1d219776cf0dc4e4935f035af81841b40adcf74fdf2"

        /**
         * Model 93 MB TIDAK dibundel di APK: itu membuat berkas pasang
         * membengkak tiga kali lipat padahal fiturnya bawaan mati. Model
         * diunduh sekali atas permintaan pengguna dan disimpan di
         * penyimpanan internal aplikasi.
         */
        fun berkas(ctx: Context): java.io.File = java.io.File(ctx.filesDir, NAMA)

        /** Unduhan terpotong menghasilkan ONNX rusak, jadi ukuran ikut dicek. */
        fun tersedia(ctx: Context): Boolean =
            berkas(ctx).let { it.isFile && it.length() == UKURAN }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val imageInput: String
    private val maskInput: String

    init {
        val bytes = berkas(ctx).readBytes()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, opts)
        val names = session.inputNames.toList()
        maskInput = names.firstOrNull { it.contains("mask", true) } ?: names[1]
        imageInput = names.firstOrNull { it != maskInput } ?: names[0]
    }

    /**
     * Hapus isi [boxes] dari [page], menggambar ulang latar di baliknya.
     * Menggambar langsung ke [page]. Mengembalikan jumlah petak yang diproses.
     */
    fun erase(page: Bitmap, boxes: List<IntArray>, log: (String) -> Unit = {}): Int {
        if (boxes.isEmpty()) return 0
        val imgW = page.width
        val imgH = page.height

        val dilated = boxes.map { InpaintMath.dilate(it, InpaintMath.maskPad(it), imgW, imgH) }
            .filter { it[2] > it[0] && it[3] > it[1] }
        if (dilated.isEmpty()) return 0

        val groups = InpaintMath.groupIntoTiles(dilated, imgW, imgH)
        var done = 0
        for ((tile, anggota) in groups) {
            val kotak = anggota.map { dilated[it] }
            runCatching { prosesTile(page, tile, kotak) }
                .onFailure { log("  [!] Inpaint gagal pada satu petak: ${it.message}") }
                .onSuccess { done++ }
        }
        return done
    }

    private fun prosesTile(page: Bitmap, tile: InpaintMath.Tile, boxes: List<IntArray>) {
        val T = InpaintMath.TILE

        // Potong petak lalu skalakan ke ukuran model.
        val potong = Bitmap.createBitmap(page, tile.x1, tile.y1, tile.w, tile.h)
        val masuk = if (potong.width == T && potong.height == T) potong
        else Bitmap.createScaledBitmap(potong, T, T, true)

        val piksel = IntArray(T * T)
        masuk.getPixels(piksel, 0, T, 0, 0, T, T)

        val plane = T * T
        val img = FloatBuffer.allocate(3 * plane)
        val ia = img.array()
        for (i in 0 until plane) {
            val p = piksel[i]
            ia[i] = ((p shr 16) and 0xFF) / 255f
            ia[plane + i] = ((p shr 8) and 0xFF) / 255f
            ia[2 * plane + i] = (p and 0xFF) / 255f
        }

        // Masker dalam koordinat petak yang sudah diskalakan.
        val sx = T.toFloat() / tile.w
        val sy = T.toFloat() / tile.h
        val msk = FloatBuffer.allocate(plane)
        val ma = msk.array()
        for (b in boxes) {
            val x1 = (((b[0] - tile.x1) * sx).toInt()).coerceIn(0, T - 1)
            val y1 = (((b[1] - tile.y1) * sy).toInt()).coerceIn(0, T - 1)
            val x2 = (((b[2] - tile.x1) * sx).toInt()).coerceIn(0, T)
            val y2 = (((b[3] - tile.y1) * sy).toInt()).coerceIn(0, T)
            for (y in y1 until y2) {
                val row = y * T
                for (x in x1 until x2) ma[row + x] = 1f
            }
        }

        val hasil: Bitmap
        OnnxTensor.createTensor(env, img, longArrayOf(1, 3, T.toLong(), T.toLong())).use { ti ->
            OnnxTensor.createTensor(env, msk, longArrayOf(1, 1, T.toLong(), T.toLong())).use { tm ->
                session.run(mapOf(imageInput to ti, maskInput to tm)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val arr = out[0].value as Array<Array<Array<FloatArray>>>
                    val ch = arr[0]
                    // Beberapa ekspor mengeluarkan 0..1, sebagian lain 0..255.
                    var maks = 0f
                    for (c in 0 until 3) for (y in 0 until T) for (x in 0 until T) {
                        val v = ch[c][y][x]
                        if (v > maks) maks = v
                    }
                    val skala = if (maks <= 1.5f) 255f else 1f
                    val keluar = IntArray(plane)
                    for (y in 0 until T) {
                        val row = y * T
                        for (x in 0 until T) {
                            val r = (ch[0][y][x] * skala).toInt().coerceIn(0, 255)
                            val g = (ch[1][y][x] * skala).toInt().coerceIn(0, 255)
                            val bl = (ch[2][y][x] * skala).toInt().coerceIn(0, 255)
                            keluar[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                        }
                    }
                    hasil = Bitmap.createBitmap(keluar, T, T, Bitmap.Config.ARGB_8888)
                }
            }
        }

        // Kembalikan ke ukuran petak, lalu tempel HANYA di area masker supaya
        // bagian gambar yang tidak dihapus tetap piksel aslinya.
        val balik = if (hasil.width == tile.w && hasil.height == tile.h) hasil
        else Bitmap.createScaledBitmap(hasil, tile.w, tile.h, true)

        val c = Canvas(page)
        for (b in boxes) {
            val src = Rect(b[0] - tile.x1, b[1] - tile.y1, b[2] - tile.x1, b[3] - tile.y1)
            val dst = Rect(b[0], b[1], b[2], b[3])
            if (src.width() <= 0 || src.height() <= 0) continue
            c.drawBitmap(balik, src, dst, null)
        }

        if (balik !== hasil) balik.recycle()
        hasil.recycle()
        if (masuk !== potong) masuk.recycle()
        potong.recycle()
    }

    override fun close() {
        runCatching { session.close() }
    }
}
