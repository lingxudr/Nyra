package com.nyra.comic

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tipografi adaptif: mengukur bagaimana teks ASLI ditata, lalu menata
 * terjemahan supaya menyerupainya.
 *
 * Kenapa ada. Sebelum ini renderer memilih dari tiga tuple yang dipaku di
 * kode (`chooseSetting`): besar/kecil balon dikali panjang/pendek teks. Tiga
 * kemungkinan untuk seluruh ragam komik. Balon teriakan dan balon bisikan
 * mendapat perlakuan yang sama persis, karena satu-satunya yang dilihat
 * adalah ukuran kotak dan jumlah karakter — bukan gambarnya.
 *
 * Padahal buktinya sudah ada di tangan. RT-DETR mengembalikan kelas 1
 * (`text_bubble`), yaitu kotak teks ASLI di dalam balon. Dari kotak itu tiga
 * hal bisa diukur langsung dari piksel, tanpa satu token pun:
 *
 *  1. ORIENTASI    - blok yang jauh lebih tinggi daripada lebar berarti
 *                    tulisan aslinya tegak (tategaki).
 *  2. RASIO ISI    - luas blok teks dibagi luas balon: seberapa penuh balon
 *                    aslinya. Nilai inilah yang menggantikan skala tetap
 *                    0,76-0,85 yang dulu dipakai untuk semua balon.
 *  3. BERAT        - tebal goresan (median run-length piksel tinta per baris)
 *                    dan kepadatan tinta. Pada halaman uji: 2,0 px untuk
 *                    balon normal, 1,0 px untuk balon 28x86 - sinyal terukur
 *                    bahwa balon kecil memakai huruf lebih tipis.
 *
 * Kenapa diukur, bukan ditanyakan ke AI. Ukuran font dari LLM berubah tiap
 * panggilan, jadi tidak ada satu pun tes yang bisa mengikatnya; ia menambah
 * token dan latensi untuk keterangan yang sudah terbaca gratis dari piksel;
 * dan modelnya tidak pernah melihat hasil akhir, jadi ia menebak lebar teks
 * tanpa tahu font mana yang akan dipakai. Mode gagalnya pun jelek: ukuran
 * ngawur berarti teks luber keluar balon tanpa ada yang menahan. Pengukuran
 * bersifat deterministik, gratis, dan bisa diuji.
 *
 * Berkas ini sengaja murni-Kotlin: tidak ada android.graphics sama sekali,
 * sehingga seluruh isinya bisa diuji di JVM biasa tanpa Robolectric. Piksel
 * masuk sebagai IntArray ARGB dan luminansi dihitung dengan geseran bit -
 * android.graphics.Color.red/green/blue mengembalikan 0 di unit test JVM.
 */
object Typography {

    // --- ambang pengukuran -------------------------------------------------

    /** Blok dianggap tegak bila tinggi >= lebar * ini. */
    const val TEGAK_RASIO = 1.30f

    /** Blok dianggap mendatar bila lebar >= tinggi * ini. */
    const val DATAR_RASIO = 1.30f

    /** Piksel dianggap tinta bila luminansinya di bawah ini (latar terang). */
    const val AMBANG_TINTA = 128

    /** Blok terlalu kecil untuk diukur dengan andal. */
    const val MIN_SISI_UKUR = 10

    /** Kepadatan tinta di atas ini menandakan huruf tebal/teriakan. */
    const val PADAT_TEBAL = 0.115f

    /** Kepadatan tinta di bawah ini menandakan huruf tipis/bisikan. */
    const val PADAT_TIPIS = 0.045f

    // --- batas keputusan ---------------------------------------------------

    /** Rasio isi tidak pernah dipakai di luar rentang ini, sekonyol apa pun ukurannya. */
    const val ISI_MIN = 0.55f
    const val ISI_MAKS = 0.92f

    /** Bila blok teks asli tidak diketahui, pakai nilai ini (perilaku lama). */
    const val ISI_BAWAAN = 0.78f

    const val FONT_MIN = 8
    const val FONT_MAKS = 96

    /** Jarak antar baris sebagai pecahan ukuran font. */
    const val SPASI_RAPAT = 0.050f
    const val SPASI_NORMAL = 0.070f
    const val SPASI_LONGGAR = 0.095f

    /** Berat huruf yang terukur dari goresan asli. */
    enum class Berat { TIPIS, NORMAL, TEBAL }

    /** Arah tulisan asli. */
    enum class Arah { TEGAK, DATAR, TIDAK_JELAS }

