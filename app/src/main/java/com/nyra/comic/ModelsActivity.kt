package com.nyra.comic

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.nyra.comic.databinding.ActivityModelsBinding
import kotlin.concurrent.thread

/**
 * Manajemen model dan berkas besar.
 *
 * Sebelum layar ini, unduhan LaMa dan paket font terselip di tengah panel
 * setelan bersama tiga puluh sakelar lain, dan satu-satunya keterangan yang
 * didapat pengguna adalah "siap" atau "belum". Tidak ada cara melihat berapa
 * penyimpanan yang sebenarnya terpakai, tidak ada cara membuktikan berkas
 * yang sudah terpasang masih utuh, dan sisa unduhan .part yang gagal
 * memakan puluhan MB tanpa pernah muncul di mana pun.
 *
 * Yang penting di sini adalah verifikasi. Gerbang SHA-256 di ModelDownloader
 * hanya berlaku sekali, saat berkas baru diunduh; setelah mendarat, isinya
 * tidak pernah diperiksa lagi walau langsung dijalankan sebagai graf ONNX.
 * Berkas yang rusak tanpa berubah ukuran — flash aus, salinan cadangan yang
 * separuh, berkas yang ditukar — lolos begitu saja. Tombol Verifikasi di
 * sini membaca ulang berkasnya dan membandingkan hash yang sebenarnya.
 */
class ModelsActivity : AppCompatActivity() {

    private lateinit var b: ActivityModelsBinding

    @Volatile private var unduhBerjalan: String? = null
    @Volatile private var dibatalkan = false

