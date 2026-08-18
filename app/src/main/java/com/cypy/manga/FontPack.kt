package com.cypy.manga

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * Paket font tambahan yang diunduh sendiri oleh pengguna.
 *
 * Ini JARING PENGAMAN, bukan kebutuhan umum. Bacalah bagian ini sebelum
 * memperluas perannya.
 *
 * Cakupan font bawaan memang tipis: kosugi.ttf tidak punya satu pun glif
 * Hangul (0/11172) maupun Thai (0/128), dan hanya 31,8% Han. Ronde 22
 * awalnya menyimpulkan dari angka itu bahwa menerjemahkan ke Korea/Thai
 * menghasilkan kotak kosong. Kesimpulan itu KELIRU dan sempat dikirim sebagai
 * peringatan ke pengguna.
 *
 * Sebabnya: Typeface.createFromAsset membangun typeface lewat Typeface.Builder
 * yang menyetel fallback ke DEFAULT_FAMILY, sehingga karakter yang absen dari
 * font kustom ditambal dari rantai font sistem — dan rantai AOSP memuat
 * NotoSansThai-Regular.ttf serta NotoSansCJK-Regular.ttc. Render sungguhan
 * kalimat Thai dan Korea tanpa paket ini terbukti bersih tanpa satu pun tofu.
 * Cakupan cmap font aplikasi BUKAN penentu munculnya tofu.
 *
 * Yang tersisa nyata: ROM murah/Go edition atau ROM yang dipangkas per-region
 * kadang betul-betul membuang font CJK/Thai. Hanya di situ paket ini menolong,
 * dan hanya perangkat itu sendiri yang bisa menjawabnya — karena itu
 * perluUnduh() bertanya lewat Paint.hasGlyph, bukan menebak dari tabel cmap.
 *
 * Membundel Noto berarti APK naik ~13 MB demi kasus pinggiran, jadi polanya
 * disamakan dengan model inpaint LaMa: diunduh atas permintaan, lalu menetap
 * di penyimpanan internal.
 *
 * Kenapa KR dan SC keduanya, bukan satu font CJK saja: keduanya diukur, dan
 * tidak ada yang menutup semuanya. NotoSansKR menutup Hangul 100% tapi Han
 * hanya 38,8%; NotoSansSC menutup Han 99,9% tapi Hangul 0%. Memilih salah satu
 * berarti membiarkan satu bahasa tetap rusak.
 *
 * Sumber: repositori resmi notofonts (SIL Open Font License 1.1), yang
 * mengizinkan pemakaian dan distribusi ulang tanpa syarat royalti.
 */
object FontPack {

    /** Satu berkas font yang bisa diunduh. */
    data class Item(
        val id: String,
        val namaBerkas: String,
        val alamat: String,
        val ukuran: Long,
        val sha256: String,
        /** Untuk label UI. */
        val judul: String,
    )

    const val ID_KR = "kr"
    const val ID_SC = "sc"
    const val ID_TH = "th"

    val KR = Item(
        id = ID_KR,
        namaBerkas = "noto_kr.otf",
        alamat = "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/SubsetOTF/KR/NotoSansKR-Regular.otf",
        ukuran = 4_644_748L,
        sha256 = "69975a0ac8472717870aefeab0a4d52739308d90856b9955313b2ad5e0148d68",
        judul = "Korea (Hangul)"
    )

    val SC = Item(
        id = ID_SC,
        namaBerkas = "noto_sc.otf",
        alamat = "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf",
        ukuran = 8_331_336L,
        sha256 = "faa6c9df652116dde789d351359f3d7e5d2285a2b2a1f04a2d7244df706d5ea9",
        judul = "Mandarin (Han lengkap)"
    )

    val TH = Item(
        id = ID_TH,
        namaBerkas = "noto_th.ttf",
        alamat = "https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/NotoSansThai/full/ttf/NotoSansThai-Regular.ttf",
        ukuran = 72_812L,
        sha256 = "2101faa471942570d506a394df7da555d15246c7814c7522c63d4fa85b0d8d88",
        judul = "Thai"
    )

    val SEMUA = listOf(KR, SC, TH)

    /** Total byte bila semuanya diunduh. */
    val UKURAN_TOTAL: Long get() = SEMUA.sumOf { it.ukuran }

    fun dir(ctx: Context): File = File(ctx.filesDir, "fonts")

