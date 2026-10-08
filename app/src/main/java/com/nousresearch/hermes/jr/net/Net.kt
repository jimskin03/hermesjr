package com.nousresearch.hermes.jr.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

object JrLog {
    private const val TAG = "HermesJr"
    fun i(message: String) {
        Log.i(TAG, message)
    }
}

class RpcException(val code: Int, message: String, val data: JSONObject?) : Exception(message)

fun isPrivateAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress) return true
    val bytes = address.address
    if (bytes.size == 4) {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        if (a == 10) return true
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 168) return true
        if (a == 169 && b == 254) return true
        if (a == 100 && b in 64..127) return true
        return false
    }
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    if (first == 0xfe && (second and 0xc0) == 0x80) return true
    if ((first and 0xfe) == 0xfc) return true
    return false
}

fun hostIsPhoneLoopback(host: String): Boolean {
    val bare = host.trim().trim('[', ']').lowercase()
    return bare == "localhost" || bare == "127.0.0.1" || bare == "::1" || bare == "0.0.0.0"
}

fun addressesArePrivate(host: String): Boolean {
    val resolved = InetAddress.getAllByName(host)
    return resolved.isNotEmpty() && resolved.all { isPrivateAddress(it) }
}

class CleartextInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.scheme == "http") {
            val address = chain.connection()?.socket()?.inetAddress
            if (address == null || !isPrivateAddress(address)) {
                throw java.io.IOException("Refusing cleartext to a public address")
            }
        }
        return chain.proceed(request)
    }
}

class TokenStore(context: Context) {
    private val file = File(context.filesDir, "tokens.bin")
    private val lock = Any()

    data class Tokens(val access: String, val refresh: String, val provider: String, val userId: String)

    fun read(): Tokens? = synchronized(lock) {
        if (!file.exists() || file.length() < 13) return null
        try {
            val raw = file.readBytes()
            val iv = raw.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
            Tokens(
                access = json.getString("access"),
                refresh = json.getString("refresh"),
                provider = json.optString("provider"),
                userId = json.optString("userId"),
            )
        } catch (error: Exception) {
            JrLog.i("token read failed")
            null
        }
    }

    fun write(tokens: Tokens) = synchronized(lock) {
        val json = JSONObject()
            .put("access", tokens.access)
            .put("refresh", tokens.refresh)
            .put("provider", tokens.provider)
            .put("userId", tokens.userId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        // Android Keystore keys require randomized encryption by default, so the
        // cipher must pick its own IV. Passing a caller-made IV here throws
        // InvalidAlgorithmParameterException ("Caller-provided IV not permitted").
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv != null && iv.size == 12) { "unexpected GCM IV" }
        val sealed = cipher.doFinal(json)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(iv + sealed)
        if (!tmp.renameTo(file)) {
            file.writeBytes(iv + sealed)
            tmp.delete()
        }
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "hermes-jr-tokens"
    }
}

class RpcSocket {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val buffer = StringBuilder()
    private val heldWindows = mutableListOf<Triple<String, String, JSONObject>>()
    private var listenerGen = 0
    @Volatile var socket: WebSocket? = null
    @Volatile var declinesNotShown: Boolean? = null
    @Volatile var lastInboundMs: Long = System.currentTimeMillis()
    var onEvent: (JSONObject) -> Unit = {}
    var onServerRequest: (String, String, JSONObject) -> Unit = { _, _, _ -> }
    var onClosed: (Int) -> Unit = {}

