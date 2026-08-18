package com.cypy.manga

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mengambil warna latar balon dan warna teks aslinya, supaya hasil terjemahan
 * tidak selalu "kotak putih + huruf hitam".
 *
 * Ini menutup celah yang tercatat sejak ronde 7: balon hitam / navy berhasil
 * dideteksi, tetapi TextRenderer mengecatnya putih sehingga panel jadi rusak.
 * comic-translate menyelesaikannya dengan inpainting LaMa; di HP itu terlalu
 * berat, jadi kita pakai pendekatan yang jauh lebih murah namun cukup:
 *
 *   bg = modus warna "cincin" SEMPIT di sekeliling kotak teks
 *   fg = median piksel yang paling kontras terhadap bg di dalam kotak teks
 *
 * Cincinnya sengaja sempit dan lebarnya dipatok pada ukuran balon (6% sisi
 * terpendek). Ronde 12 membuktikan kenapa: kotak RT-DETR untuk balon oval
 * selalu lebih besar dari ovalnya sendiri, jadi kalau seluruh isi kotak ikut
 * disampel, latar halaman yang terang di keempat sudut menang suara dan balon
 * navy (88,24,8) salah terbaca sebagai putih (248,248,248).
 *
 * Terverifikasi atas gambar bukti pengguna: balon normal menghasilkan
 * bg=(248,248,248) fg=hitam, sedangkan balon hitam menghasilkan bg=(8,8,8)
 * fg=(255,255,255) dan balon navy serupa.
 */
object Palette {

    /** Luminansi 0..255 (Rec. 601). */
    fun luminance(c: Int): Float =
        0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    fun isDark(c: Int): Boolean = luminance(c) < 128f

    data class Colors(
        val background: Int,
        val foreground: Int,
        /** true bila warna diambil sungguhan dari gambar, bukan nilai bawaan. */
        val diukur: Boolean,
        /**
         * Warna tiap baris teks asli, urut dari atas ke bawah.
         *
         * Ronde 16: satu balon bisa memakai lebih dari satu warna — bukti
         * pengguna memuat "GIVE ME" hijau tua di atas "FOOD." hitam. Satu
         * warna per balon tidak akan pernah bisa mewakilinya: modus memilih
         * hitam dan hijaunya hilang, rata-rata mengarang hijau lumpur yang
         * tidak ada di gambar. Menyimpan warna per baris membuat penekanan
         * yang disengaja pembuatnya tetap hidup.
         *
         * Kosong bila teks hanya satu warna; TextRenderer memakai
         * [foreground] untuk kasus itu.
         */
        val warnaBaris: List<Int> = emptyList(),
        /**
         * Warna garis luar huruf bila balon aslinya memakai garis luar yang
         * berbeda dari latarnya.
         *
         * TextRenderer selalu menggambar garis luar memakai [background],
         * karena itulah yang membuat teks tetap terbaca di atas gambar. Tapi
         * pada teks lepas di luar balon - SFX dan teriakan di atas panel -
         * yang benar sering bukan warna latar: huruf hitam dengan garis luar
         * putih di atas panel gelap. Null berarti tidak ada bukti kuat, dan
         * pemanggil tetap memakai [background] seperti sebelumnya.
         */
        val garisLuar: Int? = null
    )

    val DEFAULT = Colors(Color.WHITE, Color.BLACK, false)

    /** Berapa banyak piksel cincin minimal supaya modus warna layak dipercaya. */
    private const val MIN_RING = 48

    /** Kuantisasi warna saat mencari modus (16 -> 16 tingkat per kanal). */
    private const val QUANT = 16

    /** Lebar cincin sampel, sebagai pecahan sisi terpendek kotak balon. */
    private const val RING_RATIO = 0.06f