    /**
     * Hasil pengukuran teks asli pada satu balon.
     *
     * [terukur] false berarti tidak ada blok teks yang bisa diukur (kotak
     * terlalu kecil, atau RT-DETR tidak memberi kelas 1 untuk balon ini).
     * Dalam keadaan itu semua nilai lain adalah nilai bawaan dan pemanggil
     * mendapat perilaku lama - ini yang menjaga paritas.
     */
    data class Gaya(
        val arah: Arah,
        val rasioIsi: Float,
        val kepadatan: Float,
        val tebalGoresan: Float,
        val berat: Berat,
        val terukur: Boolean
    ) {
        companion object {
            val BAWAAN = Gaya(
                arah = Arah.TIDAK_JELAS,
                rasioIsi = ISI_BAWAAN,
                kepadatan = 0f,
                tebalGoresan = 0f,
                berat = Berat.NORMAL,
                terukur = false
            )
        }
    }

    /**
     * Rencana penataan untuk terjemahan.
     *
     * [ukuranFont] adalah hasil pencarian biner yang dijamin muat; pemanggil
     * tidak perlu menyusutkannya lagi.
     */
    data class Rencana(
        val ukuranFont: Int,
        val berat: Berat,
        val spasiBaris: Float,
        val skalaLebar: Float,
        val skalaTinggi: Float,
        val tegak: Boolean
    )

