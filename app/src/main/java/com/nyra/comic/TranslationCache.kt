package com.nyra.comic

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Cache terjemahan per balon.
 *
 * MASALAH YANG DIPERBAIKI: setiap kali sebuah halaman diproses ulang - karena
 * pengguna menyunting kotak lalu merender ulang, mencoba font lain, mengulang
 * bab yang sama, atau menjalankan berkas yang ternyata sudah pernah
 * diterjemahkan - seluruh balonnya dibayar penuh lagi ke API. Padahal gambar
 * potongannya identik dan bahasanya sama, sehingga jawabannya pasti sama.
 *
 * KUNCI. Sidik SHA-256 dari piksel potongan yang sudah dinormalkan, digabung
 * dengan bahasa sasaran dan nama model. Ketiganya wajib:
 *
 *  - piksel, karena dua balon berbeda pada halaman berbeda bisa punya
 *    koordinat yang sama persis; koordinat bukan identitas.
 *  - bahasa, jelas.
 *  - model, karena mutu terjemahan berbeda antar model dan pengguna yang
 *    sengaja pindah ke model lebih baik tidak boleh malah disuguhi jawaban
 *    lama dari model yang murah.
 *
 * NORMALISASI PIKSEL. Potongan diskalakan ke [SISI] x [SISI] abu-abu lalu
 * dikuantisasi ke 16 aras sebelum di-hash. Kuantisasi itu menyerap derau
 * kompresi JPEG dan pembulatan padding, yang kalau tidak akan membuat balon
 * yang secara visual sama tidak pernah cocok.
 *
 * BATAS YANG DISENGAJA: ini sidik EKSAK, bukan sidik perseptual. Mengubah
 * resolusi kerja (maxImageSide) mengubah interpolasi tepi huruf, dan itu sudah
 * cukup untuk menghasilkan sidik berbeda - diukur 70 dari 1024 byte berubah
 * saat potongan yang sama dirender pada dua ukuran. Akibatnya cache MELESET
 * setelah pengguna mengubah resolusi.
 *
 * Itu pilihan sadar. Alternatifnya - sidik perseptual dengan ambang jarak
 * Hamming - memang tahan skala, tapi dua balon berbeda yang tata letak
 * teksnya mirip bisa jatuh di dalam ambang yang sama, dan akibatnya adalah
 * TERJEMAHAN YANG SALAH disisipkan diam-diam ke halaman. Cache yang meleset
 * hanya memakan biaya satu request; cache yang salah merusak hasil tanpa
 * jejak. Untuk kasus pemakaian yang sebenarnya - menjalankan ulang berkas yang
 * sama dengan setelan yang sama - potongan yang dihasilkan identik bit demi
 * bit, sehingga sidik eksak sudah kena.
 *
 * RISIKO TABRAKAN dan cara menekannya: sidik 256-bit atas gambar 32x32 abu-abu
 * memang bisa saja menyamakan dua balon yang tampak mirip pada resolusi itu.
 * Karena itu teks SUMBER hasil OCR tidak dipakai sebagai kunci - ia sering
 * kosong - melainkan entri menyimpan ukuran asli potongan, dan entri hanya
 * dianggap cocok bila rasio aspeknya juga sepadan. Balon "Ya." dan "Tidak."
 * yang sama-sama kecil dan bulat punya rasio berbeda dan tidak akan tertukar.
 *
 * PENYIMPANAN. Satu berkas JSON di filesDir. Sederhana dan cukup: satu entri
 * hanya beberapa puluh byte, dan [MAKS_ENTRI] menjaga berkasnya tetap kecil
 * lewat pemangkasan LRU. Tidak memakai SQLite karena tidak ada kueri yang
 * perlu dilakukan selain "ambil berdasarkan kunci".
 */
class TranslationCache(private val berkas: File) {

