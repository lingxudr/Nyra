package com.nyra.comic

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Melanjutkan arsip yang terhenti di tengah jalan.
 *
 * Menerjemahkan satu bab 200 halaman berarti ratusan permintaan berbayar yang
 * berjalan belasan menit. Kalau prosesnya putus - pengguna menekan Stop,
 * kuota habis, jaringan mati, sistem membunuh aplikasi - seluruh biaya itu
 * hangus, karena `workRoot` dihapus di blok `finally` dan jalan berikutnya
 * memulai dari halaman pertama lagi.
 *
 * Titik simpan menghindarinya: setiap halaman yang SUDAH jadi disalin ke
 * folder permanen, dan jalan berikutnya atas arsip yang sama melewatinya tanpa
 * mendeteksi maupun menerjemahkan ulang.
 *
 * Kuncinya harus ikut memuat apa pun yang mengubah keluaran. Nama arsip saja
 * berbahaya: pengguna yang mengulang bab dengan bahasa sasaran berbeda akan
 * mendapat halaman lama dalam bahasa lama, dan kelihatannya seperti aplikasi
 * yang mengabaikan pilihannya.
 */
object Resume {

    private const val VERSION = 1

    /** Berapa arsip yang titik simpannya disimpan sebelum yang terlama dibuang. */
    const val MAX_ARSIP = 5

    /**
     * Kunci titik simpan. Murni supaya bisa diuji tanpa perangkat.
     *
     * [ukuran] ikut dihitung agar berkas berbeda yang kebetulan bernama sama
     * tidak saling mewarisi hasil - kasus yang lazim, karena bab manga sering
     * bernama `01.cbz` di setiap serinya.
     */
    fun kunci(nama: String, ukuran: Long, bahasa: String): String {
        val bersih = nama.lowercase().replace(Regex("[^a-z0-9._-]"), "_").take(48)
        val cap = "$nama|$ukuran|${bahasa.lowercase()}|$VERSION".hashCode()
        return bersih + "_" + Integer.toHexString(cap)
    }

    /** Satu titik simpan: nama halaman sumber -> berkas keluaran permanen. */
    class Titik(val dir: File, val selesai: MutableMap<String, File> = LinkedHashMap()) {
        val jumlah: Int get() = selesai.size
    }

    private fun berkasIndeks(dir: File) = File(dir, "index.json")

    fun dirUntuk(ctx: Context, kunci: String): File =
        File(File(ctx.filesDir, "resume"), kunci)

    /**
     * Baca titik simpan yang ada. Entri yang berkasnya sudah hilang dibuang,
     * jadi hasilnya selalu bisa dipakai apa adanya.
     */
    fun muat(ctx: Context, kunci: String): Titik {
        val dir = dirUntuk(ctx, kunci)
        val titik = Titik(dir)
        val idx = berkasIndeks(dir)
        if (!idx.isFile) return titik
        try {
            val obj = JSONObject(idx.readText())
            if (obj.optInt("version") != VERSION) return titik
            val hal = obj.optJSONObject("pages") ?: return titik
            for (k in hal.keys()) {
                val f = File(dir, hal.getString(k))
                if (f.isFile && f.length() > 0) titik.selesai[k] = f
            }
        } catch (_: Exception) {
            // Indeks rusak: perlakukan seperti belum ada titik simpan sama sekali.
            return Titik(dir)
        }
        return titik
    }

    /**
     * Catat satu halaman yang sudah selesai.
     *
     * [keluaran] masih berada di folder kerja yang akan dihapus, jadi isinya
     * disalin. Mengembalikan false bila penyalinan gagal - pemanggil tetap
     * boleh melanjutkan, hanya kehilangan kemampuan melanjutkan halaman itu.
     */
    fun catat(titik: Titik, namaSumber: String, keluaran: File): Boolean {
        return try {
            titik.dir.mkdirs()
            val tujuan = File(titik.dir, "p%04d.png".format(titik.selesai.size))
            keluaran.copyTo(tujuan, overwrite = true)
            titik.selesai[namaSumber] = tujuan
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Tulis indeks ke disk lewat berkas sementara supaya tidak pernah separuh. */
    fun simpan(titik: Titik) {
        try {
            titik.dir.mkdirs()
            val hal = JSONObject()
            for ((k, v) in titik.selesai) hal.put(k, v.name)
            val obj = JSONObject()
                .put("version", VERSION)
                .put("updatedAt", System.currentTimeMillis())
                .put("pages", hal)
            val tmp = File(titik.dir, "index.json.tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(berkasIndeks(titik.dir))
        } catch (_: Exception) {
        }
    }

    /** Hapus titik simpan; dipanggil setelah arsipnya benar-benar tuntas. */
    fun bersihkan(titik: Titik) {
        runCatching { titik.dir.deleteRecursively() }
    }

    /**
     * Batasi jumlah titik simpan yang tersimpan, buang yang paling lama
     * disentuh. Tanpa ini folder resume tumbuh tanpa batas di ponsel.
     */
    fun prune(ctx: Context, maks: Int = MAX_ARSIP) {
        val akar = File(ctx.filesDir, "resume")
        val anak = akar.listFiles()?.filter { it.isDirectory } ?: return
        if (anak.size <= maks) return
        anak.sortedByDescending { berkasIndeks(it).lastModified() }
            .drop(maks)
            .forEach { runCatching { it.deleteRecursively() } }
    }
}