    /**
     * @param bubble kotak balon (x1,y1,x2,y2)
     * @param textBox kotak teks di dalam balon, boleh null bila tak diketahui
     */
    fun sample(bmp: Bitmap, bubble: IntArray, textBox: IntArray?): Colors {
        val w = bmp.width
        val h = bmp.height
        val x1 = bubble[0].coerceIn(0, w)
        val y1 = bubble[1].coerceIn(0, h)
        val x2 = bubble[2].coerceIn(0, w)
        val y2 = bubble[3].coerceIn(0, h)
        val bw = x2 - x1
        val bh = y2 - y1
        if (bw < 4 || bh < 4) return DEFAULT

        // Subsample supaya balon besar tidak memakan waktu.
        val step = max(1, max(bw, bh) / 96)

        // Kotak teks diperluas sedikit agar sisa anti-aliasing huruf tidak
        // ikut terhitung sebagai warna latar.
        var tx1 = -1; var ty1 = -1; var tx2 = -1; var ty2 = -1
        if (textBox != null) {
            val pad = max(2, min(bw, bh) / 40)
            tx1 = max(x1, textBox[0] - pad); ty1 = max(y1, textBox[1] - pad)
            tx2 = min(x2, textBox[2] + pad); ty2 = min(y2, textBox[3] + pad)
        }

        // Batas luar area yang disampel.
        var ox1 = x1; var oy1 = y1; var ox2 = x2; var oy2 = y2
        if (tx1 >= 0 && tx2 > tx1 && ty2 > ty1) {
            // Cincin sempit mengelilingi teks: tetap di dalam oval balon.
            val g = max(6, (RING_RATIO * min(bw, bh)).toInt())
            ox1 = max(x1, tx1 - g); oy1 = max(y1, ty1 - g)
            ox2 = min(x2, tx2 + g); oy2 = min(y2, ty2 + g)
        } else {
            // Tanpa kotak teks: ambil inti balon (50% bagian tengah) supaya
            // sudut-sudut kotak yang berisi latar halaman tidak ikut terhitung.
            val cw = bw / 4; val ch = bh / 4
            ox1 = x1 + cw; oy1 = y1 + ch; ox2 = x2 - cw; oy2 = y2 - ch
        }
        if (ox2 - ox1 < 2 || oy2 - oy1 < 2) { ox1 = x1; oy1 = y1; ox2 = x2; oy2 = y2 }

        val hist = HashMap<Int, Int>()
        var ringCount = 0
        var y = oy1
        while (y < oy2) {
            var x = ox1
            while (x < ox2) {
                val diTeks = tx1 >= 0 && x >= tx1 && x < tx2 && y >= ty1 && y < ty2
                if (!diTeks) {
                    val p = bmp.getPixel(x, y)
                    val q = quantize(p)
                    hist[q] = (hist[q] ?: 0) + 1
                    ringCount++
                }
                x += step
            }
            y += step
        }
        if (ringCount < MIN_RING) return DEFAULT

        var bestKey = 0
        var bestCount = -1
        for ((k, v) in hist) if (v > bestCount) { bestCount = v; bestKey = k }
        val bg = dequantize(bestKey)

        // Warna teks: piksel dalam kotak teks yang paling jauh dari bg.
        var fg = if (isDark(bg)) Color.WHITE else Color.BLACK
        var warnaBaris: List<Int> = emptyList()
        // Semua piksel kotak teks, dipakai untuk menaksir garis luar nanti.
        val semuaTeks = ArrayList<Int>()
        // Piksel yang berada di TEPI huruf: calon garis luar.
        var pikselTepi: List<Int> = emptyList()
        if (tx1 >= 0 && tx2 - tx1 >= 4 && ty2 - ty1 >= 4) {
            // Piksel disimpan per BARIS pindai, bukan satu kantong besar,
            // supaya baris teks bisa dipisah setelah ambang diketahui.
            val tstep = max(1, max(tx2 - tx1, ty2 - ty1) / 96)
            // Warna dan jaraknya disimpan di DUA larik sejajar.
            //
            // Ronde 16 — inilah bug yang membuat warna tidak pernah bekerja:
            // versi lama memampatkan jarak ke 8 bit teratas satu Int
            // ("(d shl 24) or rgb"). Padahal dist() menjumlah tiga selisih
            // kanal, jadi rentangnya 0..765, bukan 0..255. Teks hitam di balon
            // putih berjarak 744; setelah dipotong 8 bit ia terbaca 232,
            // sementara ambangnya 0,70 x 744 = 520. Tidak ada satu pun piksel
            // yang lolos, n selalu 0, dan warna teks diam-diam jatuh ke
            // hitam/putih bawaan. Justru teks berkontras TINGGI — yaitu semua
            // teks komik normal — yang paling parah terkena.
            val barisWarna = ArrayList<IntArray>()
            val barisJarak = ArrayList<IntArray>()
            var jarakMaks = 0
            var total = 0
            var yy = ty1
            val lebar = (tx2 - tx1 + tstep - 1) / tstep
            while (yy < ty2) {
                var xx = tx1
                var n = 0
                val bufW = IntArray(lebar)
                val bufD = IntArray(lebar)
                while (xx < tx2 && n < lebar) {
                    val p = bmp.getPixel(xx, yy)
                    val d = dist(p, bg)
                    if (d > jarakMaks) jarakMaks = d
                    bufW[n] = p and 0xFFFFFF
                    bufD[n] = d
                    semuaTeks.add(p and 0xFFFFFF)
                    n++
                    xx += tstep
                }
                barisWarna.add(bufW.copyOf(n))
                barisJarak.add(bufD.copyOf(n))
                total += n
                yy += tstep
            }
            if (total >= 20 && jarakMaks > 40) {
                val ambang = (jarakMaks * 0.70f).toInt()
                val semua = ArrayList<Int>(total)
                // Berapa piksel teks di tiap baris pindai — dipakai untuk
                // memotong blok menjadi baris-baris kalimat.
                val hitung = IntArray(barisWarna.size)
                for (i in barisWarna.indices) {
                    val w = barisWarna[i]
                    val d = barisJarak[i]
                    var c = 0
                    for (j in w.indices) {
                        if (d[j] < ambang) continue
                        semua.add(w[j])
                        c++
                    }
                    hitung[i] = c
                }
                // Warna huruf diambil dari BAGIAN DALAM saja. Pada teks
                // bergaris luar, piksel tepi jumlahnya bisa mengalahkan isinya
                // dan modus atas seluruh piksel akan memilih warna garis luar
                // sebagai warna huruf - isi dan tepi tertukar.
                val (dalam, tepi) = pisahDalamTepi(barisWarna, barisJarak, ambang)
                val dominan =
                    if (dalam.size >= MIN_DALAM) warnaDominan(dalam) else warnaDominan(semua)
                if (dominan != null) fg = dominan
                pikselTepi = tepi
                warnaBaris = warnaTiapBaris(barisWarna, barisJarak, hitung, ambang)
            }
        }

        // Jaga keterbacaan: kalau kontras terlalu rendah, pakai hitam/putih.
        if (abs(luminance(fg) - luminance(bg)) < 60f) {
            fg = if (isDark(bg)) Color.WHITE else Color.BLACK
        }

        // Garis luar diukur SESUDAH fg final, karena definisinya bergantung
        // pada ruas fg-bg. Kalau diukur dengan fg sementara, piksel
        // anti-aliasing bisa lolos sebagai garis luar palsu.
        var garisLuar: Int? = null
        if (semuaTeks.size >= 20) {
            // Dicari di antara piksel tepi bila ada; kalau pemisahan gagal
            // (huruf terlalu tipis untuk punya bagian dalam) pakai semuanya.
            val bahan = if (pikselTepi.size >= STROKE_MIN_PIX) pikselTepi else semuaTeks
            garisLuar = warnaGarisLuar(bahan, bg, fg)
            // Garis luar hanya berguna kalau ia kontras dengan warna hurufnya.
            if (garisLuar != null && abs(luminance(garisLuar) - luminance(fg)) < 60f) {
                garisLuar = null
            }
        }
        // Baris yang tak terbaca jelas ikut aturan keterbacaan yang sama.
        if (warnaBaris.isNotEmpty()) {
            warnaBaris = warnaBaris.map { c ->
                if (abs(luminance(c) - luminance(bg)) < 60f) fg else c
            }
            // Semua baris sewarna -> tidak ada gunanya menyimpan daftar.
            if (warnaBaris.distinct().size <= 1) warnaBaris = emptyList()
        }
        return Colors(bg, fg, true, warnaBaris, garisLuar)
    }

