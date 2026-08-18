package com.cypy.manga

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cypy.manga.databinding.ActivityEditorBinding
import java.io.File
import kotlin.concurrent.thread

/**
 * Mode koreksi manual.
 *
 * Alasan keberadaannya: terjemahan mesin tidak pernah 100% benar, dan sampai
 * ronde 17 satu kata yang salah memaksa pengguna menjalankan ulang seluruh
 * bab — membayar lagi deteksi tiap halaman dan seluruh panggilan LLM. Di sini
 * yang mahal itu sudah tersimpan di proyek, jadi memperbaiki satu balon hanya
 * berarti menggambar ulang satu halaman: tanpa jaringan, tanpa detektor.
 *
 * Alurnya sengaja sesederhana mungkin: ketuk balon di gambar, ubah teksnya,
 * halaman langsung tergambar ulang. Ekspor menulis halaman yang sudah
 * diperbaiki ke folder keluaran yang sama.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditorBinding
    private lateinit var cfg: Config
    private var prj: Project? = null
    private var idx = 0
    private var pipeline: Pipeline? = null
    private var sibuk = false

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditorBinding.inflate(layoutInflater)
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

        // Pipeline dipakai HANYA sebagai mesin gambar di sini; tidak ada
        // provider yang dibuat sampai run() dipanggil, jadi tidak ada
        // jaringan dan tidak ada kunci API yang diperlukan.
        pipeline = Pipeline(this, cfg, log = {}, progress = { _, _ -> })

        b.pageView.onBoxTap = { nomor -> sunting(nomor) }
        b.btnPrev.setOnClickListener { pindah(-1) }
        b.btnNext.setOnClickListener { pindah(1) }
        b.btnExport.setOnClickListener { ekspor() }

        tampilkan()
    }

    override fun onDestroy() {
        super.onDestroy()
        b.pageView.lepas()
        runCatching { pipeline?.close() }
    }

    private fun pindah(delta: Int) {
        val p = prj ?: return
        val baru = (idx + delta).coerceIn(0, p.pages.size - 1)
        if (baru == idx) return
        idx = baru
        tampilkan()
    }

    /** Gambar ulang halaman aktif di thread latar; bisa detik-an untuk strip panjang. */
    private fun tampilkan(sorot: Int = 0) {
        val p = prj ?: return
        val page = p.pages.getOrNull(idx) ?: return
        if (sibuk) return
        sibuk = true
        b.tvPageNum.text = "${idx + 1}/${p.pages.size}"
        b.btnPrev.isEnabled = false
        b.btnNext.isEnabled = false

        thread {
            val bmp: Bitmap? = runCatching { pipeline?.renderProjectPage(p, page) }.getOrNull()
            runOnUiThread {
                sibuk = false
                b.btnPrev.isEnabled = idx > 0
                b.btnNext.isEnabled = idx < p.pages.size - 1
                if (bmp == null) {
                    Toast.makeText(this, R.string.editor_render_fail, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                b.pageView.setPage(bmp, page.boxes)
                if (sorot > 0) {
                    b.pageView.sorotKotak(sorot)
                    b.pageView.bawaKeKotak(sorot)
                }
            }
        }
    }

    private fun sunting(nomor: Int) {
        val p = prj ?: return
        val page = p.pages.getOrNull(idx) ?: return
        val kunci = nomor.toString()

        val v = LayoutInflater.from(this).inflate(R.layout.dialog_edit_bubble, null)
        val et = v.findViewById<EditText>(R.id.etText)
        val tvSrc = v.findViewById<TextView>(R.id.tvSource)
        et.setText(page.translations[kunci] ?: "")
        et.setSelection(et.text.length)
        page.sourceText[kunci]?.takeIf { it.isNotBlank() }?.let {
            tvSrc.text = it
            tvSrc.visibility = android.view.View.VISIBLE
        }

        AlertDialog.Builder(this)
            .setView(v)
            .setPositiveButton(R.string.editor_save) { _, _ ->
                val teks = et.text.toString().trim()
                if (teks.isEmpty()) page.translations.remove(kunci)
                else page.translations[kunci] = teks
                // Simpan segera: kalau aplikasi ditutup setelah menyunting,
                // suntingan tidak boleh ikut hilang.
                runCatching { p.save(this) }
                tampilkan(nomor)
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    /**
     * Tulis SEMUA halaman yang sudah diperbaiki ke folder keluaran.
     *
     * Seluruh bab, bukan cuma halaman aktif: pengguna biasanya membetulkan
     * beberapa balon di beberapa halaman lalu ingin satu set berkas yang utuh
     * dan konsisten untuk dibaca.
     */
    private fun ekspor() {
        val p = prj ?: return
        if (sibuk) return
        val treeStr = cfg.outputTreeUri
        if (treeStr.isBlank()) {
            Toast.makeText(this, R.string.editor_no_output, Toast.LENGTH_LONG).show()
            return
        }
        val tree = Uri.parse(treeStr)
        sibuk = true
        b.btnExport.isEnabled = false
        b.tvHint.setText(R.string.editor_exporting)

        thread {
            var ok = 0
            var gagal = 0
            val sub = Langs.code(p.targetLanguage).uppercase()
            for ((i, page) in p.pages.withIndex()) {
                val bmp = runCatching { pipeline?.renderProjectPage(p, page) }.getOrNull()
                if (bmp == null) { gagal++; continue }
                val nama = "p%04d_%s.png".format(i, Storage.baseName(page.srcName))
                val hasil = runCatching {
                    Storage.writeToTree(this, tree, sub, nama, "image/png") { os ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                    }
                }.getOrNull()
                if (hasil != null) ok++ else gagal++
                if (!bmp.isRecycled) bmp.recycle()
                val n = i + 1
                runOnUiThread { b.tvHint.text = getString(R.string.editor_export_progress, n, p.pages.size) }
            }
            runOnUiThread {
                sibuk = false
                b.btnExport.isEnabled = true
                b.tvHint.setText(R.string.editor_hint)
                Toast.makeText(
                    this, getString(R.string.editor_export_done, ok, gagal), Toast.LENGTH_LONG
                ).show()
                setResult(Activity.RESULT_OK, Intent().putExtra("exported", ok))
            }
        }
    }
}
