package com.nousresearch.hermes.jr.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64

/**
 * Loopback noVNC page. The viewer files (vnc.html, app/, core/, vendor/) are not bundled: they are
 * fetched from the computer's own noVNC install, served by `websockify --web=/usr/share/novnc`
 * (see README, "Screen viewer"). Like Friendly, `app/ui.js` gets a one-line hook appended so the
 * app can reach noVNC's UI object (`globalThis.__novncUI`).
 *
 * The picture itself never touches that port: `<token>/ws` is spliced to hermes serve's ticketed
 * `/api/display/ws`. The display ticket is appended only on the hop to the computer. The page URL
 * carries an unguessable path and nothing else.
 */
class RfbBridge {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addNetworkInterceptor(CleartextInterceptor())
        .build()
    /** Short timeouts for the viewer files; the picture socket above has none. */
    private val web = http.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val random = SecureRandom()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var upstream: WebSocket? = null
    @Volatile private var downstream: Socket? = null
    @Volatile private var stopped = false
    private val wsTaken = AtomicBoolean(false)
    var onUpstreamClose: (Int) -> Unit = {}
    var onActivity: () -> Unit = {}

    /**
     * Blocking check that [viewerBase] serves noVNC. Returns null when `vnc.html` and `app/ui.js`
     * load, otherwise a short reason (HTTP status or the I/O failure).
     */
    fun probe(viewerBase: String): String? {
        for (file in listOf("vnc.html", "app/ui.js")) {
            val reason = try {
                web.newCall(Request.Builder().url(viewerBase + file).get().build()).execute().use { response ->
                    if (response.isSuccessful) null else "HTTP ${response.code} for /$file"
                }
            } catch (error: Exception) {
                error.message?.take(160) ?: error.javaClass.simpleName
            }
            if (reason != null) return reason
        }
        return null
    }

