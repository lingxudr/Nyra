package com.nyra.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of cypy/core/translator.py — the whole translation pipeline:
 * detect -> crop -> mosaic -> LLM -> write text back -> save.
 */
class Pipeline(
    private val ctx: Context,
    private val cfg: Config,
    private val log: (String) -> Unit,
    private val progress: (Int, Int) -> Unit
) {

    @Volatile var cancelled = false

    private val renderer = TextRenderer(ctx)
    private val rateLimiter = RateLimiter(cfg) { log(it) }
    private var detector: YoloDetector? = null
    private var textDetector: TextRegionDetector? = null
    private var rtDetector: RtDetector? = null

    /**
     * Warna latar/teks per kotak, hasil pengukuran RT-DETR + Palette.
     * Kunci = identitas kotak (lihat kunciKotak). Kosong berarti pakai
     * putih/hitam seperti perilaku lama.
     */
    private val warnaKotak = HashMap<String, Palette.Colors>()

    /**
     * Gaya tipografi terukur per kotak (ronde 25).
     *
     * Diisi bersamaan dengan [warnaKotak] saat deteksi RT-DETR, dari kotak
     * teks asli kelas text_bubble. Sama seperti warna, isinya milik SATU
     * bagian halaman saja dan harus disalin ke PartEntry begitu deteksi
     * selesai — lihat catatan panjang di PartEntry.warna.
     */
    private val gayaKotak = HashMap<String, Typography.Gaya>()

    /**
     * Seams for instrumentation. In production these stay null and the real
     * ONNX detector / provider factory are used.
     */
    var detectorOverride: ((Bitmap) -> List<IntArray>)? = null

    /** Seam untuk detektor teks lepas-balon. */
    var textDetectorOverride: ((Bitmap) -> List<IntArray>)? = null

    /** Seam untuk detektor tiga-kelas RT-DETR. */
    var rtDetectorOverride: ((Bitmap) -> List<RtDetector.Det>)? = null

    /**
     * Seam uji inpaint. Dipanggil dengan kotak-kotak yang AKAN dihapus LaMa
     * pada satu bagian halaman, tepat sebelum model dijalankan.
     *
     * Ada karena onnxruntime tidak punya pustaka native di unit test, jadi
     * Inpainter sungguhan tidak bisa dimuat — padahal yang perlu diuji justru
     * PEMILIHAN sasarannya, bukan kualitas hasil hapusnya. Mengembalikan
     * jumlah petak (meniru Inpainter.erase) supaya jalur log tetap teruji.
     */
    var inpaintOverride: ((List<IntArray>) -> Int)? = null

    /**
     * Proyek yang sedang direkam, atau null bila perekaman dimatikan.
     *
     * Diisi di run() dan ditulis ke disk setiap kali satu berkas masukan
     * selesai, bukan sekali di akhir: proses bab panjang bisa dihentikan
     * pengguna atau dibunuh sistem, dan hasil yang sudah dibayar (deteksi +
     * panggilan LLM) tidak boleh ikut hilang.
     */
    private var project: Project? = null

    /** Proyek hasil proses terakhir; dipakai UI untuk membuka editor. */
    var lastProject: Project? = null
        private set
    var providerOverride: LLMProvider? = null

    /**
     * Glosarium yang dipakai untuk seluruh proses ini. Dibaca sekali di awal
     * agar berkas tidak dibuka ulang untuk setiap mosaik. Tes menyuntikkannya
     * langsung lewat seam ini.
     */
    var glossaryOverride: List<Glossary.Entry>? = null
    private var glossary: List<Glossary.Entry> = emptyList()

    /** Riwayat terjemahan halaman sebelumnya (konsistensi lintas halaman). */
    internal val pageContext = PageContext()

    /**
     * Kunci kotak yang berasal dari teks DI LUAR balon. Hanya kotak-kotak ini
     * yang dilewatkan ke inpaint: di dalam balon putih polos, isi-putih sudah
     * sempurna dan jauh lebih cepat.
     *
     * Isinya milik SATU bagian halaman saja dan disalin ke [PartEntry.lepas]
     * segera setelah detectBoxes() selesai — persis seperti [warnaKotak].
     * Lihat catatan panjang di PartEntry.lepas untuk alasannya.
     */
    private val teksLepasKotak = HashSet<String>()

    /**
     * Blok teks di dalam balon pada halaman yang sedang dideteksi.
     *
     * Diisi oleh jalur RT-DETR (kelas text_bubble) atau detektor teks, dan
     * dipakai ReadingDirection untuk menilai apakah tulisannya tegak
     * (tategaki, ciri manga Jepang) atau mendatar. Seperti warnaKotak, isinya
     * milik satu bagian halaman saja.
     */
    private val blokTeksKotak = ArrayList<IntArray>()

    /**
     * Arah baca yang dipakai halaman terakhir yang punya bukti meyakinkan.
     *
     * Menjadi cadangan untuk halaman berikutnya: satu bab konsisten arahnya,
     * jadi halaman sampul tanpa balon atau halaman berbalon-tunggal sebaiknya
     * mengikuti tetangganya, bukan jatuh kembali ke sakelar manual.
     */
    private var arahTerakhir: Boolean? = null

    /** Ringkasan arah baca untuk log akhir: berapa halaman RTL vs LTR. */
    private var hitungRtl = 0
    private var hitungLtr = 0

    /** Akumulator token/biaya untuk satu proses. */
    private val penghitung = Usage.Penghitung()

    /**
     * Cache terjemahan per balon; null bila sakelarnya mati.
     *
     * Dibuat malas di run() supaya berkasnya tidak disentuh sama sekali ketika
     * fitur dimatikan, dan supaya tes bisa menyuntik lewat cacheOverride.
     */
    private var cache: TranslationCache? = null

    /** Seam uji: cache yang dipakai alih-alih berkas di filesDir. */
    var cacheOverride: TranslationCache? = null

    /** Ringkasan pemakaian proses terakhir; dibaca UI setelah run() selesai. */
    var pemakaianTerakhir: Usage.Pakai = Usage.Pakai(0, 0, false)
        private set
    var biayaTerakhir: Double = 0.0
        private set
    var cacheKenaTerakhir: Int = 0
        private set

    private var inpainter: Inpainter? = null

    data class OutputItem(val name: String, val uri: Uri?, val localPath: String?)

    class Result(
        val outputs: MutableList<OutputItem> = mutableListOf(),
        var success: Int = 0,
        var failed: Int = 0,
        var total: Int = 0,
        var error: String? = null,
        /** Token yang benar-benar ditagih selama proses ini. */
        var pemakaian: Usage.Pakai = Usage.Pakai(0, 0, false),
        /** Perkiraan biaya USD; 0.0 bila tarif model tidak dikenal. */
        var biaya: Double = 0.0,
        /** Berapa balon dijawab dari cache alih-alih dari API. */
        var cacheKena: Int = 0
    )

    private fun yolo(): YoloDetector {
        detector?.let { return it }
        log("Loading detection model...")
        val d = YoloDetector(ctx)
        detector = d
        return d
    }

    private fun ocr(): TextRegionDetector? {
        textDetector?.let { return it }
        return runCatching {
            log("Memuat model detektor teks...")
            TextRegionDetector(ctx).also { textDetector = it }
        }.getOrElse {
            // Model teks bersifat tambahan: kalau gagal dimuat, terjemahan
            // balon harus tetap jalan seperti biasa.
            log("  [!] Detektor teks tidak bisa dimuat: ${it.message}")
            null
        }
    }

    /**
     * RT-DETR bersifat tambahan: kalau asetnya tidak ada atau gagal dimuat,
     * pipeline harus jatuh kembali ke YOLOv8 tanpa menggagalkan pekerjaan.
     */
    private fun rt(): RtDetector? {
        rtDetector?.let { return it }
        if (!RtDetector.tersedia(ctx)) return null
        return runCatching {
            log("Memuat detektor komik RT-DETR...")
            RtDetector(ctx).also { rtDetector = it }
        }.getOrElse {
            log("  [!] RT-DETR tidak bisa dimuat: ${it.message}")
            null
        }
    }

    fun close() {
        detector?.close()
        detector = null
        textDetector?.close()
        textDetector = null
        rtDetector?.close()
        rtDetector = null
        inpainter?.close()
        inpainter = null
        bebaskanRujukan()
        warnaKotak.clear()
        gayaKotak.clear()
    }

    fun cancel() {
        cancelled = true
        rateLimiter.cancelled = true
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    fun run(inputs: List<Uri>, targetLanguage: String, outputTree: Uri): Result {
        val result = Result()
        val started = System.currentTimeMillis()
        val provider = providerOverride ?: LLMProvider.create(cfg)

        log("Provider: ${provider.providerName} (${provider.modelName})")
        log("Target language: $targetLanguage")
        muatGlosarium()

        hitungRtl = 0
        hitungLtr = 0
        arahTerakhir = null

        cache = cacheOverride ?: if (cfg.cacheTerjemahan) {
            runCatching { TranslationCache(TranslationCache.bawaan(ctx.filesDir)) }
                .onFailure { log("  [!] Cache terjemahan tidak bisa dibuka: ${it.message}") }
                .getOrNull()
        } else null
        cache?.let { if (it.ukuran > 0) log("Cache: ${it.ukuran} balon tersimpan.") }

        val workRoot = File(ctx.cacheDir, "nyra_work_${System.currentTimeMillis()}")
        workRoot.mkdirs()

        // Proyek dibuat di awal supaya halaman bisa direkam sambil jalan.
        // Namanya diambil dari masukan pertama — itu yang dikenali pengguna
        // di daftar, bukan cap waktu.
        if (cfg.simpanProyek) {
            val judul = inputs.firstOrNull()
                ?.let { runCatching { Storage.displayName(ctx, it) }.getOrNull() }
                ?: "Proyek"
            project = Project(
                id = Project.newId(),
                name = if (inputs.size > 1) "$judul (+${inputs.size - 1})" else judul,
                targetLanguage = targetLanguage,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        }

        try {
            // Gambar lepas yang berurutan digabung menjadi SATU batch.
            //
            // Dulu tiap berkas diproses sendiri-sendiri, jadi memilih 49
            // halaman berarti 49 batch berisi satu balon-set masing-masing.
            // Batch sekecil itu tidak pernah punya lebih dari satu chunk,
            // sehingga gelombang paralel tidak punya apa pun untuk
            // dijalankan bersamaan dan setelan requestParalel tidak
            // berpengaruh sama sekali. Menggabungkannya juga membuat
            // halaman-halaman itu berbagi konteks dan rujukan seperti bab
            // sungguhan. Arsip dan PDF tetap diproses satu per satu karena
            // masing-masing sudah merupakan batch tersendiri.
            val kelompok = ArrayList<Pair<String, List<Pair<Uri, String>>>>()
            for (uri in inputs) {
                val name = Storage.displayName(ctx, uri)
                val e = Storage.ext(name)
                val terakhir = kelompok.lastOrNull()
                if (e in Storage.IMAGE_EXTS && terakhir != null && terakhir.first == "img") {
                    (terakhir.second as ArrayList<Pair<Uri, String>>).add(uri to name)
                } else {
                    kelompok.add(
                        (if (e in Storage.IMAGE_EXTS) "img" else e) to
                            arrayListOf(uri to name)
                    )
                }
            }

            var nomor = 0
            for ((idx, grup) in kelompok.withIndex()) {
                if (cancelled) break
                val (jenis, isi) = grup
                val e = if (jenis == "img") Storage.IMAGE_EXTS.first() else jenis
                log("")
                if (jenis == "img" && isi.size > 1) {
                    nomor += isi.size
                    log("[${nomor - isi.size + 1}-$nomor/${inputs.size}] ${isi.size} gambar lepas")
                } else {
                    nomor++
                    log("[$nomor/${inputs.size}] ${isi[0].second}")
                }

                // Riwayat direset hanya di batas bab yang sebenarnya, yaitu
                // saat berkas berupa PDF atau arsip. Gambar lepas diproses
                // bersama-sama tetapi lazimnya berasal dari bab yang sama,
                // jadi riwayatnya justru harus terus mengalir antar gambar.
                if (e in Storage.PDF_EXTS || e in Storage.ZIP_EXTS || e in Storage.RAR_EXTS ||
                    e in Storage.EPUB_EXTS) {
                    pageContext.clear()
                }

                val itemDir = File(workRoot, "item$idx").apply { mkdirs() }
                val (uri, name) = isi[0]
                when {
                    jenis == "img" ->
                        handleImages(isi, provider, targetLanguage, outputTree, itemDir, result)
                    e in Storage.PDF_EXTS ->
                        handlePdf(uri, name, provider, targetLanguage, outputTree, itemDir, result)
                    e in Storage.ZIP_EXTS || e in Storage.RAR_EXTS || e in Storage.EPUB_EXTS ->
                        handleArchive(uri, name, e, provider, targetLanguage, outputTree, itemDir, result)
                    else -> {
                        log("  [!] Unsupported file type: .$e")
                        result.failed++
                        result.total++
                    }
                }
            }
        } catch (ake: ApiKeyException) {
            result.error = "API key for ${provider.providerName} is missing, expired or invalid."
            log("[!] ${result.error}")
        } catch (ex: Exception) {
            result.error = ex.message ?: ex.toString()
            log("[!] ${result.error}")
        } finally {
            workRoot.deleteRecursively()
            // Simpan apa pun yang sempat direkam, termasuk saat proses gagal
            // atau dihentikan di tengah jalan: halaman yang sudah selesai
            // tetap bisa disunting tanpa membayar deteksi + LLM lagi.
            project?.let { prj ->
                if (prj.pages.isEmpty()) Project.delete(ctx, prj.id)
                else runCatching {
                    prj.save(ctx)
                    Project.prune(ctx)
                    lastProject = prj
                    log("[Proyek] ${prj.pages.size} halaman disimpan — bisa disunting lewat Hasil.")
                }.onFailure { log("  [!] Proyek gagal disimpan: ${it.message}") }
            }
            project = null

            // Cache ditulis di finally, bukan setelah sukses: proses yang
            // dihentikan pengguna tetap sudah membayar balon-balon yang
            // terlanjur diterjemahkan, jadi hasilnya harus tetap tersimpan.
            cache?.let { c ->
                runCatching { c.simpanKeDisk() }
                    .onFailure { log("  [!] Cache gagal ditulis: ${it.message}") }
                cacheKenaTerakhir = c.kena
                result.cacheKena = c.kena
            }
        }

        // ---- Ringkasan pemakaian ----
        pemakaianTerakhir = penghitung.pakai
        biayaTerakhir = penghitung.biaya(provider.modelTerakhir)
        result.pemakaian = penghitung.pakai
        result.biaya = biayaTerakhir

        if (cfg.hitungBiaya && penghitung.panggilan > 0) {
            log("")
            if (penghitung.pakai.total > 0) {
                log("[Biaya] ${penghitung.panggilan} panggilan API, " +
                    Usage.baris(penghitung.pakai, provider.modelTerakhir))
                // Angka kumulatif disimpan supaya pengguna bisa melihat total
                // lintas-proses di layar utama, bukan hanya bab terakhir.
                cfg.biayaKumulatif = (cfg.biayaKumulatif + biayaTerakhir.toFloat())
                cfg.tokenKumulatif = cfg.tokenKumulatif + penghitung.pakai.total
            }
            if (penghitung.tanpaData > 0) {
                log("[Biaya] ${penghitung.tanpaData} panggilan tidak melaporkan token " +
                    "(provider tidak mengirimkannya).")
            }
        }
        cacheKenaTerakhir.let {
            if (it > 0) log("[Cache] $it balon dihemat pada proses ini.")
        }
        if (cfg.arahBacaOtomatis && (hitungRtl + hitungLtr) > 0) {
            log("[Arah baca] $hitungRtl halaman kanan-ke-kiri, $hitungLtr kiri-ke-kanan.")
        }

        val secs = (System.currentTimeMillis() - started) / 1000.0
        log("")
        log("[Timer] Translation completed in ${"%.1f".format(secs)}s!")
        return result
    }

    /** Loose images given as content Uris; copied into cache and batched together. */
    private fun handleImages(
        items: List<Pair<Uri, String>>, provider: LLMProvider, lang: String,
        outputTree: Uri, workDir: File, result: Result
    ) {
        val files = items.mapNotNull { (uri, name) ->
            runCatching { Storage.copyToCache(ctx, uri, workDir, name) }
                .onFailure { log("  [!] Cannot read $name: ${it.message}") }
                .getOrNull()
        }
        if (files.isEmpty()) { result.failed += items.size; result.total += items.size; return }

        val outFiles = processImageBatch(files, provider, lang, workDir)
        result.total += files.size
        for ((i, f) in outFiles.withIndex()) {
            if (f == null) { result.failed++; continue }
            val outName = Storage.baseName(files[i].name) + ".png"
            val uri = runCatching {
                Storage.writeToTree(
                    ctx, outputTree, Langs.code(lang).uppercase(), outName, "image/png"
                ) { os -> f.inputStream().use { it.copyTo(os) } }
            }.getOrElse { e ->
                log("  [!] Could not write to the chosen output folder: ${e.message}")
                null
            }
            val keep = File(ctx.filesDir, "last_output/$outName")
            keep.parentFile?.mkdirs()
            runCatching { f.inputStream().use { i -> keep.outputStream().use { o -> i.copyTo(o) } } }
            result.outputs.add(OutputItem(outName, uri, keep.absolutePath))
            result.success++
            log("  Saved: ${Langs.code(lang).uppercase()}/$outName")
        }
    }

    private fun handlePdf(
        uri: Uri, name: String, provider: LLMProvider, lang: String,
        outputTree: Uri, workDir: File, result: Result
    ) {
        val pdf = Storage.copyToCache(ctx, uri, workDir, name)
        log("  Extracting PDF pages...")
        val pagesDir = File(workDir, "pages")
        val pages = Storage.renderPdfPages(ctx, pdf, pagesDir) { done, total ->
            if (done % 5 == 0 || done == total) log("  Extracted $done/$total pages")
        }
        if (pages.isEmpty()) { log("  [!] PDF has no pages."); result.failed++; result.total++; return }

        val outFiles = processImageBatch(pages, provider, lang, workDir)
        val valid = outFiles.filterNotNull()
        result.total += pages.size
        result.success += valid.size
        result.failed += pages.size - valid.size

        // The output is written even on cancel, so a stopped run still hands
        // back the pages already finished instead of nothing at all.
        if (valid.isNotEmpty()) {
            log("  Saving final PDF in original page order...")
            val outName = Storage.baseName(name) + ".pdf"
            val outUri = runCatching {
                Storage.writeToTree(
                    ctx, outputTree, Langs.code(lang).uppercase(), outName, "application/pdf"
                ) { os -> Storage.imagesToPdf(valid, cfg.maxImageSide, os) }
            }.getOrElse { e ->
                log("  [!] Could not write to the chosen output folder: ${e.message}")
                null
            }
            val localCopy = File(ctx.filesDir, "last_output/$outName")
            localCopy.parentFile?.mkdirs()
            runCatching { localCopy.outputStream().use { Storage.imagesToPdf(valid, cfg.maxImageSide, it) } }
            result.outputs.add(OutputItem(outName, outUri, localCopy.absolutePath))
            log("  Saved: ${Langs.code(lang).uppercase()}/$outName")
        }
        log("[PDF] Completed! Success: ${valid.size}, Failed: ${pages.size - valid.size}, Total: ${pages.size}")
    }

    private fun handleArchive(
        uri: Uri, name: String, ext: String, provider: LLMProvider, lang: String,
        outputTree: Uri, workDir: File, result: Result
    ) {
        log("  Extracting archive...")
        val extractDir = File(workDir, "extract")

        // EPUB menyimpan urutan bacanya sendiri di dalam OPF spine. Mengurutkan
        // isinya menurut nama berkas - yang benar untuk CBZ - mengacak babnya,
        // karena nama gambar di EPUB kerap tidak berhubungan dengan urutan.
        var urutSpine = false
        val pages: List<File> = try {
            when {
                ext in Storage.RAR_EXTS -> {
                    val rar = Storage.copyToCache(ctx, uri, workDir, name)
                    Storage.extractRar(rar, extractDir)
                }
                ext in Storage.EPUB_EXTS -> {
                    val epub = Storage.copyToCache(ctx, uri, workDir, name)
                    val urut = Storage.extractEpub(epub, extractDir)
                    if (urut.isNotEmpty()) {
                        urutSpine = true
                        urut
                    } else {
                        // Metadata cacat: lebih baik urutan tebakan daripada
                        // menolak berkasnya sama sekali.
                        log("  [!] Urutan EPUB tidak terbaca, memakai urutan nama berkas.")
                        Storage.extractZipFile(epub, extractDir)
                    }
                }
                else -> ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    Storage.extractZip(input, extractDir)
                }
            }
        } catch (e: Exception) {
            log("  [!] Extraction failed: ${e.message}")
            result.failed++; result.total++
            return
        }.let { if (urutSpine) it else it.sortedWith(compareBy(Storage.naturalComparator) { f -> f.name }) }

        if (pages.isEmpty()) { log("  [!] No images found in archive."); result.failed++; result.total++; return }
        if (urutSpine) log("  Found ${pages.size} images (EPUB reading order).")
        else log("  Found ${pages.size} images (sorted numerically).")

        // ---- titik simpan ----
        //
        // Halaman yang sudah selesai di jalan sebelumnya dilewati sepenuhnya:
        // tidak dideteksi ulang, tidak diterjemahkan ulang, tidak dibayar
        // ulang. Kuncinya memuat bahasa sasaran, jadi mengganti bahasa
        // menghasilkan titik simpan tersendiri.
        val ukuran = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        val kunciResume = Resume.kunci(name, ukuran, lang)
        val titik =
            if (cfg.lanjutkanArsip) Resume.muat(ctx, kunciResume)
            else Resume.Titik(Resume.dirUntuk(ctx, kunciResume))

        val sisa = if (cfg.lanjutkanArsip) pages.filter { it.name !in titik.selesai } else pages
        if (cfg.lanjutkanArsip && titik.jumlah > 0) {
            log("  [Lanjut] ${titik.jumlah} halaman sudah selesai sebelumnya, ${sisa.size} tersisa.")
        }

        val outFiles =
            if (sisa.isEmpty()) emptyList() else processImageBatch(sisa, provider, lang, workDir)

        // Rekam hasil baru SEBELUM workDir dihapus di blok finally milik run().
        val baru = HashMap<String, File>()
        for ((i, f) in outFiles.withIndex()) {
            val src = sisa.getOrNull(i) ?: continue
            if (f != null) baru[src.name] = f
        }
        if (cfg.lanjutkanArsip) {
            val lengkap = batchLengkap
            var dicatat = 0
            for ((i, f) in outFiles.withIndex()) {
                val src = sisa.getOrNull(i) ?: continue
                if (f == null) continue
                // Halaman yang hanya "dilewatkan apa adanya" saat proses
                // dihentikan tidak boleh dianggap selesai.
                if (lengkap.getOrNull(i) != true) continue
                if (Resume.catat(titik, src.name, f)) dicatat++
            }
            if (dicatat > 0) Resume.simpan(titik)
        }
        // Bab dianggap tuntas hanya kalau setiap halamannya benar-benar
        // diterjemahkan. Menghitung berkas keluaran tidak sah: saat proses
        // dihentikan, halaman yang belum tersentuh pun tetap ditulis.
        val tuntas = cfg.lanjutkanArsip && pages.all { it.name in titik.selesai }

        // Gabungkan hasil lama dan baru mengikuti urutan halaman aslinya,
        // supaya bab yang dilanjutkan tetap urut.
        val valid = if (cfg.lanjutkanArsip) {
            pages.mapNotNull { baru[it.name] ?: titik.selesai[it.name] }
        } else {
            outFiles.filterNotNull()
        }
        result.total += pages.size
        result.success += valid.size
        result.failed += pages.size - valid.size

        // The output is written even on cancel, so a stopped run still hands
        // back the pages already finished instead of nothing at all.
        if (valid.isNotEmpty()) {
            log("  Combining translated images into PDF...")
            val outName = Storage.baseName(name) + ".pdf"
            val outUri = runCatching {
                Storage.writeToTree(
                    ctx, outputTree, Langs.code(lang).uppercase(), outName, "application/pdf"
                ) { os -> Storage.imagesToPdf(valid, cfg.maxImageSide, os) }
            }.getOrElse { e ->
                log("  [!] Could not write to the chosen output folder: ${e.message}")
                null
            }
            val localCopy = File(ctx.filesDir, "last_output/$outName")
            localCopy.parentFile?.mkdirs()
            runCatching { localCopy.outputStream().use { Storage.imagesToPdf(valid, cfg.maxImageSide, it) } }
            result.outputs.add(OutputItem(outName, outUri, localCopy.absolutePath))
            log("  Saved: ${Langs.code(lang).uppercase()}/$outName")
        }
        // Arsip tuntas: titik simpan tidak berguna lagi. Kalau masih ada yang
        // gagal, biarkan supaya jalan berikutnya bisa melanjutkannya.
        if (cfg.lanjutkanArsip) {
            if (tuntas) Resume.bersihkan(titik)
            Resume.prune(ctx)
        }
        log("[Archive] Completed! Success: ${valid.size}, Failed: ${pages.size - valid.size}, Total: ${pages.size}")
    }

    // ------------------------------------------------------------------
    // Core batch (process_image_batch)
    // ------------------------------------------------------------------

    /** One physical output page; may consist of several split parts. */
    private class PageUnit(val srcName: String, val parts: MutableList<PartEntry> = mutableListOf())

    private class PartEntry(
        val file: File,
        val width: Int,
        val height: Int,
        val boxes: List<IntArray>,
        val translations: MutableMap<String, String> = mutableMapOf(),
        var failed: Boolean = false,
        /**
         * Warna terukur milik bagian halaman INI.
         *
         * Ronde 16: dulu warna disimpan di satu peta milik Pipeline yang
         * dibersihkan tiap kali detectBoxes() dipanggil. Karena deteksi
         * berjalan di pass 1 untuk SEMUA halaman, sementara penggambaran baru
         * terjadi di pass 3, isi peta itu selalu milik halaman terakhir saat
         * halaman mana pun digambar. Akibatnya satu-satunya halaman yang
         * pernah mendapat warna aslinya adalah halaman terakhir; sisanya jatuh
         * ke Palette.DEFAULT alias putih/hitam. Menyimpannya bersama bagian
         * halamannya membuat warna hidup selama data halaman itu hidup.
         */
        val warna: Map<String, Palette.Colors> = emptyMap(),
        /**
         * Gaya tipografi terukur milik bagian halaman INI (ronde 25).
         * Disimpan di sini karena alasan yang sama persis dengan [warna]:
         * peta milik Pipeline ditimpa oleh halaman berikutnya sebelum
         * penggambaran terjadi.
         */
        val gaya: Map<String, Typography.Gaya> = emptyMap(),
        /**
         * Kunci kotak teks-di-luar-balon milik bagian halaman INI.
         *
         * Ronde 18 — bug kembar dari warna di atas, dan yang ini merusak
         * gambar. Penandanya dulu satu HashSet milik Pipeline yang tidak
         * pernah di-clear() sama sekali, sementara kuncinya hanyalah
         * "x1,y1,x2,y2" dalam piksel mentah tanpa identitas halaman. Sekali
         * sebuah teks lepas terdaftar di halaman N, setiap kotak berkoordinat
         * sama di halaman N+1, N+2, ... ikut dianggap teks lepas dan dikirim
         * ke LaMa. Balon yang seharusnya cukup diisi putih malah dihapus
         * beserta artwork di baliknya, menyisakan bercak buram bertepi
         * melengkung (gabungan banyak Rect ber-FEATHER 16 px) — persis yang
         * dilaporkan pengguna. Tabrakan koordinat sama sekali tidak langka:
         * webtoon memakai lebar tetap dan penempatan balon yang berulang.
         *
         * Menyimpannya bersama bagian halamannya membuat penanda ini mati
         * bersama halamannya, sehingga tidak bisa bocor ke halaman lain.
         */
        val lepas: Set<String> = emptySet()
    )

    /** A bubble crop parked on disk until its mosaic request is built. */
    internal class PendingCrop(val file: File, val unitIdx: Int, val partIdx: Int, val boxIdx: Int)

    /**
     * Halaman mana saja pada batch terakhir yang BENAR-BENAR selesai, yaitu
     * setiap balon yang dipotong sudah punya jawaban.
     *
     * Ini bukan hal yang sama dengan "menghasilkan berkas keluaran". Saat
     * proses dihentikan, pass 3 tetap menulis SEMUA halaman - yang belum
     * diterjemahkan dilewatkan apa adanya supaya bab tetap utuh dan terbaca.
     * Titik simpan tidak boleh mempercayai itu: kalau halaman apa adanya
     * dicatat sebagai selesai, ia tidak akan pernah diterjemahkan pada jalan
     * berikutnya, dan pengguna mendapat bab setengah jadi secara permanen.
     */
    private var batchLengkap: List<Boolean> = emptyList()

    /**
     * Multi-page mosaic batching. Page bitmaps and bubble crops are streamed
     * through disk so peak memory stays at roughly one page plus one chunk,
     * which is what makes a 200-page archive survive on a real phone.
     */
    private fun processImageBatch(
        pages: List<File>, provider: LLMProvider, lang: String, workDir: File
    ): List<File?> {
        val total = pages.size
        log("[Multi-Page Batch] Processing $total page(s)...")

        val cropDir = File(workDir, "crops").apply { mkdirs() }
        val partDir = File(workDir, "parts").apply { mkdirs() }
        val units = ArrayList<PageUnit>()
        val pending = ArrayList<PendingCrop>()
        var ditolakBentuk = 0

        // ---- Pass 1: detect bubbles, dump crops to disk ----
        for ((pIdx, file) in pages.withIndex()) {
            if (cancelled) {
                // Stop detecting, but do NOT discard the rest of the chapter.
                // Every page not reached yet is queued as a pass-through unit so
                // it still lands in the output untranslated, instead of being
                // counted as a failure. Pressing Stop used to produce
                // "Success: 0, Failed: 59" for exactly this reason.
                for (rIdx in pIdx until total) {
                    val rest = PageUnit(pages[rIdx].name)
                    rest.parts.add(PartEntry(pages[rIdx], 0, 0, emptyList()))
                    units.add(rest)
                }
                log("  [Stopped] ${total - pIdx} page(s) not processed — saved in the original language.")
                break
            }
            progress(pIdx, total)

            val unit = PageUnit(file.name)
            units.add(unit)

            val page = Storage.decodeBitmap(file, cfg.maxImageSide)
            if (page == null) {
                log("  [!] Failed to read ${file.name}")
                unit.parts.add(PartEntry(file, 0, 0, emptyList(), failed = true))
                continue
            }

            // Auto-split wide double-page spreads (right-to-left reading order).
            val splits = splitLandscape(page)
            val partBitmaps: List<Bitmap>
            if (splits != null) {
                val r = page.width.toFloat() / page.height.toFloat()
                log("  [Auto-Split] Wide page (ratio ${"%.2f".format(r)}) split into ${splits.size} parts.")
                partBitmaps = splits
                page.recycle()
            } else {
                partBitmaps = listOf(page)
            }

            for ((partIdx, bmp) in partBitmaps.withIndex()) {
                // An unsplit page is already on disk: re-encoding a 1080x11700
                // strip to PNG here (and decoding it again in pass 3) cost
                // seconds per page and produced a byte-identical picture.
                val partFile = if (splits == null) file
                else File(partDir, "u%04d_p%02d.png".format(pIdx, partIdx)).also {
                    Storage.savePng(bmp, it)
                }

                val boxes = detectBoxes(bmp)
                // Warna diukur saat deteksi, jadi salin sekarang: peta milik
                // Pipeline akan ditimpa oleh halaman berikutnya (ronde 16).
                unit.parts.add(
                    PartEntry(
                        partFile, bmp.width, bmp.height, boxes,
                        warna = HashMap(warnaKotak),
                        gaya = HashMap(gayaKotak),
                        lepas = HashSet(teksLepasKotak)
                    )
                )

                for ((bIdx0, box) in boxes.withIndex()) {
                    // Kotak yang PASTI ditolak saat menggambar tidak usah
                    // dikirim. Dulu bentuk seperti ini tetap dipotong, ikut
                    // masuk mosaik, dan dibayar sebagai token - lalu drawText
                    // membuangnya tanpa satu baris log pun. Pada berkas oracle
                    // 12,7% kotak (61 dari 480) berada di golongan ini.
                    if (bentukDitolak(box, bmp.width, bmp.height)) {
                        ditolakBentuk++
                        continue
                    }
                    val crop = buildCrop(bmp, box, boxes) ?: continue
                    val cropFile = File(cropDir, "c%04d_%02d_%03d.png".format(pIdx, partIdx, bIdx0))
                    Storage.savePng(crop, cropFile)
                    crop.recycle()
                    pending.add(PendingCrop(cropFile, pIdx, partIdx, bIdx0 + 1))
                }
                bmp.recycle()
            }

            val found = unit.parts.sumOf { it.boxes.size }
            log("  [${pIdx + 1}/$total] ${file.name}: $found speech bubble(s)")
        }

        log("[Multi-Page Batch] Extracted ${pending.size} speech bubbles across $total pages.")
        if (ditolakBentuk > 0) {
            log("  [i] $ditolakBentuk kotak dilewati sebelum dikirim: bentuknya terlalu " +
                "memanjang/lebar untuk balon (kemungkinan panel atau SFX), dan penata teks " +
                "memang akan menolaknya. Tidak ada token yang terpakai untuk kotak ini.")
        }

        // ---- Pass 2: mosaic + translate ----
        //
        // Sebelum apa pun dikirim, balon yang sudah pernah diterjemahkan
        // dijawab dari cache. Ini terjadi di sini - bukan di dalam loop chunk -
        // supaya balon yang kena cache benar-benar HILANG dari pembagian
        // chunk. Kalau disaring belakangan, sebuah chunk berisi 20 balon yang
        // 19 di antaranya sudah dikenal tetap menjadi satu request berbayar.
        var sisaPending = pending
        if (cfg.cacheTerjemahan && pending.isNotEmpty() && !cancelled) {
            val c = cache
            if (c != null) {
                val belum = ArrayList<PendingCrop>(pending.size)
                var terjawab = 0
                // Balon yang dijawab cache TETAP masuk riwayat konteks halaman.
                // Konteks adalah rekaman percakapan, bukan catatan siapa yang
                // membayar: kalau cache mengosongkannya, halaman berikutnya
                // kehilangan konteks justru pada bab yang paling sering
                // diproses ulang.
                val konteksDariCache = LinkedHashMap<String, MutableList<String>>()
                for (pc in pending) {
                    val hit = ambilDariCache(pc, provider, lang)
                    if (hit != null) {
                        units.getOrNull(pc.unitIdx)?.parts?.getOrNull(pc.partIdx)
                            ?.translations?.put(pc.boxIdx.toString(), hit)
                        if (cfg.konteksHalaman) {
                            val nama = units.getOrNull(pc.unitIdx)?.srcName
                            if (nama != null) {
                                konteksDariCache.getOrPut(nama) { mutableListOf() }.add(hit)
                            }
                        }
                        terjawab++
                    } else belum.add(pc)
                }
                for ((nama, baris) in konteksDariCache) pageContext.add(nama, baris)
                if (terjawab > 0) {
                    log("[Cache] $terjawab dari ${pending.size} balon dijawab dari cache " +
                        "(tidak dikirim ke API).")
                }
                sisaPending = belum
            }
        }

        if (sisaPending.isNotEmpty() && !cancelled) {
            val maxPer = cfg.maxBubblesPerRequest
            val chunks = sisaPending.chunked(maxPer)
            log("[Multi-Page Batch] Stitching ${sisaPending.size} bubble(s) into ${chunks.size} " +
                "mosaic request(s) (max $maxPer bubbles/request)...")

            // Request dikirim per GELOMBANG. Di dalam satu gelombang request
            // berjalan bersamaan; antar gelombang tetap berurutan supaya hasil
            // gelombang N sempat masuk pageContext sebelum gelombang N+1
            // menyusun prompt-nya. Ini menjaga konsistensi nama tokoh yang
            // akan hilang kalau semua request dilepas sekaligus.
            val lebarGelombang = cfg.requestParalel.coerceIn(1, 8)
            var selesai = 0
            for (gelombang in chunks.indices.chunked(lebarGelombang)) {
                if (cancelled) break

                // Hasil tiap request ditampung dulu, lalu diterapkan ke state
                // bersama secara BERURUTAN di thread ini setelah gelombang
                // selesai. units/pageContext/cache/penghitung tidak dirancang
                // untuk ditulis banyak thread, dan urutan penerapan yang tetap
                // membuat hasil akhir sama persis dengan mode serial.
                val hasilGelombang = arrayOfNulls<HasilChunk>(gelombang.size)

                if (lebarGelombang == 1 || gelombang.size == 1) {
                    hasilGelombang[0] = kerjakanChunk(
                        chunks[gelombang[0]], gelombang[0], chunks.size, units,
                        provider, lang
                    )
                } else {
                    // Kegagalan fatal harus keluar dari thread pekerja.
                    //
                    // ApiKeyException sengaja dilempar ulang oleh
                    // translateMosaic supaya run() berhenti dengan pesan yang
                    // jelas. Kalau ia ikut ditelan bersama kegagalan biasa,
                    // pengguna berkunci kedaluwarsa cuma melihat halaman
                    // kosong sementara kita terus menghantam server yang
                    // menolak. Disimpan lalu dilempar ulang setelah join
                    // supaya thread lain tetap tuntas dengan rapi.
                    val fatal = java.util.concurrent.atomic.AtomicReference<ApiKeyException>()
                    val threads = gelombang.mapIndexed { slot, cIdx ->
                        Thread {
                            hasilGelombang[slot] = runCatching {
                                kerjakanChunk(
                                    chunks[cIdx], cIdx, chunks.size, units,
                                    provider, lang
                                )
                            }.getOrElse { e ->
                                if (e is ApiKeyException) fatal.compareAndSet(null, e)
                                else log("  [!] Request ${cIdx + 1} gagal: ${e.message}")
                                null
                            }
                        }.apply { name = "nyra-req-${cIdx + 1}"; start() }
                    }
                    threads.forEach { it.join() }
                    fatal.get()?.let { throw it }
                }

                for (h in hasilGelombang) {
                    if (h == null) continue
                    terapkanHasil(h, units, provider, lang)
                }

                selesai += gelombang.size
                progress(minOf(selesai, chunks.size), chunks.size)
            }
        }

        // Pass 2 selesai: gambar rujukan terakhir tidak akan dipakai lagi, dan
        // pass 3 butuh memorinya untuk mendekode halaman utuh.
        bebaskanRujukan()

        // ---- Pass 3: render text back, one page at a time ----
        //
        // This pass is deliberately NOT aborted on cancel. Everything expensive
        // (detection + network) has already been paid for, so throwing the
        // result away would waste the whole run — which is exactly what used to
        // happen when the user pressed Stop during a rate-limit wait. When
        // cancelled we still emit every page: translated ones carry their text,
        // untranslated ones are passed through unchanged so the output stays a
        // complete, readable chapter.
        val outDir = File(workDir, "out").apply { mkdirs() }
        val outputs = arrayOfNulls<File>(total)
        var tidakTergambar = 0
        if (cancelled) log("[Stopped] Saving the ${units.count { u -> u.parts.any { it.translations.isNotEmpty() } }} page(s) already translated...")

        for ((pIdx, unit) in units.withIndex()) {
            if (unit.parts.isEmpty() || unit.parts.all { it.failed }) continue
            progress(pIdx, total)

            val rendered = ArrayList<Bitmap>()
            var drawn = 0
            for (part in unit.parts) {
                if (part.failed) continue
                val bmp = Storage.decodeBitmap(part.file, cfg.maxImageSide) ?: continue

                // Hapus teks asli di luar balon dengan LaMa SEBELUM menggambar
                // terjemahan: isi-putih di atas artwork meninggalkan kotak
                // putih yang merusak screentone dan siluet di baliknya.
                if (cfg.inpaintLama) {
                    val sasaran = sasaranInpaint(
                        part.boxes, part.translations, part.lepas, bmp.width, bmp.height
                    )
                    if (sasaran.isNotEmpty()) {
                        val seam = inpaintOverride
                        val ip = if (seam != null) null else inpaint()
                        if (seam != null || ip != null) {
                            val t0 = System.currentTimeMillis()
                            val n = runCatching {
                                if (seam != null) seam(sasaran)
                                else ip!!.erase(bmp, sasaran) { m -> log(m) }
                            }.getOrElse { e -> log("  [!] Inpaint dilewati: ${e.message}"); 0 }
                            if (n > 0) {
                                val dt = (System.currentTimeMillis() - t0) / 1000.0
                                log("  [i] Inpaint: ${sasaran.size} teks lepas dibersihkan " +
                                    "dalam $n petak (${"%.1f".format(dt)} s).")
                            }
                        }
                    }
                }

                val canvas = Canvas(bmp)
                for ((numStr, text) in part.translations) {
                    val bIdx = numStr.toIntOrNull() ?: continue
                    val box = part.boxes.getOrNull(bIdx - 1) ?: continue
                    if (drawText(canvas, bmp, box, text, lang, part.warna, part.gaya)) drawn++
                    else if (!teksKosong(text)) tidakTergambar++
                }
                rendered.add(bmp)
            }
            if (rendered.isEmpty()) continue

            val finalBmp = if (rendered.size == 1) rendered[0] else hconcatReversed(rendered)
            val outFile = File(outDir, "p%04d_%s.png".format(pIdx, Storage.baseName(unit.srcName)))
            Storage.savePng(finalBmp, outFile)
            outputs[pIdx] = outFile

            if (finalBmp !in rendered) finalBmp.recycle()
            rendered.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
            log("  Rendered ${unit.srcName} ($drawn bubble(s) translated)")

            // Rekam halaman ini ke proyek SEBELUM cropDir/partDir dihapus.
            // Yang disalin adalah berkas sumber yang MASIH BERSIH, bukan bmp
            // yang barusan digambari: editor harus bisa menggambar ulang dari
            // nol berkali-kali, dan menggambar di atas hasil gambar akan
            // menumpuk teks lama di bawah teks baru.
            rekamHalaman(unit)
        }

        cropDir.deleteRecursively()
        partDir.deleteRecursively()

        // Kelengkapan per halaman: semua potongan yang diminta sudah dijawab.
        val diminta = IntArray(total)
        for (pc in pending) if (pc.unitIdx in 0 until total) diminta[pc.unitIdx]++
        batchLengkap = (0 until total).map { i ->
            val u = units.getOrNull(i)
            val dijawab = u?.parts?.sumOf { it.translations.size } ?: 0
            val gagal = u == null || u.parts.isEmpty() || u.parts.all { it.failed }
            !gagal && dijawab >= diminta[i]
        }

        val translatedBubbles = units.sumOf { u -> u.parts.sumOf { it.translations.size } }
        val missed = pending.size - translatedBubbles
        if (missed > 0) {
            log("[Ringkasan] $translatedBubbles/${pending.size} balon diterjemahkan. " +
                "$missed balon dibiarkan dalam bahasa asli" +
                (if (cancelled) " karena proses dihentikan." else " karena provider tidak menjawab.") +
                " Halaman tetap tersimpan — jalankan ulang untuk melengkapi sisanya.")
        }
        // Terjemahan yang SUDAH dibayar tapi tidak muncul di gambar. Ini beda
        // dari 'missed' di atas: modelnya menjawab, tekannya ada, tapi penata
        // teks menolak kotaknya. Dulu selisih ini tidak pernah dilaporkan,
        // jadi pengguna melihat "20 balon diterjemahkan" pada halaman yang
        // jelas-jelas masih berbahasa Jepang dan tidak punya cara tahu kenapa.
        if (tidakTergambar > 0) {
            log("  [!] $tidakTergambar terjemahan tidak tergambar karena bentuk kotaknya " +
                "ditolak penata teks. Terjemahannya tersimpan di proyek dan bisa " +
                "ditempatkan manual lewat editor.")
        }
        return outputs.toList()
    }

    /**
     * Gambar rujukan yang sedang dipakai, beserta identitas halamannya.
     *
     * Satu halaman dengan 40 balon terpecah jadi beberapa chunk, dan dulu tiap
     * chunk mendekode ulang halaman utuh yang sama dari disk hanya untuk
     * dijadikan gambar rujukan. Cache berisi SATU halaman saja - chunk disusun
     * berurutan per halaman, jadi satu slot sudah menangkap hampir semua
     * pengulangan tanpa menambah puncak memori secara berarti (ini pipeline
     * yang sengaja mengalirkan halaman lewat disk supaya bab 200 halaman tetap
     * hidup di ponsel).
     */
    private var rujukanKunci: PageReference.Kunci? = null
    private var rujukanBmp: Bitmap? = null
    private val rujukanLock = Any()

    /** Ambil gambar rujukan halaman [kunci], mendekode hanya bila berganti halaman. */
    private fun rujukanTerpakai(kunci: PageReference.Kunci, pf: File): Bitmap? =
        synchronized(rujukanLock) {
            val ada = rujukanBmp
            if (ada != null && !ada.isRecycled && rujukanKunci == kunci) return ada
            ada?.let { runCatching { if (!it.isRecycled) it.recycle() } }
            rujukanBmp = null
            rujukanKunci = null
            val baru = Storage.decodeBitmap(pf, cfg.sisiRujukanMaks) ?: return null
            rujukanBmp = baru
            rujukanKunci = kunci
            return baru
        }

    private fun bebaskanRujukan() = synchronized(rujukanLock) {
        rujukanBmp?.let { runCatching { if (!it.isRecycled) it.recycle() } }
        rujukanBmp = null
        rujukanKunci = null
    }

    /**
     * Satu potongan mosaik yang sudah jadi, beserta jumlah potongan yang
     * benar-benar masuk ke dalamnya.
     */
    internal class Mosaik(val bitmap: Bitmap, val id: List<String>) {
        val jumlah: Int get() = id.size
    }

    /**
     * Decode potongan, susun jadi mosaik bernomor, lalu bebaskan SEMUA bitmap
     * antara. Pemanggil cukup me-recycle [Mosaik.bitmap] saat selesai.
     *
     * Sebelumnya urutan decode -> shrinkIfTooTall -> build -> recycle ditulis
     * dua kali di [kerjakanChunk]: sekali untuk mosaik utama, sekali lagi di
     * jalur permintaan ulang. Duplikat itu bukan sekadar berulang - blok
     * kedua harus menyalin persis logika pembebasan bitmapnya, dan bitmap
     * mosaik berukuran belasan megabita, jadi satu baris yang lupa disalin
     * langsung jadi kebocoran memori. Sekarang satu jalur untuk keduanya.
     *
     * Aman dipanggil dari beberapa thread: [numberPaint] milik pemanggil dan
     * tidak ada state bersama yang disentuh.
     *
     * @return null bila tidak ada satu pun potongan yang berhasil didecode.
     */
    internal fun susunMosaik(
        potongan: List<Pair<String, PendingCrop>>, numberPaint: Paint
    ): Mosaik? {
        // Potongan yang gagal didecode dijatuhkan di sini dan nomornya TIDAK
        // ikut dikembalikan, supaya pemanggil tidak pernah menjanjikan nomor
        // yang sebenarnya tidak ada di dalam gambar.
        val crops = ArrayList<Mosaic.Crop>(potongan.size)
        for ((id, pc) in potongan) {
            val bmp = Storage.decodeBitmap(pc.file, 4000) ?: continue
            crops.add(Mosaic.Crop(id, bmp))
        }
        if (crops.isEmpty()) return null

        val shrunk = Mosaic.shrinkIfTooTall(
            crops, cfg.maxTinggiMosaik, cfg.jarakAntarPotongan, 20
        )
        val mosaic = Mosaic.build(shrunk, cfg, numberPaint)

        // shrinkIfTooTall mengembalikan crops ASLI kalau semuanya sudah muat,
        // jadi bitmap yang sama bisa muncul di kedua daftar. Bandingkan dulu
        // supaya tidak ada yang di-recycle dua kali.
        val originals = crops.map { it.bitmap }
        shrunk.forEach { if (it.bitmap !in originals) it.bitmap.recycle() }
        originals.forEach { it.recycle() }

        return Mosaik(mosaic, shrunk.map { it.id })
    }

    /** Hasil satu request mosaik, belum diterapkan ke state bersama. */
    private class HasilChunk(
        val diterima: Map<String, String>,
        val idMapping: Map<String, PendingCrop>
    )

    /**
     * Bangun mosaik satu chunk, kirim ke model, dan kembalikan jawabannya.
     *
     * Fungsi ini AMAN dijalankan beberapa thread sekaligus: semua yang
     * disentuhnya bersifat lokal (bitmap, mosaik, pemetaan nomor). Penulisan
     * ke units, pageContext, dan cache sengaja TIDAK dilakukan di sini -
     * itu tugas [terapkanHasil] yang berjalan berurutan.
     */
    private fun kerjakanChunk(
        chunk: List<PendingCrop>, cIdx: Int, totalChunk: Int,
        units: List<PageUnit>, provider: LLMProvider, lang: String
    ): HasilChunk? {
        if (cancelled) return null

        // Paint BUKAN kelas yang aman dipakai banyak thread, dan Canvas
        // menyimpan state gambar di dalamnya saat drawText berjalan. Karena
        // beberapa chunk kini menyusun mosaiknya bersamaan, tiap thread harus
        // memakai Paint-nya SENDIRI - satu instance bersama akan menghasilkan
        // nomor yang salah ukuran atau hilang secara acak.
        val numberPaint = renderer.mosaicNumberPaint()

        val urut = ArrayList<Pair<String, PendingCrop>>(chunk.size)
        for ((i, pc) in chunk.withIndex()) urut.add((i + 1).toString() to pc)

        val susunan = susunMosaik(urut, numberPaint) ?: return null
        val mosaic = susunan.bitmap
        val jumlahPotongan = susunan.jumlah

        // Hanya nomor yang benar-benar tergambar di mosaik yang boleh masuk
        // idMapping. Kalau sebuah potongan gagal didecode, menaruhnya di sini
        // akan membuat jalur "minta ulang" mengejar nomor yang tidak pernah
        // dikirim ke model.
        val tergambar = susunan.id.toHashSet()
        val idMapping = HashMap<String, PendingCrop>()
        for ((id, pc) in urut) if (id in tergambar) idMapping[id] = pc

        if (cfg.konteksHalaman && pageContext.size > 0) {
            log("  [i] Konteks: ${pageContext.size} halaman sebelumnya disertakan.")
        }

        // Gambar rujukan: halaman utuh asal balon-balon ini. Hanya bila
        // seluruh chunk berasal dari satu bagian halaman yang sama,
        // karena chunk dipotong per 20 balon dan bisa melintasi halaman.
        var rujukan: Bitmap? = null
        if (cfg.gambarRujukan) {
            val kunci = PageReference.pilih(
                chunk.map { PageReference.Kunci(it.unitIdx, it.partIdx) }
            )
            if (kunci != null) {
                val pf = units.getOrNull(kunci.unitIdx)?.parts?.getOrNull(kunci.partIdx)?.file
                if (pf != null) {
                    rujukan = rujukanTerpakai(kunci, pf)
                    if (rujukan != null) log("  [i] Rujukan: halaman utuh disertakan.")
                }
            } else {
                log("  [i] Rujukan dilewati: request ini memuat lebih dari satu halaman.")
            }
        }

        // Pemeriksaan terakhir sebelum permintaan BERBAYAR dikirim. Menyusun
        // mosaik butuh waktu, dan dalam mode paralel tombol Stop bisa ditekan
        // (atau chunk lain gagal fatal) tepat di sela itu. Request yang sudah
        // terbang tidak bisa ditarik kembali - paling banyak requestParalel-1
        // permintaan masih akan diselesaikan - tapi yang belum berangkat tidak
        // boleh lagi menghabiskan kuota pengguna.
        if (cancelled) {
            mosaic.recycle()
            log("  [!] Request ${cIdx + 1} dibatalkan sebelum dikirim.")
            return null
        }

        log("  [Request ${cIdx + 1}/$totalChunk] Translating $jumlahPotongan bubbles with ${provider.providerName}...")
        val translations = translateMosaic(mosaic, provider, lang, rujukan)
        mosaic.recycle()

        if (translations.isEmpty()) {
            // rujukan TIDAK di-recycle: bitmapnya milik cacheRujukan dan masih
            // dipakai chunk lain dari halaman yang sama.
            if (cancelled) {
                log("  [!] Request ${cIdx + 1} stopped before it returned.")
            } else {
                log("  [!] No translation returned for request ${cIdx + 1} " +
                    "($jumlahPotongan bubble(s) left untranslated).")
            }
            return null
        }
        val diterima = HashMap<String, String>(translations)

        // Model kadang membalas hanya sebagian nomor. Dulu sisanya
        // dibiarkan dalam bahasa asli tanpa penjelasan, padahal
        // deteksi dan potongannya sudah dibayar. Minta ulang khusus
        // nomor yang hilang, sekali, sebelum menyerah.
        val hilang = idMapping.keys.filter { it !in diterima }
        if (hilang.isNotEmpty() && !cancelled) {
            log("  [!] ${hilang.size} nomor tidak dijawab model, meminta ulang...")
            val ulang = hilang.mapNotNull { id ->
                idMapping[id]?.let { id to it }
            }
            val ulangSusunan = susunMosaik(ulang, numberPaint)
            if (ulangSusunan != null) {
                val ulangMosaic = ulangSusunan.bitmap
                val tambahan = runCatching {
                    translateMosaic(ulangMosaic, provider, lang, rujukan)
                }.getOrElse { emptyMap() }
                ulangMosaic.recycle()
                for ((id, text) in tambahan) {
                    if (id in idMapping && id !in diterima) diterima[id] = text
                }
                val masihHilang = idMapping.keys.count { it !in diterima }
                if (masihHilang > 0) {
                    log("  [!] $masihHilang balon tetap tidak dijawab setelah diminta ulang.")
                } else {
                    log("  [OK] Semua nomor yang tertinggal berhasil dilengkapi.")
                }
            }
        }

        return HasilChunk(diterima, idMapping)
    }

    /**
     * Terapkan jawaban satu request ke state bersama.
     *
     * SELALU dipanggil dari thread pemanggil pass 2, satu per satu dan dalam
     * urutan chunk aslinya. units, cache, dan pageContext karena itu tidak
     * pernah disentuh dua thread sekaligus, dan riwayat konteks tersusun dalam
     * urutan yang sama seperti mode serial.
     */
    private fun terapkanHasil(
        h: HasilChunk, units: List<PageUnit>, provider: LLMProvider, lang: String
    ) {
        for ((localId, text) in h.diterima) {
            val pc = h.idMapping[localId] ?: continue
            units.getOrNull(pc.unitIdx)?.parts?.getOrNull(pc.partIdx)
                ?.translations?.put(pc.boxIdx.toString(), text)
            simpanKeCache(pc, provider, lang, text)
        }

        // Catat ke riwayat, dikelompokkan per halaman sumber: satu
        // mosaik bisa memuat balon dari beberapa halaman sekaligus.
        if (cfg.konteksHalaman) {
            val perHalaman = LinkedHashMap<String, MutableList<String>>()
            for ((localId, text) in h.diterima) {
                val pc = h.idMapping[localId] ?: continue
                val nama = units.getOrNull(pc.unitIdx)?.srcName ?: continue
                perHalaman.getOrPut(nama) { mutableListOf() }.add(text)
            }
            for ((nama, baris) in perHalaman) pageContext.add(nama, baris)
        }
    }


    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    /**
     * Detects bubbles on one page.
     *
     * Tall webtoon strips are scanned in overlapping vertical windows. The
     * detector letterboxes whatever it is given into 640x640, so handing it a
     * 1080x11700 strip squashes every bubble into a couple of pixels and it
     * returns nothing — which is why long chapters came back with only a
     * handful of bubbles. Windows are detected independently and their boxes
     * are mapped back into full-page coordinates.
     */
    private fun detectBoxes(bmp: Bitmap): List<IntArray> {
        warnaKotak.clear()
        gayaKotak.clear()
        teksLepasKotak.clear()
        blokTeksKotak.clear()

        // Jalur baru: detektor komik tiga-kelas. Kalau tersedia, dia yang
        // memimpin — balon, teks-dalam-balon dan teks-luar-balon didapat dari
        // satu kali inferensi, jadi rantai heuristik lama tidak diperlukan.
        // Seam uji: memasang detectorOverride berarti tes itu sengaja
        // menguji jalur YOLO lama, jadi RT-DETR tidak boleh mengambil alih.
        val jalurLamaDipaksa = detectorOverride != null && rtDetectorOverride == null
        if (cfg.detektorRtdetr && !jalurLamaDipaksa) {
            val viaRt = detectViaRtdetr(bmp)
            if (viaRt != null) return viaRt
        }

        val windows = DetectMath.tileWindows(bmp.width, bmp.height)

        val tiled = windows.size > 1

        val raw: List<IntArray> = if (!tiled) {
            BoxUtils.sanitize(detectRaw(bmp), bmp.width, bmp.height)
        } else {
            val acc = ArrayList<IntArray>()
            for (w in windows) {
                if (cancelled) break
                val h = w[1] - w[0]
                if (h <= 0) continue
                val slice = Bitmap.createBitmap(bmp, 0, w[0], bmp.width, h)
                try {
                    // Bersihkan SETIAP jendela sendiri-sendiri. dropFakeGiants
                    // membandingkan luas antar kotak: kalau dijalankan setelah
                    // semua jendela digabung, balon besar di satu jendela akan
                    // menelan balon kecil yang sah di jendela lain. Pada halaman
                    // 1080x2400 itu memangkas 6 balon jadi 1.
                    var wb = BoxUtils.sanitize(detectRaw(slice), slice.width, slice.height)
                    wb = BoxUtils.dropFakeGiants(wb)
                    wb = BoxUtils.mergeOverlapping(wb)
                    for (b in wb) {
                        acc.add(intArrayOf(b[0], b[1] + w[0], b[2], b[3] + w[0]))
                    }
                } finally {
                    if (slice !== bmp) slice.recycle()
                }
            }
            DetectMath.dedupeTiled(acc)
        }

        // Untuk halaman berjendela, giants/merge sudah dikerjakan per jendela.
        var boxes = if (tiled) raw else BoxUtils.mergeOverlapping(BoxUtils.dropFakeGiants(raw))
        boxes = BoxUtils.dropAbsurd(boxes, bmp.width, bmp.height)
        if (cfg.filterSfxAktif) boxes = BoxUtils.dropSfxAndArt(bmp, boxes, cfg.sfxMode)

        // Tahap kedua: teks yang tidak berada di dalam balon mana pun.
        // eyecypy.onnx hanya dilatih mencari balon, jadi narasi kotak, teks
        // latar dan papan nama tidak pernah terlihat olehnya. Wilayah teks
        // hanya DITAMBAHKAN; kotak balon tidak pernah diubah atau dibuang,
        // sehingga paritas dengan cypy Python tetap terjaga saat sakelar mati.
        if (cfg.ocrTeksLepas && !cancelled) {
            val mentah = textDetectorOverride?.invoke(bmp)
                ?: ocr()?.let { runCatching { it.detect(bmp) }.getOrElse { emptyList() } }
                ?: emptyList()
            if (mentah.isNotEmpty()) {
                // Baris teks mentah dari detektor OCR juga menjadi bahan bukti
                // arah baca: yang diperlukan hanya bentuk blok, dan blok yang
                // JATUH DI DALAM balon justru sampel terbaiknya.
                blokTeksKotak.addAll(BoxUtils.sanitize(mentah, bmp.width, bmp.height))
                val tambahan = TextRegionMath.refine(mentah, boxes, bmp.width, bmp.height)
                val bersih = if (cfg.filterSfxAktif) {
                    BoxUtils.dropSfxAndArt(bmp, tambahan, cfg.sfxMode)
                } else tambahan
                if (bersih.isNotEmpty()) {
                    log("  [+] ${bersih.size} teks di luar balon ikut diterjemahkan.")
                    for (b in bersih) teksLepasKotak.add(kunciKotak(b))
                    boxes = boxes + bersih
                }
            }
        }

        // Urutan baca menentukan penomoran ID merah, jadi ia menentukan urutan
        // percakapan yang dibaca model.
        return BoxUtils.urutBaca(boxes, arahBaca(bmp.width, bmp.height, boxes))
    }

    /**
     * Arah baca untuk halaman ini.
     *
     * Bila [Config.arahBacaOtomatis] mati, jawabannya adalah sakelar manual -
     * perilaku lama, apa adanya. Bila menyala, ReadingDirection menilai bukti
     * tata letak; kalau buktinya tipis dipakai keputusan halaman sebelumnya,
     * dan kalau belum ada halaman sebelumnya barulah sakelar manual.
     *
     * Hanya mencatat ke konsol saat arah benar-benar berpengaruh (ada dua
     * balon sebaris), supaya log tidak penuh keterangan yang tidak berarti.
     */
    private fun arahBaca(lebar: Int, tinggi: Int, balon: List<IntArray>): Boolean {
        if (!cfg.arahBacaOtomatis) return cfg.bacaKananKeKiri

        val cadangan = arahTerakhir ?: cfg.bacaKananKeKiri
        val bukti = ReadingDirection.putuskan(lebar, tinggi, blokTeksKotak, cadangan)
        if (bukti.yakin) arahTerakhir = bukti.kananKeKiri

        if (bukti.kananKeKiri) hitungRtl++ else hitungLtr++

        if (ReadingDirection.arahBerpengaruh(balon)) {
            val sumber = if (bukti.yakin) bukti.alasan
            else if (arahTerakhir != null) "mengikuti halaman sebelumnya"
            else "setelan manual"
            log("  [i] Arah baca: ${ReadingDirection.label(bukti.kananKeKiri)} ($sumber).")
        }
        return bukti.kananKeKiri
    }

    /**
     * Deteksi memakai RT-DETR-v2 tiga-kelas.
     *
     * Mengembalikan null bila model tidak tersedia, sehingga pemanggil jatuh
     * kembali ke jalur YOLOv8 lama.
     *
     * Perbedaan penting dengan jalur lama:
     *  - Tidak ada penjendelaan. Uji atas strip 1080x11700 (15 balon
     *    sebenarnya) menghasilkan tepat 15 balon dalam satu inferensi, karena
     *    model dilatih dengan webtoon panjang yang dipecah vertikal.
     *  - Tidak ada dropFakeGiants / dropSfxAndArt. Filter itu ada untuk
     *    membersihkan false positive YOLO; RT-DETR tidak memproduksinya
     *    (contoh terukur: pada satu tangkapan layar non-manga YOLO memberi 7
     *    balon palsu, RT-DETR memberi 0).
     *  - Warna tiap balon diukur di sini dan disimpan untuk tahap render.
     */
    private fun detectViaRtdetr(bmp: Bitmap): List<IntArray>? {
        val dets = rtDetectorOverride?.invoke(bmp)
            ?: rt()?.let { d -> runCatching { d.detect(bmp) }.getOrElse { null } }
            ?: return null

        val bubbles = ArrayList<IntArray>()
        val textIn = ArrayList<IntArray>()
        val textFree = ArrayList<IntArray>()
        for (d in dets) when (d.cls) {
            RtDetector.CLASS_BUBBLE -> bubbles.add(d.box)
            RtDetector.CLASS_TEXT_BUBBLE -> textIn.add(d.box)
            RtDetector.CLASS_TEXT_FREE -> textFree.add(d.box)
        }

        var balon = BoxUtils.sanitize(bubbles, bmp.width, bmp.height)
        balon = BoxUtils.mergeOverlapping(balon)
        balon = BoxUtils.dropAbsurd(balon, bmp.width, bmp.height)

        // Blok teks di dalam balon adalah bahan bukti arah baca: bentuknya
        // memberitahu apakah tulisannya tegak (manga) atau mendatar.
        blokTeksKotak.addAll(BoxUtils.sanitize(textIn, bmp.width, bmp.height))

        // Pasangkan kotak teks ke balonnya, lalu ukur warna.
        val pasangan = RtMath.pairTextToBubbles(balon, textIn)
        if (cfg.warnaOtomatis) {
            for (i in balon.indices) {
                val c = runCatching { Palette.sample(bmp, balon[i], pasangan[i]) }
                    .getOrDefault(Palette.DEFAULT)
                if (c.diukur) warnaKotak[kunciKotak(balon[i])] = c
            }
        }

        // Ronde 25: ukur tipografi teks ASLI selagi piksel aslinya masih utuh.
        // Setelah inpaint tidak ada lagi yang bisa diukur, jadi pengukuran
        // harus terjadi di sini, bukan saat menggambar. Hanya kotak yang
        // punya pasangan teks (kelas text_bubble) yang bisa diukur; balon
        // tanpa pasangan tetap memakai Gaya.BAWAAN alias perilaku lama.
        if (cfg.tipografiAdaptif) {
            val piksel = IntArray(bmp.width * bmp.height)
            bmp.getPixels(piksel, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            for (i in balon.indices) {
                val teks = pasangan[i] ?: continue
                val g = runCatching {
                    Typography.ukur(piksel, bmp.width, bmp.height, balon[i], teks)
                }.getOrDefault(Typography.Gaya.BAWAAN)
                if (g.terukur) gayaKotak[kunciKotak(balon[i])] = g
            }
        }

        var hasil: List<IntArray> = balon

        // Teks di luar balon: langsung dari model, tanpa tahap OCR terpisah.
        if (cfg.ocrTeksLepas) {
            val lepas = RtMath.refineFreeText(
                BoxUtils.sanitize(textFree, bmp.width, bmp.height),
                balon, bmp.width, bmp.height
            )
            if (lepas.isNotEmpty()) {
                log("  [+] ${lepas.size} teks di luar balon ikut diterjemahkan.")
                for (b in lepas) teksLepasKotak.add(kunciKotak(b))
                hasil = hasil + lepas
            }
        }

        val jumlahGelap = warnaKotak.count { Palette.isDark(it.value.background) }
        log("  [i] RT-DETR: ${balon.size} balon, ${textIn.size} teks-dalam, " +
            "${textFree.size} teks-luar" +
            (if (jumlahGelap > 0) ", $jumlahGelap balon gelap dipertahankan" else ""))

        return BoxUtils.urutBaca(hasil, arahBaca(bmp.width, bmp.height, balon))
    }

    /**
     * Sesi inpaint dibuat sekali dan dipakai ulang; memuat model 93 MB untuk
     * tiap halaman akan mendominasi waktu proses. Modelnya diunduh terpisah
     * (tidak dibundel di APK), jadi ketiadaannya adalah keadaan normal:
     * kembalikan null dan alur lanjut memakai isi-putih.
     */
    private fun inpaint(): Inpainter? {
        inpainter?.let { return it }
        if (!Inpainter.tersedia(ctx)) {
            log("  [!] Model inpaint belum diunduh (Setelan > Unduh model), memakai isi-putih.")
            return null
        }
        return runCatching { Inpainter(ctx).also { inpainter = it } }
            .getOrElse { log("  [!] Inpaint tidak bisa dimuat: ${it.message}"); null }
    }

    /**
     * Catat token panggilan terakhir provider dan laporkan biayanya.
     *
     * Dipanggil SETELAH tiap panggilan yang mengembalikan badan respons -
     * termasuk panggilan ulang untuk nomor yang tidak dijawab, karena panggilan
     * itu ditagih penuh juga dan justru bagian biaya yang paling mudah luput.
     *
     * Biaya dihitung dengan provider.modelTerakhir, bukan modelName: Gemini
     * bisa diam-diam pindah ke model cadangan saat model utama sibuk, dan
     * tarifnya berbeda.
     */
    private fun catatPemakaian(provider: LLMProvider) {
        val p = provider.pemakaianTerakhir
        penghitung.tambah(p)
        if (!cfg.hitungBiaya || p.tanpaData) return
        log("  [$] ${Usage.baris(p, provider.modelTerakhir)}")
    }

    /**
     * Kunci cache untuk satu potongan balon, atau null bila gambarnya tidak
     * bisa dibaca (potongan rusak tidak boleh menghentikan proses).
     *
     * Potongan dibaca pada resolusi kecil: yang dibutuhkan hanya sidik 32x32,
     * jadi mendekode 4000 px hanya untuk mengecilkannya lagi adalah pemborosan
     * yang nyata pada bab ratusan balon.
     */
    private fun kunciCache(pc: PendingCrop, provider: LLMProvider, lang: String): Pair<String, Float>? {
        val bmp = Storage.decodeBitmap(pc.file, 256) ?: return null
        return try {
            if (bmp.width <= 0 || bmp.height <= 0) return null
            val rasio = bmp.width.toFloat() / bmp.height.toFloat()
            val sidik = TranslationCache.sidik(bmp)
            TranslationCache.kunci(sidik, lang, provider.modelName) to rasio
        } catch (_: Throwable) {
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun ambilDariCache(pc: PendingCrop, provider: LLMProvider, lang: String): String? {
        val c = cache ?: return null
        val (k, rasio) = kunciCache(pc, provider, lang) ?: return null
        return c.ambil(k, rasio)
    }

    private fun simpanKeCache(pc: PendingCrop, provider: LLMProvider, lang: String, teks: String) {
        val c = cache ?: return
        if (!cfg.cacheTerjemahan) return
        val (k, rasio) = kunciCache(pc, provider, lang) ?: return
        c.simpan(k, teks, rasio)
    }

    private fun kunciKotak(b: IntArray): String = "${b[0]},${b[1]},${b[2]},${b[3]}"

    /**
     * Apakah kotak ini akan DITOLAK oleh drawText karena bentuknya?
     *
     * Kotak yang sangat lebar dan gepeng hampir selalu salah deteksi: garis
     * panel, bilah kredit penerjemah, atau sapuan latar. Menggambar teks di
     * situ merusak halaman, jadi drawText menolaknya.
     *
     * Aturan ini WAJIB dipakai bersama oleh penghapus dan penggambar. Dulu
     * hanya drawText yang memakainya sementara inpaint menghapus semua teks
     * lepas tanpa saring, sehingga kotak di celah itu dihapus LaMa tapi tidak
     * pernah digambari ulang - hasilnya bercak buram di atas artwork tanpa
     * teks apa pun. Menghapus sesuatu hanya boleh kalau kita memang berniat
     * menggambar penggantinya.
     */
    /** Teks yang memang TIDAK dimaksudkan tergambar: kosong atau 'SKIP'. */
    private fun teksKosong(teks: String): Boolean {
        val t = teks.trim()
        return t.isEmpty() || t.uppercase() == "SKIP"
    }

    private fun bentukDitolak(box: IntArray, imgW: Int, imgH: Int): Boolean {
        val w = max(1, box[2] - box[0])
        val h = max(1, box[3] - box[1])
        val ratio = w.toFloat() / h.toFloat()
        val areaRatio = (w.toFloat() * h.toFloat()) / max(1, imgW * imgH).toFloat()
        if (ratio >= 3.2f && w >= imgW * 0.35f) return true
        if (areaRatio >= 0.035f && ratio >= 2.8f) return true
        return false
    }

    /**
     * Kotak yang benar-benar akan dihapus LaMa: teks lepas yang punya
     * terjemahan DAN yang penggantinya pasti tergambar.
     */
    private fun sasaranInpaint(
        boxes: List<IntArray>, translations: Map<String, String>,
        lepas: Set<String>, imgW: Int, imgH: Int
    ): List<IntArray> = translations.mapNotNull { (numStr, teks) ->
        val bIdx = numStr.toIntOrNull() ?: return@mapNotNull null
        val box = boxes.getOrNull(bIdx - 1) ?: return@mapNotNull null
        // Teks kosong / SKIP juga ditolak drawText: jangan hapus apa pun.
        val t = teks.trim()
        if (t.isEmpty() || t.uppercase() == "SKIP") return@mapNotNull null
        if (kunciKotak(box) !in lepas) return@mapNotNull null
        if (bentukDitolak(box, imgW, imgH)) return@mapNotNull null
        box
    }

    private fun detectRaw(bmp: Bitmap): List<IntArray> =
        detectorOverride?.invoke(bmp) ?: yolo().predictStages(bmp)

    private fun buildCrop(bmp: Bitmap, box: IntArray, all: List<IntArray>): Bitmap? {
        val boxW = max(1, box[2] - box[0])
        val boxH = max(1, box[3] - box[1])
        val padX = max(cfg.minPad, (boxW * cfg.padXRatio).toInt())
        val padY = max(cfg.minPad, (boxH * cfg.padYRatio).toInt())

        val c = BoxUtils.roomyCrop(box, all, bmp.width, bmp.height, padX, padY, cfg.overlapBatasCrop)
        val cw = c[2] - c[0]; val ch = c[3] - c[1]
        if (cw <= 0 || ch <= 0) return null

        var crop = Bitmap.createBitmap(bmp, c[0], c[1], cw, ch)
        if (cfg.maskAreaLuarBox) {
            val margin = Mosaic.effectiveMaskMargin(
                boxW, boxH, cfg.maskMargin, cfg.maskMarginBoxRatio, cfg.maskMarginMax
            )
            val masked = Mosaic.maskOutsideMainBox(
                crop, c[0], c[1], box[0], box[1], box[2], box[3], margin
            )
            if (masked !== crop) { crop.recycle(); crop = masked }
        }
        // Skala potongan dibatasi oleh tinggi yang MEMANG akan selamat.
        //
        // Ronde 26 — dulu potongan selalu dinaikkan skalaPotonganMosaik (2.0x),
        // ditulis ke disk, dibaca lagi, lalu shrinkIfTooTall menurunkannya ke
        // ~0.51x supaya mosaik muat di maxTinggiMosaik. Skala akhirnya 1.02x:
        // praktis ukuran asli, tetapi dibayar dengan DUA kali resample bilinear
        // (yang melunakkan tepi huruf) dan berkas disk ~3.8x lebih besar.
        //
        // Sekarang jatah tinggi tiap potongan dihitung di muka, jadi potongan
        // langsung dibuat pada ukuran yang akan dikirim. Satu resample saja,
        // hasilnya sedikit lebih tajam. Batas bawahnya 1.0: tahap ini tidak
        // boleh MENGURANGI resolusi - kalau mosaik masih kepanjangan (potongan
        // tinggi-tinggi), shrinkIfTooTall tetap jadi jaring pengaman.
        val skalaEfektif = skalaPotongan(ch)
        val scaled = Mosaic.scale(crop, skalaEfektif)
        if (scaled !== crop) crop.recycle()
        return scaled
    }

    /**
     * Skala untuk satu potongan setinggi [tinggiAsli] px.
     *
     * Jatah tinggi per potongan = sisa maxTinggiMosaik setelah dikurangi jarak
     * antar potongan dan padding, dibagi jumlah potongan per request. Menaikkan
     * potongan melebihi jatah itu selalu sia-sia: shrinkIfTooTall akan
     * mengembalikannya lagi.
     */
    internal fun skalaPotongan(tinggiAsli: Int): Float {
        val diminta = cfg.skalaPotonganMosaik
        if (diminta <= 1f || tinggiAsli <= 0) return diminta
        val n = max(1, cfg.maxBubblesPerRequest)
        val jatah = (cfg.maxTinggiMosaik - n * cfg.jarakAntarPotongan - 20).toFloat() / n
        if (jatah <= 0f) return 1f
        val batas = jatah / tinggiAsli
        return diminta.coerceAtMost(max(1f, batas))
    }

    private fun translateMosaic(
        mosaic: Bitmap, provider: LLMProvider, lang: String, rujukan: Bitmap? = null
    ): Map<String, String> {
        if (!provider.validateApiKey()) throw ApiKeyException()
        val prompt = buildPrompt(lang, rujukan != null)

        val raw = try {
            rateLimiter.executeWithRetry(provider.providerName) {
                provider.translateWithReference(mosaic, rujukan, prompt)
            }
        } catch (ake: ApiKeyException) {
            throw ake
        } catch (ex: Exception) {
            log("  [!] ${provider.providerName} request failed: ${ex.message}")
            return emptyMap()
        } ?: return emptyMap()

        catatPemakaian(provider)

        return try {
            val obj = JSONObject(BoxUtils.cleanJson(raw))
            val map = LinkedHashMap<String, String>()
            for (key in obj.keys()) map[key] = obj.optString(key, "")
            map
        } catch (e: Exception) {
            log("  [!] Could not parse translation JSON: ${e.message}")
            emptyMap()
        }
    }

    /** Re-applies the write-back guards and draws the translated text. */
    /**
     * Salin satu halaman beserta metadatanya ke proyek yang sedang direkam.
     *
     * Gagal menyalin TIDAK boleh menggagalkan proses: proyek adalah fitur
     * kenyamanan, sementara keluaran gambar adalah hasil yang dibayar
     * pengguna. Karena itu seluruh badannya dibungkus runCatching.
     */
    private fun rekamHalaman(unit: PageUnit) {
        val prj = project ?: return
        runCatching {
            val dir = File(prj.dir(ctx), "pages").apply { mkdirs() }
            for (part in unit.parts) {
                if (part.failed || part.boxes.isEmpty()) continue
                val nama = "p%04d.png".format(prj.pages.size)
                val dst = File(dir, nama)
                part.file.copyTo(dst, overwrite = true)

                val page = Project.Page(
                    srcName = unit.srcName,
                    imagePath = "pages/$nama",
                    width = part.width,
                    height = part.height
                )
                page.boxes.addAll(part.boxes)
                page.translations.putAll(part.translations)
                page.colors.putAll(part.warna)
                page.styles.putAll(part.gaya)
                page.freeText.addAll(part.lepas)
                prj.pages.add(page)
            }
        }.onFailure { log("  [!] Proyek tidak bisa disimpan: ${it.message}") }
    }

    /**
     * Gambar ulang SATU halaman proyek memakai terjemahan yang tersimpan.
     *
     * Inilah inti mode koreksi manual: tidak ada deteksi, tidak ada panggilan
     * jaringan, hanya piksel. Selalu dimulai dari salinan halaman bersih,
     * sehingga menyunting balon yang sama sepuluh kali tetap menghasilkan
     * gambar yang sama seperti menyuntingnya sekali.
     *
     * @return bitmap hasil, atau null bila halamannya tidak bisa dibaca.
     */
    fun renderProjectPage(prj: Project, page: Project.Page): Bitmap? {
        val src = prj.pageFile(ctx, page)
        val bmp = Storage.decodeBitmap(src, cfg.maxImageSide) ?: return null

        if (cfg.inpaintLama) {
            val sasaran = sasaranInpaint(
                page.boxes, page.translations, page.freeText, bmp.width, bmp.height
            )
            if (sasaran.isNotEmpty()) {
                val seam = inpaintOverride
                val ip = if (seam != null) null else inpaint()
                if (seam != null || ip != null) {
                    runCatching {
                        if (seam != null) seam(sasaran)
                        else ip!!.erase(bmp, sasaran) { m -> log(m) }
                    }.onFailure { log("  [!] Inpaint dilewati: ${it.message}") }
                }
            }
        }

        val canvas = Canvas(bmp)
        for ((numStr, text) in page.translations) {
            val bIdx = numStr.toIntOrNull() ?: continue
            val box = page.boxes.getOrNull(bIdx - 1) ?: continue
            drawText(canvas, bmp, box, text, prj.targetLanguage, page.colors, page.styles)
        }
        return bmp
    }

    private fun drawText(
        canvas: Canvas, bmp: Bitmap, box: IntArray, text0: String, lang: String,
        warnaHalaman: Map<String, Palette.Colors> = emptyMap(),
        gayaHalaman: Map<String, Typography.Gaya> = emptyMap()
    ): Boolean {
        val text = text0.trim()
        if (text.isEmpty() || text.uppercase() == "SKIP") return false

        val (x1, y1, x2, y2) = listOf(box[0], box[1], box[2], box[3])
        val w = max(1, x2 - x1)
        val h = max(1, y2 - y1)
        val ratio = w.toFloat() / h.toFloat()

        // Aturan bentuk yang sama persis dipakai sasaranInpaint, supaya kotak
        // yang ditolak di sini tidak pernah terlanjur dihapus LaMa.
        if (bentukDitolak(box, bmp.width, bmp.height)) return false

        val flat = ratio >= cfg.rasioBoxGepeng &&
                w >= bmp.width * cfg.lebarBoxGepengRatio &&
                h <= bmp.height * cfg.tinggiBoxGepengRatio

        val usePatch = cfg.patchGepeng && flat
        // Warna diukur saat deteksi (RT-DETR). Kalau kotak ini tidak punya
        // entri - misalnya berasal dari jalur YOLO lama, atau sakelar warna
        // dimatikan - Palette.DEFAULT mengembalikan perilaku putih/hitam.
        val warna = if (cfg.warnaOtomatis) {
            warnaHalaman[kunciKotak(box)] ?: Palette.DEFAULT
        } else Palette.DEFAULT
        renderer.drawInBubble(
            canvas, bmp, text, x1, y1, x2, y2,
            backgroundPatch = usePatch,
            targetLanguage = lang,
            maskMarginRatio = cfg.maskMarginRatio,
            colors = warna,
            ikutiKontur = cfg.konturBalon,
            // Sama seperti warna: tanpa entri, Gaya.BAWAAN = perilaku lama.
            gaya = if (cfg.tipografiAdaptif) {
                gayaHalaman[kunciKotak(box)] ?: Typography.Gaya.BAWAAN
            } else Typography.Gaya.BAWAAN
        )
        return true
    }

    /**
     * Auto-split for landscape (double-page) images: splits right-to-left,
     * translates each half, then recombines with hconcat order reversed.
     */
    fun splitLandscape(bmp: Bitmap): List<Bitmap>? {
        val ratio = bmp.width.toFloat() / bmp.height.toFloat()
        if (ratio <= 1.2f) return null
        val parts = max(2, (ratio / 0.71f).roundToInt())
        val splitWidth = bmp.width / parts
        val out = ArrayList<Bitmap>()
        for (i in 0 until parts) {
            val xEnd = bmp.width - (i * splitWidth)
            val xStart = if (i == parts - 1) 0 else xEnd - splitWidth
            val w = xEnd - xStart
            if (w <= 0) continue
            out.add(Bitmap.createBitmap(bmp, xStart, 0, w, bmp.height))
        }
        return out
    }

    fun hconcatReversed(parts: List<Bitmap>): Bitmap {
        val ordered = parts.reversed()
        val targetH = ordered.maxOf { it.height }
        val scaled = ordered.map {
            if (it.height == targetH) it
            else Bitmap.createScaledBitmap(it, (it.width * targetH / it.height), targetH, true)
        }
        val totalW = scaled.sumOf { it.width }
        val out = Bitmap.createBitmap(totalW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        var x = 0
        for (b in scaled) {
            canvas.drawBitmap(b, null, Rect(x, 0, x + b.width, targetH), Paint(Paint.FILTER_BITMAP_FLAG))
            x += b.width
        }
        return out
    }

    // ------------------------------------------------------------------
    // Prompt (ported verbatim from translate_mosaic)
    // ------------------------------------------------------------------

    /**
     * Baca berkas glosarium sekali per proses. Kegagalan apa pun di sini tidak
     * boleh menggagalkan terjemahan: glosarium adalah penyempurna, bukan
     * syarat. Berkas yang hilang atau rusak hanya dilaporkan ke log.
     */
    private fun muatGlosarium() {
        glossaryOverride?.let {
            glossary = Glossary.budgetEntries(it)
            if (glossary.isNotEmpty()) log("[i] Glosarium: ${glossary.size} istilah.")
            return
        }
        glossary = emptyList()
        val uriStr = cfg.glossaryUri
        if (uriStr.isBlank()) return
        try {
            val teks = ctx.contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            if (teks == null) {
                log("[!] Glosarium tidak bisa dibuka, dilewati.")
                return
            }
            val hasil = Glossary.parse(teks)
            glossary = Glossary.budgetEntries(hasil.entries)
            when {
                hasil.entries.isEmpty() ->
                    log("[!] Glosarium kosong atau formatnya tidak dikenali, dilewati.")
                else -> {
                    log("[i] Glosarium: ${glossary.size} istilah dipakai" +
                        (if (hasil.entries.size > glossary.size)
                            " (dari ${hasil.entries.size}, dibatasi ${Glossary.MAX_ENTRIES})" else "") +
                        ".")
                    if (hasil.conflicts.isNotEmpty()) {
                        log("[!] ${hasil.conflicts.size} istilah bentrok, dipakai yang pertama:")
                        for (c in hasil.conflicts.take(5)) log("    $c")
                    }
                    if (hasil.rejected.isNotEmpty()) {
                        log("[!] ${hasil.rejected.size} baris glosarium tidak terbaca.")
                    }
                }
            }
        } catch (e: Exception) {
            log("[!] Glosarium gagal dibaca (${e.javaClass.simpleName}), dilewati.")
        }
    }

    internal fun buildPrompt(lang0: String, adaRujukan: Boolean = false): String {
        val lang = lang0.ifBlank { "Indonesian" }
        val examples = mapOf(
            "english" to ("Hello!" to "Mother... wait..."),
            "indonesian" to ("Cepat bangun!" to "Ibu... tunggu..."),
            "japanese" to ("早く起きて！" to "お母さん…待って…"),
            "mandarin" to ("快点起床！" to "妈妈……等等……"),
            "spanish" to ("¡Despierta rápido!" to "Madre... espera..."),
            "portuguese" to ("Acorde rápido!" to "Mãe... espere..."),
            "javanese" to ("Ndang tangi!" to "Ibu... enteni...")
        )
        val (ex1, ex3) = examples[lang.lowercase()] ?: examples["english"]!!

        // Contoh ID kedua sengaja berupa erangan. Dulu nilainya 'SKIP', dan
        // model meniru contoh itu: pada halaman uji pengguna, balon berisi
        // "うぐっ…" dikembalikan sebagai SKIP sehingga satu-satunya balon yang
        // tidak tersentuh di seluruh halaman. Suara yang keluar dari mulut
        // tokoh adalah dialog, jadi contohnya harus memperlihatkan terjemahan.
        val ex2 = when (lang.lowercase()) {
            "indonesian" -> "Ugh..."
            "japanese" -> "うぐっ…"
            "mandarin" -> "呃……"
            "spanish" -> "Ugh..."
            "portuguese" -> "Ugh..."
            "javanese" -> "Adhuh..."
            else -> "Ugh..."
        }

        return buildString {
            append("You are an accurate, literal manga translator from its original language to $lang. ")
            append("The image contains several speech bubbles arranged vertically. ")
            append("Each bubble is prefixed with a LARGE RED NUMBER on its left as its ID. \n\n")

            append("MAIN TASK:\n")
            append("Read the text in each bubble, then translate it into $lang, faithfully preserving the original meaning. \n\n")

            append("VERTICAL READING RULES:\n")
            append("1. Read vertical text from top to bottom. \n")
            append("2. If there are multiple vertical columns, read the rightmost column first, then move left. \n")
            append("3. Do not reverse column orders. \n")
            append("4. Do not mix text between bubbles. \n\n")

            append("TRANSLATION RULES:\n")
            append("1. Translate literally and accurately. Do not make it overly polite, do not summarize, and do not invent content. \n")
            append("2. Do not add subjects or objects not present in the original text. \n")
            append("3. Do not alter the relationships between characters. \n")
            append("4. If the text is rude, explicit, teasing, degrading, bashful, or begging, maintain that exact tone. \n")
            append("5. If the text contains a question, the $lang output must also be a question. \n")
            append("6. Do not create new sentences that sound unnatural if they are not in the original text. \n")
            append("7. For long sentences, keep all parts of the meaning. Do not truncate. \n")
            append("8. If unsure about some text, use [?] for that part. \n")
            append("9. Reply with 'SKIP' ONLY when the bubble is truly empty, contains no readable characters at all, or is pure background art. A bubble that contains ANY readable text must be translated. \n\n")

            append("HONORIFICS RULE:\n")
            append("1. If the original text contains Japanese honorifics (san, kun, chan, sama, senpai, sensei, etc.), keep them as-is in the translation. Do NOT translate honorifics. \n")
            append("2. Examples: -san stays as -san, -kun stays as -kun, -chan stays as -chan. \n")
            append("3. This applies even when translating to non-Japanese languages. \n\n")

            append("SFX AND VOICE RULE:\n")
            append("1. A sound a CHARACTER MAKES WITH THEIR VOICE is dialogue, not SFX. Always translate it. \n")
            append("2. This includes groans, gasps, cries, laughs, screams, sighs, grunts, hesitation and stammering. \n")
            append("3. Examples that MUST be translated: うぐっ, ぐっ, あっ, はぁ, うわぁ, ひっ, んっ, えっ, ふふ, あはは, きゃー, うっ, ぐぬぬ. \n")
            append("4. Render them as a natural $lang equivalent interjection, keeping the same length and intensity. \n")
            append("5. Only NON-VOCAL environmental sounds drawn as background art are SFX: ドドド, ゴゴゴ, バキ, ドカーン, キラキラ. \n")
            append("6. Even so, if such a sound sits INSIDE a speech bubble, translate it rather than skipping it. \n")
            append("7. If a bubble has BOTH dialogue and SFX, translate the dialogue and keep the vocal sounds. \n\n")

            append("RETURN ALL IDs RULE:\n")
            append("1. You MUST return a JSON entry for EVERY red ID number visible in the image. \n")
            append("2. Do NOT skip any ID numbers. If ID 1, 2, 3, 4, 5 are visible, your JSON must contain all 5 keys. \n")
            append("3. For IDs you cannot read or translate, use 'SKIP' as the value. \n")
            append("4. This is critical - missing IDs will cause errors. \n")
            append("5. Before answering, check every ID once more: a short bubble with only one or two characters is still a real bubble and usually a vocal reaction. Do not leave it out and do not mark it 'SKIP' just because it is short. \n\n")

            // Glosarium ditaruh sesudah semua aturan lain dan tepat sebelum
            // format keluaran: aturan yang paling dekat dengan akhir prompt
            // cenderung paling dipatuhi, dan baris ini memang harus menang
            // atas aturan honorifik di atasnya.
            // Baca lewat override bila ada, bukan lewat field yang hanya terisi
            // di dalam run(): dengan begitu seam tes benar-benar menguji jalur
            // yang sama dengan produksi. promptSection sudah menerapkan batas
            // jumlah istilahnya sendiri.
            append(Glossary.promptSection(glossaryOverride ?: glossary))
            if (cfg.konteksHalaman) append(pageContext.promptSection())
            // Hanya dijanjikan bila gambarnya benar-benar ikut dikirim.
            if (adaRujukan) append(PageReference.promptSection())

            append("OUTPUT FORMAT:\n")
            append("Provide the response ONLY in valid JSON without markdown formatting. \n")
            append("Keys must be the red ID numbers as strings. \n")
            append("Values must be the $lang translation or 'SKIP'. \n")
            append("Example output: {\"1\": \"$ex1\", \"2\": \"$ex2\", \"3\": \"$ex3\", \"4\": \"SKIP\", \"5\": \"$ex1\"}")
        }
    }
}
