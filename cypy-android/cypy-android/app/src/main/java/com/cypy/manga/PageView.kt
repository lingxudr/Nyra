package com.cypy.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Penampil halaman yang bisa digeser, dizoom, dan diketuk per balon.
 *
 * Ada sebagai View sendiri karena mode koreksi manual butuh dua hal yang
 * tidak diberikan ImageView biasa: (1) halaman webtoon bisa 1080x11700 px
 * sehingga harus bisa dizoom untuk membaca satu balon, dan (2) ketukan harus
 * diterjemahkan kembali dari koordinat layar ke koordinat BITMAP supaya tahu
 * balon mana yang dimaksud.
 *
 * Matriksnya disimpan sendiri, bukan lewat ImageView.setImageMatrix, sebab
 * pemetaan balik koordinat perlu skala dan geseran yang pasti — inilah satu-
 * satunya sumber kebenaran untuk keduanya.
 */
class PageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var bmp: Bitmap? = null
    private var boxes: List<IntArray> = emptyList()

    /** Nomor kotak (berbasis 1) yang sedang disorot, 0 = tidak ada. */
    private var sorot = 0

    /** Dipanggil dengan nomor kotak berbasis 1 saat sebuah balon diketuk. */
    var onBoxTap: ((Int) -> Unit)? = null

    private val m = Matrix()
    private val nilai = FloatArray(9)
    private var skalaDasar = 1f
    private var siapDipasang = false

    private val catKotak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#80EC4899")
    }
    private val catSorot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FFEC4899")
    }

    private val zoomDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val s = d.scaleFactor
                val sekarang = skalaSaatIni()
                // Batas bawah dipatok ke skala "muat layar" supaya halaman
                // tidak bisa mengecil jadi titik dan hilang.
                val faktor = (sekarang * s).coerceIn(skalaDasar * 0.9f, skalaDasar * 12f) / sekarang
                m.postScale(faktor, faktor, d.focusX, d.focusY)
                batasi()
                invalidate()
                return true
            }
        })

    private var lastX = 0f
    private var lastY = 0f
    private var geser = false
    private var bergerak = false
    private var turunX = 0f
    private var turunY = 0f

    fun setPage(bitmap: Bitmap?, kotak: List<IntArray>) {
        bmp?.let { if (it !== bitmap && !it.isRecycled) it.recycle() }
        bmp = bitmap
        boxes = kotak
        sorot = 0
        siapDipasang = false
        requestLayout()
        invalidate()
    }

    fun sorotKotak(nomor: Int) {
        sorot = nomor
        invalidate()
    }

    /** Bitmap yang sedang tampil — dipakai activity untuk mengekspor. */
    fun bitmap(): Bitmap? = bmp

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        siapDipasang = false
        invalidate()
    }

    /** Pas-kan halaman ke lebar view, ditempel ke atas (cara baca webtoon). */
    private fun pasang() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val s = width.toFloat() / b.width.toFloat()
        skalaDasar = s
        m.reset()
        m.postScale(s, s)
        siapDipasang = true
    }

    private fun skalaSaatIni(): Float {
        m.getValues(nilai)
        return nilai[Matrix.MSCALE_X]
    }

    /** Jaga supaya halaman tidak bisa digeser keluar dari layar. */
    private fun batasi() {
        val b = bmp ?: return
        m.getValues(nilai)
        val s = nilai[Matrix.MSCALE_X]
        var tx = nilai[Matrix.MTRANS_X]
        var ty = nilai[Matrix.MTRANS_Y]
        val lebar = b.width * s
        val tinggi = b.height * s

        tx = if (lebar <= width) (width - lebar) / 2f
        else tx.coerceIn(width - lebar, 0f)
        ty = if (tinggi <= height) (height - tinggi) / 2f
        else ty.coerceIn(height - tinggi, 0f)

        nilai[Matrix.MTRANS_X] = tx
        nilai[Matrix.MTRANS_Y] = ty
        m.setValues(nilai)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        if (!siapDipasang) { pasang(); batasi() }
        canvas.drawBitmap(b, m, null)

        // Kotak digambar lewat matriks yang sama supaya selalu menempel di
        // balonnya, berapa pun zoom dan geserannya.
        m.getValues(nilai)
        val s = nilai[Matrix.MSCALE_X]
        val tx = nilai[Matrix.MTRANS_X]
        val ty = nilai[Matrix.MTRANS_Y]
        for ((i, box) in boxes.withIndex()) {
            val r = RectF(
                box[0] * s + tx, box[1] * s + ty,
                box[2] * s + tx, box[3] * s + ty
            )
            if (r.bottom < 0 || r.top > height) continue
            canvas.drawRect(r, if (i + 1 == sorot) catSorot else catKotak)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        zoomDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                turunX = event.x; turunY = event.y
                geser = true; bergerak = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (geser && !zoomDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (kotlin.math.abs(event.x - turunX) > 12 ||
                        kotlin.math.abs(event.y - turunY) > 12) bergerak = true
                    m.postTranslate(dx, dy)
                    batasi()
                    invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                // Ketukan, bukan geseran: cari balon di bawah jari.
                if (geser && !bergerak) ketuk(event.x, event.y)
                geser = false
            }
            MotionEvent.ACTION_CANCEL -> geser = false
        }
        return true
    }

    private fun ketuk(x: Float, y: Float) {
        m.getValues(nilai)
        val s = nilai[Matrix.MSCALE_X]
        if (s <= 0f) return
        val bx = (x - nilai[Matrix.MTRANS_X]) / s
        val by = (y - nilai[Matrix.MTRANS_Y]) / s

        // Balon bisa bertumpuk; pilih yang TERKECIL yang memuat titik itu,
        // sebab kotak besar biasanya panel atau narasi yang membungkusnya.
        var pilih = 0
        var luasTerkecil = Float.MAX_VALUE
        for ((i, b) in boxes.withIndex()) {
            if (bx < b[0] || bx > b[2] || by < b[1] || by > b[3]) continue
            val luas = (b[2] - b[0]).toFloat() * (b[3] - b[1]).toFloat()
            if (luas < luasTerkecil) { luasTerkecil = luas; pilih = i + 1 }
        }
        if (pilih > 0) {
            sorot = pilih
            invalidate()
            onBoxTap?.invoke(pilih)
        }
    }

    /** Geser tampilan supaya kotak [nomor] terlihat di tengah layar. */
    fun bawaKeKotak(nomor: Int) {
        val b = boxes.getOrNull(nomor - 1) ?: return
        m.getValues(nilai)
        val s = nilai[Matrix.MSCALE_X]
        val tengahY = (b[1] + b[3]) / 2f * s
        val tengahX = (b[0] + b[2]) / 2f * s
        nilai[Matrix.MTRANS_X] = width / 2f - tengahX
        nilai[Matrix.MTRANS_Y] = height / 2f - tengahY
        m.setValues(nilai)
        batasi()
        invalidate()
    }

    fun lepas() {
        bmp?.let { if (!it.isRecycled) it.recycle() }
        bmp = null
    }
}