    fun berkas(ctx: Context, item: Item): File = File(dir(ctx), item.namaBerkas)

    /**
     * Terpasang = berkas ada DAN ukurannya tepat.
     *
     * Ukuran ikut diperiksa supaya sisa unduhan yang terpotong tidak pernah
     * dianggap font sah lalu membuat Typeface.createFromFile melempar galat
     * di tengah penggambaran halaman.
     */
    fun terpasang(ctx: Context, item: Item): Boolean {
        val f = berkas(ctx, item)
        return f.isFile && f.length() == item.ukuran
    }

    fun adaYangTerpasang(ctx: Context): Boolean = SEMUA.any { terpasang(ctx, it) }

    /** Byte yang sudah dipakai paket font di penyimpanan. */
    fun terpakai(ctx: Context): Long =
        SEMUA.filter { terpasang(ctx, it) }.sumOf { it.ukuran }

    /**
     * Aksara yang butuh font tambahan, dipetakan dari teks.
     *
     * Dipisah dari pemuatan font supaya bisa diuji tanpa Android: aturannya
     * murni soal rentang Unicode.
     */
    enum class Aksara { LATIN, JEPANG, HANGUL, HAN, THAI, SIRILIK }

    /**
     * Aksara dominan sebuah teks.
     *
     * Diputuskan dengan MENGHITUNG, bukan dengan menemukan karakter pertama:
     * kalimat Korea kerap menyelipkan satu-dua Han, dan kalimat Jepang selalu
     * bercampur kanji. Memilih font dari karakter pertama yang cocok akan
     * melempar kalimat Korea ke font Mandarin hanya karena ada satu hanja.
     *
     * Kana diperlakukan sebagai penanda kuat Jepang: begitu ada kana, kanji di
     * kalimat yang sama pasti Jepang, bukan Mandarin.
     */
    fun aksara(teks: String): Aksara {
        var kana = 0; var hangul = 0; var han = 0; var thai = 0; var siri = 0
        for (c in teks) {
            val k = c.code
            when {
                k in 0x3040..0x30FF -> kana++
                k in 0xAC00..0xD7A3 || k in 0x1100..0x11FF -> hangul++
                k in 0x4E00..0x9FFF || k in 0x3400..0x4DBF -> han++
                k in 0x0E00..0x0E7F -> thai++
                k in 0x0400..0x04FF -> siri++
            }
        }
        if (thai > 0) return Aksara.THAI
        if (kana > 0) return Aksara.JEPANG
        if (hangul > 0) return Aksara.HANGUL
        if (han > 0) return Aksara.HAN
        if (siri > 0) return Aksara.SIRILIK
        return Aksara.LATIN
    }

    /**
     * Font tambahan yang dibutuhkan aksara tertentu, null bila font bawaan
     * sudah cukup.
     *
     * Jepang dan Sirilik sengaja mengembalikan null: kosugi.ttf sudah diukur
     * menutup keduanya (hiragana 90,6%, katakana 93,8%, kalimat uji lolos
     * penuh), jadi memaksa unduhan untuk itu hanya membuang kuota pengguna.
     */
    fun butuh(aksara: Aksara): Item? = when (aksara) {
        Aksara.HANGUL -> KR
        Aksara.HAN -> SC
        Aksara.THAI -> TH
        else -> null
    }

    /** Font tambahan yang dibutuhkan sebuah teks, null bila tidak perlu. */
    fun butuhUntuk(teks: String): Item? = butuh(aksara(teks))

    /**
     * Paket yang MENCAKUP sebuah bahasa tujuan.
     *
     * Perhatikan: ini hanya pemetaan bahasa -> paket. Ia TIDAK berarti paket
     * itu dibutuhkan; lihat perluUnduh() untuk pertanyaan tersebut.
     */
    fun untukBahasa(bahasa: String): Item? = when (bahasa.lowercase()) {
        "korean" -> KR
        "mandarin", "chinese" -> SC
        "thai" -> TH
        else -> null
    }

    /**
     * Karakter contoh untuk menguji apakah perangkat sanggup menggambar aksara
     * sebuah paket.
     *
     * Dipilih yang lazim dipakai, bukan yang langka: tujuannya mendeteksi ROM
     * yang membuang seluruh font aksara ini, bukan menuntut cakupan sempurna.
     * Menuntut karakter langka akan menyalakan peringatan di perangkat normal
     * yang sebenarnya sanggup menggambar 99% teks nyata.
     */
    fun contoh(item: Item): List<String> = when (item.id) {
        ID_KR -> listOf("가", "힣", "한", "글")
        ID_SC -> listOf("说", "话", "的", "我")
        ID_TH -> listOf("ก", "เ", "ห", "อ")
        else -> emptyList()
    }

