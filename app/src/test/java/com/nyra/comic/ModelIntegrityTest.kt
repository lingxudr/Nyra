package com.nyra.comic

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Integritas berkas model lewat jalur produksi yang sebenarnya.
 *
 * ModelManagerTest membuktikan aturannya dengan hash yang disuntikkan.
 * Yang ini memakai berkas sungguhan di filesDir dan perhitungan SHA-256
 * sungguhan milik ModelDownloader, memakai jalur berkas yang persis dipakai
 * Inpainter dan FontPack — supaya kesalahan penamaan berkas atau direktori
 * tidak lolos hanya karena tesnya memakai jalur karangan sendiri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ModelIntegrityTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun tulis(f: File, isi: ByteArray) {
        f.parentFile?.mkdirs()
        f.writeBytes(isi)
    }

    @Test
    fun `berkas LaMa palsu berukuran tepat lolos periksa tapi gagal verifikasi`() {
        // Inilah lubangnya: sebelum ronde 24, aplikasi hanya pernah bertanya
        // "apakah ukurannya tepat" dan langsung menjalankan isinya sebagai
        // graf ONNX.
        val f = Inpainter.berkas(ctx)
        f.parentFile?.mkdirs()

        // Berkas dibuat tepat sepanjang UKURAN, ditulis per blok supaya tes
        // tidak mengalokasikan 93 MB sekaligus di JVM yang RAM-nya terbatas.
        f.outputStream().use { out ->
            var sisa = Inpainter.UKURAN
            val blok = ByteArray(65536)
            while (sisa > 0) {
                val n = minOf(sisa, blok.size.toLong()).toInt()
                out.write(blok, 0, n)
                sisa -= n
            }
        }
        assertEquals(Inpainter.UKURAN, f.length())
        assertTrue("cek lama hanya melihat ukuran", Inpainter.tersedia(ctx))

        val murah = ModelManager.periksa("lama", "LaMa", f, Inpainter.UKURAN)
        assertEquals(ModelManager.Status.TERPASANG, murah.status)

        val teliti = ModelManager.verifikasi(
            "lama", "LaMa", f, Inpainter.UKURAN, Inpainter.SHA256
        )
        assertEquals(ModelManager.Status.RUSAK, teliti.status)
        assertFalse(teliti.bisaDipakai)
        assertNotEquals(Inpainter.SHA256, teliti.hashTerhitung)

        f.delete()
    }

    @Test
    fun `hash sungguhan atas berkas kecil cocok dengan nilai yang diharapkan`() {
        // Membuktikan ModelManager.verifikasi memakai perhitungan sungguhan
        // dari ujung ke ujung, bukan hash yang disuntikkan tes.
        val f = File(ctx.filesDir, "contoh.bin")
        tulis(f, "nyra".toByteArray())
        val hash = ModelDownloader.sha256(f)

        val l = ModelManager.verifikasi("x", "X", f, f.length(), hash)
        assertEquals(ModelManager.Status.TERVERIFIKASI, l.status)
        assertEquals(hash, l.hashTerhitung)
        assertEquals(64, hash.length)
        f.delete()
    }

    @Test
    fun `sisa unduhan font terhitung sebagai penyimpanan terpakai`() {
        // Sisa .part yang gagal memakan puluhan MB tanpa pernah terlihat
        // pengguna sebelum layar ini ada.
        val item = FontPack.SEMUA.first()
        val target = FontPack.berkas(ctx, item)
        val part = File(target.parentFile, "${target.name}.part")
        tulis(part, ByteArray(4096))

        val l = ModelManager.periksa(item.id, item.judul, target, item.ukuran)
        assertEquals(ModelManager.Status.SEPARUH, l.status)
        assertEquals(4096L, l.byteTerpakai)
        assertTrue(l.perluUnduh)

        part.delete()
    }

    @Test
    fun `membuang berkas rusak membebaskan berkas utuh dan sisa part sekaligus`() {
        val f = Inpainter.berkas(ctx)
        tulis(f, ByteArray(2048))
        val part = File(f.parentFile, "${Inpainter.NAMA}.part")
        tulis(part, ByteArray(1024))

        val bebas = ModelDownloader.hapus(ctx)
        assertEquals(3072L, bebas)
        assertFalse(f.exists())
        assertFalse(part.exists())

        val l = ModelManager.periksa("lama", "LaMa", f, Inpainter.UKURAN)
        assertEquals(ModelManager.Status.BELUM_ADA, l.status)
        assertEquals(0L, l.byteTerpakai)
    }

    @Test
    fun `hash paket font yang tercatat berupa sha256 yang sah`() {
        // Nilai yang salah bentuk hanya akan ketahuan setelah pengguna
        // mengunduh berkas 8 MB dan gagal di gerbang terakhir.
        for (item in FontPack.SEMUA) {
            assertEquals("${item.id}: panjang hash", 64, item.sha256.length)
            assertTrue(
                "${item.id}: hash bukan heksadesimal",
                item.sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            )
            assertTrue("${item.id}: ukuran tidak masuk akal", item.ukuran > 1000L)
            assertTrue("${item.id}: alamat bukan https", item.alamat.startsWith("https://"))
        }
    }

    @Test
    fun `hash LaMa yang tercatat berupa sha256 yang sah`() {
        assertEquals(64, Inpainter.SHA256.length)
        assertTrue(Inpainter.SHA256.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(ModelDownloader.URL_LAMA.startsWith("https://"))
    }
}
