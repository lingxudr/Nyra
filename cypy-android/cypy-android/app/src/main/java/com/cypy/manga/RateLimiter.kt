package com.cypy.manga

import kotlin.math.pow

/** Port of cypy/core/services/rate_limiter.py */
class RateLimiter(private val cfg: Config, private val log: (String) -> Unit) {

    @Volatile private var lastCallTime: Long = 0L
    @Volatile var cancelled: Boolean = false

    private val lock = Any()

    private fun waitForSlot() {
        val minDelay = (cfg.minRequestDelay * 1000).toLong()
        if (minDelay <= 0) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastCallTime
            if (elapsed < minDelay) {
                val sleep = minDelay - elapsed
                sleepInterruptible(sleep)
            }
            lastCallTime = System.currentTimeMillis()
        }
    }

    private fun sleepInterruptible(ms: Long) {
        var remaining = ms
        while (remaining > 0 && !cancelled) {
            val chunk = minOf(remaining, 250L)
            Thread.sleep(chunk)
            remaining -= chunk
        }
    }

    fun <T> executeWithRetry(providerName: String, maxRetries: Int = 6, call: () -> T): T? {
        waitForSlot()

        for (attempt in 0 until maxRetries) {
            if (cancelled) return null
            try {
                synchronized(lock) { lastCallTime = System.currentTimeMillis() }
                return call()
            } catch (ake: ApiKeyException) {
                throw ake
            } catch (ex: Exception) {
                val err = (ex.message ?: "").lowercase()

                val isRateLimit = err.contains("429") || err.contains("too many requests") ||
                        err.contains("rate limit") || err.contains("quota exceeded")

                if (isRateLimit) {
                    var waitSeconds = 5.0 * 2.0.pow(attempt.toDouble())
                    Regex("retry in (\\d+(?:\\.\\d+)?)s").find(err)?.let { m ->
                        m.groupValues[1].toDoubleOrNull()?.let { waitSeconds = it + 1.5 }
                    }
                    // A quota wait can be a full minute. Say what is happening and
                    // how long is left, so the wait does not look like a freeze.
                    log("  [Kuota] $providerName menolak sementara (429). " +
                        "Menunggu ${waitSeconds.toInt()}s — percobaan ${attempt + 1}/$maxRetries. " +
                        "Tekan Stop untuk berhenti; halaman yang sudah selesai tetap disimpan.")
                    sleepInterruptible((waitSeconds * 1000).toLong())
                    if (cancelled) return null
                    continue
                }

                // "high demand" = model sedang penuh. Ini sering terjadi pada
                // model flash terbaru dan butuh tunggu lebih lama daripada
                // gangguan jaringan biasa.
                val isOverloaded = err.contains("503") || err.contains("high demand") ||
                        err.contains("unavailable") || err.contains("overloaded")

                val isTransient = isOverloaded ||
                        err.contains("timeout") || err.contains("timed out") ||
                        err.contains("socket") || err.contains("connection") ||
                        err.contains("unreachable") || err.contains("reset") ||
                        err.contains("stream was") || err.contains("500") ||
                        err.contains("502") || err.contains("504")

                if (isTransient && attempt < maxRetries - 1) {
                    // Backoff eksponensial dengan jitter, dibatasi 45 detik.
                    val base = if (isOverloaded) 6.0 else 3.0
                    val wait = minOf(45.0, base * 2.0.pow(attempt.toDouble())) +
                            (Math.random() * 2.0)
                    val why = if (isOverloaded)
                        "$providerName sedang penuh (503)" else "Gangguan jaringan"
                    log("  [Retry ${attempt + 1}/$maxRetries] $why, coba lagi dalam ${wait.toInt()}s... " +
                        "Tekan Stop untuk berhenti; halaman yang sudah selesai tetap disimpan.")
                    sleepInterruptible((wait * 1000).toLong())
                    if (cancelled) return null
                    continue
                }

                if (attempt >= maxRetries - 1) throw ex
            }
        }
        return null
    }
}
