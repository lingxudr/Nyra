package com.cypy.manga

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * Parity test: replays 120 randomized cases whose expected output was produced by
 * running the ORIGINAL Python implementation (cypy/core/services/image_service.py).
 * Any divergence in the ported box filtering math fails the build.
 */
class BoxParityTest {

    private fun load(): JSONArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("boxes.json")
        return JSONArray(stream.bufferedReader().readText())
    }

    private fun toBoxes(arr: JSONArray): List<IntArray> =
        (0 until arr.length()).map { i ->
            val b = arr.getJSONArray(i)
            intArrayOf(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
        }

    private fun fmt(list: List<IntArray>) = list.joinToString(" ") { it.joinToString(",") }

    @Test
    fun boxPipelineMatchesPythonReference() {
        val cases = load()
        var giantMismatch = 0
        var mergeMismatch = 0
        var absurdMismatch = 0
        var cropMismatch = 0
        val firstFailures = StringBuilder()

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val w = c.getInt("W")
            val h = c.getInt("H")
            val input = toBoxes(c.getJSONArray("inp"))

            val giants = BoxUtils.dropFakeGiants(input)
            val expGiants = toBoxes(c.getJSONArray("giants"))
            if (fmt(giants) != fmt(expGiants)) {
                giantMismatch++
                if (firstFailures.length < 1200)
                    firstFailures.append("case $i giants:\n  kt =${fmt(giants)}\n  py =${fmt(expGiants)}\n")
            }

            val merged = BoxUtils.mergeOverlapping(giants)
            val expMerged = toBoxes(c.getJSONArray("merged"))
            if (fmt(merged) != fmt(expMerged)) {
                mergeMismatch++
                if (firstFailures.length < 1200)
                    firstFailures.append("case $i merged:\n  kt =${fmt(merged)}\n  py =${fmt(expMerged)}\n")
            }

            val absurd = BoxUtils.dropAbsurd(merged, w, h)
            val expAbsurd = toBoxes(c.getJSONArray("absurd"))
            if (fmt(absurd) != fmt(expAbsurd)) {
                absurdMismatch++
                if (firstFailures.length < 1200)
                    firstFailures.append("case $i absurd:\n  kt =${fmt(absurd)}\n  py =${fmt(expAbsurd)}\n")
            }

            val expCrops = toBoxes(c.getJSONArray("crops"))
            val gotCrops = absurd.map { b ->
                val bw = max(1, b[2] - b[0])
                val bh = max(1, b[3] - b[1])
                val px = max(35, (bw * 0.40f).toInt())
                val py = max(35, (bh * 0.25f).toInt())
                BoxUtils.roomyCrop(b, absurd, w, h, px, py, 0.35f)
            }
            if (fmt(gotCrops) != fmt(expCrops)) {
                cropMismatch++
                if (firstFailures.length < 1200)
                    firstFailures.append("case $i crops:\n  kt =${fmt(gotCrops)}\n  py =${fmt(expCrops)}\n")
            }
        }

        println("parity: giants=$giantMismatch merge=$mergeMismatch absurd=$absurdMismatch crops=$cropMismatch of ${cases.length()}")
        assertEquals("dropFakeGiants diverges from Python\n$firstFailures", 0, giantMismatch)
        assertEquals("mergeOverlapping diverges from Python\n$firstFailures", 0, mergeMismatch)
        assertEquals("dropAbsurd diverges from Python\n$firstFailures", 0, absurdMismatch)
        assertEquals("roomyCrop diverges from Python\n$firstFailures", 0, cropMismatch)
    }

    /**
     * Regresi ronde 11 bug 1: satu kotak cacat (lebar negatif, luas 0) tidak
     * boleh menghapus balon sah. Kotak [0,1138,-3,1940] betulan keluar dari
     * detektor pada halaman pengguna dan memusnahkan seluruh halaman.
     */
    @Test
    fun kotakCacatTidakMenghapusBalonSah() {
        val mentah = listOf(
            intArrayOf(219, 205, 855, 788),
            intArrayOf(0, 1138, -3, 1940),
            intArrayOf(1, 1, 3, 900)
        )
        val bersih = BoxUtils.sanitize(mentah, 1080, 1940)
        assertEquals("kotak cacat harus dibuang", 1, bersih.size)
        assertEquals(219, bersih[0][0])

        val hidup = BoxUtils.dropFakeGiants(bersih)
        assertEquals("balon sah harus selamat", 1, hidup.size)

        // Tanpa sanitize, aturan lama menghapus segalanya. Buktikan bahwa
        // penjaga area<=0 di dropFakeGiants juga menahannya sendirian.
        assertTrue(BoxUtils.dropFakeGiants(mentah).any { it[0] == 219 })
    }

    /**
     * Regresi ronde 11 bug 2: balon berduri (STOOOP!, ARGH!, SCRIIII) tidak
     * boleh dibuang gara-gara menelan pecahan durinya sendiri. Angka di bawah
     * adalah kotak asli dari halaman STOOOP! pengguna.
     */
    @Test
    fun balonBerduriSelamatDariDropFakeGiants() {
        val balon = intArrayOf(291, 585, 878, 1553)
        val duriA = intArrayOf(320, 590, 470, 640)
        val duriB = intArrayOf(330, 600, 480, 655)
        val hidup = BoxUtils.dropFakeGiants(listOf(balon, duriA, duriB))
        assertTrue("balon berduri harus selamat", hidup.any { it.contentEquals(balon) })

        // Raksasa palsu sungguhan -- membungkus dua balon yang saling terpisah
        // di ujung berlawanan -- tetap harus dibuang.
        val raksasa = intArrayOf(0, 0, 1000, 1000)
        val kiri = intArrayOf(20, 20, 220, 220)
        val kanan = intArrayOf(700, 700, 900, 900)
        val hasil = BoxUtils.dropFakeGiants(listOf(raksasa, kiri, kanan))
        assertTrue("raksasa palsu harus dibuang", hasil.none { it.contentEquals(raksasa) })
        assertEquals(2, hasil.size)
    }

    @Test
    fun cleanJsonHandlesRealModelOutput() {
        val fenced = "```json\n{\"1\": \"Halo!\", \"2\": \"SKIP\"}\n```"
        assertEquals("{\"1\": \"Halo!\", \"2\": \"SKIP\"}", BoxUtils.cleanJson(fenced))

        val chatty = "Sure! Here you go:\n{\"1\": \"Cepat bangun!\"}\nHope that helps."
        val obj = JSONObject(BoxUtils.cleanJson(chatty))
        assertEquals("Cepat bangun!", obj.getString("1"))

        val bare = "{\"12\": \"Ibu... tunggu...\"}"
        assertTrue(JSONObject(BoxUtils.cleanJson(bare)).has("12"))
    }
}
