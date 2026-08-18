package com.cypy.manga

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Berkas proyek: semua yang dibutuhkan untuk MENGGAMBAR ULANG sebuah bab
 * tanpa mendeteksi dan menerjemahkan lagi.
 *
 * Alasannya keras: satu bab 40 halaman butuh 40 kali inferensi detektor plus
 * beberapa lusin panggilan LLM berbayar. Sebelum ini, satu kata yang salah
 * ketik berarti mengulang semuanya dari nol — biaya dan waktu penuh untuk
 * memperbaiki satu balon. Yang mahal itu deteksi dan terjemahan; menggambar
 * ulang satu halaman hanya perlu piksel dan Canvas.
 *
 * Karena itu proyek menyimpan tiga hal yang tidak bisa dihitung ulang dengan
 * murah: kotak hasil deteksi, teks terjemahan, dan warna terukur. Halaman
 * sumbernya ikut disalin ke dalam folder proyek sebab Uri pilihan pengguna
 * (SAF) bisa kedaluwarsa begitu aplikasi ditutup, dan cache kerja Pipeline
 * memang sengaja dihapus di akhir setiap proses.
 *
 * Tata letak di disk:
 *
 *     filesDir/projects/<id>/
 *         project.json      metadata + kotak + terjemahan + warna
 *         pages/p0000.png   salinan halaman sumber (sebelum digambari)
 */
