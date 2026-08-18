package com.nyra.comic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.nyra.comic.databinding.ActivityResultBinding
import java.io.File

class ResultActivity : AppCompatActivity() {

    private lateinit var b: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)

        val result = TranslationService.lastResult
        val items = result?.outputs ?: emptyList()

        b.tvSummary.text = if (result == null) "No results yet."
        else "Success: ${result.success}  •  Failed: ${result.failed}  •  Total: ${result.total}"

        b.list.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: LayoutInflater.from(this@ResultActivity)
                    .inflate(R.layout.item_result, parent, false)
                val item = items[p]
                v.findViewById<TextView>(R.id.tvName).text = item.name
                v.findViewById<TextView>(R.id.tvPath).text =
                    item.uri?.toString() ?: item.localPath ?: ""
                return v
            }
        }

        b.list.setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
        b.btnBack.setOnClickListener { finish() }

        // Tombol perbaiki hanya muncul kalau memang ada proyek untuk disunting.
        //
        // Proyek TERBARU dipakai karena itulah hasil yang barusan dilihat
        // pengguna. Sebelum ronde 24 inilah satu-satunya jalan menuju editor,
        // sehingga bab-bab lama tak pernah bisa dibuka lagi walau masih utuh
        // di disk; sekarang jalan yang lengkap ada di LibraryActivity dan
        // tombol ini tinggal jadi jalan pintas ke bab yang baru selesai.
        val prj = Project.list(this).firstOrNull()
        if (prj != null && prj.pages.isNotEmpty()) {
            b.btnEdit.visibility = View.VISIBLE
            b.btnEdit.setOnClickListener {
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_PROJECT_ID, prj.id)
                )
            }
        }
    }

    private fun open(item: Pipeline.OutputItem) {
        val uri: Uri? = item.uri ?: item.localPath?.let { path ->
            val f = File(path)
            if (f.exists()) FileProvider.getUriForFile(this, "$packageName.fileprovider", f) else null
        }
        if (uri == null) { Toast.makeText(this, "File not available", Toast.LENGTH_SHORT).show(); return }

        val mime = if (item.name.endsWith(".pdf", true)) "application/pdf" else "image/png"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
