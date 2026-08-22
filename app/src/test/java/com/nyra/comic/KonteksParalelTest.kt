package com.nyra.comic

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ronde 39. Dalam mode paralel, thread pekerja membaca `pageContext` untuk
 * menyusun prompt (size + promptSection) sementara thread utama menulis hasil
 * gelombang sebelumnya lewat add(). PageContext dulu memakai ArrayDeque polos
 * tanpa kunci, padahal add() melakukan addLast lalu rangkaian removeFirst
 * (batas 12 halaman dan pemangkasan anggaran token). Pembacaan yang berbarengan
 * bisa melihat deque di tengah perubahan.
 *
 * Log lapangan berhenti tepat pada "Konteks: 12 halaman sebelumnya disertakan"
 * — persis di batas MAX_PAGES, saat pemangkasan paling sibuk.
 */
class KonteksParalelTest {

    /**
     * Baca dan tulis bersamaan tidak boleh melempar apa pun.
     *
     * Tanpa sinkronisasi tes ini memunculkan ConcurrentModificationException
     * atau IndexOutOfBoundsException dari dalam render()/promptSection().
     */
    @Test
    fun bacaTulisBersamaanTidakMeledak() {
        val ctx = PageContext()
        val galat = AtomicReference<Throwable>()
        val mulai = CountDownLatch(1)
        val putaran = 3000

        val penulis = Thread {
            mulai.await()
            runCatching {
                for (i in 0 until putaran) {
                    ctx.add("halaman-$i", listOf("Nama tokoh $i", "Baris kedua $i"))
                }
            }.onFailure { galat.compareAndSet(null, it) }
        }

        val pembaca = (1..3).map {
            Thread {
                mulai.await()
                runCatching {
                    for (i in 0 until putaran) {
                        if (ctx.size > 0) ctx.promptSection()
                    }
                }.onFailure { galat.compareAndSet(null, it) }
            }
        }

        val semua = pembaca + penulis
        semua.forEach { it.start() }
        mulai.countDown()
        semua.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        galat.get()?.let { throw AssertionError("akses paralel gagal: $it", it) }
    }

    /** Batas MAX_PAGES tetap dihormati setelah tekanan paralel. */
    @Test
    fun batasHalamanTetapDijaga() {
        val ctx = PageContext()
        val mulai = CountDownLatch(1)
        val penulis = (1..4).map { t ->
            Thread {
                mulai.await()
                for (i in 0 until 500) ctx.add("t$t-$i", listOf("baris $i"))
            }
        }
        penulis.forEach { it.start() }
        mulai.countDown()
        penulis.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertTrue(
            "size=${ctx.size} melebihi MAX_PAGES=${PageContext.MAX_PAGES}",
            ctx.size in 1..PageContext.MAX_PAGES
        )
    }

    /** clear() saat pembaca aktif juga harus aman. */
    @Test
    fun bersihkanSaatDibacaTidakMeledak() {
        val ctx = PageContext()
        repeat(12) { ctx.add("awal-$it", listOf("baris $it")) }

        val galat = AtomicReference<Throwable>()
        val mulai = CountDownLatch(1)
        val a = Thread {
            mulai.await()
            runCatching { repeat(2000) { ctx.clear(); ctx.add("x$it", listOf("y$it")) } }
                .onFailure { galat.compareAndSet(null, it) }
        }
        val b = Thread {
            mulai.await()
            runCatching { repeat(2000) { ctx.promptSection() } }
                .onFailure { galat.compareAndSet(null, it) }
        }
        a.start(); b.start(); mulai.countDown()
        a.join(TimeUnit.SECONDS.toMillis(30)); b.join(TimeUnit.SECONDS.toMillis(30))

        galat.get()?.let { throw AssertionError("clear paralel gagal: $it", it) }
    }
}