    /**
     * Memotong blok teks menjadi baris lewat proyeksi mendatar, lalu mengambil
     * warna dominan tiap baris.
     *
     * Baris dipisah oleh celah tempat hampir tidak ada piksel teks. Ambangnya
     * relatif terhadap baris terpadat (6%) supaya tetap bekerja pada huruf
     * tipis maupun tebal. Potongan yang terlalu pendek dibuang: itu biasanya
     * titik pada huruf 'i' atau tanda baca, bukan baris tersendiri.
     */
    private fun warnaTiapBaris(
        barisWarna: List<IntArray>, barisJarak: List<IntArray>,
        hitung: IntArray, ambang: Int
    ): List<Int> {
        var maks = 0
        for (v in hitung) if (v > maks) maks = v
        if (maks <= 0) return emptyList()
        val batas = max(1, (maks * 0.06f).toInt())

        val hasil = ArrayList<Int>()
        var mulai = -1
        var i = 0
        while (i <= hitung.size) {
            val aktif = i < hitung.size && hitung[i] >= batas
            if (aktif && mulai < 0) mulai = i
            else if (!aktif && mulai >= 0) {
                if (i - mulai >= 2) {
                    val px = ArrayList<Int>()
                    for (j in mulai until i) {
                        val w = barisWarna[j]
                        val d = barisJarak[j]
                        for (k in w.indices) {
                            if (d[k] < ambang) continue
                            px.add(w[k])
                        }
                    }
                    val c = warnaDominan(px)
                    if (c != null) hasil.add(c)
                }
                mulai = -1
            }
            i++
        }
        return if (hasil.size >= 2) hasil else emptyList()
    }