    /**
     * Opens the loopback page. [viewerBase] is the computer's noVNC web root URL, ending in `/`.
     * Returns the page URL with noVNC's own query settings (autoconnect, scale, view-only start).
     */
    fun open(upstreamUrl: String, viewerBase: String): String {
        close()
        stopped = false
        wsTaken.set(false)
        val token = token()
        val bound = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = bound
        Thread({
            while (!stopped) {
                val client = try {
                    bound.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ handle(client, token, upstreamUrl, viewerBase) }, "hermes-jr-rfb").apply { isDaemon = true }.start()
            }
        }, "hermes-jr-rfb-accept").apply { isDaemon = true }.start()
        // host/port are spelled out: older noVNC needs them, and newer noVNC resolves a bare path
        // relative to vnc.html. path picks the splice below.
        val port = bound.localPort
        return "http://127.0.0.1:$port/$token/vnc.html" +
            "?autoconnect=1&reconnect=0&resize=scale&view_only=1&show_dot=1&encrypt=0" +
            "&host=127.0.0.1&port=$port&path=$token/ws"
    }

    fun close() {
        stopped = true
        wsTaken.set(false)
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        try {
            downstream?.close()
        } catch (_: Exception) {
        }
        downstream = null
        upstream?.close(1000, null)
        upstream = null
    }

    private fun handle(socket: Socket, token: String, upstreamUrl: String, viewerBase: String) {
        socket.use { client ->
            client.tcpNoDelay = true
            val head = readHead(client.getInputStream()) ?: return
            val line = head.lineSequence().firstOrNull().orEmpty()
            val parts = line.split(" ")
            if (parts.size < 2) {
                writeStatus(client, 400, "Bad Request"); return
            }
            val path = parts[1].substringBefore("?")
            val prefix = "/$token/"
            if (!path.startsWith(prefix) || path.contains("..")) {
                writeStatus(client, 404, "Not Found"); return
            }
            val rel = path.removePrefix(prefix)
            if (rel == "ws") {
                if (!head.contains("Upgrade: websocket", ignoreCase = true)) {
                    writeStatus(client, 400, "Bad Request"); return
                }
                if (!wsTaken.compareAndSet(false, true)) {
                    writeStatus(client, 403, "Busy"); return
                }
                bridge(client, head, upstreamUrl)
                wsTaken.set(false)
                return
            }
            if (!parts[0].equals("GET", ignoreCase = true)) {
                writeStatus(client, 405, "Method Not Allowed"); return
            }
            serveViewerFile(client, viewerBase, rel)
        }
    }

    /** Streams one noVNC file from the computer to the WebView, hooking `app/ui.js` on the way. */
    private fun serveViewerFile(client: Socket, viewerBase: String, rel: String) {
        val out = client.getOutputStream()
        try {
            web.newCall(Request.Builder().url(viewerBase + rel).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    writeStatus(client, response.code, if (response.code == 404) "Not Found" else "Upstream Error")
                    return
                }
                var bytes = response.body?.bytes() ?: ByteArray(0)
                if (rel == "app/ui.js") bytes += UI_HOOK.toByteArray(Charsets.UTF_8)
                val type = response.header("Content-Type") ?: typeOf(rel)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(bytes)
                out.flush()
            }
        } catch (error: Exception) {
            JrLog.i("viewer file failed ${error.javaClass.simpleName}")
            writeStatus(client, 502, "Bad Gateway")
        }
    }

    private fun typeOf(rel: String): String = when {
        rel.endsWith(".js") -> "text/javascript; charset=utf-8"
        rel.endsWith(".html") -> "text/html; charset=utf-8"
        rel.endsWith(".css") -> "text/css"
        rel.endsWith(".json") -> "application/json"
        rel.endsWith(".svg") -> "image/svg+xml"
        rel.endsWith(".png") -> "image/png"
        rel.endsWith(".woff2") -> "font/woff2"
        rel.endsWith(".woff") -> "font/woff"
        rel.endsWith(".mp3") -> "audio/mpeg"
        rel.endsWith(".oga") -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun bridge(client: Socket, head: String, upstreamUrl: String) {
        val key = header(head, "Sec-WebSocket-Key")
        if (key.isBlank()) {
            writeStatus(client, 400, "Bad Request"); return
        }
        val opened = java.util.concurrent.CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val request = Request.Builder().url(upstreamUrl).build()
        val remote = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                upstream = webSocket
                opened.countDown()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onActivity()
                try {
                    writeFrame(client.getOutputStream(), 2, bytes.toByteArray())
                } catch (_: Exception) {
                    webSocket.close(1000, null)
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                onActivity()
                val raw = text.encodeToByteArray()
                val cut = if (raw.size > CUT) raw.copyOf(CUT) else raw
                try {
                    writeFrame(client.getOutputStream(), 1, cut)
                } catch (_: Exception) {
                    webSocket.close(1000, null)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onUpstreamClose(code)
                try {
                    client.close()
                } catch (_: Exception) {
                }
                webSocket.close(code, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                JrLog.i("display hop failed ${t.javaClass.simpleName}")
                failed.set(true)
                opened.countDown()
                onUpstreamClose(response?.code ?: -1)
            }
        })
        if (!opened.await(20, TimeUnit.SECONDS) || failed.get()) {
            remote.close(1011, null)
            writeStatus(client, 502, "Bad Gateway")
            return
        }
        downstream = client
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP,
        )
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1),
        )
        out.flush()
        val input = client.getInputStream()
        try {
            val partial = java.io.ByteArrayOutputStream()
            var opcode = 1
            while (!stopped) {
                val frame = readFrame(input) ?: break
                onActivity()
                when (frame.opcode) {
                    8 -> break
                    9 -> writeFrame(out, 10, frame.data)
                    10 -> Unit
                    0, 1, 2 -> {
                        if (frame.opcode != 0) {
                            opcode = frame.opcode
                            partial.reset()
                        }
                        partial.write(frame.data)
                        if (frame.fin) {
                            var payload = partial.toByteArray()
                            if (opcode == 1 && payload.size > CUT) payload = payload.copyOf(CUT)
                            val sent = if (opcode == 1) {
                                remote.send(String(payload, Charsets.UTF_8))
                            } else {
                                remote.send(payload.toByteString())
                            }
                            if (!sent) break
                            partial.reset()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            remote.close(1000, null)
            if (downstream === client) downstream = null
        }
    }

    private fun token(): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private data class Frame(val opcode: Int, val fin: Boolean, val data: ByteArray)

    private fun readFrame(input: InputStream): Frame? {
        val b0 = input.read(); if (b0 < 0) return null
        val b1 = input.read(); if (b1 < 0) return null
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = ((input.read().toLong() shl 8) or input.read().toLong())
        else if (len == 127L) {
            var value = 0L
            repeat(8) { value = (value shl 8) or input.read().toLong() }
            len = value
        }
        if (len < 0 || len > 16L * 1024 * 1024) return null
        val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(input, it) } else null
        val data = ByteArray(len.toInt())
        readFully(input, data)
        if (mask != null) {
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(b0 and 0x0f, b0 and 0x80 != 0, data)
    }

    private fun writeFrame(out: OutputStream, opcode: Int, data: ByteArray) {
        synchronized(out) {
            out.write(0x80 or opcode)
            val n = data.size
            when {
                n < 126 -> out.write(n)
                n <= 0xffff -> {
                    out.write(126)
                    out.write(n shr 8)
                    out.write(n and 0xff)
                }
                else -> {
                    out.write(127)
                    for (shift in 56 downTo 0 step 8) out.write(((n.toLong() shr shift) and 0xff).toInt())
                }
            }
            out.write(data)
            out.flush()
        }
    }

    private fun readFully(input: InputStream, dest: ByteArray) {
        var off = 0
        while (off < dest.size) {
            val n = input.read(dest, off, dest.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < 16384) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            val size = out.size()
            if (size >= 4) {
                val raw = out.toByteArray()
                if (raw[size - 4] == '\r'.code.toByte() && raw[size - 3] == '\n'.code.toByte() &&
                    raw[size - 2] == '\r'.code.toByte() && raw[size - 1] == '\n'.code.toByte()
                ) {
                    return String(raw, Charsets.ISO_8859_1)
                }
            }
        }
        return null
    }

    private fun header(head: String, name: String): String =
        head.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(":")?.trim().orEmpty()

    private fun writeStatus(socket: Socket, code: Int, text: String) {
        val body = text
        val raw = "HTTP/1.1 $code $text\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        try {
            socket.getOutputStream().write(raw.toByteArray())
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val CUT = 256 * 1024
        /** Same hook as Friendly's DesktopControlSheet: noVNC keeps UI inside an ES module. */
        const val UI_HOOK = "\nglobalThis.__novncUI = UI;\n"
    }
}
