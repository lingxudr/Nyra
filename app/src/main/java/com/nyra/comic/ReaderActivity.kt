package com.nyra.comic

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nyra.comic.databinding.ActivityReaderBinding
import kotlin.concurrent.thread

/**
 * Pembaca non-destruktif.
 *
 * Bedanya dengan layar Hasil: yang dibaca di sini bukan berkas PNG yang sudah
 * "dibakar", melainkan halaman ASLI yang bersih plus terjemahan yang digambar
 * ulang saat itu juga dari proyek. Karena aslinya tidak pernah ditimpa,
 * pengguna bisa berpindah bolak-balik antara versi terjemahan dan versi asli
 * kapan saja — berguna saat terjemahan terasa aneh dan ia ingin melihat teks
 * Jepangnya, atau saat ingin menikmati gambar tanpa tulisan menutupi wajah.
 *
 * Membakar hasil ke berkas gambar tetap ada, tetapi menjadi tindakan sadar
 * (ekspor di editor), bukan efek samping dari membaca.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var b: ActivityReaderBinding
    private lateinit var cfg: Config
    private var prj: Project? = null
    private var idx = 0
    private var pipeline: Pipeline? = null

    /** false = tampilkan halaman asli, true = tampilkan hasil terjemahan. */
    private var tampilTerjemahan = true

    /** Kedua versi halaman yang sedang dibuka; dipegang activity, bukan view. */
    private var bmpAsli: Bitmap? = null
    private var bmpTerjemahan: Bitmap? = null
    private var sibuk = false

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)
        cfg = Config(this)

        val id = intent.getStringExtra(EXTRA_PROJECT_ID)
        val p = id?.let { Project.load(this, it) }
        if (p == null || p.pages.isEmpty()) {
            Toast.makeText(this, R.string.editor_no_project, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        prj = p
        b.tvTitle.text = p.name

        // Activity memegang dua bitmap sekaligus dan menukarnya; kalau view
        // ikut mendaur ulang, menekan tombol lapisan dua kali akan memakai
        // bitmap yang sudah dibuang.
        b.pageView.milikSendiri = false

        pipeline = Pipeline(this, cfg, log = {}, progress = { _, _ -> })

        // Mengetuk balon menampilkan teks aslinya. Inilah gunanya menyimpan
        // sourceText: pembaca bisa memeriksa satu balon tanpa menukar seluruh
        // halaman ke versi asli.
        b.pageView.onBoxTap = { nomor -> tampilkanSumber(nomor) }

        b.btnPrev.setOnClickListener { pindah(-1) }
        b.btnNext.setOnClickListener { pindah(1) }
        b.btnLayer.setOnClickListener { tukarLapisan() }
        b.btnBack.setOnClickListener { finish() }

        b.tvHint.setText(R.string.reader_hint)
        muat()
    }

    override fun onDestroy() {
        super.onDestroy()
        // View sudah tidak memiliki bitmapnya, jadi pembebasan dilakukan di sini.
        b.pageView.setPage(null, emptyList())
        bebaskan()
    }

    private fun bebaskan() {
        bmpAsli?.let { if (!it.isRecycled) it.recycle() }
        bmpTerjemahan?.let { if (!it.isRecycled) it.recycle() }
        bmpAsli = null
        bmpTerjemahan = null
    }

    private fun pindah(arah: Int) {
        val p = prj ?: return
        val baru = idx + arah
        if (baru < 0 || baru >= p.pages.size) return
        idx = baru
        muat()
    }

    private fun tukarLapisan() {
        if (sibuk) return
        tampilTerjemahan = !tampilTerjemahan
        pasangLapisan()
    }

    /**
     * Tampilkan lapisan yang dipilih tanpa menggambar ulang apa pun.
     *
     * Kedua bitmap sudah disiapkan saat halaman dimuat, jadi menukar lapisan
     * harus terasa seketika. Menggambar ulang di sini akan membuat tombol
     * terasa berat padahal pekerjaannya sudah selesai.
     */
    private fun pasangLapisan() {
        val p = prj ?: return
        val hal = p.pages.getOrNull(idx) ?: return
        val bmp = if (tampilTerjemahan) bmpTerjemahan ?: bmpAsli else bmpAsli
        // Kotak hanya ditandai di lapisan asli; di lapisan terjemahan
        // tulisannya sudah tergambar, jadi bingkai merah muda cuma mengganggu.
        val kotak = if (tampilTerjemahan) emptyList() else hal.boxes.toList()
        b.pageView.setPage(bmp, kotak)
        b.btnLayer.setText(
            if (tampilTerjemahan) R.string.reader_translated else R.string.reader_original
        )
        b.tvPage.text = getString(R.string.reader_page, idx + 1, p.pages.size)
    }

    private fun muat() {
        val p = prj ?: return
        val hal = p.pages.getOrNull(idx) ?: return
        if (sibuk) return
        sibuk = true
        b.tvPage.setText(R.string.reader_loading)

        thread {
            // Halaman bersih dibaca dengan batas sisi yang sama seperti
            // renderProjectPage, supaya kedua lapisan berukuran identik dan
            // menukarnya tidak menggeser tata letak.
            val asli = runCatching {
                Storage.decodeBitmap(p.pageFile(this, hal), cfg.maxImageSide)
            }.getOrNull()
            val terjemahan = runCatching {
                pipeline?.renderProjectPage(p, hal)
            }.getOrNull()

            runOnUiThread {
                // Bitmap halaman sebelumnya baru dilepas setelah yang baru
                // siap: melepasnya lebih awal membuat layar berkedip kosong.
                b.pageView.setPage(null, emptyList())
                bebaskan()
                bmpAsli = asli
                bmpTerjemahan = terjemahan
                sibuk = false

                if (asli == null && terjemahan == null) {
                    b.tvPage.setText(R.string.reader_failed)
                    return@runOnUiThread
                }
                pasangLapisan()
                b.btnPrev.isEnabled = idx > 0
                b.btnNext.isEnabled = idx < p.pages.size - 1
            }
        }
    }

    /**
     * Tampilkan teks asli sebuah balon, dengan terjemahannya sebagai pembanding.
     */
    private fun tampilkanSumber(nomor: Int) {
        val p = prj ?: return
        val hal = p.pages.getOrNull(idx) ?: return
        val kunci = nomor.toString()
        val sumber = hal.sourceText[kunci]?.trim().orEmpty()
        val terjemah = hal.translations[kunci]?.trim().orEmpty()
        if (sumber.isEmpty() && terjemah.isEmpty()) return

        val isi = buildString {
            if (sumber.isNotEmpty()) {
                append(getString(R.string.reader_source)).append("\n").append(sumber)
            }
            if (terjemah.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(getString(R.string.reader_translation)).append("\n").append(terjemah)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reader_bubble, nomor))
            .setMessage(isi)
            .setPositiveButton(R.string.editor_ok, null)
            .setNeutralButton(R.string.lib_edit) { _, _ ->
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_PROJECT_ID, p.id)
                )
            }
            .show()
    }
}
