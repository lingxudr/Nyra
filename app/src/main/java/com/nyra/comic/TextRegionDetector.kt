package com.nyra.comic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * Detektor wilayah teks berbasis PP-OCRv5 mobile det (ONNX, Apache-2.0).
 *
 * Kenapa ada: `eyecypy.onnx` dilatih mencari BALON. Teks yang ditulis langsung
 * di atas gambar tanpa balon — narasi kotak, teks latar, papan nama, efek
 * bergaya pada manhua/manhwa — tak pernah terdeteksi, jadi tak pernah
 * diterjemahkan. Model ini menemukan teks apa pun bentuk latarnya.
 *
 * Yang TIDAK dilakukan: mengenali aksaranya. Hanya letaknya yang dicari;
 * pembacaan tetap diserahkan ke model visi penerjemah, yang jauh lebih baik
 * membaca manga bergaya daripada pengenal teks generik. Jadi hanya bagian
 * "det" dari PaddleOCR yang dipakai, bukan "rec" — hemat 8 MB aset dan
 * menghindari kamus per bahasa.
 *
 * Keluaran model adalah peta probabilitas 1 kanal seukuran masukan; kotak
 * diambil dari komponen terhubung pada peta itu.
 */
class TextRegionDetector(ctx: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val modelBytes = ctx.assets.open(ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Utas.untukPerangkat())
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, opts)
        inputName = session.inputNames.iterator().next()
    }

    /** Wilayah teks mentah dalam koordinat halaman penuh. */
    fun detect(src: Bitmap): List<IntArray> {
        val (inW, inH) = TextRegionMath.inputSizeFor(src.width, src.height)
        val resized = Bitmap.createScaledBitmap(src, inW, inH, true)
        val prob: FloatArray
        try {
            prob = runInference(resized, inW, inH) ?: return emptyList()
        } finally {
            if (resized !== src) resized.recycle()
        }
        val kotak = connectedBoxes(prob, inW, inH)
        // Kembalikan ke koordinat halaman penuh.
        val sx = src.width.toFloat() / inW
        val sy = src.height.toFloat() / inH
        return kotak.map {
            intArrayOf(
                (it[0] * sx).toInt(), (it[1] * sy).toInt(),
                (it[2] * sx).toInt(), (it[3] * sy).toInt()
            )
        }
    }

    private fun runInference(bmp: Bitmap, w: Int, h: Int): FloatArray? {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Normalisasi ImageNet, tata letak NCHW — sama seperti PaddleOCR.
        val buf = FloatBuffer.allocate(3 * w * h)
        val arr = buf.array()
        val plane = w * h
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            arr[i] = (r - 0.485f) / 0.229f
            arr[plane + i] = (g - 0.456f) / 0.224f
            arr[2 * plane + i] = (b - 0.406f) / 0.225f
        }

        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        OnnxTensor.createTensor(env, buf, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val out = res[0].value as? Array<Array<Array<FloatArray>>> ?: return null
                val map = out[0][0]
                val flat = FloatArray(w * h)
                for (y in 0 until min(h, map.size)) {
                    val row = map[y]
                    for (x in 0 until min(w, row.size)) flat[y * w + x] = row[x]
                }
                return flat
            }
        }
    }

    /**
     * Kotak pembatas dari komponen terhubung pada peta probabilitas.
     * Penelusuran iteratif (bukan rekursif) supaya peta besar tidak
     * menghabiskan tumpukan.
     */
    private fun connectedBoxes(prob: FloatArray, w: Int, h: Int): List<IntArray> {
        val thr = TextRegionMath.PROB_THRESHOLD
        val visited = BooleanArray(w * h)
        val hasil = ArrayList<IntArray>()
        val antrian = IntArray(w * h)

        for (start in 0 until w * h) {
            if (visited[start] || prob[start] <= thr) continue
            var head = 0
            var tail = 0
            antrian[tail++] = start
            visited[start] = true
            var x1 = start % w; var x2 = x1
            var y1 = start / w; var y2 = y1
            var jumlah = 0
            var total = 0f

            while (head < tail) {
                val cur = antrian[head++]
                val cx = cur % w
                val cy = cur / w
                jumlah++
                total += prob[cur]
                if (cx < x1) x1 = cx
                if (cx > x2) x2 = cx
                if (cy < y1) y1 = cy
                if (cy > y2) y2 = cy

                if (cx > 0) push(cur - 1, prob, visited, antrian, tail, thr)?.let { tail = it }
                if (cx < w - 1) push(cur + 1, prob, visited, antrian, tail, thr)?.let { tail = it }
                if (cy > 0) push(cur - w, prob, visited, antrian, tail, thr)?.let { tail = it }
                if (cy < h - 1) push(cur + w, prob, visited, antrian, tail, thr)?.let { tail = it }
            }

            if (jumlah < 8) continue
            if (total / jumlah < TextRegionMath.BOX_THRESHOLD) continue
            hasil.add(intArrayOf(x1, y1, x2 + 1, y2 + 1))
        }
        return hasil
    }

    private fun push(
        idx: Int, prob: FloatArray, visited: BooleanArray,
        antrian: IntArray, tail: Int, thr: Float
    ): Int? {
        if (visited[idx] || prob[idx] <= thr) return null
        visited[idx] = true
        antrian[tail] = idx
        return tail + 1
    }

    override fun close() {
        runCatching { session.close() }
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    companion object {
        const val ASSET = "ocr_det.onnx"
    }
}
