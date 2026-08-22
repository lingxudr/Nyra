package com.nyra.comic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Foreground service that owns the translation run so it survives
 * screen-off / app backgrounding on a long batch.
 */
class TranslationService : Service() {

    companion object {
        const val ACTION_START = "com.nyra.comic.START"
        const val ACTION_STOP = "com.nyra.comic.STOP"
        const val EXTRA_INPUTS = "inputs"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_LANG = "lang"

        private const val CHANNEL_ID = "cypy_translation"
        private const val NOTIF_ID = 1001

        /** Live state observed by MainActivity. */
        val logLines: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        @Volatile var running: Boolean = false
        @Volatile var lastResult: Pipeline.Result? = null

        var listener: Listener? = null

        interface Listener {
            fun onLog(line: String)
            fun onProgress(current: Int, total: Int)
            fun onFinished(result: Pipeline.Result?)
        }

        fun start(ctx: Context, inputs: List<Uri>, output: Uri, lang: String) {
            val i = Intent(ctx, TranslationService::class.java).apply {
                action = ACTION_START
                putParcelableArrayListExtra(EXTRA_INPUTS, ArrayList(inputs))
                putExtra(EXTRA_OUTPUT, output)
                putExtra(EXTRA_LANG, lang)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TranslationService::class.java).apply { action = ACTION_STOP })
        }
    }

    private var pipeline: Pipeline? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pipeline?.cancel()
                emit("[!] Stop requested — finishing current step...")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                @Suppress("DEPRECATION")
                val inputs: List<Uri> = intent.getParcelableArrayListExtra<Uri>(EXTRA_INPUTS) ?: emptyList()
                @Suppress("DEPRECATION")
                val output: Uri? = intent.getParcelableExtra(EXTRA_OUTPUT)
                val lang = intent.getStringExtra(EXTRA_LANG) ?: "Indonesian"
                if (inputs.isEmpty() || output == null) { stopSelf(); return START_NOT_STICKY }

                createChannel()
                startForegroundCompat(notification("Starting…", 0, 0))
                runPipeline(inputs, output, lang)
            }
        }
        return START_NOT_STICKY
    }

    private fun runPipeline(inputs: List<Uri>, output: Uri, lang: String) {
        running = true
        logLines.clear()
        lastResult = null

        job = scope.launch {
            val cfg = Config.get(applicationContext)
            val p = Pipeline(
                applicationContext, cfg,
                log = { line -> emit(line) },
                progress = { cur, total ->
                    listener?.onProgress(cur, total)
                    updateNotification("Translating…", cur, total)
                }
            )
            pipeline = p
            var result: Pipeline.Result? = null
            try {
                result = p.run(inputs, lang, output)
            } catch (e: Throwable) {
                // Throwable, bukan Exception. Kegagalan paling mahal di sini
                // adalah OutOfMemoryError saat menyusun mosaik, dan itu Error
                // bukan Exception: dulu ia lolos dari catch dan mematikan
                // coroutine tanpa satu baris pun di log, sehingga dari sisi
                // pengguna aplikasi tampak "FC" begitu saja. Sekarang selalu
                // ada penjelasan, dan blok finally tetap membereskan pipeline.
                val nama = e::class.java.simpleName
                emit("[!] Fatal error: $nama: ${e.message}")
                if (e is OutOfMemoryError) {
                    emit("[!] Kehabisan memori. Turunkan 'Request paralel' " +
                        "atau 'Balon per request' di Pengaturan, lalu coba lagi.")
                }
            } finally {
                p.close()
                pipeline = null
                running = false
                lastResult = result
                listener?.onFinished(result)
                val summary = result?.let {
                    "Success: ${it.success}, Failed: ${it.failed}, Total: ${it.total}"
                } ?: "Finished"
                notifyDone(summary)
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun emit(line: String) {
        logLines.add(line)
        if (logLines.size > 4000) logLines.removeAt(0)
        listener?.onLog(line)
    }

    // ---- notifications ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Translation", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, i, flags)
    }

    private fun notification(text: String, cur: Int, total: Int): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("cypy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
        if (total > 0) b.setProgress(total, cur, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun updateNotification(text: String, cur: Int, total: Int) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, notification(text, cur, total))
        }
    }

    private fun notifyDone(summary: String) {
        runCatching {
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("cypy — done")
                .setContentText(summary)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        pipeline?.cancel()
        job?.cancel()
        running = false
        super.onDestroy()
    }
}