    fun connect(httpUrl: String, ticket: String) {
        close(notify = false)
        declinesNotShown = null
        val gen = ++listenerGen
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val wsUrl = httpUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/api/ws"
        val request = Request.Builder()
            .url(wsUrl)
            .header("Sec-WebSocket-Protocol", "hermes-gateway-v1, hermes-gateway-ticket.$ticket")
            .build()
        lastInboundMs = System.currentTimeMillis()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == listenerGen) ingest(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen == listenerGen) ingest(bytes.utf8())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                JrLog.i("ws failed ${t.javaClass.simpleName}")
                finish(response?.code ?: -1)
            }

            private fun finish(code: Int) {
                if (gen != listenerGen || !once.compareAndSet(false, true)) return
                JrLog.i("ws closed $code")
                if (socket != null) socket = null
                failPending("socket closed")
                onClosed(code)
            }
        })
    }

    fun close(notify: Boolean = true) {
        listenerGen++
        val ws = socket
        socket = null
        synchronized(buffer) { buffer.setLength(0) }
        failPending("closed")
        synchronized(heldWindows) { heldWindows.clear() }
        ws?.close(1000, null)
        if (notify) onClosed(1000)
    }

    suspend fun call(method: String, params: JSONObject, timeoutMs: Long = 30_000): JSONObject {
        val id = ids.getAndIncrement().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val sent = socket?.send(frame.toString() + "\n") == true
        if (!sent) {
            pending.remove(id)
            throw RpcException(-1, "Not connected", null)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (error: RpcException) {
            throw error
        } catch (error: Exception) {
            pending.remove(id)
            throw RpcException(-1, error.message ?: "request failed", null)
        }
    }

    fun respondResult(id: String, result: JSONObject) {
        val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        socket?.send(frame.toString() + "\n")
    }

    fun respondError(id: String, code: Int, message: String) {
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
        socket?.send(frame.toString() + "\n")
    }

    fun noteCapabilities(declines: Boolean) {
        declinesNotShown = declines
        val queued = synchronized(heldWindows) { heldWindows.toList().also { heldWindows.clear() } }
        queued.forEach { (id, method, params) -> dispatchWindow(id, method, params) }
    }

    private fun ingest(text: String) {
        lastInboundMs = System.currentTimeMillis()
        buffer.append(text)
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline < 0) break
            val line = buffer.substring(0, newline).trim()
            buffer.delete(0, newline + 1)
            if (line.isEmpty()) continue
            val json = try {
                JSONObject(line)
            } catch (_: Exception) {
                continue
            }
            dispatch(json)
        }
    }

    private fun dispatch(json: JSONObject) {
        val id = if (json.has("id") && !json.isNull("id")) json.get("id").toString() else null
        val method = json.optString("method", "")
        if (id != null && method.isNotEmpty()) {
            val params = json.optJSONObject("params") ?: JSONObject()
            handleServerRequest(id, method, params)
            return
        }
        if (id != null && (json.has("result") || json.has("error"))) {
            val waiter = pending.remove(id) ?: return
            if (json.has("error")) {
                val error = json.optJSONObject("error")
                waiter.completeExceptionally(
                    RpcException(
                        error?.optInt("code") ?: -1,
                        error?.optString("message") ?: "error",
                        error?.optJSONObject("data"),
                    ),
                )
            } else {
                val result = json.opt("result")
                waiter.complete(if (result is JSONObject) result else JSONObject().put("value", result))
            }
            return
        }
        if (method == "event" || method.isNotEmpty()) onEvent(json)
    }

    private fun handleServerRequest(id: String, method: String, params: JSONObject) {
        if (method in WINDOW_METHODS) {
            if (declinesNotShown == null) {
                synchronized(heldWindows) { heldWindows.add(Triple(id, method, params)) }
            } else {
                dispatchWindow(id, method, params)
            }
            return
        }
        if (method in CARD_METHODS) {
            onServerRequest(id, method, params)
            return
        }
        JrLog.i("rpc unknown server request")
        respondError(id, -32601, "method not found")
    }

    private fun dispatchWindow(id: String, method: String, params: JSONObject) {
        if (declinesNotShown == true) respondError(id, 4404, "not shown")
    }

    private fun failPending(message: String) {
        pending.values.forEach { it.completeExceptionally(RpcException(-1, message, null)) }
        pending.clear()
    }

    companion object {
        val WINDOW_METHODS = setOf("preview.read", "preview.act", "terminal.read", "window.read", "tour")
        val CARD_METHODS = setOf(
            "approval", "sudo", "secret", "display.install.sudo", "clarify",
            "vault.unlock_prompt", "vault.save_login", "vault.code",
        )
    }
}

class GatewayHttp(context: Context, private val tokens: TokenStore) {
    private val refreshLock = Mutex()
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(CleartextInterceptor())
        .build()
    private val slow = client.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()

    suspend fun get(url: String, auth: Boolean = true): JSONObject = request("GET", url, null, auth, client)
    suspend fun post(url: String, body: JSONObject?, auth: Boolean = true): JSONObject =
        request("POST", url, body, auth, client)
    suspend fun put(url: String, body: JSONObject, auth: Boolean = true): JSONObject =
        request("PUT", url, body, auth, client)
    suspend fun delete(url: String): JSONObject = request("DELETE", url, null, true, slow)

    private suspend fun request(
        method: String,
        url: String,
        body: JSONObject?,
        auth: Boolean,
        http: OkHttpClient,
    ): JSONObject {
        var response = execute(http, method, url, body, auth)
        if (response.code == 401 && auth) {
            response.close()
            if (!refresh(url)) throw RpcException(401, "Signed out", null)
            response = execute(http, method, url, body, true)
        }
        val text = response.body?.string().orEmpty()
        val code = response.code
        if (code !in 200..299) {
            val detail = try {
                JSONObject(text).optString("detail").ifBlank { JSONObject(text).optString("error") }
            } catch (_: Exception) {
                text.take(180)
            }
            throw RpcException(code, detail.ifBlank { "The computer rejected that ($code)" }, null)
        }
        if (text.isBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject().put("raw", text.take(200))
        }
    }

