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
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        file.writeBytes(iv + cipher.doFinal(json))
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
            tokens.clear()
            return false
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

suspend fun awaitLoopback(server: java.net.ServerSocket): Pair<String, String> = suspendCancellableCoroutine { cont ->
    val thread = Thread {
        try {
            server.soTimeout = 10 * 60 * 1000
            val socket = server.accept()
            socket.use { client ->
                val reader = client.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val html = "<html><body><p>Signed in. You can close this tab.</p></body></html>"
                val bytes = html.toByteArray()
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$html"
                client.getOutputStream().write(response.toByteArray())
                client.getOutputStream().flush()
                val query = request.substringAfter("?", "").substringBefore(" ")
                val map = query.split("&").mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
                }.toMap()
                if (cont.isActive) cont.resume(map["code"].orEmpty() to map["state"].orEmpty())
            }
        } catch (error: Exception) {
            if (cont.isActive) cont.resumeWithException(error)
        }
    }
    cont.invokeOnCancellation { server.close() }
    thread.isDaemon = true
    thread.start()
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