    /**
     * Mengukur gaya teks asli.
     *
     * [piksel] adalah ARGB seluruh halaman, [lebar]x[tinggi]. [balon] adalah
     * kotak balon; [teksAsli] kotak blok teks di dalamnya (kelas 1 RT-DETR),
     * boleh null bila tidak diketahui.
     *
     * Yang diukur adalah isi [teksAsli], bukan seluruh balon: mengukur
     * seluruh balon akan mencampur ruang kosong ke dalam kepadatan dan
     * membuat setiap balon tampak tipis.
     */
    fun ukur(
        piksel: IntArray,
        lebar: Int,
        tinggi: Int,
        balon: IntArray,
        teksAsli: IntArray?
    ): Gaya {
        if (teksAsli == null) return Gaya.BAWAAN
        if (lebar <= 0 || tinggi <= 0 || piksel.size < lebar * tinggi) return Gaya.BAWAAN

        val tx1 = teksAsli[0].coerceIn(0, lebar)
        val ty1 = teksAsli[1].coerceIn(0, tinggi)
        val tx2 = teksAsli[2].coerceIn(0, lebar)
        val ty2 = teksAsli[3].coerceIn(0, tinggi)
        val tw = tx2 - tx1
        val th = ty2 - ty1
        if (tw < MIN_SISI_UKUR || th < MIN_SISI_UKUR) return Gaya.BAWAAN

        val bw = max(1, balon[2] - balon[0])
        val bh = max(1, balon[3] - balon[1])

        // Rasio isi memakai akar dari perbandingan luas, bukan perbandingan
        // luas mentah. Yang dibutuhkan pemanggil adalah skala LINEAR (berapa
        // bagian lebar dan tinggi balon yang boleh dipakai), sedangkan
        // perbandingan luas bersifat kuadratik: blok yang mengisi setengah
        // luas balon mengisi ~0,71 sisinya, bukan 0,5.
        val luasTeks = (tw.toFloat() * th.toFloat())
        val luasBalon = (bw.toFloat() * bh.toFloat())
        val isiLinear = sqrt((luasTeks / luasBalon).coerceIn(0f, 1f))
        val rasioIsi = isiLinear.coerceIn(ISI_MIN, ISI_MAKS)

        // Kepadatan tinta dan tebal goresan.
        var jumlahTinta = 0
        var totalPiksel = 0
        val runs = ArrayList<Int>(th)
        for (y in ty1 until ty2) {
            var run = 0
            val baris = y * lebar
            for (x in tx1 until tx2) {
                val p = piksel[baris + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Luminansi Rec.601 dengan geseran bit: android.graphics.Color
                // tidak berfungsi di unit test JVM biasa.
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                totalPiksel++
                if (lum < AMBANG_TINTA) {
                    jumlahTinta++
                    run++
                } else {
                    if (run > 0) runs.add(run)
                    run = 0
                }
            }
            if (run > 0) runs.add(run)
        }
        if (totalPiksel == 0) return Gaya.BAWAAN

        val kepadatan = jumlahTinta.toFloat() / totalPiksel
        val tebal = if (runs.isEmpty()) 0f else median(runs)

        val arah = when {
            th >= tw * TEGAK_RASIO -> Arah.TEGAK
            tw >= th * DATAR_RASIO -> Arah.DATAR
            else -> Arah.TIDAK_JELAS
        }

        // Berat dinilai dari kepadatan, bukan dari tebal goresan mentah:
        // goresan diukur dalam piksel, jadi ia ikut membesar bila halaman
        // dipindai pada resolusi tinggi. Kepadatan bersifat tak-berdimensi
        // sehingga sebanding antar halaman apa pun resolusinya.
        val berat = when {
            kepadatan >= PADAT_TEBAL -> Berat.TEBAL
            kepadatan <= PADAT_TIPIS -> Berat.TIPIS
            else -> Berat.NORMAL
        }

        return Gaya(
            arah = arah,
            rasioIsi = rasioIsi,
            kepadatan = kepadatan,
            tebalGoresan = tebal,
            berat = berat,
            terukur = true
        )
    }

    private fun median(v: List<Int>): Float {
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2].toFloat()
        else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    /**
     * Pencarian biner ukuran font terbesar yang masih muat.
     *
     * Pengganti perulangan menurun `for (size in maxFont downTo minFont)` yang
     * lama: perulangan itu memanggil pembungkus teks sampai 89 kali per balon,
     * sedangkan pencarian biner memerlukan ~7 kali untuk rentang yang sama.
     *
     * [muat] menerima ukuran font dan menjawab apakah teks muat di dalam
     * ruang yang tersedia. Kecocokan monotonik diasumsikan: bila ukuran n
     * muat maka semua ukuran di bawahnya juga muat. Anggapan itu berlaku
     * untuk pembungkusan teks biasa.
     *
     * Mengembalikan [min] bila tidak ada ukuran yang muat - pemanggil tetap
     * menggambar, karena teks yang sedikit luber lebih baik daripada balon
     * kosong tanpa keterangan apa pun.
     */
    fun cariUkuran(min: Int, maks: Int, muat: (Int) -> Boolean): Int {
        val lo0 = max(1, min)
        val hi0 = max(lo0, maks)
        if (!muat(lo0)) return lo0
        var lo = lo0
        var hi = hi0
        // Invarian: lo muat, hi+1 tidak muat.
        while (lo < hi) {
            val tengah = lo + (hi - lo + 1) / 2
            if (muat(tengah)) lo = tengah else hi = tengah - 1
        }
        return lo
    }

    /**
     * Menyusun rencana penataan dari gaya terukur.
     *
     * [ukuranMuat] didapat dari [cariUkuran] oleh pemanggil, yang memegang
     * pengukur teks sesungguhnya (Paint). Fungsi ini hanya memutuskan
     * bagaimana ukuran itu disesuaikan dengan gaya asli.
     *
     * Bila gaya tidak terukur, hasilnya sengaja sama dengan perilaku lama.
     */
    fun putuskan(
        gaya: Gaya,
        ukuranMuat: Int,
        panjangTeks: Int,
        bahasaTegak: Boolean
    ): Rencana {
        val spasi = when {
            !gaya.terukur -> SPASI_NORMAL
            // Balon yang aslinya padat memerlukan baris rapat supaya jumlah
            // baris terjemahan tetap masuk.
            gaya.kepadatan >= PADAT_TEBAL -> SPASI_RAPAT
            gaya.kepadatan <= PADAT_TIPIS -> SPASI_LONGGAR
            else -> SPASI_NORMAL
        }

        // Teks yang sangat panjang selalu perlu baris lebih rapat, terukur
        // atau tidak; kalau tidak, terjemahan panjang memaksa font mengecil
        // sampai tak terbaca demi menampung jarak antar baris.
        val spasiAkhir = if (panjangTeks > 120) min(spasi, SPASI_RAPAT) else spasi

        val skala = if (gaya.terukur) gaya.rasioIsi else ISI_BAWAAN

        return Rencana(
            ukuranFont = ukuranMuat.coerceIn(FONT_MIN, FONT_MAKS),
            berat = if (gaya.terukur) gaya.berat else Berat.NORMAL,
            spasiBaris = spasiAkhir,
            skalaLebar = skala,
            skalaTinggi = skala,
            // Arah tulisan mengikuti bahasa sasaran; pengukuran hanya
            // menguatkan, tidak pernah memaksa. Merender bahasa Indonesia
            // secara tegak hanya karena aslinya tategaki akan menghasilkan
            // satu huruf per baris - tidak terbaca.
            tegak = bahasaTegak
        )
    }

    /**
     * Lebar goresan garis luar untuk ukuran font dan berat tertentu.
     *
     * Huruf tebal memerlukan garis luar lebih tebal supaya tetap terpisah
     * dari latar; huruf tipis memerlukan yang lebih halus supaya tidak
     * tertelan garis luarnya sendiri.
     */
    fun lebarGarisLuar(ukuranFont: Int, berat: Berat): Float {
        val dasar = max(1f, ukuranFont / 11f)
        return when (berat) {
            Berat.TEBAL -> dasar * 1.25f
            Berat.TIPIS -> dasar * 0.75f
            Berat.NORMAL -> dasar
        }
    }
}
