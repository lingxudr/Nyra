package com.cypy.manga

/**
 * Riwayat terjemahan halaman sebelumnya, untuk dikirim sebagai konteks.
 *
 * Tujuannya konsistensi lintas halaman: nama tokoh, sapaan, dan gaya bicara
 * tidak berubah-ubah di tengah bab. Idenya mengikuti "translation history"
 * BallonsTranslator, termasuk anggaran token bawaannya (4096).
 *
 * Yang disimpan hanya hasil TERJEMAHAN, karena pipeline kita mengirim mosaik
 * gambar tanpa OCR - tidak ada teks sumber yang bisa disimpan.
 */
class PageContext(
    private val budgetTokens: Int = DEFAULT_BUDGET_TOKENS
) {
    data class Page(val name: String, val lines: List<String>)

    private val pages = ArrayDeque<Page>()

    val size: Int get() = pages.size

    /** Catat satu halaman selesai. Baris kosong dan penanda SKIP dibuang. */
    fun add(name: String, lines: List<String>) {
        val bersih = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(SKIP, ignoreCase = true) }
            .map { if (it.length > MAX_LINE_LEN) it.take(MAX_LINE_LEN) + "…" else it }
        if (bersih.isEmpty()) return

        pages.addLast(Page(name, bersih))
        while (pages.size > MAX_PAGES) pages.removeFirst()
        trim()
    }

    fun clear() = pages.clear()

    /**
     * Buang halaman terlama selama total masih di atas anggaran, tetapi selalu
     * sisakan minimal satu halaman: kalau satu halaman saja sudah melebihi
     * anggaran, mengosongkan semuanya akan mematikan fitur ini diam-diam.
     */
    private fun trim() {
        while (pages.size > 1 && estimateTokens(render()) > budgetTokens) {
            pages.removeFirst()
        }
    }

    private fun render(): String = buildString {
        for (p in pages) {
            append(p.name).append('\n')
            for (l in p.lines) append("- ").append(l).append('\n')
        }
    }

    /** Blok prompt; string kosong kalau belum ada riwayat. */
    fun promptSection(): String {
        if (pages.isEmpty()) return ""
        return buildString {
            append("\nPREVIOUS PAGES CONTEXT:\n")
            append("These are your own translations from earlier pages of this same chapter. ")
            append("Use them as background only, to keep character names, honorifics, pronouns ")
            append("and speech style consistent. They are NOT part of the current image: ")
            append("do NOT translate, copy, or return any of these lines in your answer.\n")
            append(render())
        }
    }

    companion object {
        const val MAX_PAGES = 12
        const val MAX_LINE_LEN = 160
        const val DEFAULT_BUDGET_TOKENS = 4096
        private const val SKIP = "SKIP"

        /**
         * Perkiraan kasar jumlah token. Karakter non-ASCII (CJK) dihitung tiga
         * kali lebih berat karena tokenizer memecahnya jauh lebih halus
         * daripada teks Latin.
         */
        fun estimateTokens(s: String): Int {
            if (s.isEmpty()) return 0
            var berat = 0
            for (c in s) berat += if (c.code > 127) 3 else 1
            return berat / 3
        }
    }
}