    /** Sisi gambar yang di-hash. Cukup kecil agar tahan derau, cukup besar agar khas. */
    companion object {
        const val SISI = 32

        /** Batas jumlah entri; yang paling lama tidak dipakai dibuang. */
        const val MAKS_ENTRI = 4000

        const val VERSI = 1

        /** Selisih rasio aspek maksimum agar sebuah entri dianggap cocok. */
        const val TOLERANSI_RASIO = 0.15f

        /** Berkas cache bawaan di dalam filesDir aplikasi. */
        fun bawaan(dir: File): File = File(dir, "translation_cache.json")

        /**
         * Sidik potongan yang tahan derau kecerahan (bukan tahan skala).
         *
         * Bitmap diskalakan ke SISI x SISI, dijadikan abu-abu, lalu tiap piksel
         * dikuantisasi ke 16 aras. Kuantisasi itu yang membuat perbedaan
         * kecerahan akibat kompresi tidak mengubah hasil. Lihat catatan kelas
         * soal mengapa perubahan resolusi sengaja dibiarkan meleset.
         */
        fun sidik(bmp: Bitmap): String {
            val kecil = Bitmap.createScaledBitmap(bmp, SISI, SISI, true)
            val piksel = IntArray(SISI * SISI)
            kecil.getPixels(piksel, 0, SISI, 0, 0, SISI, SISI)
            if (kecil !== bmp) kecil.recycle()

            val data = ByteArray(piksel.size)
            for (i in piksel.indices) {
                val p = piksel[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Luma BT.601 dalam bilangan bulat, lalu 16 aras.
                val abu = (r * 299 + g * 587 + b * 114) / 1000
                data[i] = (abu / 16).toByte()
            }
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(data).joinToString("") { "%02x".format(it) }
        }

        /** Kunci gabungan; dipisahkan agar bisa diuji tanpa Bitmap. */
        fun kunci(sidik: String, bahasa: String, model: String): String =
            "$sidik|${bahasa.trim().lowercase()}|${model.trim().lowercase()}"

        /** True bila dua rasio aspek cukup dekat untuk dianggap balon yang sama. */
        fun rasioCocok(a: Float, b: Float): Boolean {
            if (a <= 0f || b <= 0f) return false
            val besar = maxOf(a, b)
            val kecil = minOf(a, b)
            return (besar - kecil) / besar <= TOLERANSI_RASIO
        }
    }

    private data class Entri(val teks: String, val rasio: Float, var dipakai: Long)

    private val peta = LinkedHashMap<String, Entri>()
    private var berubah = false

    /** Jumlah entri saat ini. */
    val ukuran: Int get() = peta.size

    /** Statistik proses berjalan: berapa kali kena dan berapa kali meleset. */
    var kena = 0
        private set
    var meleset = 0
        private set

    init {
        muat()
    }

    private fun muat() {
        if (!berkas.exists()) return
        runCatching {
            val obj = JSONObject(berkas.readText())
            if (obj.optInt("versi", 0) != VERSI) return
            val e = obj.optJSONObject("entri") ?: return
            for (k in e.keys()) {
                val o = e.optJSONObject(k) ?: continue
                peta[k] = Entri(
                    o.optString("t", ""),
                    o.optDouble("r", 0.0).toFloat(),
                    o.optLong("u", 0L)
                )
            }
        }
        // Berkas rusak bukan alasan menggagalkan proses: cache adalah
        // pengoptimalan, bukan sumber kebenaran. Mulai saja dari kosong.
    }

    /**
     * Ambil terjemahan tersimpan, atau null.
     *
     * @param rasio lebar/tinggi potongan; penjaga tabrakan.
     */
    @Synchronized
    fun ambil(kunci: String, rasio: Float): String? {
        val e = peta[kunci]
        if (e == null) {
            meleset++
            return null
        }
        if (!rasioCocok(e.rasio, rasio)) {
            // Sidik sama tapi bentuk berbeda: hampir pasti tabrakan. Perlakukan
            // sebagai meleset dan biarkan entri baru menimpanya nanti.
            meleset++
            return null
        }
        e.dipakai = System.currentTimeMillis()
        berubah = true
        kena++
        return e.teks
    }

    /**
     * Simpan satu terjemahan.
     *
     * Teks kosong TIDAK disimpan: hasil kosong berarti model gagal menjawab
     * atau balon itu memang tanpa teks, dan mengunci kegagalan itu ke dalam
     * cache berarti balon tersebut tidak akan pernah diterjemahkan lagi.
     */
    @Synchronized
    fun simpan(kunci: String, teks: String, rasio: Float) {
        if (teks.isBlank()) return
        peta[kunci] = Entri(teks, rasio, System.currentTimeMillis())
        berubah = true
    }

    /** Tulis ke disk bila ada perubahan; pangkas dulu bila kelebihan. */
    @Synchronized
    fun simpanKeDisk() {
        if (!berubah) return
        pangkas()
        runCatching {
            val e = JSONObject()
            for ((k, v) in peta) {
                e.put(k, JSONObject().apply {
                    put("t", v.teks)
                    put("r", v.rasio.toDouble())
                    put("u", v.dipakai)
                })
            }
            val obj = JSONObject().apply {
                put("versi", VERSI)
                put("entri", e)
            }
            // Tulis ke berkas sementara lalu ganti nama: proses yang dibunuh di
            // tengah penulisan tidak boleh meninggalkan cache setengah jadi.
            val tmp = File(berkas.parentFile, berkas.name + ".tmp")
            tmp.writeText(obj.toString())
            if (berkas.exists()) berkas.delete()
            tmp.renameTo(berkas)
        }
        berubah = false
    }

    private fun pangkas() {
        if (peta.size <= MAKS_ENTRI) return
        val urut = peta.entries.sortedBy { it.value.dipakai }
        val buang = peta.size - MAKS_ENTRI
        for (i in 0 until buang) peta.remove(urut[i].key)
    }

    /** Kosongkan cache, di disk maupun di memori. */
    @Synchronized
    fun bersihkan() {
        peta.clear()
        kena = 0
        meleset = 0
        berubah = false
        runCatching { if (berkas.exists()) berkas.delete() }
    }

    /** Ukuran berkas cache di disk, byte. */
    fun byteDiDisk(): Long = if (berkas.exists()) berkas.length() else 0L
}
