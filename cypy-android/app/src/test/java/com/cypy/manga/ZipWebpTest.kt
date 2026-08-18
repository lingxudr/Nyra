package com.cypy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the archive path that broke on a real 59-page user upload: a CBZ whose
 * WEBP pages were packed with a compression method java.util.zip cannot read.
 *
 * Each fixture holds the same 15 pages, packed a different way. Anything that
 * extracts fewer than 15 images means pages would silently vanish from the
 * translated output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZipWebpTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name).readBytes()
        val f = tmp.newFile(name.substringAfterLast('/'))
        f.writeBytes(bytes)
        return f
    }

    private fun extract(resName: String): List<File> {
        val zip = resource(resName)
        val out = tmp.newFolder(resName.substringAfterLast('/').removeSuffix(".zip") + "_out")
        return Storage.extractZipFile(zip, out)
    }

    private fun assertAllPages(files: List<File>) {
        assertEquals("expected all 15 pages to be extracted", 15, files.size)
        for (f in files) {
            assertTrue("extracted file is empty: ${f.name}", f.length() > 0)
            assertTrue("not a webp: ${f.name}", f.name.endsWith(".webp"))
        }
    }

    @Test
    fun deflateZipExtractsEveryPage() = assertAllPages(extract("zip/aa_deflate.zip"))

    @Test
    fun storedZipExtractsEveryPage() = assertAllPages(extract("zip/aa_stored.zip"))

    /** Method 12. Unsupported by java.util.zip — the original round-3 bug. */
    @Test
    fun bzip2ZipExtractsEveryPage() = assertAllPages(extract("zip/aa_bzip2.zip"))

    /** Method 14. Needs getRawInputStream plus a hand-parsed LZMA header. */
    @Test
    fun lzmaZipExtractsEveryPage() = assertAllPages(extract("zip/aa_lzma.zip"))

    /** Nested directories must be flattened, not dropped. */
    @Test
    fun subfolderZipExtractsEveryPage() {
        val files = extract("zip/aa_folder.zip")
        assertAllPages(files)
        assertTrue("paths should be flattened", files.none { it.name.contains('/') })
    }

    /** Names are prefixed with an index, so page order survives extraction. */
    @Test
    fun extractedNamesKeepOriginalPageOrder() {
        val files = extract("zip/aa_deflate.zip")
        val sorted = files.map { it.name }.sortedWith(Storage.naturalComparator)
        assertEquals(files.map { it.name }, sorted)
        assertTrue(files.first().name.endsWith("001.webp"))
        assertTrue(files.last().name.endsWith("015.webp"))
    }

    /** An archive with no images yields an empty list rather than throwing. */
    @Test
    fun archiveWithoutImagesYieldsNothing() {
        assertEquals(0, extract("zip/aa_notimages.zip").size)
    }
}
