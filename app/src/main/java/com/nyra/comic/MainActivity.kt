package com.nyra.comic

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nyra.comic.databinding.ActivityMainBinding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), TranslationService.Companion.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var cfg: Config

    private val inputUris = ArrayList<Uri>()
    private var outputTree: Uri? = null

    private val sfxModes = listOf("relaxed", "balanced", "strict")
    private val maxSideSteps = (600..4000 step 100).toList()

    // ---- launchers ----

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        for (u in uris) {
            runCatching {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (!inputUris.contains(u)) inputUris.add(u)
        }
        renderFiles()
    }

    private val pickGlossary = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        cfg.glossaryUri = uri.toString()
        cfg.glossaryName = Storage.displayName(this, uri)
        renderGlosarium()
    }

    private val pickTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        outputTree = uri
        cfg.outputTreeUri = uri.toString()
        renderOutput()
    }

    private val askNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        cfg = Config.get(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        cfg.outputTreeUri.takeIf { it.isNotBlank() }?.let { outputTree = Uri.parse(it) }

        setupProviders()
        setupLanguages()
        setupTweaks()
        setupButtons()

        renderFiles()
        renderOutput()
        restoreLog()
        setRunning(TranslationService.running)
    }

    override fun onResume() {
        super.onResume()
        TranslationService.listener = this
        setRunning(TranslationService.running)
    }

    override fun onPause() {
        if (TranslationService.listener === this) TranslationService.listener = null
        super.onPause()
    }

    // ---- setup ----

    private fun setupProviders() {
        val keys = Providers.REGISTRY.keys.toList()
        val names = Providers.REGISTRY.values.map { it.displayName }
        b.spProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        b.spProvider.setSelection(keys.indexOf(cfg.provider).coerceAtLeast(0))

        b.spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val key = keys[pos]
                cfg.provider = key
                bindProviderFields(key)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        bindProviderFields(cfg.provider)

        b.etApiKey.addTextChangedListener(simpleWatcher { cfg.setApiKey(cfg.provider, it) })
        b.btnFetchModels.setOnClickListener { fetchModelsForKey() }
        b.etModel.addTextChangedListener(simpleWatcher { cfg.setModel(cfg.provider, it) })
        b.etBaseUrl.addTextChangedListener(simpleWatcher { cfg.customBaseUrl = it })
    }

    /**
     * Tanya langsung ke API key: model apa saja yang benar-benar bisa dipakai.
     *
     * ListModels saja tidak cukup - Google tetap mencantumkan model yang sudah
     * ditutup untuk pengguna baru (mis. gemini-2.5-flash menjawab 404). Karena
     * itu tiap kandidat diuji dengan satu request kecil sebelum ditawarkan.
     */
    private fun fetchModelsForKey() {
        val provider = cfg.provider
        val key = b.etApiKey.text?.toString()?.trim().orEmpty()

        if (provider != "gemini") {
            setModelStatus("Cek otomatis baru tersedia untuk Google Gemini.", warn = true)
            return
        }
        if (key.isBlank()) {
            setModelStatus("Isi API key dulu, lalu tekan Cek model.", warn = true)
            return
        }

        b.btnFetchModels.isEnabled = false
        setModelStatus("Menghubungi Gemini...", warn = false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val p = GeminiProvider(key, "gemini-flash-latest")
                    val listed = p.listVisionModels()
                    if (listed.isEmpty()) return@runCatching emptyList<String>()

                    // Buang model yang bukan untuk terjemahan teks-dari-gambar:
                    // generator gambar, TTS, embedding, dan varian eksperimental.
                    val usable = listed.filter { m ->
                        !m.contains("-image") && !m.contains("-tts") &&
                            !m.contains("embedding") && !m.contains("embed") &&
                            !m.contains("-eap") && !m.contains("thinking") &&
                            !m.contains("learnlm") && !m.contains("gemma")
                    }

                    // Utamakan alias -latest dan flash: paling cocok untuk manga.
                    val ranked = usable.sortedWith(
                        compareBy(
                            { !it.endsWith("-latest") },
                            { !it.contains("flash") },
                            { it }
                        )
                    )
                    // Uji kandidat teratas; berhenti setelah dapat 6 yang valid.
                    val ok = ArrayList<String>()
                    for (m in ranked) {
                        if (ok.size >= 6) break
                        if (p.probeModel(m) == null) ok.add(m)
                    }
                    ok
                }
            }

            b.btnFetchModels.isEnabled = true
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    setModelStatus(
                        "Tidak ada model yang bisa dipakai dengan key ini.", warn = true
                    )
                } else {
                    b.etModel.setSimpleItems(models.toTypedArray())
                    val current = b.etModel.text?.toString()?.trim().orEmpty()
                    if (current.isBlank() || current !in models) {
                        b.etModel.setText(models.first(), false)
                        cfg.setModel(provider, models.first())
                    }
                    setModelStatus(
                        "${models.size} model terverifikasi. Terpilih: " +
                            (b.etModel.text?.toString().orEmpty()), warn = false
                    )
                }
            }.onFailure {
                setModelStatus("Gagal: ${it.message?.take(90)}", warn = true)
            }
        }
    }

    private fun setModelStatus(msg: String, warn: Boolean) {
        b.tvModelStatus.visibility = View.VISIBLE
        b.tvModelStatus.text = msg
        b.tvModelStatus.setTextColor(
            if (warn) 0xFFE69933.toInt() else 0xFF00FF00.toInt()
        )
    }

    private fun bindProviderFields(key: String) {
        val meta = Providers.REGISTRY[key] ?: return
        b.tvProviderDesc.text = buildString {
            append(meta.desc)
            if (meta.url.isNotBlank()) append("  •  ").append(meta.url)
            if (!meta.requiresKey) append("  •  API key optional")
        }
        b.etApiKey.setText(cfg.apiKey(key))

        // Tawarkan model yang diketahui valid; pengguna tetap bisa mengetik bebas.
        val suggestions = Providers.MODEL_SUGGESTIONS[key].orEmpty()
        b.etModel.setSimpleItems(suggestions.toTypedArray())
        // filter=false: jangan saring daftar berdasarkan teks yang sudah ada.
        b.etModel.setText(cfg.model(key), false)
        b.etBaseUrl.setText(cfg.customBaseUrl)
        b.rowBaseUrl.visibility = if (key == "custom") View.VISIBLE else View.GONE
        b.tvModelStatus.visibility = View.GONE
    }

    private fun setupLanguages() {
        b.spLanguage.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Langs.CHOICES
        )
        b.spLanguage.setSelection(Langs.CHOICES.indexOf(cfg.targetLanguage).coerceAtLeast(0))
        b.spLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                cfg.targetLanguage = Langs.CHOICES[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupTweaks() {
        b.tweakHeader.setOnClickListener {
            val show = b.tweakBody.visibility != View.VISIBLE
            b.tweakBody.visibility = if (show) View.VISIBLE else View.GONE
            b.tvTweakToggle.text = if (show) "Hide" else "Show"
        }

        b.sbBubbles.progress = (cfg.maxBubblesPerRequest - 1).coerceIn(0, 29)
        b.sbDelay.progress = (cfg.minRequestDelay * 10).toInt().coerceIn(0, 200)
        b.sbPadX.progress = (cfg.padXRatio * 100).toInt().coerceIn(0, 100)
        b.sbPadY.progress = (cfg.padYRatio * 100).toInt().coerceIn(0, 100)
        b.sbMask.progress = (cfg.maskMarginRatio * 100).toInt().coerceIn(0, 40)
        b.sbMaxSide.progress = maxSideSteps.indexOfFirst { it >= cfg.maxImageSide }.coerceAtLeast(0)

        bindSeek(b.sbBubbles) {
            val v = it + 1; cfg.maxBubblesPerRequest = v
            b.lblBubbles.text = "Balon per permintaan: $v"
        }
        bindSeek(b.sbDelay) {
            val v = it / 10f; cfg.minRequestDelay = v
            b.lblDelay.text = "Jeda antar permintaan: ${"%.1f".format(v)}s"
        }
        bindSeek(b.sbPadX) {
            val v = it / 100f; cfg.padXRatio = v
            b.lblPadX.text = "Padding potongan X: ${"%.2f".format(v)}"
        }
        bindSeek(b.sbPadY) {
            val v = it / 100f; cfg.padYRatio = v
            b.lblPadY.text = "Padding potongan Y: ${"%.2f".format(v)}"
        }
        bindSeek(b.sbMask) {
            val v = it / 100f; cfg.maskMarginRatio = v
            b.lblMask.text = "Margin bersih balon: ${"%.2f".format(v)}"
        }
        bindSeek(b.sbMaxSide) {
            val v = maxSideSteps[it.coerceIn(0, maxSideSteps.size - 1)]
            cfg.maxImageSide = v
            b.lblMaxSide.text = "Sisi gambar maksimum: $v px"
        }

        b.spSfx.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            sfxModes.map { it.replaceFirstChar { c -> c.uppercase() } }
        )
        b.spSfx.setSelection(sfxModes.indexOf(cfg.sfxMode).coerceAtLeast(0))
        b.spSfx.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                cfg.sfxMode = sfxModes[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.swPatch.isChecked = cfg.patchGepeng
        b.swPatch.setOnCheckedChangeListener { _, checked -> cfg.patchGepeng = checked }

        b.swOcr.isChecked = cfg.ocrTeksLepas
        b.swOcr.setOnCheckedChangeListener { _, checked -> cfg.ocrTeksLepas = checked }
        b.swRtdetr.isChecked = cfg.detektorRtdetr
        b.swRtdetr.setOnCheckedChangeListener { _, checked -> cfg.detektorRtdetr = checked }
        b.swWarna.isChecked = cfg.warnaOtomatis
        b.swWarna.setOnCheckedChangeListener { _, checked -> cfg.warnaOtomatis = checked }

        // Arah baca: sakelar manual hanya berlaku saat deteksi otomatis mati,
        // jadi ia diredupkan supaya pengguna tidak mengira setelannya diabaikan
        // diam-diam.
        b.swArahOtomatis.isChecked = cfg.arahBacaOtomatis
        b.swKananKiri.isEnabled = !cfg.arahBacaOtomatis
        b.swArahOtomatis.setOnCheckedChangeListener { _, checked ->
            cfg.arahBacaOtomatis = checked
            b.swKananKiri.isEnabled = !checked
        }
        b.swKananKiri.isChecked = cfg.bacaKananKeKiri
        b.swKananKiri.setOnCheckedChangeListener { _, checked -> cfg.bacaKananKeKiri = checked }
        b.swLanjut.isChecked = cfg.lanjutkanArsip
        b.swLanjut.setOnCheckedChangeListener { _, checked -> cfg.lanjutkanArsip = checked }
        b.swRujukan.isChecked = cfg.gambarRujukan
        b.swRujukan.setOnCheckedChangeListener { _, checked -> cfg.gambarRujukan = checked }
        b.swKonteks.isChecked = cfg.konteksHalaman
        b.swKonteks.setOnCheckedChangeListener { _, checked -> cfg.konteksHalaman = checked }
        b.swProyek.isChecked = cfg.simpanProyek
        b.swProyek.setOnCheckedChangeListener { _, checked -> cfg.simpanProyek = checked }

        b.swCache.isChecked = cfg.cacheTerjemahan
        b.swCache.setOnCheckedChangeListener { _, checked -> cfg.cacheTerjemahan = checked }
        b.swBiaya.isChecked = cfg.hitungBiaya
        b.swBiaya.setOnCheckedChangeListener { _, checked ->
            cfg.hitungBiaya = checked
            renderPemakaian()
        }

        b.btnResetBiaya.setOnClickListener {
            cfg.biayaKumulatif = 0f
            cfg.tokenKumulatif = 0L
            renderPemakaian()
            Toast.makeText(this, R.string.cost_reset, Toast.LENGTH_SHORT).show()
        }
        b.btnBersihkanCache.setOnClickListener {
            runCatching {
                TranslationCache(TranslationCache.bawaan(filesDir)).bersihkan()
            }
            renderPemakaian()
            Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
        }
        renderPemakaian()

        b.swInpaint.isChecked = cfg.inpaintLama
        b.swInpaint.setOnCheckedChangeListener { _, checked ->
            cfg.inpaintLama = checked
            // Menyalakan sakelar tanpa model hanya menghasilkan isi-putih
            // diam-diam, jadi pengguna diberi tahu di tempat.
            if (checked && !Inpainter.tersedia(this)) {
                Toast.makeText(this, R.string.model_belum_ada, Toast.LENGTH_LONG).show()
            }
        }

        b.btnUnduhModel.setOnClickListener { unduhModel() }
        b.btnHapusModel.setOnClickListener {
            val bebas = ModelDownloader.hapus(this)
            Toast.makeText(this, getString(R.string.model_terhapus,
                ModelDownloader.mb(bebas)), Toast.LENGTH_SHORT).show()
            renderModel()
        }
        renderModel()

        b.btnPilihGlosarium.setOnClickListener {
            // Sengaja */* : penyedia berkas sering melabeli .tsv dan .txt
            // dengan MIME yang tidak konsisten, dan filter ketat justru
            // membuat berkasnya tampak abu-abu dan tidak bisa dipilih.
            runCatching { pickGlossary.launch(arrayOf("*/*")) }
        }
        b.btnHapusGlosarium.setOnClickListener {
            cfg.glossaryUri = ""
            cfg.glossaryName = ""
            renderGlosarium()
        }
        renderGlosarium()
        renderFont()

        b.btnResetTweaks.setOnClickListener {
            cfg.maxBubblesPerRequest = 20
            cfg.minRequestDelay = 2.0f
            cfg.padXRatio = 0.40f
            cfg.padYRatio = 0.25f
            cfg.maskMarginRatio = 0.12f
            cfg.maxImageSide = 2200
            cfg.sfxMode = "balanced"
            cfg.patchGepeng = true
            cfg.ocrTeksLepas = true
            cfg.detektorRtdetr = true
            cfg.warnaOtomatis = true
            cfg.bacaKananKeKiri = true
            cfg.arahBacaOtomatis = true
            cfg.cacheTerjemahan = true
            cfg.hitungBiaya = true
            cfg.gambarRujukan = true
            cfg.lanjutkanArsip = true
            cfg.konteksHalaman = true
            cfg.simpanProyek = true
            cfg.inpaintLama = false
            recreate()
        }
    }

    /**
     * Isi kartu pemakaian: total token/biaya kumulatif dan ukuran cache.
     *
     * Angka biaya selalu diberi label "perkiraan" karena tarifnya tabel statis
     * di dalam aplikasi, bukan tagihan sungguhan dari provider.
     */
    private fun renderPemakaian() {
        val tok = cfg.tokenKumulatif
        val usd = cfg.biayaKumulatif.toDouble()
        val entri = runCatching {
            TranslationCache(TranslationCache.bawaan(filesDir)).ukuran
        }.getOrDefault(0)

        val baris = StringBuilder()
        if (tok > 0) {
            baris.append("Total terpakai: ${Usage.ringkasToken(tok)} token")
            if (usd > 0) baris.append(" · perkiraan ${Usage.rupiahkanUsd(usd)}")
        } else {
            baris.append("Belum ada pemakaian tercatat.")
        }
        baris.append("\nCache: $entri balon tersimpan.")
        b.tvPemakaian.text = baris.toString()
    }

    private fun bindSeek(sb: SeekBar, onValue: (Int) -> Unit) {
        onValue(sb.progress)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onValue(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun setupButtons() {
        b.btnPickFiles.setOnClickListener {
            pickFiles.launch(arrayOf(
                "image/png", "image/jpeg", "image/webp",
                "application/pdf", "application/zip",
                "application/x-cbz", "application/vnd.comicbook+zip",
                "application/x-rar-compressed", "application/vnd.comicbook-rar",
                "application/epub+zip",
                "application/octet-stream"
            ))
        }
        b.btnClearFiles.setOnClickListener { inputUris.clear(); renderFiles() }
        b.btnPickOutput.setOnClickListener { pickTree.launch(null) }
        b.tvClearLog.setOnClickListener {
            TranslationService.logLines.clear()
            b.tvLog.text = ""
        }
        b.btnTranslate.setOnClickListener { startTranslation() }
        b.btnStop.setOnClickListener { TranslationService.stop(this) }
        b.btnResults.setOnClickListener {
            startActivity(Intent(this, ResultActivity::class.java))
        }
    }

    // ---- actions ----

    private fun startTranslation() {
        if (inputUris.isEmpty()) { toast("Pilih minimal satu berkas dulu."); return }
        val out = outputTree
        if (out == null) { toast("Pilih folder keluaran dulu."); return }

        val meta = cfg.currentMeta()
        if (meta.requiresKey && cfg.currentKey().isBlank()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("API key required")
                .setMessage("${meta.displayName} needs an API key. Get one at ${meta.url}")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (cfg.provider == "custom" && cfg.customBaseUrl.isBlank()) {
            toast("Penyedia custom butuh base URL."); return
        }
        if (cfg.currentModel().isBlank()) { toast("Nama model masih kosong."); return }

        // Peringatan font harus muncul SEBELUM terjemahan dibayar: bila
        // perangkat ini memang tak sanggup menggambar aksara tujuan, pengguna
        // baru menyadarinya setelah seluruh bab selesai diproses.
        //
        // perluUntukBahasa() bertanya ke perangkat lewat Paint.hasGlyph, bukan
        // mengasumsikan dari cakupan font bawaan. Mayoritas ponsel menambal
        // Hangul/Thai/Han dari font sistem, jadi di sana dialog ini memang
        // tidak boleh muncul sama sekali.
        val fontKurang = FontPack.perluUntukBahasa(this, cfg.targetLanguage)
        if (fontKurang != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.font_judul)
                .setMessage(getString(R.string.font_peringatan, cfg.targetLanguage))
                .setPositiveButton(R.string.font_unduh) { _, _ -> unduhFont(fontKurang) }
                .setNegativeButton(R.string.font_nanti) { _, _ -> mulaiTerjemahan(out) }
                .show()
            return
        }

        mulaiTerjemahan(out)
    }

    private fun mulaiTerjemahan(out: Uri) {
        b.tvLog.text = ""
        appendLog("Starting translation of ${inputUris.size} item(s)...")
        setRunning(true)
        TranslationService.start(this, inputUris, out, cfg.targetLanguage)
    }

    private fun setRunning(running: Boolean) {
        b.btnTranslate.isEnabled = !running
        b.btnStop.isEnabled = running
        b.btnTranslate.text = getString(if (running) R.string.btn_translating else R.string.btn_translate)
        b.progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) b.progress.isIndeterminate = true
    }

    private fun renderFiles() {
        b.tvFiles.text = if (inputUris.isEmpty())
            "No files selected. Supported: PNG, JPG, WEBP, PDF, ZIP/CBZ, RAR/CBR"
        else inputUris.joinToString("\n") { "• " + Storage.displayName(this, it) }
    }

    private fun renderOutput() {
        val uri = outputTree
        b.tvOutput.text = if (uri == null)
            "Not set — results are saved into a language subfolder (ID/, EN/, JP/…)"
        else "Output: ${Uri.decode(uri.toString().substringAfterLast("/"))}  →  " +
                "${Langs.code(cfg.targetLanguage).uppercase()}/"
    }

    /**
     * Tampilkan nama berkas glosarium beserta jumlah istilah yang terbaca.
     * Membaca berkasnya di sini disengaja: kalau formatnya salah, pengguna
     * tahu saat memilih, bukan setelah menunggu proses berjalan.
     */
    /** Sedang mengunduh: dipakai tombol batal dan penjaga klik ganda. */
    @Volatile private var unduhBerjalan = false
    @Volatile private var unduhDibatalkan = false

    private fun renderModel() {
        val ada = Inpainter.tersedia(this)
        b.tvModelInpaint.setText(if (ada) R.string.model_siap else R.string.model_belum_ada)
        b.btnUnduhModel.visibility = if (ada) View.GONE else View.VISIBLE
        b.btnHapusModel.visibility = if (ada) View.VISIBLE else View.GONE
    }

    /**
     * Unduh model inpaint di utas IO sambil melaporkan kemajuan.
     *
     * Tombol unduh berubah jadi tombol batal selama proses: unduhan 93 MB di
     * jaringan seluler bisa lama dan pengguna harus punya jalan keluar.
     */
    private fun unduhModel() {
        if (unduhBerjalan) { unduhDibatalkan = true; return }
        unduhBerjalan = true
        unduhDibatalkan = false
        b.btnUnduhModel.setText(R.string.btn_batal_unduh)
        b.tvModelInpaint.setText(R.string.model_mengunduh)
        lifecycleScope.launch {
            val hasil = withContext(Dispatchers.IO) {
                ModelDownloader.unduhLama(
                    applicationContext,
                    progress = { sudah, total ->
                        val persen = if (total > 0) (sudah * 100 / total).toInt() else 0
                        runOnUiThread {
                            b.tvModelInpaint.text = getString(
                                R.string.model_progres, persen,
                                ModelDownloader.mb(sudah), ModelDownloader.mb(total))
                        }
                    },
                    batal = { unduhDibatalkan },
                )
            }
            unduhBerjalan = false
            b.btnUnduhModel.setText(R.string.btn_unduh_model)
            when (hasil) {
                is ModelDownloader.Hasil.Sukses ->
                    Toast.makeText(this@MainActivity, R.string.model_siap, Toast.LENGTH_SHORT).show()
                is ModelDownloader.Hasil.Dibatalkan ->
                    Toast.makeText(this@MainActivity, R.string.model_dibatalkan, Toast.LENGTH_SHORT).show()
                is ModelDownloader.Hasil.Gagal ->
                    Toast.makeText(this@MainActivity, getString(R.string.model_gagal, hasil.pesan),
                        Toast.LENGTH_LONG).show()
            }
            renderModel()
        }
    }

    /** Paket font yang sedang diunduh; null bila tidak ada. */
    @Volatile private var fontBerjalan: String? = null
    @Volatile private var fontDibatalkan = false

    /**
     * Gambar ulang daftar paket font.
     *
     * Barisnya dibuat secara programatik, bukan tiga blok XML kembar: daftar
     * paketnya ada di FontPack dan menyalinnya ke layout berarti menambah font
     * keempat kelak butuh menyunting dua tempat yang gampang lupa disamakan.
     */
    private fun renderFont() {
        val box = b.boxFont
        box.removeAllViews()
        for (item in FontPack.SEMUA) {
            val baris = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val ada = FontPack.terpasang(this, item)
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@MainActivity, if (ada) R.color.text_secondary else R.color.text_muted))
                text = getString(
                    if (ada) R.string.font_siap else R.string.font_belum,
                    item.judul, ModelDownloader.mb(item.ukuran))
            }
            val tombol = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                textSize = 11f
                setText(
                    when {
                        fontBerjalan == item.id -> R.string.btn_batal_unduh
                        ada -> R.string.font_hapus
                        else -> R.string.font_unduh
                    }
                )
                setOnClickListener {
                    when {
                        fontBerjalan == item.id -> fontDibatalkan = true
                        ada -> {
                            val bebas = FontPack.hapus(this@MainActivity, item)
                            TextRenderer.invalidateFontCache()
                            Toast.makeText(this@MainActivity,
                                getString(R.string.font_terhapus, ModelDownloader.mb(bebas)),
                                Toast.LENGTH_SHORT).show()
                            renderFont()
                        }
                        else -> unduhFont(item)
                    }
                }
            }
            baris.addView(label)
            baris.addView(tombol)
            box.addView(baris)
        }
    }

    /**
     * Unduh satu paket font.
     *
     * Hanya satu unduhan boleh berjalan sekaligus: dua unduhan paralel di
     * jaringan seluler saling memperlambat, dan pelaporan kemajuannya akan
     * saling menimpa di baris yang sama.
     */
    private fun unduhFont(item: FontPack.Item) {
        if (fontBerjalan != null) {
            Toast.makeText(this, R.string.font_sedang_lain, Toast.LENGTH_SHORT).show()
            return
        }
        fontBerjalan = item.id
        fontDibatalkan = false
        renderFont()
        lifecycleScope.launch {
            val hasil = withContext(Dispatchers.IO) {
                ModelDownloader.unduhFont(
                    applicationContext, item,
                    progress = { sudah, total ->
                        val persen = if (total > 0) (sudah * 100 / total).toInt() else 0
                        runOnUiThread {
                            (b.boxFont.getChildAt(FontPack.SEMUA.indexOf(item)) as? LinearLayout)
                                ?.let { (it.getChildAt(0) as? TextView) }
                                ?.text = getString(R.string.font_mengunduh, item.judul, persen)
                        }
                    },
                    batal = { fontDibatalkan },
                )
            }
            fontBerjalan = null
            // Peta typeface di TextRenderer dibaca sekali saat dibuat, jadi
            // tanpa pembatalan cache font baru tidak terpakai sampai proses
            // aplikasi dimulai ulang — bug yang akan terlihat seperti unduhan
            // yang tidak berpengaruh sama sekali.
            TextRenderer.invalidateFontCache()
            when (hasil) {
                is ModelDownloader.Hasil.Sukses -> Toast.makeText(
                    this@MainActivity, getString(R.string.font_selesai, item.judul),
                    Toast.LENGTH_SHORT).show()
                is ModelDownloader.Hasil.Dibatalkan -> Toast.makeText(
                    this@MainActivity, R.string.model_dibatalkan, Toast.LENGTH_SHORT).show()
                is ModelDownloader.Hasil.Gagal -> Toast.makeText(
                    this@MainActivity, getString(R.string.font_gagal, hasil.pesan),
                    Toast.LENGTH_LONG).show()
            }
            renderFont()
        }
    }

    private fun renderGlosarium() {
        val uriStr = cfg.glossaryUri
        if (uriStr.isBlank()) {
            b.tvGlosarium.text = getString(R.string.glosarium_kosong)
            b.btnHapusGlosarium.visibility = View.GONE
            return
        }
        b.btnHapusGlosarium.visibility = View.VISIBLE
        val nama = cfg.glossaryName.ifBlank { "glosarium" }
        val hasil = runCatching {
            contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                Glossary.parse(it.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrNull()
        b.tvGlosarium.text = when {
            hasil == null -> "$nama — tidak bisa dibaca"
            hasil.isEmpty -> "$nama — kosong atau format tidak dikenali"
            else -> buildString {
                append(nama)
                append(" — ")
                append(hasil.entries.size)
                append(" istilah")
                if (hasil.entries.size > Glossary.MAX_ENTRIES) {
                    append(", dipakai ${Glossary.MAX_ENTRIES} pertama")
                }
                if (hasil.conflicts.isNotEmpty()) {
                    append(", ${hasil.conflicts.size} bentrok")
                }
            }
        }
    }

    private fun restoreLog() {
        val existing = synchronized(TranslationService.logLines) {
            TranslationService.logLines.joinToString("\n")
        }
        if (existing.isNotBlank()) b.tvLog.text = existing
    }

    private fun appendLog(line: String) {
        b.tvLog.append(if (b.tvLog.text.isEmpty()) line else "\n$line")
        b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString()?.trim().orEmpty())
    }

    // ---- service callbacks ----

    override fun onLog(line: String) = runOnUiThread { appendLog(line) }

    override fun onProgress(current: Int, total: Int) = runOnUiThread {
        if (total > 0) {
            b.progress.isIndeterminate = false
            b.progress.max = total
            b.progress.progress = current
        }
    }

    override fun onFinished(result: Pipeline.Result?) = runOnUiThread {
        setRunning(false)
        result?.let {
            appendLog("")
            appendLog("Done. Success: ${it.success}, Failed: ${it.failed}, Total: ${it.total}")
            if (it.outputs.isNotEmpty()) {
                startActivity(Intent(this, ResultActivity::class.java))
            } else if (it.error != null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Translation failed")
                    .setMessage(it.error)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
