package com.nyra.comic

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
import com.nyra.comic.databinding.ActivityEditorBinding
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

    /** Kotak terpilih (berbasis 1) di mode sunting kotak; 0 = tidak ada. */
    private var terpilih = 0

    /**
     * Benar bila seretan berikutnya membuat kotak watermark, bukan kotak teks.
     *
     * Dua fitur memakai gerakan seret yang sama, jadi tujuan seretan harus
     * jelas sebelum jari menyentuh layar - kalau tidak, pengguna yang bermaksud
     * menghapus watermark malah membuat balon teks kosong.
     */
    private var modeWatermark = false

    /**
     * Tumpukan pembatalan.
     *
     * Menghapus watermark itu merusak: piksel aslinya ditimpa dan tidak ada
     * cara mengembalikannya kalau tebakannya salah. Karena penghapusan selalu
     * dihitung ulang dari halaman bersih, membatalkan cukup berarti membuang
     * kotaknya lagi dari daftar - murah, dan membuat fitur ini aman dicoba.
     */
    private val urungan = ArrayDeque<() -> Unit>()

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"

        /** Batas tumpukan pembatalan, supaya tidak tumbuh tanpa akhir. */
        const val MAKS_URUNG = 20

        /** Berapa halaman tetangga dipindai untuk mencari pengulangan. */
        const val TETANGGA_PINDAI = 2
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

        b.pageView.onBoxTap = { nomor -> if (b.swEditKotak.isChecked) pilihKotak(nomor) else sunting(nomor) }
        b.btnPrev.setOnClickListener { pindah(-1) }
        b.btnNext.setOnClickListener { pindah(1) }
        b.btnExport.setOnClickListener { pilihFormatEkspor() }

        b.swEditKotak.setOnCheckedChangeListener { _, on ->
            b.pageView.modeEdit = on
            b.btnAddBox.isEnabled = on
            b.btnDelBox.isEnabled = on && terpilih > 0
            if (!on) {
                b.pageView.modeTambah = false
                terpilih = 0
            }
            b.tvHint.setText(if (on) R.string.editor_hint_box else R.string.editor_hint)
        }

        b.btnAddBox.setOnClickListener {
            val aktif = !b.pageView.modeTambah
            b.pageView.modeTambah = aktif
            b.tvHint.setText(if (aktif) R.string.editor_hint_draw else R.string.editor_hint_box)
        }

        b.btnDelBox.setOnClickListener { hapusKotak() }

        // Kotak digeser/diubah ukuran: simpan koordinat baru lalu gambar ulang
        // sekali di akhir gerakan, bukan setiap piksel.
        b.pageView.onBoxChanged = { nomor, kotak ->
            val page = prj?.pages?.getOrNull(idx)
            if (page != null) {
                BoxEdit.perbarui(page, nomor, kotak)
                runCatching { prj?.save(this) }
                tampilkan(nomor)
            }
        }

        b.pageView.onBoxCreated = { kotak ->
            if (modeWatermark) tambahWatermark(kotak) else tambahKotak(kotak)
        }

        b.btnWatermark.setOnClickListener { dialogWatermark() }
        b.btnUndo.setOnClickListener { urungkan() }

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

    /** Pilih kotak tanpa membuka dialog teks (mode sunting kotak). */
    private fun pilihKotak(nomor: Int) {
        terpilih = nomor
        b.btnDelBox.isEnabled = nomor > 0
        b.pageView.sorotKotak(nomor)
    }

    /**
     * Tambah kotak hasil seretan.
     *
     * Setelah disisipkan, dialog teks langsung dibuka: kotak kosong tanpa
     * terjemahan tidak menggambar apa pun, jadi membiarkan pengguna menebak
     * langkah berikutnya hanya membuat fitur ini terasa rusak.
     */
    private fun tambahKotak(kotak: IntArray) {
        val p = prj ?: return
        val page = p.pages.getOrNull(idx) ?: return
        val pos = BoxEdit.sisipkan(page, kotak, cfg.bacaKananKeKiri)
        runCatching { p.save(this) }
        b.pageView.modeTambah = false
        b.tvHint.setText(R.string.editor_hint_box)
        terpilih = pos + 1
        tampilkan(terpilih)
        sunting(terpilih)
    }

    private fun hapusKotak() {
        val p = prj ?: return
        val page = p.pages.getOrNull(idx) ?: return
        val nomor = terpilih
        if (nomor <= 0 || nomor > page.boxes.size) {
            Toast.makeText(this, R.string.editor_box_pick_first, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.editor_box_del_confirm, nomor))
            .setPositiveButton(R.string.editor_box_del) { _, _ ->
                if (BoxEdit.hapus(page, nomor)) {
                    runCatching { p.save(this) }
                    terpilih = 0
                    b.btnDelBox.isEnabled = false
                    tampilkan()
                }
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Hapus watermark
    // ------------------------------------------------------------------

    /**
     * Menu utama penghapus watermark.
     *
     * Mesin yang aktif ditampilkan di judul, bukan disembunyikan: hasil LaMa
     * dan tambal lokal berbeda kualitas, dan pengguna berhak tahu mana yang
     * sedang dipakai sebelum menimpa gambarnya.
     */
    private fun dialogWatermark() {
        if (sibuk) return
        val mesin = HapusWatermark.mesinAktif(this)
        val judul = getString(
            if (mesin == HapusWatermark.Mesin.LAMA) R.string.editor_wm_engine_lama
            else R.string.editor_wm_engine_local
        )

        val pilihan = arrayOf(
            getString(R.string.editor_wm_scan),
            getString(R.string.editor_wm_draw),
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.editor_wm_title)
            .setMessage(judul)
            .setItems(pilihan) { _, which ->
                if (which == 0) pindaiWatermark() else mulaiGambarWatermark()
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    /** Nyalakan mode seret khusus watermark. */
    private fun mulaiGambarWatermark() {
        modeWatermark = true
        // Mode kotak wajib hidup supaya PageView menerima seretan sama sekali.
        b.swEditKotak.isChecked = true
        b.pageView.modeEdit = true
        b.pageView.modeTambah = true
        b.tvHint.setText(R.string.editor_wm_hint_draw)
    }

    /**
     * Cari kandidat watermark pada halaman aktif.
     *
     * Detektor teks OCR dipakai ulang di sini alih-alih menulis pencari baru:
     * watermark adalah teks, dan detektor itu sudah terbukti menemukan teks di
     * luar balon pada ronde sebelumnya. Halaman tetangga ikut dipindai karena
     * pengulangan posisi adalah bukti terkuat bahwa sesuatu itu watermark dan
     * bukan dialog.
     */
    private fun pindaiWatermark() {
        val p = prj ?: return
        val page = p.pages.getOrNull(idx) ?: return
        if (sibuk) return
        sibuk = true
        b.tvHint.setText(R.string.editor_wm_scan)
        Toast.makeText(this, R.string.editor_wm_scanning, Toast.LENGTH_SHORT).show()

        thread {
            val kandidat = runCatching { cariKandidat(p, page) }.getOrDefault(emptyList())
            runOnUiThread {
                sibuk = false
                b.tvHint.setText(if (b.swEditKotak.isChecked) R.string.editor_hint_box else R.string.editor_hint)
                if (kandidat.isEmpty()) {
                    Toast.makeText(this, R.string.editor_wm_none, Toast.LENGTH_LONG).show()
                    mulaiGambarWatermark()
                    return@runOnUiThread
                }
                pilihKandidat(kandidat)
            }
        }
    }

    /** Jalankan detektor teks pada halaman aktif dan tetangganya. */
    private fun cariKandidat(p: Project, page: Project.Page): List<WatermarkMath.Kandidat> {
        TextRegionDetector(this).use { det ->
            val bmp = Storage.decodeBitmap(p.pageFile(this, page), cfg.maxImageSide)
                ?: return emptyList()
            val regions = det.detect(bmp)
            val w = bmp.width
            val h = bmp.height
            bmp.recycle()
            if (regions.isEmpty()) return emptyList()

            // Halaman tetangga hanya dipakai untuk bukti pengulangan, dan
            // dibatasi beberapa halaman saja: memindai seluruh bab bisa
            // memakan menit-an tanpa menambah keyakinan yang berarti.
            val lain = ArrayList<List<IntArray>>()
            for (d in listOf(-2, -1, 1, 2)) {
                if (lain.size >= TETANGGA_PINDAI) break
                val hal = p.pages.getOrNull(idx + d) ?: continue
                val b2 = Storage.decodeBitmap(p.pageFile(this, hal), cfg.maxImageSide) ?: continue
                // Hanya halaman berukuran sama yang bisa dibandingkan posisinya.
                if (b2.width == w && b2.height == h) {
                    lain.add(runCatching { det.detect(b2) }.getOrDefault(emptyList()))
                }
                b2.recycle()
            }

            return WatermarkMath.cari(regions, w, h, lain, page.boxes.toList())
        }
    }

    /** Daftar centang kandidat; tidak ada yang dihapus tanpa dipilih. */
    private fun pilihKandidat(kandidat: List<WatermarkMath.Kandidat>) {
        val label = kandidat.map { k ->
            val alasan = when (k.alasan) {
                "ulang" -> getString(R.string.editor_wm_reason_ulang, k.berulang + 1)
                "tepi" -> getString(R.string.editor_wm_reason_tepi)
                else -> getString(R.string.editor_wm_reason_lain)
            }
            getString(R.string.editor_wm_item, alasan, (k.skor * 100).toInt())
        }.toTypedArray()

        // Kandidat terkuat dicentang lebih dulu; sisanya keputusan pengguna.
        val dicentang = BooleanArray(kandidat.size) { it == 0 }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editor_wm_found, kandidat.size))
            .setMultiChoiceItems(label, dicentang) { _, which, isChecked ->
                dicentang[which] = isChecked
            }
            .setPositiveButton(R.string.editor_wm_apply) { _, _ ->
                val pilih = kandidat.filterIndexed { i, _ -> dicentang[i] }.map { it.kotak }
                if (pilih.isNotEmpty()) terapkanWatermark(pilih)
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    /** Kotak watermark hasil seretan manual. */
    private fun tambahWatermark(kotak: IntArray) {
        modeWatermark = false
        b.pageView.modeTambah = false
        b.tvHint.setText(R.string.editor_hint_box)
        terapkanWatermark(listOf(kotak))
    }

    /**
     * Simpan kotak watermark ke proyek lalu gambar ulang.
     *
     * Penghapusan sesungguhnya terjadi di [Pipeline.renderProjectPage], bukan
     * di sini: halaman selalu digambar dari salinan bersih, jadi menyimpan
     * kotaknya sudah cukup dan hasilnya otomatis ikut ke ekspor.
     */
    private fun terapkanWatermark(kotak: List<IntArray>) {
        val p = prj ?: return
        val page = p.pages.getOrNull(idx) ?: return

        val mulai = page.watermarks.size
        page.watermarks.addAll(kotak)
        runCatching { p.save(this) }

        catatUrung {
            // Buang persis kotak yang barusan ditambahkan.
            while (page.watermarks.size > mulai) page.watermarks.removeAt(page.watermarks.size - 1)
            runCatching { p.save(this) }
        }

        val mesin = getString(
            if (HapusWatermark.mesinAktif(this) == HapusWatermark.Mesin.LAMA)
                R.string.editor_wm_engine_short_lama
            else R.string.editor_wm_engine_short_local
        )
        Toast.makeText(
            this, getString(R.string.editor_wm_done, kotak.size, mesin), Toast.LENGTH_SHORT
        ).show()
        tampilkan()
    }

    // ------------------------------------------------------------------
    // Urungkan
    // ------------------------------------------------------------------

    /** Daftarkan satu langkah yang bisa dibatalkan. */
    private fun catatUrung(aksi: () -> Unit) {
        urungan.addLast(aksi)
        while (urungan.size > MAKS_URUNG) urungan.removeFirst()
        b.btnUndo.isEnabled = true
    }

    private fun urungkan() {
        if (sibuk) return
        val aksi = urungan.removeLastOrNull()
        if (aksi == null) {
            Toast.makeText(this, R.string.editor_undo_none, Toast.LENGTH_SHORT).show()
            b.btnUndo.isEnabled = false
            return
        }
        aksi()
        b.btnUndo.isEnabled = urungan.isNotEmpty()
        Toast.makeText(this, R.string.editor_undo_done, Toast.LENGTH_SHORT).show()
        tampilkan()
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
                val sebelum = page.translations[kunci]
                if (teks == (sebelum ?: "")) return@setPositiveButton
                if (teks.isEmpty()) page.translations.remove(kunci)
                else page.translations[kunci] = teks
                catatUrung {
                    if (sebelum == null) page.translations.remove(kunci)
                    else page.translations[kunci] = sebelum
                    runCatching { p.save(this) }
                }
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
    /** Tanya format sebelum mengekspor: berkas PNG lepas atau satu arsip CBZ. */
    private fun pilihFormatEkspor() {
        if (sibuk) return
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_export)
            .setItems(
                arrayOf(getString(R.string.editor_export_png), getString(R.string.editor_export_cbz))
            ) { _, which -> if (which == 0) ekspor() else eksporCbz() }
            .show()
    }

    /**
     * Ekspor seluruh bab sebagai satu berkas CBZ.
     *
     * Halaman dikodekan satu per satu lalu langsung ditulis ke arsip, dan
     * bitmapnya didaur ulang setelah dipakai: menahan 40 halaman strip di
     * memori sekaligus adalah cara paling pasti membuat telepon kelas menengah
     * kehabisan memori di tengah ekspor.
     */
    private fun eksporCbz() {
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
            val sub = Langs.code(p.targetLanguage).uppercase()
            val halaman = ArrayList<CbzWriter.Halaman>()
            var gagal = 0
            for ((i, page) in p.pages.withIndex()) {
                val bmp = runCatching { pipeline?.renderProjectPage(p, page) }.getOrNull()
                if (bmp == null) { gagal++; continue }
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                if (!bmp.isRecycled) bmp.recycle()
                halaman.add(
                    CbzWriter.Halaman(
                        CbzWriter.namaEntri(i, Storage.baseName(page.srcName) + ".png"),
                        bos.toByteArray()
                    )
                )
                val n = i + 1
                runOnUiThread { b.tvHint.text = getString(R.string.editor_export_progress, n, p.pages.size) }
            }

            val nama = CbzWriter.namaArsip(p.name, sub)
            val hasil = runCatching {
                Storage.writeToTree(this, tree, sub, nama, CbzWriter.MIME) { os ->
                    CbzWriter.tulis(os, halaman)
                }
            }.getOrNull()

            runOnUiThread {
                sibuk = false
                b.btnExport.isEnabled = true
                b.tvHint.setText(R.string.editor_hint)
                val pesan = if (hasil == null) getString(R.string.editor_export_cbz_fail)
                else getString(R.string.editor_export_cbz_done, nama, halaman.size, gagal)
                Toast.makeText(this, pesan, Toast.LENGTH_LONG).show()
                if (hasil != null) setResult(Activity.RESULT_OK, Intent().putExtra("exported", halaman.size))
            }
        }
    }

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