    /**
     * Apakah paket perlu diunduh.
     *
     * Logikanya sengaja dipisah dari Android supaya bisa diuji: keputusannya
     * murni "belum terpasang DAN perangkat tak sanggup menggambarnya".
     *
     * Kenapa harus bertanya ke perangkat, bukan ke tabel cmap font bawaan:
     * Typeface.createFromAsset membangun typeface lewat Typeface.Builder, yang
     * menyetel fallback ke DEFAULT_FAMILY. Karakter yang tidak ada di font
     * kustom TIDAK menjadi kotak kosong — sistem menambalnya dari rantai font
     * bawaan, yang pada AOSP memuat NotoSansThai dan NotoSansCJK. Jadi
     * cakupan cmap kosugi.ttf (Hangul 0%, Thai 0%) sama sekali bukan penentu
     * munculnya tofu, dan menyimpulkan sebaliknya hanya melahirkan peringatan
     * palsu yang menakut-nakuti pengguna agar mengunduh 13 MB tanpa alasan.
     *
     * Yang tersisa nyata: ROM murah atau ROM yang dipangkas per-region kadang
     * betul-betul membuang font CJK/Thai. Hanya di perangkat itu paket ini
     * berguna, dan hanya perangkat itu sendiri yang bisa menjawabnya.
     */
    fun perluUnduh(item: Item, terpasang: Boolean, adaGlif: (String) -> Boolean): Boolean {
        if (terpasang) return false
        return contoh(item).any { !adaGlif(it) }
    }

    /**
     * Apakah perangkat ini sanggup menggambar sebuah karakter.
     *
     * Diuji lewat font bawaan aplikasi (kosugi), persis typeface yang dipakai
     * TextRenderer, supaya jawabannya mencerminkan rantai fallback yang benar-
     * benar berlaku saat halaman digambar — bukan rantai hipotetis.
     */
    private fun adaGlif(ctx: Context, c: String): Boolean = runCatching {
        val p = android.graphics.Paint()
        p.typeface = runCatching {
            Typeface.createFromAsset(ctx.assets, "kosugi.ttf")
        }.getOrDefault(Typeface.DEFAULT)
        p.hasGlyph(c)
    }.getOrDefault(true) // ragu-ragu = jangan ganggu pengguna

    /**
     * Paket yang benar-benar dibutuhkan perangkat ini untuk sebuah bahasa
     * tujuan; null bila font bawaan/sistem sudah sanggup.
     *
     * Dipakai untuk memperingatkan SEBELUM pengguna membayar terjemahan satu
     * bab penuh — tapi hanya bila peringatannya memang berdasar.
     */
    fun perluUntukBahasa(ctx: Context, bahasa: String): Item? {
        val item = untukBahasa(bahasa) ?: return null
        val perlu = perluUnduh(item, terpasang(ctx, item)) { adaGlif(ctx, it) }
        return if (perlu) item else null
    }

    /**
     * Muat font terunduh; null bila belum ada atau berkasnya tidak sah.
     *
     * Kegagalan sengaja tidak dilempar: penggambaran halaman harus tetap jalan
     * dengan font bawaan meski paket rusak, sebab halaman yang tergambar
     * kurang sempurna jauh lebih berguna daripada bab yang gagal total.
     */
    fun muat(ctx: Context, item: Item): Typeface? {
        if (!terpasang(ctx, item)) return null
        return runCatching { Typeface.createFromFile(berkas(ctx, item)) }.getOrNull()
    }

    /** Hapus satu paket beserta sisa unduhannya; mengembalikan byte yang dibebaskan. */
    fun hapus(ctx: Context, item: Item): Long {
        var bebas = 0L
        val f = berkas(ctx, item)
        if (f.isFile) { bebas += f.length(); f.delete() }
        val p = File(dir(ctx), "${item.namaBerkas}.part")
        if (p.isFile) { bebas += p.length(); p.delete() }
        return bebas
    }

    fun hapusSemua(ctx: Context): Long = SEMUA.sumOf { hapus(ctx, it) }
}
