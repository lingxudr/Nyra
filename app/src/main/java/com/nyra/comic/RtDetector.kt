package com.nyra.comic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max

/**
 * Detektor RT-DETR-v2 (ogkalu/comic-text-and-bubble-detector, Apache-2.0),
 * fine-tune atas ~11k gambar manga/webtoon/manhua/komik Barat.
 *
 * Berbeda dari eyecypy.onnx (YOLOv8, 1 kelas balon), model ini mengenal
 * TIGA kelas sekaligus:
 *
 *   0 = bubble      balon
 *   1 = text_bubble teks DI DALAM balon
 *   2 = text_free   teks DI LUAR balon (SFX, narasi, papan nama)
 *
 * Dua konsekuensi besar untuk pipeline:
 *
 *  - `text_free` menggantikan tahap OCR PP-OCRv5 untuk kasus teks lepas.
 *  - `text_bubble` memberi kotak teks yang presisi, jadi pembersihan tidak
 *    perlu mengecat seluruh balon — ini yang menutup celah balon gelap.
 *
 * Catatan teknis penting (hasil uji langsung, jangan diubah tanpa uji ulang):
 *
 *  - Post-processing (NMS + penskalaan) SUDAH ada di dalam graf ONNX. Keluaran
 *    langsung berupa koordinat piksel pada gambar asli, sehingga tidak ada
 *    letterbox/NMS manual seperti pada YoloDetector.
 *  - Masukan kedua `orig_target_sizes` HARUS berurutan (lebar, tinggi).
 *    Terbalik menjadi (tinggi, lebar) membuat kotak melar horizontal.
 *  - Gambar di-resize PAKSA ke 640x640 (bukan letterbox). Itu memang cara
 *    model ini dilatih: "Training Images were resized, not cropped."
 */
class RtDetector(ctx: Context) : AutoCloseable {

    companion object {
        const val ASSET = "rtdetr.onnx"
        const val CLASS_BUBBLE = 0
        const val CLASS_TEXT_BUBBLE = 1
        const val CLASS_TEXT_FREE = 2

        private const val INPUT_SIZE = 640

        /** Ambang balon. Sweep 0.3-0.7 atas 12 halaman manga asli: jumlah
         *  balon stabil (22) pada 0.4-0.5, turun ke 19 pada 0.6. */
        const val THRESHOLD_BUBBLE = 0.45f

        /** Ambang teks dibuat lebih tinggi: text_free jauh lebih sensitif
         *  (54 kotak pada 0.3 vs 22 pada 0.5) dan mudah memungut elemen UI. */
        const val THRESHOLD_TEXT = 0.55f

        fun tersedia(ctx: Context): Boolean = runCatching {
            ctx.assets.open(ASSET).use { it.close() }
            true
        }.getOrDefault(false)
    }

    /** Satu deteksi: kotak (x1,y1,x2,y2), kelas, dan skor. */
    data class Det(val box: IntArray, val cls: Int, val score: Float) {
        override fun equals(other: Any?): Boolean =
            other is Det && cls == other.cls && score == other.score &&
                box.contentEquals(other.box)

        override fun hashCode(): Int =
            box.contentHashCode() * 31 * 31 + cls * 31 + score.hashCode()
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val imageInput: String
    private val sizeInput: String

    init {
        val bytes = ctx.assets.open(ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, opts)
        val names = session.inputNames.toList()
        imageInput = names.firstOrNull { it.contains("image") } ?: names[0]
        sizeInput = names.firstOrNull { it != imageInput } ?: names[1]
    }

    fun detect(src: Bitmap): List<Det> {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return emptyList()

        val scaled = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== src) scaled.recycle()

        // NCHW, RGB, dinormalisasi ke 0..1 (tanpa mean/std ImageNet).
        val plane = INPUT_SIZE * INPUT_SIZE
        val buf = FloatBuffer.allocate(3 * plane)
        val arr = buf.array()
        for (i in 0 until plane) {
            val p = pixels[i]
            arr[i] = ((p shr 16) and 0xFF) / 255f
            arr[plane + i] = ((p shr 8) and 0xFF) / 255f
            arr[2 * plane + i] = (p and 0xFF) / 255f
        }

        val sizeBuf = LongBuffer.wrap(longArrayOf(w.toLong(), h.toLong()))

        val imgTensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val sizeTensor = OnnxTensor.createTensor(env, sizeBuf, longArrayOf(1, 2))

        val out = ArrayList<Det>()
        try {
            session.run(mapOf(imageInput to imgTensor, sizeInput to sizeTensor)).use { res ->
                val labels = res[0].value as Array<LongArray>
                val boxes = res[1].value as Array<Array<FloatArray>>
                val scores = res[2].value as Array<FloatArray>

                val lab = labels[0]
                val box = boxes[0]
                val sc = scores[0]

                for (i in lab.indices) {
                    val cls = lab[i].toInt()
                    val s = sc[i]
                    val thr = if (cls == CLASS_BUBBLE) THRESHOLD_BUBBLE else THRESHOLD_TEXT
                    if (s < thr) continue
                    val b = box[i]
                    val x1 = b[0].toInt().coerceIn(0, w)
                    val y1 = b[1].toInt().coerceIn(0, h)
                    val x2 = b[2].toInt().coerceIn(0, w)
                    val y2 = b[3].toInt().coerceIn(0, h)
                    if (x2 - x1 < BoxUtils.MIN_BOX_SIDE || y2 - y1 < BoxUtils.MIN_BOX_SIDE) continue
                    out.add(Det(intArrayOf(x1, y1, x2, y2), cls, s))
                }
            }
        } finally {
            imgTensor.close()
            sizeTensor.close()
        }
        return out
    }

    override fun close() {
        runCatching { session.close() }
    }
}