    private fun execute(http: OkHttpClient, method: String, url: String, body: JSONObject?, auth: Boolean): Response {
        val builder = Request.Builder().url(url)
        if (auth) {
            val access = tokens.read()?.access ?: throw RpcException(401, "Signed out", null)
            builder.header("Authorization", "Bearer $access")
        }
        val payload = body?.toString()?.toRequestBody(JSON)
        builder.method(method, if (method == "GET" || method == "DELETE") null else payload ?: "{}".toRequestBody(JSON))
        return http.newCall(builder.build()).execute()
    }

    private suspend fun refresh(anyUrl: String): Boolean = refreshLock.withLock {
        val current = tokens.read() ?: return false
        val base = anyUrl.substringBefore("/api/").substringBefore("/auth/")
        val body = JSONObject().put("refresh_token", current.refresh).put("provider", current.provider)
        val request = Request.Builder()
            .url(base.trimEnd('/') + "/auth/native/refresh")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val response = client.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            // 400/401 mean the refresh token is dead. Anything else (503 provider
            // unreachable, 5xx) is transient, so keep the tokens for the next try.
            if (response.code == 400 || response.code == 401) {
                tokens.clear()
                return false
            }
            throw RpcException(response.code, "The computer could not renew the sign-in (${response.code}). Try again.", null)
        }
        val json = JSONObject(text)
        tokens.write(
            TokenStore.Tokens(
                access = json.getString("access_token"),
                refresh = json.getString("refresh_token"),
                provider = json.optString("provider", current.provider),
                userId = json.optString("user_id", current.userId),
            ),
        )
        true
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

object Pkce {
    fun verifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

/** Deep link the loopback page uses to bring Hermes Jr. back to the front. */
const val RETURN_SCHEME = "hermesjr"
const val RETURN_HOST = "signed-in"
private const val RETURN_INTENT =
    "intent://$RETURN_HOST#Intent;scheme=$RETURN_SCHEME;package=com.nousresearch.hermes.jr;end"

private fun loopbackPage(ok: Boolean): String {
    val title = if (ok) "Signed in" else "Sign-in did not finish"
    val line = if (ok) "Returning to Hermes Jr." else "Go back to Hermes Jr. and tap Sign in again."
    val auto = if (ok) "<script>setTimeout(function(){location.replace('$RETURN_INTENT')},150)</script>" else ""
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>body{background:#0d1117;color:#e6edf3;font:16px system-ui,sans-serif;display:flex;min-height:90vh;align-items:center;justify-content:center;text-align:center}
a{display:inline-block;margin-top:16px;padding:12px 20px;border-radius:10px;background:#2f81f7;color:#fff;text-decoration:none}</style></head>
<body><div><h2>$title</h2><p>$line</p><a href="$RETURN_INTENT">Open Hermes Jr.</a></div>$auto</body></html>"""
}

/**
 * Waits for the gateway's loopback redirect (GET /cb?code=…&state=…) and returns (code, state).
 * Each connection is handled on its own thread so a browser preconnect or a favicon request
 * cannot swallow or block the real callback.
 */
suspend fun awaitLoopback(server: java.net.ServerSocket): Pair<String, String> = suspendCancellableCoroutine { cont ->
    val done = java.util.concurrent.atomic.AtomicBoolean(false)
    fun handle(client: java.net.Socket) {
        client.use { sock ->
            sock.soTimeout = 15_000
            val reader = sock.getInputStream().bufferedReader()
            val request = reader.readLine().orEmpty()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val target = request.substringAfter(' ', "").substringBefore(' ')
            val path = target.substringBefore('?')
            if (path != "/cb") {
                sock.getOutputStream().apply {
                    write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                return
            }
            val map = target.substringAfter("?", "").split("&").mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()
            val code = map["code"].orEmpty()
            val bytes = loopbackPage(code.isNotBlank()).toByteArray()
            sock.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
            if (done.compareAndSet(false, true) && cont.isActive) cont.resume(code to map["state"].orEmpty())
        }
    }
    val thread = Thread {
        try {
            server.soTimeout = 10 * 60 * 1000
            while (!done.get() && cont.isActive) {
                val client = server.accept()
                Thread {
                    try {
                        handle(client)
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true }.start()
            }
        } catch (error: Exception) {
            if (done.compareAndSet(false, true) && cont.isActive) cont.resumeWithException(error)
        }
    }
    cont.invokeOnCancellation { runCatching { server.close() } }
    thread.isDaemon = true
    thread.start()
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