class Project(
    val id: String,
    var name: String,
    var targetLanguage: String,
    val createdAt: Long,
    var updatedAt: Long,
    val pages: MutableList<Page> = mutableListOf()
) {

    /**
     * Satu halaman keluaran. Halaman lebar yang terbelah otomatis menjadi dua
     * bagian disimpan sebagai dua Page berurutan dengan [srcName] sama, persis
     * seperti PartEntry di Pipeline.
     */
    class Page(
        /** Nama berkas sumber, dipakai untuk penamaan keluaran. */
        val srcName: String,
        /** Salinan halaman BERSIH (belum digambari), relatif ke folder proyek. */
        val imagePath: String,
        val width: Int,
        val height: Int,
        val boxes: MutableList<IntArray> = mutableListOf(),
        /** Kunci = nomor balon berbasis 1, sama seperti ID mosaik. */
        val translations: MutableMap<String, String> = mutableMapOf(),
        /** Warna terukur per kotak, kunci = "x1,y1,x2,y2". */
        val colors: MutableMap<String, Palette.Colors> = mutableMapOf(),
        /** Kunci kotak yang berasal dari teks di LUAR balon. */
        val freeText: MutableSet<String> = mutableSetOf()
    ) {
        /** Teks asli hasil OCR/LLM bila ada — ditampilkan sebagai rujukan editor. */
        val sourceText: MutableMap<String, String> = mutableMapOf()
    }

    fun dir(ctx: Context): File = dirFor(ctx, id)

    fun pageFile(ctx: Context, page: Page): File = File(dir(ctx), page.imagePath)

    // ------------------------------------------------------------------
    // Serialisasi
    // ------------------------------------------------------------------

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("id", id)
        o.put("name", name)
        o.put("targetLanguage", targetLanguage)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        val arr = JSONArray()
        for (p in pages) {
            val po = JSONObject()
            po.put("srcName", p.srcName)
            po.put("imagePath", p.imagePath)
            po.put("width", p.width)
            po.put("height", p.height)
            val ba = JSONArray()
            for (b in p.boxes) {
                ba.put(JSONArray().put(b[0]).put(b[1]).put(b[2]).put(b[3]))
            }
            po.put("boxes", ba)
            po.put("translations", JSONObject(p.translations as Map<*, *>))
            po.put("sourceText", JSONObject(p.sourceText as Map<*, *>))
            val co = JSONObject()
            for ((k, c) in p.colors) co.put(k, colorsToJson(c))
            po.put("colors", co)
            po.put("freeText", JSONArray(p.freeText.toList()))
            arr.put(po)
        }
        o.put("pages", arr)
        return o
    }

    fun save(ctx: Context) {
        updatedAt = System.currentTimeMillis()
        val d = dir(ctx).apply { mkdirs() }
        // Tulis ke berkas sementara lalu ganti nama: proyek yang separuh
        // tertulis karena aplikasi dimatikan akan hilang seluruhnya, dan itu
        // jauh lebih buruk daripada kehilangan satu suntingan terakhir.
        val tmp = File(d, "$FILE.tmp")
        tmp.writeText(toJson().toString())
        val dst = File(d, FILE)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }

    companion object {
        const val VERSION = 1
        private const val FILE = "project.json"

        fun rootDir(ctx: Context): File = File(ctx.filesDir, "projects")

        fun dirFor(ctx: Context, id: String): File = File(rootDir(ctx), id)

        fun colorsToJson(c: Palette.Colors): JSONObject {
            val j = JSONObject()
            j.put("bg", c.background)
            j.put("fg", c.foreground)
            j.put("diukur", c.diukur)
            if (c.warnaBaris.isNotEmpty()) j.put("baris", JSONArray(c.warnaBaris))
            // Ronde 22: warna garis luar sempat hilang di sini. Akibatnya
            // halaman yang digambar ulang dari proyek kehilangan warna outline
            // yang sudah diukur mahal-mahal saat deteksi, dan hasil editor
            // berbeda dari hasil jalur utama untuk halaman yang sama.
            c.garisLuar?.let { j.put("garisLuar", it) }
            return j
        }

        fun colorsFromJson(j: JSONObject): Palette.Colors {
            val baris = j.optJSONArray("baris")
            val list = if (baris == null) emptyList()
            else (0 until baris.length()).map { baris.getInt(it) }
            return Palette.Colors(
                j.getInt("bg"), j.getInt("fg"), j.optBoolean("diukur", true), list,
                if (j.has("garisLuar")) j.getInt("garisLuar") else null
            )
        }

        fun fromJson(o: JSONObject): Project {
            val p = Project(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                targetLanguage = o.optString("targetLanguage", "indonesian"),
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L)
            )
            val arr = o.optJSONArray("pages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val po = arr.getJSONObject(i)
                val page = Page(
                    srcName = po.getString("srcName"),
                    imagePath = po.getString("imagePath"),
                    width = po.getInt("width"),
                    height = po.getInt("height")
                )
                val ba = po.optJSONArray("boxes") ?: JSONArray()
                for (k in 0 until ba.length()) {
                    val b = ba.getJSONArray(k)
                    page.boxes.add(
                        intArrayOf(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
                    )
                }
                po.optJSONObject("translations")?.let { t ->
                    for (k in t.keys()) page.translations[k] = t.getString(k)
                }
                po.optJSONObject("sourceText")?.let { t ->
                    for (k in t.keys()) page.sourceText[k] = t.getString(k)
                }
                po.optJSONObject("colors")?.let { c ->
                    for (k in c.keys()) {
                        runCatching { page.colors[k] = colorsFromJson(c.getJSONObject(k)) }
                    }
                }
                po.optJSONArray("freeText")?.let { f ->
                    for (k in 0 until f.length()) page.freeText.add(f.getString(k))
                }
                p.pages.add(page)
            }
            return p
        }

        fun load(ctx: Context, id: String): Project? {
            val f = File(dirFor(ctx, id), FILE)
            if (!f.exists()) return null
            return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
        }

        /** Semua proyek, terbaru dulu. */
        fun list(ctx: Context): List<Project> {
            val root = rootDir(ctx)
            if (!root.isDirectory) return emptyList()
            return (root.listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .mapNotNull { load(ctx, it.name) }
                .sortedByDescending { it.updatedAt }
        }

        fun delete(ctx: Context, id: String) {
            dirFor(ctx, id).deleteRecursively()
        }

        /**
         * Buang proyek terlama sampai tersisa [maks] buah.
         *
         * Proyek menyimpan salinan halaman penuh, jadi satu bab webtoon bisa
         * puluhan MB. Tanpa batas, folder ini akan tumbuh diam-diam sampai
         * memenuhi penyimpanan telepon.
         */
        fun prune(ctx: Context, maks: Int = MAX_PROJECTS) {
            val semua = list(ctx)
            if (semua.size <= maks) return
            for (p in semua.drop(maks)) delete(ctx, p.id)
        }

        const val MAX_PROJECTS = 10

        fun newId(): String = "prj_" + System.currentTimeMillis()
    }
}
