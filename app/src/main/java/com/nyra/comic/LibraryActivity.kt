package com.nyra.comic

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nyra.comic.databinding.ActivityLibraryBinding
import kotlin.concurrent.thread

/**
 * Perpustakaan proyek: pintu masuk ke semua bab yang pernah diterjemahkan.
 *
 * Sebelum layar ini ada, proyek memang tersimpan tetapi hanya yang TERBARU
 * bisa dibuka kembali, lewat satu tombol di layar Hasil. Bab-bab sebelumnya
 * tetap memakan penyimpanan sambil membawa hasil deteksi dan terjemahan LLM
 * yang sudah dibayar, lalu dihapus diam-diam oleh `Project.prune()` tanpa
 * pernah bisa dilihat lagi. Di sini semuanya bisa dibuka, dibaca, diganti
 * nama, dan dihapus atas keputusan pengguna sendiri.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var b: ActivityLibraryBinding

    /** Semua entri hasil pemindaian disk. */
    private var semua: List<Library.Entri> = emptyList()

    /** Entri yang benar-benar tampil setelah disaring. */
    private var tampil: List<Library.Entri> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = tampil.size
        override fun getItem(position: Int) = tampil[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_library, parent, false)
            val e = tampil[position]

            v.findViewById<TextView>(R.id.tvName).text = e.nama

            val bahasa = e.bahasa.replaceFirstChar { it.uppercase() }
            val hal = resources.getQuantityString(
                R.plurals.lib_pages, e.jumlahHalaman, e.jumlahHalaman
            )
            v.findViewById<TextView>(R.id.tvMeta).text =
                "$bahasa · $hal · ${Library.ukuranRingkas(e.ukuranByte)}"

            // Bab yang berhenti di tengah harus terlihat jelas: itu bab yang
            // layak dilanjutkan, bukan diulang dari nol.
            val status = v.findViewById<TextView>(R.id.tvStatus)
            val umur = Library.umurRingkas(e.diperbaruiPada, System.currentTimeMillis())
            if (e.lengkap) {
                status.text = getString(R.string.lib_status_done, e.jumlahBalon, umur)
                status.setTextColor(getColor(R.color.text_muted))
            } else {
                val persen = (e.kelengkapan * 100).toInt()
                status.text = getString(
                    R.string.lib_status_partial, e.jumlahTerjemahan, e.jumlahBalon, persen, umur
                )
                status.setTextColor(getColor(R.color.orange))
            }

            v.findViewById<Button>(R.id.btnMore).setOnClickListener { menu(e) }
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ -> buka(tampil[pos]) }
        b.btnBack.setOnClickListener { finish() }

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = saring()
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
    }

    // Dimuat ulang di onResume, bukan sekali di onCreate: pengguna kembali ke
    // sini setelah menyunting atau membaca, dan daftar yang basi akan
    // menampilkan jumlah terjemahan yang salah.
    override fun onResume() {
        super.onResume()
        muat()
    }

    private fun muat() {
        thread {
            val daftar = runCatching {
                Project.list(this).map { p ->
                    Library.ringkas(p, ukuranFolder(p))
                }
            }.getOrDefault(emptyList())
            runOnUiThread {
                semua = Library.urutkan(daftar)
                saring()
            }
        }
    }

    /** Ukuran folder proyek; salinan halaman biasanya mendominasi. */
    private fun ukuranFolder(p: Project): Long {
        val d = Project.dirFor(this, p.id)
        if (!d.isDirectory) return 0L
        var total = 0L
        d.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    private fun saring() {
        tampil = Library.saring(semua, b.etSearch.text?.toString() ?: "")
        adapter.notifyDataSetChanged()
        val kosong = tampil.isEmpty()
        b.tvEmpty.visibility = if (kosong) View.VISIBLE else View.GONE
        b.tvEmpty.setText(
            if (semua.isEmpty()) R.string.lib_empty else R.string.lib_no_match
        )
    }

    private fun buka(e: Library.Entri) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_PROJECT_ID, e.id)
        )
    }

    private fun menu(e: Library.Entri) {
        val pilihan = arrayOf(
            getString(R.string.lib_read),
            getString(R.string.lib_edit),
            getString(R.string.lib_rename),
            getString(R.string.lib_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(e.nama)
            .setItems(pilihan) { _, which ->
                when (which) {
                    0 -> buka(e)
                    1 -> startActivity(
                        Intent(this, EditorActivity::class.java)
                            .putExtra(EditorActivity.EXTRA_PROJECT_ID, e.id)
                    )
                    2 -> gantiNama(e)
                    3 -> konfirmasiHapus(e)
                }
            }
            .show()
    }

    private fun gantiNama(e: Library.Entri) {
        val input = EditText(this).apply {
            setText(e.nama)
            setSelection(e.nama.length)
            setTextColor(getColor(R.color.text_primary))
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lib_rename)
            .setView(input)
            .setPositiveButton(R.string.editor_save) { _, _ ->
                val nama = Library.rapikanNama(input.text?.toString() ?: "", e.nama)
                thread {
                    val p = Project.load(this, e.id)
                    if (p != null) {
                        p.name = nama
                        runCatching { p.save(this) }
                    }
                    runOnUiThread { muat() }
                }
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    private fun konfirmasiHapus(e: Library.Entri) {
        // Konfirmasi memang wajib di sini: yang dibuang bukan sekadar gambar,
        // melainkan deteksi dan terjemahan berbayar yang tidak bisa dipulihkan.
        AlertDialog.Builder(this)
            .setTitle(R.string.lib_delete)
            .setMessage(getString(R.string.lib_delete_msg, e.nama))
            .setPositiveButton(R.string.lib_delete) { _, _ ->
                thread {
                    runCatching { Project.delete(this, e.id) }
                    runOnUiThread {
                        Toast.makeText(this, R.string.lib_deleted, Toast.LENGTH_SHORT).show()
                        muat()
                    }
                }
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }
}