    /**
     * Warna yang paling sering muncul, bukan rata-ratanya.
     *
     * Ronde 16, dari bukti pengguna: satu balon berisi "GIVE ME" hijau tua
     * (8,88,56) dan "FOOD." hitam (8,8,8). Merata-rata dua warna yang berbeda
     * menghasilkan (3,57,37) — hijau lumpur yang tidak ada di gambar aslinya.
     * Rata-rata hanya benar kalau semua piksel teks sewarna; begitu ada dua
     * warna, hasilnya justru warna yang tidak dipakai keduanya.
     *
     * Modus memilih warna nyata yang paling banyak dipakai. Untuk kasus di
     * atas ia mengembalikan hijau (20,7% piksel) — warna kalimat pertama dan
     * yang paling menonjol secara visual.
     *
     * Kuantisasinya lebih halus (8) daripada milik latar (16): warna huruf
     * bergradasi karena anti-aliasing, jadi ember yang terlalu lebar akan
     * menggabungkan hijau dengan hitam dan mengulang bug yang sama. Puncak
     * dihaluskan dengan merata-rata piksel di dalam ember pemenang saja,
     * supaya hasilnya tidak terkunci pada kelipatan 8.
     */
    private const val QUANT_FG = 8

    fun warnaDominan(piksel: List<Int>): Int? {
        if (piksel.isEmpty()) return null
        val hist = HashMap<Int, Int>()
        for (p in piksel) {
            val k = (((p shr 16) and 0xFF) / QUANT_FG shl 16) or
                (((p shr 8) and 0xFF) / QUANT_FG shl 8) or
                ((p and 0xFF) / QUANT_FG)
            hist[k] = (hist[k] ?: 0) + 1
        }
        var bestKey = -1
        var bestCount = -1
        for ((k, v) in hist) if (v > bestCount) { bestCount = v; bestKey = k }
        if (bestKey < 0) return null
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        for (p in piksel) {
            val k = (((p shr 16) and 0xFF) / QUANT_FG shl 16) or
                (((p shr 8) and 0xFF) / QUANT_FG shl 8) or
                ((p and 0xFF) / QUANT_FG)
            if (k != bestKey) continue
            rs += (p shr 16) and 0xFF
            gs += (p shr 8) and 0xFF
            bs += p and 0xFF
            n++
        }
        if (n == 0) return null
        return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    private fun quantize(p: Int): Int {
        val r = ((p shr 16) and 0xFF) / QUANT
        val g = ((p shr 8) and 0xFF) / QUANT
        val b = (p and 0xFF) / QUANT
        return (r shl 16) or (g shl 8) or b
    }

    private fun dequantize(q: Int): Int {
        val half = QUANT / 2
        val r = (((q shr 16) and 0xFF) * QUANT + half).coerceAtMost(255)
        val g = (((q shr 8) and 0xFF) * QUANT + half).coerceAtMost(255)
        val b = ((q and 0xFF) * QUANT + half).coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    // ---- garis luar (stroke) ----

    /**
     * Seberapa jauh sebuah piksel harus menyimpang dari garis fg-bg sebelum
     * dianggap warna tersendiri, bukan anti-aliasing. Satuannya jarak Euclid
     * RGB (0..441).
     */
    private const val STROKE_MIN_OFFSET = 60f

    /** Minimum piksel garis luar sebelum hasilnya layak dipercaya. */
    private const val STROKE_MIN_PIX = 12

    /**
     * Bagian minimum dari seluruh piksel kotak teks. Garis luar melingkari
     * setiap huruf, jadi porsinya tidak pernah sekadar beberapa piksel nyasar.
     */
    private const val STROKE_MIN_FRAC = 0.02f

    /**
     * Jarak Euclid dari sebuah warna ke ruas garis antara dua warna lain.
     *
     * Inilah yang memisahkan garis luar sungguhan dari anti-aliasing. Piksel
     * anti-aliasing adalah campuran linear teks dan latar, jadi letaknya
     * PERSIS di ruas fg-bg dan jaraknya nyaris nol. Warna garis luar yang
     * sengaja dipilih pembuat (putih di sekeliling huruf hitam pada panel
     * gelap) tidak berada di ruas itu.
     */
    fun jarakKeRuas(p: Int, a: Int, b: Int): Float {
        val px = Color.red(p).toFloat(); val py = Color.green(p).toFloat(); val pz = Color.blue(p).toFloat()
        val ax = Color.red(a).toFloat(); val ay = Color.green(a).toFloat(); val az = Color.blue(a).toFloat()
        val bx = Color.red(b).toFloat(); val by = Color.green(b).toFloat(); val bz = Color.blue(b).toFloat()
        val vx = bx - ax; val vy = by - ay; val vz = bz - az
        val len2 = vx * vx + vy * vy + vz * vz
        // fg dan bg sewarna: ruasnya menciut jadi titik.
        val t = if (len2 <= 0.0001f) 0f
        else (((px - ax) * vx + (py - ay) * vy + (pz - az) * vz) / len2).coerceIn(0f, 1f)
        val dx = px - (ax + t * vx); val dy = py - (ay + t * vy); val dz = pz - (az + t * vz)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Pisahkan piksel huruf menjadi BAGIAN DALAM dan TEPI.
     *
     * Tanpa informasi ruang, warna isi huruf dan warna garis luarnya tidak
     * bisa dibedakan sama sekali - keduanya hanya "warna yang bukan latar",
     * dan aturan lama (ambil yang paling jauh dari latar) justru memilih garis
     * luarnya pada teks bergaris luar terang. Akibatnya isi dan tepi tertukar
     * saat digambar ulang.
     *
     * Pembedanya geometris dan murah: piksel huruf yang SEMUA tetangganya juga
     * huruf pasti berada di bagian dalam; yang bersentuhan dengan latar pasti
     * berada di tepi. Itu persis definisi isi dan garis luar.
     *
     * [barisWarna]/[barisJarak] adalah kisi hasil pindai kotak teks, dan
     * [ambang] batas "bukan latar" yang sudah dipakai di tempat lain.
     */
    fun pisahDalamTepi(
        barisWarna: List<IntArray>, barisJarak: List<IntArray>, ambang: Int
    ): Pair<List<Int>, List<Int>> {
        val dalam = ArrayList<Int>()
        val tepi = ArrayList<Int>()

        fun huruf(i: Int, j: Int): Boolean {
            if (i < 0 || i >= barisJarak.size) return false
            val b = barisJarak[i]
            if (j < 0 || j >= b.size) return false
            return b[j] >= ambang
        }

        for (i in barisWarna.indices) {
            val w = barisWarna[i]
            val d = barisJarak[i]
            for (j in w.indices) {
                if (j >= d.size || d[j] < ambang) continue
                // Piksel di pinggir kisi diperlakukan sebagai tepi: kita tidak
                // tahu apa yang ada di sebelahnya.
                val terkepung = huruf(i - 1, j) && huruf(i + 1, j) &&
                    huruf(i, j - 1) && huruf(i, j + 1)
                if (terkepung) dalam.add(w[j]) else tepi.add(w[j])
            }
        }
        return Pair(dalam, tepi)
    }

    /** Minimum piksel bagian dalam sebelum ia boleh menentukan warna huruf. */
    private const val MIN_DALAM = 12

    /**
     * Perkirakan warna garis luar teks dari piksel kotak teks.
     *
     * [piksel] adalah SEMUA piksel kotak teks (teks, latar, dan peralihan);
     * [bg] dan [fg] adalah warna yang sudah diukur. Kembalikan null bila tidak
     * ada bukti kuat adanya garis luar berwarna lain - itu keadaan yang lazim,
     * dan pemanggilnya harus tetap memakai [bg] seperti sebelumnya.
     */
    fun warnaGarisLuar(piksel: List<Int>, bg: Int, fg: Int): Int? {
        if (piksel.size < STROKE_MIN_PIX) return null
        val kandidat = ArrayList<Int>()
        for (p in piksel) {
            val rgb = p and 0xFFFFFF
            // Piksel yang sudah jelas teks atau jelas latar bukan garis luar.
            if (dist(rgb, fg) < 40 || dist(rgb, bg) < 40) continue
            if (jarakKeRuas(rgb, fg, bg) < STROKE_MIN_OFFSET) continue
            kandidat.add(rgb)
        }
        if (kandidat.size < STROKE_MIN_PIX) return null
        if (kandidat.size < piksel.size * STROKE_MIN_FRAC) return null
        val warna = warnaDominan(kandidat) ?: return null
        // Hasil akhir harus tetap berbeda dari keduanya, kalau tidak ia hanya
        // mengulang perilaku bawaan dengan biaya tambahan.
        if (dist(warna and 0xFFFFFF, bg) < 40 || dist(warna and 0xFFFFFF, fg) < 40) return null
        return warna
    }

    private fun dist(p: Int, c: Int): Int =
        abs(((p shr 16) and 0xFF) - Color.red(c)) +
            abs(((p shr 8) and 0xFF) - Color.green(c)) +
            abs((p and 0xFF) - Color.blue(c))
}
