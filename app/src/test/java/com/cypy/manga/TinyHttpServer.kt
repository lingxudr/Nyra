package com.cypy.manga

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Minimal real HTTP/1.1 server on loopback, used so provider tests exercise the
 * genuine OkHttp network path (android.jar stubs exclude com.sun.net.httpserver).
 */
class TinyHttpServer {

    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    @Volatile var responseCode: Int = 200
    @Volatile var responseBody: String = "{}"

    @Volatile var lastBody: String = ""

    /**
     * Semua body permintaan yang diterima, berurutan. lastBody saja tak cukup
     * untuk menguji fitur yang baru muncul pada permintaan kedua, seperti
     * konteks halaman.
     */
    val bodies: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var lastRequestLine: String = ""
    val lastHeaders = HashMap<String, String>()

    private var worker: Thread? = null

    fun start() {
        worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock: Socket = try { server.accept() } catch (e: Exception) { break }
                try { handle(sock) } catch (_: Exception) { } finally { runCatching { sock.close() } }
            }
        }
    }

    private fun handle(sock: Socket) {
        val input = BufferedInputStream(sock.getInputStream())

        // Read headers byte-by-byte up to the blank line (keeps body bytes intact).
        val head = StringBuilder()
        var consecutive = 0
        while (consecutive < 2) {
            val b = input.read()
            if (b == -1) return
            val c = b.toChar()
            head.append(c)
            if (c == '\n') consecutive++ else if (c != '\r') consecutive = 0
        }

        val lines = head.toString().split("\r\n").filter { it.isNotBlank() }
        lastRequestLine = lines.firstOrNull().orEmpty()
        synchronized(lastHeaders) {
            lastHeaders.clear()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) lastHeaders[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
        }

        val len = lastHeaders["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n <= 0) break
            read += n
        }
        lastBody = String(body, 0, read, StandardCharsets.UTF_8)
        bodies.add(lastBody)

        val payload = responseBody.toByteArray(StandardCharsets.UTF_8)
        val out: OutputStream = sock.getOutputStream()
        val reason = if (responseCode == 200) "OK" else "ERROR"
        out.write(
            ("HTTP/1.1 $responseCode $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        )
        out.write(payload)
        out.flush()
    }

    /** Path + query of the most recent request line, e.g. "/v1/x?key=abc". */
    fun lastTarget(): String = lastRequestLine.split(" ").getOrElse(1) { "" }

    fun stop() {
        runCatching { server.close() }
        worker?.join(1000)
    }
}
