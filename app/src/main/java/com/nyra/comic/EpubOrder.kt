package com.nyra.comic

/**
 * Urutan baca berkas EPUB.
 *
 * EPUB memang sebuah ZIP, jadi menggilas isinya lewat extractZip sudah
 * menghasilkan gambar. Masalahnya URUTAN: penamaan berkas di dalam EPUB
 * kerap acak (`img_0012.jpg` bisa jadi halaman pertama, aset `cover.jpg`
 * sering tidak dipakai sama sekali), sehingga pengurutan alfabetis/numerik
 * yang dipakai untuk CBZ menghasilkan bab yang teracak.
 *
 * Urutan yang benar tertulis di dalam berkasnya sendiri:
 *
 *   META-INF/container.xml  -> menunjuk berkas OPF
 *   OPF <manifest>          -> id -> href tiap sumber daya
 *   OPF <spine>             -> urutan baca, sebagai deretan idref
 *
 * Isi spine biasanya halaman XHTML yang masing-masing memuat satu gambar,
 * jadi gambarnya harus ditarik dari XHTML itu, menjaga urutan spine.
 *
 * Semua fungsi di sini murni string supaya bisa diuji tanpa perangkat.
 * Penguraiannya sengaja berbasis regex, bukan XML parser: berkas EPUB di
 * dunia nyata sering cacat ringan, dan parser ketat akan menolaknya bulat
 * bulat padahal informasinya masih terbaca.
 */
object EpubOrder {

    val EPUB_EXTS = setOf("epub")

    private val RE_ROOTFILE = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val RE_ITEM = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val RE_ITEMREF = Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val RE_ID = Regex("""\bid\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val RE_IDREF = Regex("""\bidref\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val RE_HREF = Regex("""\bhref\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val RE_MEDIA = Regex("""\bmedia-type\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val RE_PROPS = Regex("""\bproperties\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val RE_SRC = Regex(
        """<(?:img|image)\b[^>]*?\b(?:src|xlink:href|href)\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    /** Letak berkas OPF menurut container.xml, atau null bila tidak terbaca. */
    fun opfPath(containerXml: String): String? {
        val m = RE_ROOTFILE.find(containerXml) ?: return null
        return normalkan(m.groupValues[1])
    }

    /**
     * Gabungkan href relatif terhadap folder tempat [dasar] berada, lalu
     * rapikan `.` dan `..`.
     *
     * Ini sumber bug paling lazim saat membaca EPUB: href di dalam OPF relatif
     * terhadap OPF (`OEBPS/content.opf` + `images/p1.jpg` =
     * `OEBPS/images/p1.jpg`), sedangkan href di dalam XHTML relatif terhadap
     * XHTML itu, yang sering berada satu folder lebih dalam lagi.
     */
    fun resolve(dasar: String, href: String): String {
        val bersih = href.substringBefore('#').substringBefore('?')
        val h = urlDecode(bersih.replace('\\', '/'))
        if (h.startsWith("/")) return normalkan(h.removePrefix("/"))
        val dir = dasar.replace('\\', '/').substringBeforeLast('/', "")
        val gabung = if (dir.isEmpty()) h else "$dir/$h"
        return normalkan(gabung)
    }

    private fun normalkan(path: String): String {
        val out = ArrayList<String>()
        for (bagian in path.replace('\\', '/').split('/')) {
            when (bagian) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(bagian)
            }
        }
        return out.joinToString("/")
    }

    /** `%20` dan kawan-kawan: nama berkas di EPUB sering mengandung spasi. */
    private fun urlDecode(s: String): String {
        if (!s.contains('%')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) { sb.append(v.toChar()); i += 3; continue }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private class Item(val href: String, val media: String, val props: String)

    /**
     * Daftar jalur gambar dalam urutan baca.
     *
     * [opfPath] jalur OPF di dalam arsip, [opfXml] isinya, dan [bacaTeks]
     * pembaca isi berkas lain di arsip (dipakai untuk XHTML). Bila spine tidak
     * terbaca sama sekali, hasilnya daftar kosong dan pemanggil harus kembali
     * ke pengurutan nama seperti CBZ.
     */
    fun gambarUrut(
        opfPath: String, opfXml: String, bacaTeks: (String) -> String?
    ): List<String> {
        val manifest = HashMap<String, Item>()
        for (m in RE_ITEM.findAll(opfXml)) {
            val tag = m.value
            val id = RE_ID.find(tag)?.groupValues?.get(1) ?: continue
            val href = RE_HREF.find(tag)?.groupValues?.get(1) ?: continue
            manifest[id] = Item(
                resolve(opfPath, href),
                RE_MEDIA.find(tag)?.groupValues?.get(1)?.lowercase() ?: "",
                RE_PROPS.find(tag)?.groupValues?.get(1)?.lowercase() ?: ""
            )
        }
        if (manifest.isEmpty()) return emptyList()

        val hasil = ArrayList<String>()
        val sudah = HashSet<String>()

        fun tambah(path: String) {
            if (Storage.ext(path) !in Storage.IMAGE_EXTS) return
            if (sudah.add(path)) hasil.add(path)
        }

        // Sampul kerap tidak masuk spine, padahal ia halaman pertama.
        manifest.values
            .firstOrNull { it.props.contains("cover-image") }
            ?.let { tambah(it.href) }

        val spine = RE_ITEMREF.findAll(opfXml)
            .mapNotNull { RE_IDREF.find(it.value)?.groupValues?.get(1) }
            .toList()
        if (spine.isEmpty()) return emptyList()

        for (idref in spine) {
            val item = manifest[idref] ?: continue
            if (item.media.startsWith("image/") || Storage.ext(item.href) in Storage.IMAGE_EXTS) {
                // Sebagian EPUB manga menaruh gambarnya langsung di spine.
                tambah(item.href)
                continue
            }
            val isi = bacaTeks(item.href) ?: continue
            for (m in RE_SRC.findAll(isi)) {
                tambah(resolve(item.href, m.groupValues[1]))
            }
        }
        return hasil
    }
}
