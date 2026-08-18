package com.cypy.manga

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import java.io.File

/**
 * Berkas proyek harus bertahan utuh melintasi tulis-baca.
 *
 * Yang disimpan di sini adalah hasil kerja termahal dalam aplikasi: kotak
 * deteksi (satu inferensi ONNX per halaman) dan terjemahan (panggilan LLM
 * berbayar). Kalau satu bidang saja hilang saat dibaca ulang, mode koreksi
 * manual justru memaksa pengguna membayar semuanya lagi — persis masalah yang
 * hendak dihapus fitur ini.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class ProjectTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Project.rootDir(ctx).deleteRecursively()
    }

    private fun contoh(id: String = "prj_uji"): Project {
        val p = Project(
            id = id, name = "Bab 1", targetLanguage = "indonesian",
            createdAt = 1000L, updatedAt = 2000L
        )
        val page = Project.Page("hal1.png", "pages/p0000.png", 800, 1200)
        page.boxes.add(intArrayOf(10, 20, 110, 90))
        page.boxes.add(intArrayOf(200, 300, 400, 460))
        page.translations["1"] = "Halo dunia"
        page.translations["2"] = "Baris satu\nBaris dua"
        page.sourceText["1"] = "HELLO WORLD"
        page.colors["10,20,110,90"] = Palette.Colors(
            Color.WHITE, Color.BLACK, true, listOf(Color.RED, Color.BLUE)
        )
        page.freeText.add("200,300,400,460")
        p.pages.add(page)
        return p
    }

    @Test
    fun roundTripsEverySavedField() {
        val asli = contoh()
        val ulang = Project.fromJson(JSONObject(asli.toJson().toString()))

        assertEquals(asli.id, ulang.id)
        assertEquals(asli.name, ulang.name)
        assertEquals(asli.targetLanguage, ulang.targetLanguage)
        assertEquals(asli.createdAt, ulang.createdAt)
        assertEquals(1, ulang.pages.size)

        val a = asli.pages[0]
        val u = ulang.pages[0]
        assertEquals(a.srcName, u.srcName)
        assertEquals(a.imagePath, u.imagePath)
        assertEquals(a.width, u.width)
        assertEquals(a.height, u.height)
        assertEquals(a.boxes.size, u.boxes.size)
        for (i in a.boxes.indices) {
            assertEquals("kotak $i", a.boxes[i].toList(), u.boxes[i].toList())
        }
        // Terjemahan multi-baris adalah kasus nyata: LLM sering memecah
        // kalimat, dan "\n" yang hilang mengubah tata letak balon.
        assertEquals(a.translations, u.translations)
        assertEquals(a.sourceText, u.sourceText)
        assertEquals(a.freeText, u.freeText)

        val ca = a.colors["10,20,110,90"]!!
        val cu = u.colors["10,20,110,90"]!!
        assertEquals(ca.background, cu.background)
        assertEquals(ca.foreground, cu.foreground)
        assertEquals(ca.diukur, cu.diukur)
        // warnaBaris adalah hasil ronde 16; kehilangannya diam-diam
        // mengembalikan bug "semua teks satu warna".
        assertEquals(ca.warnaBaris, cu.warnaBaris)
    }

    @Test
    fun savesAndLoadsFromDisk() {
        val p = contoh("prj_disk")
        p.save(ctx)
        assertTrue(File(Project.dirFor(ctx, "prj_disk"), "project.json").exists())

        val dimuat = Project.load(ctx, "prj_disk")
        assertNotNull(dimuat)
        assertEquals("Bab 1", dimuat!!.name)
        assertEquals("Halo dunia", dimuat.pages[0].translations["1"])
        // save() memperbarui cap waktu, jadi urutan daftar tetap masuk akal.
        assertTrue(dimuat.updatedAt >= 2000L)
    }

    @Test
    fun listOrdersNewestFirstAndDeleteRemoves() {
        val a = contoh("prj_a"); a.save(ctx)
        Thread.sleep(5)
        val b = contoh("prj_b"); b.name = "Bab 2"; b.save(ctx)

        val daftar = Project.list(ctx)
        assertEquals(2, daftar.size)
        assertEquals("proyek terbaru harus di atas", "prj_b", daftar[0].id)

        Project.delete(ctx, "prj_b")
        assertNull(Project.load(ctx, "prj_b"))
        assertEquals(1, Project.list(ctx).size)
    }

    @Test
    fun pruneKeepsOnlyTheNewest() {
        // Proyek menyimpan salinan halaman penuh; tanpa pemangkasan folder ini
        // tumbuh diam-diam sampai memenuhi penyimpanan telepon.
        for (i in 1..6) {
            val p = contoh("prj_$i")
            p.save(ctx)
            Thread.sleep(3)
        }
        assertEquals(6, Project.list(ctx).size)

        Project.prune(ctx, maks = 3)

        val sisa = Project.list(ctx)
        assertEquals(3, sisa.size)
        assertEquals("yang tersisa harus yang terbaru", listOf("prj_6", "prj_5", "prj_4"),
            sisa.map { it.id })
    }

    @Test
    fun loadReturnsNullOnMissingOrCorruptFile() {
        assertNull("proyek tak ada harus null, bukan crash", Project.load(ctx, "tidak_ada"))

        val d = Project.dirFor(ctx, "rusak").apply { mkdirs() }
        File(d, "project.json").writeText("{ini bukan json")
        assertNull("berkas rusak harus null, bukan crash", Project.load(ctx, "rusak"))
        // Proyek rusak tidak boleh menjatuhkan seluruh daftar.
        assertEquals(0, Project.list(ctx).size)
    }
}
