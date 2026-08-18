package com.cypy.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Archive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CcZipFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.XZInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** SAF + container (PDF / ZIP / CBZ / RAR / CBR) helpers. */
object Storage {

    /** Height ceiling for a single strip; beyond this we must subsample. */
    const val MAX_STRIP_HEIGHT = 20000

    /** ~24 MP working ceiling (about 96 MB as ARGB_8888) to stay inside largeHeap. */
    const val MAX_PAGE_PIXELS = 24_000_000L

    val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")
    val PDF_EXTS = setOf("pdf")
    val ZIP_EXTS = setOf("zip", "cbz")
    val RAR_EXTS = setOf("rar", "cbr")

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path!!).name
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "input"
    }

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun baseName(name: String): String =
        if (name.contains('.')) name.substringBeforeLast('.') else name

    /** natural_sort_key equivalent. */
    val naturalComparator: Comparator<String> = Comparator { a, b ->
        val re = Regex("(\\d+)")
        val pa = re.split(a.lowercase()); val na = re.findAll(a).map { it.value }.toList()
        val pb = re.split(b.lowercase()); val nb = re.findAll(b).map { it.value }.toList()
        var i = 0
        while (i < max(pa.size, pb.size)) {
            val sa = pa.getOrElse(i) { "" }; val sb = pb.getOrElse(i) { "" }
            val c = sa.compareTo(sb)
            if (c != 0) return@Comparator c
            val da = na.getOrNull(i)?.toBigIntegerOrNull()
            val db = nb.getOrNull(i)?.toBigIntegerOrNull()
            if (da != null && db != null) {
                val c2 = da.compareTo(db)
                if (c2 != 0) return@Comparator c2
            }
            i++
        }
        a.compareTo(b)
    }

    fun copyToCache(ctx: Context, uri: Uri, targetDir: File, fileName: String): File {
        targetDir.mkdirs()
        val out = File(targetDir, fileName)
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open input stream for $uri" }
            FileOutputStream(out).use { input.copyTo(it) }
        }
        return out
    }

    /**
     * Decodes a page, limiting its WIDTH rather than its longest side.
     *
     * Webtoon pages are extremely tall strips (e.g. 1080x11700). Clamping the
     * longest side to 2200 downsampled such a page to 135x1462 — a 98% pixel
     * loss that both blurred the output and left the bubbles far too small for
     * the detector. Height is only capped at a much looser ceiling to protect
     * memory, so a normal page still behaves exactly as before.
     */
    /**
     * The subsampling factor decodeBitmap will use. Pure arithmetic, so the
     * resolution rules can be unit-tested without allocating huge bitmaps.
     */
    fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxSide ||
            height / sample > MAX_STRIP_HEIGHT ||
            (width / sample).toLong() * (height / sample) > MAX_PAGE_PIXELS
        ) sample *= 2
        return sample
    }

    fun decodeBitmap(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun savePng(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Writes bytes into the SAF output tree under <LANG>/name, returns the created Uri. */
    fun writeToTree(
        ctx: Context, treeUri: Uri, subDir: String, fileName: String, mime: String,
        writer: (java.io.OutputStream) -> Unit
    ): Uri? {
        // Plain filesystem destination (also what tests and file:// pickers give us).
        if (treeUri.scheme == "file") {
            val rootDir = treeUri.path?.let { File(it) } ?: return null
            val dir = File(rootDir, subDir).apply { mkdirs() }
            val target = File(dir, fileName)
            target.outputStream().use(writer)
            return Uri.fromFile(target)
        }

        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return null
        val dir = root.findFile(subDir)?.takeIf { it.isDirectory } ?: root.createDirectory(subDir) ?: return null
        dir.findFile(fileName)?.delete()
        val doc = dir.createFile(mime, fileName) ?: return null
        ctx.contentResolver.openOutputStream(doc.uri)?.use(writer) ?: return null
        return doc.uri
    }

    // ---------- PDF ----------

    /** Renders every PDF page to PNG in dir, mirroring the dpi clamp in translator.py. */
    fun renderPdfPages(ctx: Context, pdf: File, dir: File, onProgress: (Int, Int) -> Unit): List<File> {
        dir.mkdirs()
        val out = ArrayList<File>()
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val n = renderer.pageCount
                for (i in 0 until n) {
                    renderer.openPage(i).use { page ->
                        val maxPt = max(page.width, page.height).toFloat()
                        val dpi = if (maxPt > 0) min(300f, max(72f, (2000f / maxPt) * 72f)) else 300f
                        val scale = dpi / 72f
                        val w = max(1, (page.width * scale).roundToInt())
                        val h = max(1, (page.height * scale).roundToInt())
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(dir, String.format("page_%04d.png", i))
                        savePng(bmp, f)
                        bmp.recycle()
                        out.add(f)
                    }
                    onProgress(i + 1, n)
                }
            }
        }
        return out
    }

    /** Combines images into a single PDF, one page per image. */
    fun imagesToPdf(images: List<File>, maxSide: Int, out: java.io.OutputStream) {
        val doc = PdfDocument()
        try {
            for ((idx, f) in images.withIndex()) {
                val bmp = decodeBitmap(f, maxSide) ?: continue
                val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
                bmp.recycle()
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    // ---------- Archives ----------

    /** Raised when an archive cannot be read at all. */
    class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Extracts images from a ZIP/CBZ.
     *
     * java.util.zip only understands STORED and DEFLATE, so comic archives
     * packed with bzip2, LZMA or XZ used to come out empty. The stream is
     * spooled to a temp file first so Commons Compress can read the central
     * directory, which is what makes those methods reachable.
     */
    fun extractZip(input: InputStream, dir: File): List<File> {
        dir.mkdirs()
        val spool = File(dir, ".spool.zip")
        try {
            FileOutputStream(spool).use { input.buffered().copyTo(it) }
            return extractZipFile(spool, dir)
        } finally {
            spool.delete()
        }
    }

    fun extractZipFile(zip: File, dir: File): List<File> {
        dir.mkdirs()
        val files = ArrayList<File>()

        // Pass 1: Commons Compress via the central directory. Handles every
        // method we care about, including the exotic ones.
        try {
            CcZipFile(zip).use { zf ->
                val entries = zf.entries.toList().filter { !it.isDirectory }
                for (e in entries) {
                    val safe = e.name.replace('\\', '/').substringAfterLast('/')
                    if (safe.isBlank() || ext(safe) !in IMAGE_EXTS) continue
                    val out = File(dir, "${files.size}_$safe")
                    val ok = runCatching {
                        if (zf.canReadEntryData(e)) {
                            zf.getInputStream(e).use { ins ->
                                FileOutputStream(out).use { ins.copyTo(it) }
                            }
                            true
                        } else {
                            decodeExoticEntry(zf, e, out)
                        }
                    }.getOrDefault(false)
                    if (ok && out.length() > 0) files.add(out) else out.delete()
                }
            }
        } catch (_: Exception) {
            // Central directory unreadable (truncated / streamed zip): fall through.
        }

        // Pass 2: sequential fallback for archives without a usable directory.
        if (files.isEmpty()) {
            try {
                ZipInputStream(zip.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val safe = entry.name.replace('\\', '/').substringAfterLast('/')
                            if (safe.isNotBlank() && ext(safe) in IMAGE_EXTS) {
                                val out = File(dir, "${files.size}_$safe")
                                runCatching {
                                    FileOutputStream(out).use { zis.copyTo(it) }
                                }
                                if (out.length() > 0) files.add(out) else out.delete()
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) {
                if (files.isEmpty()) throw ArchiveException("Arsip ZIP tidak bisa dibaca: ${e.message}", e)
            }
        }
        return files
    }

    /**
     * Decodes entries Commons Compress declines to stream itself: method 14
     * (LZMA) and method 95 (XZ). Their payload is read raw and fed to the
     * matching tukaani decoder.
     */
    private fun decodeExoticEntry(zf: CcZipFile, e: ZipArchiveEntry, out: File): Boolean {
        val raw = zf.getRawInputStream(e) ?: return false
        return when (e.method) {
            14 -> {
                raw.use { ins ->
                    // ZIP-embedded LZMA header: 2 bytes SDK version,
                    // 2 bytes LE property length, then the properties.
                    val hdr = ByteArray(4)
                    if (ins.read(hdr) != 4) return false
                    val propLen = (hdr[2].toInt() and 0xFF) or ((hdr[3].toInt() and 0xFF) shl 8)
                    val props = ByteArray(propLen)
                    if (ins.read(props) != propLen) return false
                    var dictSize = 0
                    for (i in 0 until 4) dictSize = dictSize or ((props[1 + i].toInt() and 0xFF) shl (8 * i))
                    // Size must be -1: the embedded stream has no end marker
                    // and the declared size makes the decoder throw.
                    LZMAInputStream(ins, -1L, props[0], dictSize).use { lz ->
                        FileOutputStream(out).use { lz.copyTo(it) }
                    }
                }
                true
            }
            95 -> {
                raw.use { ins ->
                    XZInputStream(ins).use { xz ->
                        FileOutputStream(out).use { xz.copyTo(it) }
                    }
                }
                true
            }
            else -> false
        }
    }

    fun extractRar(rar: File, dir: File): List<File> {
        dir.mkdirs()
        val files = ArrayList<File>()
        Archive(rar).use { archive ->
            var header = archive.nextFileHeader()
            var i = 0
            while (header != null) {
                if (!header.isDirectory) {
                    val name = header.fileName.replace('\\', '/').substringAfterLast('/')
                    if (name.isNotBlank() && ext(name) in IMAGE_EXTS) {
                        val out = File(dir, "${i}_$name")
                        FileOutputStream(out).use { archive.extractFile(header, it) }
                        files.add(out)
                        i++
                    }
                }
                header = archive.nextFileHeader()
            }
        }
        return files
    }
}