    /** Hash yang sedang dihitung; menahan tombol supaya tidak dobel. */
    @Volatile private var verifikasiBerjalan = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityModelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        render()
    }

    // ------------------------------------------------------------------
    // Penyusunan daftar
    // ------------------------------------------------------------------

    private fun laporanLama(): ModelManager.Laporan = ModelManager.periksa(
        "lama", getString(R.string.mdl_lama), Inpainter.berkas(this), Inpainter.UKURAN
    )

    private fun laporanFont(item: FontPack.Item): ModelManager.Laporan = ModelManager.periksa(
        item.id, item.judul, FontPack.berkas(this, item), item.ukuran
    )

    private fun semuaOpsional(): List<ModelManager.Laporan> =
        listOf(laporanLama()) + FontPack.SEMUA.map { laporanFont(it) }

    private fun render() {
        val opsional = semuaOpsional()
        val terpakai = ModelManager.totalByte(opsional)

        // Aset bawaan APK tidak bisa hilang atau rusak sendiri — ia bagian
        // dari berkas pemasangan yang sudah ditandatangani. Jadi ia hanya
        // didaftar, bukan diberi tombol unduh yang menyesatkan.
        val bundled = listOf(
            Triple("rtdetr.onnx", getString(R.string.mdl_rtdetr), 11_120_765L),
            Triple("ocr_det.onnx", getString(R.string.mdl_ocr), 4_826_518L),
            Triple("eyecypy.onnx", getString(R.string.mdl_yolo), 12_265_203L),
            Triple("komika.ttf", getString(R.string.mdl_komika), 53_996L),
            Triple("kosugi.ttf", getString(R.string.mdl_kosugi), 3_565_692L)
        )
        val totalBundled = bundled.sumOf { it.third }

        b.tvSummary.text = getString(
            R.string.mdl_summary,
            Library.ukuranRingkas(terpakai),
            Library.ukuranRingkas(totalBundled)
        )

        b.boxBundled.removeAllViews()
        for ((berkas, judul, ukuran) in bundled) {
            b.boxBundled.addView(barisBundled(berkas, judul, ukuran))
        }

        b.boxOptional.removeAllViews()
        for (l in opsional) b.boxOptional.addView(barisOpsional(l))
    }

    private fun inflateBaris(): View =
        layoutInflater.inflate(R.layout.item_model, b.boxOptional, false)

    private fun barisBundled(berkas: String, judul: String, ukuran: Long): View {
        val v = inflateBaris()
        v.findViewById<TextView>(R.id.tvName).text = judul
        v.findViewById<TextView>(R.id.tvDesc).text = getString(R.string.mdl_bundled_in_apk)
        v.findViewById<TextView>(R.id.tvDetail).text =
            "$berkas · ${Library.ukuranRingkas(ukuran)}"
        val badge = v.findViewById<TextView>(R.id.tvBadge)
        badge.setText(R.string.mdl_badge_bundled)
        badge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        v.findViewById<LinearLayout>(R.id.boxActions).visibility = View.GONE
        return v
    }

    private fun barisOpsional(l: ModelManager.Laporan): View {
        val v = inflateBaris()
        v.findViewById<TextView>(R.id.tvName).text = l.judul

        val desc = v.findViewById<TextView>(R.id.tvDesc)
        desc.text = when (l.id) {
            "lama" -> getString(R.string.mdl_lama_desc)
            else -> getString(R.string.mdl_font_desc)
        }

        val badge = v.findViewById<TextView>(R.id.tvBadge)
        val (teks, warna) = when (l.status) {
            ModelManager.Status.BELUM_ADA ->
                R.string.mdl_st_absent to R.color.text_muted
            ModelManager.Status.SEPARUH ->
                R.string.mdl_st_partial to R.color.orange
            ModelManager.Status.TERPASANG ->
                R.string.mdl_st_installed to R.color.green
            ModelManager.Status.TERVERIFIKASI ->
                R.string.mdl_st_verified to R.color.green
            ModelManager.Status.RUSAK ->
                R.string.mdl_st_broken to R.color.red
        }
        badge.setText(teks)
        badge.setTextColor(ContextCompat.getColor(this, warna))

        val detail = StringBuilder()
        detail.append(Library.ukuranRingkas(l.byteTerpakai))
        detail.append(" / ").append(Library.ukuranRingkas(l.byteDiharapkan))
        if (l.hashTerhitung != null) {
            detail.append("\nsha256 ").append(ModelManager.hashPendek(l.hashTerhitung))
        }
        if (l.keterangan.isNotBlank()) detail.append("\n").append(l.keterangan)
        v.findViewById<TextView>(R.id.tvDetail).text = detail

        val aksi = v.findViewById<LinearLayout>(R.id.boxActions)
        aksi.removeAllViews()

        if (unduhBerjalan == l.id) {
            aksi.addView(tombol(getString(R.string.btn_batal_unduh)) { dibatalkan = true })
        } else if (unduhBerjalan != null) {
            // Satu unduhan pada satu waktu: dua unduhan besar bersamaan di
            // jaringan seluler membuat keduanya lambat dan gampang putus.
            aksi.addView(tombol(getString(R.string.mdl_wait)) {}.apply { isEnabled = false })
        } else {
            if (l.perluUnduh) {
                val label = if (l.status == ModelManager.Status.SEPARUH)
                    getString(R.string.mdl_resume) else getString(R.string.font_unduh)
                aksi.addView(tombol(label) { unduh(l) })
            } else {
                aksi.addView(tombol(getString(R.string.mdl_verify)) { verifikasi(l) })
                aksi.addView(tombol(getString(R.string.font_hapus)) { hapus(l) })
            }
            if (l.status == ModelManager.Status.SEPARUH || l.status == ModelManager.Status.RUSAK) {
                aksi.addView(tombol(getString(R.string.mdl_clean)) { hapus(l) })
            }
        }
        return v
    }

    private fun tombol(teks: String, aksi: () -> Unit): MaterialButton =
        MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = teks
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16 }
            gravity = Gravity.CENTER
            setOnClickListener { aksi() }
        }

    // ------------------------------------------------------------------
    // Tindakan
    // ------------------------------------------------------------------

    private fun unduh(l: ModelManager.Laporan) {
        if (unduhBerjalan != null) return
        unduhBerjalan = l.id
        dibatalkan = false
        render()

        thread {
            val progres: (Long, Long) -> Unit = { sudah, total ->
                runOnUiThread {
                    b.tvSummary.text = getString(
                        R.string.mdl_downloading, l.judul,
                        ModelManager.persen(sudah, total),
                        Library.ukuranRingkas(sudah), Library.ukuranRingkas(total)
                    )
                }
            }
            val hasil = if (l.id == "lama") {
                ModelDownloader.unduhLama(applicationContext, progres) { dibatalkan }
            } else {
                val item = FontPack.SEMUA.first { it.id == l.id }
                ModelDownloader.unduhFont(applicationContext, item, progres) { dibatalkan }
            }
            runOnUiThread {
                unduhBerjalan = null
                when (hasil) {
                    is ModelDownloader.Hasil.Sukses -> {
                        // Font baru harus membatalkan cache Typeface, kalau
                        // tidak halaman berikutnya masih memakai font lama.
                        if (l.id != "lama") TextRenderer.invalidateFontCache()
                        Toast.makeText(this, R.string.model_siap, Toast.LENGTH_SHORT).show()
                    }
                    is ModelDownloader.Hasil.Dibatalkan ->
                        Toast.makeText(this, R.string.model_dibatalkan, Toast.LENGTH_SHORT).show()
                    is ModelDownloader.Hasil.Gagal ->
                        Toast.makeText(
                            this, getString(R.string.model_gagal, hasil.pesan), Toast.LENGTH_LONG
                        ).show()
                }
                render()
            }
        }
    }

    /**
     * Hitung ulang SHA-256 berkas yang sudah terpasang.
     *
     * Membaca 93 MB perlu waktu, jadi ini selalu berjalan di utas lain dan
     * tidak pernah dipanggil otomatis saat layar dibuka.
     */
    private fun verifikasi(l: ModelManager.Laporan) {
        if (verifikasiBerjalan) return
        verifikasiBerjalan = true
        b.tvSummary.text = getString(R.string.mdl_verifying, l.judul)

        thread {
            val hasil = if (l.id == "lama") {
                ModelManager.verifikasi(
                    l.id, l.judul, Inpainter.berkas(this),
                    Inpainter.UKURAN, Inpainter.SHA256
                )
            } else {
                val item = FontPack.SEMUA.first { it.id == l.id }
                ModelManager.verifikasi(
                    item.id, item.judul, FontPack.berkas(this, item),
                    item.ukuran, item.sha256
                )
            }
            runOnUiThread {
                verifikasiBerjalan = false
                render()
                if (hasil.status == ModelManager.Status.TERVERIFIKASI) {
                    Toast.makeText(
                        this, getString(R.string.mdl_verify_ok, l.judul), Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Berkas rusak tidak dihapus diam-diam: pengguna yang
                    // memutuskan, sebab mengunduh ulang 93 MB bisa mahal.
                    AlertDialog.Builder(this)
                        .setTitle(R.string.mdl_verify_fail_title)
                        .setMessage(
                            getString(
                                R.string.mdl_verify_fail, l.judul,
                                ModelManager.hashPendek(hasil.hashTerhitung), hasil.keterangan
                            )
                        )
                        .setPositiveButton(R.string.mdl_clean) { _, _ -> hapus(l) }
                        .setNegativeButton(R.string.editor_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun hapus(l: ModelManager.Laporan) {
        thread {
            val bebas = if (l.id == "lama") {
                ModelDownloader.hapus(applicationContext)
            } else {
                val item = FontPack.SEMUA.first { it.id == l.id }
                val n = FontPack.hapus(applicationContext, item)
                TextRenderer.invalidateFontCache()
                n
            }
            runOnUiThread {
                Toast.makeText(
                    this, getString(R.string.model_dihapus, Library.ukuranRingkas(bebas)),
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }
    }
}
