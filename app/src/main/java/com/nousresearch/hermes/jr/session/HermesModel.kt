package com.nousresearch.hermes.jr.session

import android.app.Application
import android.net.Uri
import android.util.Log
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nousresearch.hermes.jr.data.JrStore
import com.nousresearch.hermes.jr.net.GatewayHttp
import com.nousresearch.hermes.jr.net.JrLog
import com.nousresearch.hermes.jr.net.Pkce
import com.nousresearch.hermes.jr.net.RfbBridge
import com.nousresearch.hermes.jr.net.RpcException
import com.nousresearch.hermes.jr.net.RpcSocket
import com.nousresearch.hermes.jr.net.TokenStore
import com.nousresearch.hermes.jr.net.addressesArePrivate
import com.nousresearch.hermes.jr.net.NetFailure
import com.nousresearch.hermes.jr.net.awaitLoopback
import com.nousresearch.hermes.jr.net.whereOf
import com.nousresearch.hermes.jr.net.hostIsPhoneLoopback
import com.nousresearch.hermes.jr.net.objects
import com.nousresearch.hermes.jr.relay.RelayService
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Bot(val name: String, val model: String, val sessionId: String, val isDefault: Boolean, val preview: String)
data class Line(val role: String, val text: String)
data class Chat(
    val profile: String,
    val sessionId: String,
    val lines: List<Line>,
    val draft: String,
    val streaming: Boolean,
    /** Reasoning/thinking deltas of the running turn, kept apart from the reply text. */
    val reasoning: String = "",
    /** The tool the running turn is using right now, for the "Using …" indicator. */
    val activity: String = "",
)
data class RoomRow(val id: String, val name: String, val members: Int)
data class Member(val id: String, val profile: String, val handle: String)
/**
 * One user-visible line of a room transcript. [role] is "user", "bot" or "notice" (a subtle centered
 * line for room lifecycle changes worth knowing about). Turn bookkeeping events are never RoomEvents.
 */
data class RoomEvent(
    val seq: Int,
    val who: String,
    val text: String,
    val key: String = "",
    val role: String = "bot",
    val eventId: String = "",
    /** Epoch millis; 0 when unknown. */
    val at: Long = 0L,
    val profile: String = "",
    /** Optimistic local echo the server has not confirmed yet. */
    val sending: Boolean = false,
)
data class PendingAction(val kind: String, val memberId: String, val taskId: String, val generation: Int, val requestId: String, val choices: List<String>, val label: String)
data class RoomView(
    val id: String,
    val name: String,
    val members: List<Member>,
    val events: List<RoomEvent>,
    val pending: List<PendingAction>,
    val working: Boolean,
    val live: String,
    /** Who [live] (a streaming partial reply) belongs to. */
    val liveWho: String = "",
    /** Display names of members with a turn in flight ("@x is thinking…"). */
    val thinking: List<String> = emptyList(),
    /** member_id -> display name for members whose turn has started and not settled. */
    val activeTurns: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
)
data class MirrorRoom(val id: String, val name: String, val lines: List<String>, val omitted: Int)
data class McpServer(val name: String, val source: String, val enabled: Boolean, val detail: String, val status: String)
data class McpView(val profile: String, val servers: List<McpServer>, val catalog: List<Pair<String, String>>)
data class Question(val id: String, val prompt: String, val choices: List<String>, val multi: Boolean)
data class Card(val id: String, val method: String, val title: String, val body: String, val choices: List<String>, val questions: List<Question>, val secret: Boolean, val saveLogin: Boolean)
data class ScreenState(
    val profile: String = "default",
    val supported: Boolean = false,
    val running: Boolean = false,
    val installed: Boolean = true,
    val holder: String = "",
    val blocker: String = "",
    val pageUrl: String = "",
    val controlling: Boolean = false,
    val thumbnail: String = "",
    val checks: String = "",
    val paused: Boolean = false,
    val message: String = "",
)
data class UiState(
    val booting: Boolean = true,
    val busy: Boolean = false,
    /** Browser sign-in is open and the app is waiting for the loopback callback. */
    val signingIn: Boolean = false,
    /** Tokens are stored; the app is (re)opening the chat WebSocket. */
    val signedIn: Boolean = false,
    val ready: Boolean = false,
    val baseUrl: String = "",
    val notice: String = "",
    val offline: Boolean = false,
    val profiles: List<Bot> = emptyList(),
    val rooms: List<RoomRow> = emptyList(),
    val mirror: List<MirrorRoom> = emptyList(),
    val membersEditable: Boolean = false,
    val chat: Chat? = null,
    val room: RoomView? = null,
    val mirrorOpen: MirrorRoom? = null,
    val mcp: McpView? = null,
    val cards: List<Card> = emptyList(),
    val screen: ScreenState = ScreenState(),
)

class HermesModel(app: Application) : AndroidViewModel(app) {
    private val tokens = TokenStore(app)
    private val http = GatewayHttp(app, tokens)
    private val store = JrStore(app)
    private val rpc = RpcSocket()
    private val bridge = RfbBridge(app.assets)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Last line of defence: an unexpected exception in any model coroutine (a server payload with a
     * shape this build does not expect, a bug) becomes a notice instead of killing the process.
     */
    private val safe = CoroutineExceptionHandler { _, error ->
        JrLog.i("uncaught in model: ${error.javaClass.name}: ${error.message}")
        Log.e("HermesJr", "uncaught in model coroutine", error)
        _state.value = _state.value.copy(busy = false, notice = "Something went wrong: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(200)}")
    }

    private var base = ""
    private var signProvider: String? = null
    private var wantSocket = false
    private var socketEpoch = 0
    private var readyEpoch = -1
    private var heartbeat = false
    private var pingJob: Job? = null
    private var roomJob: Job? = null
    private var screenJob: Job? = null
    private var thumbJob: Job? = null
    private var signJob: Job? = null
    private val streaming = mutableSetOf<String>()
    private val cursors = mutableMapOf<String, Int>()
    private var viewerId = ""
    private var screenWanted = false
    private var screenMetered = false
    private var rfbCloses = ArrayDeque<Long>()
    private var lastRfbMs = 0L
    private var relayWaiting: Boolean? = null
    private var intentionalBridgeClose = false

    init {
        rpc.onEvent = { frame -> viewModelScope.launch(safe + Dispatchers.Main.immediate) { onFrame(frame) } }
        rpc.onServerRequest = { id, method, params ->
            viewModelScope.launch(safe + Dispatchers.Main.immediate) { addCard(id, method, params); syncRelay() }
        }
        rpc.onClosed = {
            viewModelScope.launch(safe + Dispatchers.Main.immediate) {
                if (!wantSocket) return@launch
                val why = rpc.lastError
                if (why.isNotBlank()) JrLog.i("ws lost: $why")
                // Before the first gateway.ready the connect screen is still up and has no offline
                // banner, so say why the chat connection is not coming up.
                _state.value = if (!_state.value.ready && why.isNotBlank()) {
                    _state.value.copy(offline = true, notice = "Signed in, but the chat connection failed: $why. Retrying.")
                } else {
                    _state.value.copy(offline = true)
                }
                pingJob?.cancel()
                delay(1_000)
                if (wantSocket) openSocket()
            }
        }
        bridge.onActivity = { lastRfbMs = System.currentTimeMillis() }
        bridge.onUpstreamClose = { code ->
            viewModelScope.launch(safe + Dispatchers.Main.immediate) { onRfbClosed(code) }
        }
        viewModelScope.launch(safe) {
            val saved = store.read()
            base = saved.baseUrl
            parseCursors(saved.cursors)
            _state.value = _state.value.copy(baseUrl = saved.baseUrl, booting = false)
            if (saved.baseUrl.isNotBlank() && tokens.read() != null) useHost(saved.baseUrl)
        }
    }

    fun useHost(raw: String) {
        signJob?.cancel()
        viewModelScope.launch(safe) {
            _state.value = _state.value.copy(busy = true, signingIn = false, notice = "")
            try {
                var normalized = try {
                    normalizeBase(raw)
                } catch (_: Exception) {
                    errorNotice("That address is not a valid URL.")
                }
                val host = URI(normalized).host ?: errorNotice("That address is not a host.")
                if (hostIsPhoneLoopback(host)) errorNotice("That address is this phone.")
                // A hostname (MagicDNS, .local) needs a DNS lookup, which is not allowed on the main thread.
                val privateHost = try {
                    io { addressesArePrivate(host) }
                } catch (_: Exception) {
                    errorNotice("Could not find that computer.")
                }
                if (!normalized.startsWith("https://") && !privateHost) {
                    errorNotice("Refusing a public address over plain HTTP.")
                }
                val status = try {
                    io { http.get("$normalized/api/status", auth = false) }
                } catch (error: java.io.IOException) {
                    if (!normalized.startsWith("https://") || !privateHost) throw error
                    // hermes serve speaks plain HTTP unless something terminates TLS in front of it.
                    val plain = "http://" + normalized.removePrefix("https://")
                    val probe = try {
                        io { http.get("$plain/api/status", auth = false) }
                    } catch (_: Exception) {
                        throw error
                    }
                    normalized = plain
                    probe
                }
                val providers = strings(status.optJSONArray("auth_providers"))
                JrLog.i("probe auth_required=${status.optBoolean("auth_required")} providers=${providers.size}")
                if (!status.optBoolean("auth_required")) errorNotice("This computer is not asking anyone to sign in.")
                val flows = strings(status.optJSONArray("auth_flows"))
                if ("native_pkce" !in flows) errorNotice("Update Hermes on the computer. This app needs native sign-in.")
                if (providers.isEmpty()) {
                    errorNotice("Sign-in is not configured. Set HERMES_DASHBOARD_BASIC_AUTH_USERNAME, HERMES_DASHBOARD_BASIC_AUTH_PASSWORD, and HERMES_DASHBOARD_BASIC_AUTH_SECRET on the computer.")
                }
                val oauth = providers.filter { it != "basic" }
                if (!privateHost && oauth.isEmpty()) {
                    errorNotice("Use Tailscale or OAuth. This app will not send a password to a public address.")
                }
                signProvider = when {
                    !privateHost && "nous" in oauth -> "nous"
                    providers.size == 1 -> providers.first()
                    !privateHost && oauth.size == 1 -> oauth.first()
                    else -> null
                }
                val install = status.optString("install_id")
                val saved = store.read()
                var note = ""
                if (saved.installId.isNotBlank() && install.isNotBlank() && saved.installId != install) {
                    tokens.clear()
                    store.wipeSession()
                    cursors.clear()
                    note = "This address is a different computer. Sign in again."
                }
                if (install.isNotBlank()) store.saveInstall(install)
                store.saveUrl(normalized)
                base = normalized
                if (note.isBlank() && raw.trim().startsWith("https://") && normalized.startsWith("http://")) {
                    note = "This computer answers over plain HTTP, not HTTPS. Using $normalized."
                }
                val signedIn = tokens.read() != null
                _state.value = _state.value.copy(busy = false, baseUrl = normalized, ready = false, signedIn = signedIn, notice = note)
                if (signedIn) {
                    wantSocket = true
                    openSocket()
                }
            } catch (error: Notice) {
                _state.value = _state.value.copy(busy = false, notice = error.message.orEmpty())
            } catch (error: Exception) {
                _state.value = _state.value.copy(busy = false, notice = error.jr())
            }
        }
    }

    fun signIn(context: android.content.Context) {
        if (base.isBlank()) return
        signJob?.cancel()
        signJob = viewModelScope.launch(safe) {
            _state.value = _state.value.copy(busy = true, notice = "")
            var stage = "Could not start sign-in"
            val server = try {
                ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            } catch (error: Exception) {
                _state.value = _state.value.copy(busy = false, notice = "$stage: ${error.jr()}")
                return@launch
            }
            try {
                val verifier = Pkce.verifier()
                val state = ident()
                val redirect = "http://127.0.0.1:${server.localPort}/cb"
                val authorize = StringBuilder("$base/auth/native/authorize?code_challenge=")
                    .append(enc(Pkce.challenge(verifier)))
                    .append("&code_challenge_method=S256&redirect_uri=")
                    .append(enc(redirect))
                    .append("&state=").append(enc(state))
                signProvider?.let { authorize.append("&provider=").append(enc(it)) }
                try {
                    CustomTabsIntent.Builder().setShowTitle(true).build()
                        .launchUrl(context, Uri.parse(authorize.toString()))
                } catch (_: android.content.ActivityNotFoundException) {
                    errorNotice("No web browser is installed. Install or enable a browser, then tap Sign in again.")
                }
                stage = "Sign-in did not finish"
                // Waiting on the browser can take minutes, or never finish if the tab is closed.
                // Leave the buttons usable so Sign in can restart the flow.
                _state.value = _state.value.copy(busy = false, signingIn = true)
                val (code, got) = awaitLoopback(server)
                _state.value = _state.value.copy(busy = true, signingIn = false)
                if (got != state || code.isBlank()) errorNotice("Sign-in did not finish. Try again.")
                stage = "Could not exchange the sign-in code with the computer"
                val body = io {
                    http.post(
                        "$base/auth/native/token",
                        JSONObject().put("code", code).put("code_verifier", verifier),
                        auth = false,
                    )
                }
                if (body.optString("access_token").isBlank() || body.optString("refresh_token").isBlank()) {
                    errorNotice("$stage: the computer answered without tokens. Update Hermes on the computer.")
                }
                stage = "Signed in, but could not save the sign-in on this phone"
                io {
                    tokens.write(
                        TokenStore.Tokens(
                            access = body.getString("access_token"),
                            refresh = body.getString("refresh_token"),
                            provider = body.optString("provider", signProvider.orEmpty()),
                            userId = body.optString("user_id"),
                        ),
                    )
                }
                if (tokens.read() == null) errorNotice("$stage: the stored sign-in could not be read back.")
                JrLog.i("sign-in complete")
                wantSocket = true
                _state.value = _state.value.copy(busy = false, signedIn = true)
                openSocket()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Notice) {
                _state.value = _state.value.copy(busy = false, signingIn = false, notice = error.message.orEmpty())
            } catch (error: Exception) {
                JrLog.i("sign-in failed at '$stage': ${error.javaClass.name}")
                _state.value = _state.value.copy(busy = false, signingIn = false, notice = "$stage: ${error.jr()}")
            } finally {
                try {
                    server.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun signOut() {
        wantSocket = false
        pingJob?.cancel()
        roomJob?.cancel()
        screenJob?.cancel()
        rpc.close(notify = false)
        releaseBridge()
        tokens.clear()
        streaming.clear()
        cursors.clear()
        viewerId = ""
        viewModelScope.launch(safe) { store.wipeSession() }
        stopRelay()
        _state.value = UiState(booting = false, baseUrl = base, notice = "Signed out on this phone.")
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = "")
    }

    fun closeChat() {
        _state.value = _state.value.copy(chat = null)
    }

    fun closeRoom() {
        roomJob?.cancel()
        _state.value = _state.value.copy(room = null)
    }

    fun closeMirror() {
        _state.value = _state.value.copy(mirrorOpen = null)
    }

    fun openMirror(room: MirrorRoom) {
        _state.value = _state.value.copy(mirrorOpen = room)
    }

    fun closeMcp() {
        _state.value = _state.value.copy(mcp = null)
    }

    fun openBot(name: String) {
        viewModelScope.launch(safe) {
            try {
                val bot = _state.value.profiles.find { it.name == name }
                val snap = if (bot != null && bot.sessionId.isNotBlank()) {
                    rpc("session.resume", JSONObject().put("session_id", bot.sessionId).put("profile", name).put("inline_images", false))
                } else {
                    rpc("session.create", JSONObject().put("profile", name).put("title", "Bot Chat"))
                }
                val runtime = snap.optString("session_id")
                if (runtime.isBlank()) errorNotice("The computer did not open a chat.")
                val history = try {
                    rpc("session.history", JSONObject().put("session_id", runtime).put("profile", name))
                } catch (_: Exception) {
                    snap
                }
                snap.optJSONArray("open_requests")?.objects()?.forEach { replay(it) }
                val running = snap.optBoolean("running") || runtime in streaming
                if (running) streaming.add(runtime)
                _state.value = _state.value.copy(
                    chat = Chat(name, runtime, linesFrom(history.optJSONArray("messages")), "", running),
                    notice = "",
                )
                remember()
                syncRelay()
            } catch (error: Notice) {
                note(error.message.orEmpty())
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun sendChat(text: String) {
        val chat = _state.value.chat ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch(safe) {
            val next = chat.copy(lines = chat.lines + Line("user", body), streaming = true)
            _state.value = _state.value.copy(chat = next)
            streaming.add(chat.sessionId)
            syncRelay()
            try {
                val result = rpc(
                    "prompt.submit",
                    JSONObject().put("session_id", chat.sessionId).put("profile", chat.profile).put("text", body),
                    60_000,
                )
                JrLog.i("rpc prompt.submit ${result.optString("status")}")
            } catch (error: Exception) {
                streaming.remove(chat.sessionId)
                val current = _state.value.chat
                if (current?.sessionId == chat.sessionId) {
                    _state.value = _state.value.copy(chat = current.copy(streaming = false))
                }
                note(error.jr())
                syncRelay()
            }
        }
    }

    fun stopChat() {
        val chat = _state.value.chat ?: return
        viewModelScope.launch(safe) {
            try {
                rpc("session.interrupt", JSONObject().put("session_id", chat.sessionId).put("profile", chat.profile))
            } catch (error: Exception) {
                note(error.jr())
            }
            streaming.remove(chat.sessionId)
            val current = _state.value.chat
            if (current?.sessionId == chat.sessionId) {
                _state.value = _state.value.copy(chat = current.copy(streaming = false))
            }
            syncRelay()
        }
    }

    fun attach(uri: Uri) {
        val chat = _state.value.chat ?: return
        viewModelScope.launch(safe) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "upload"
                val mime = resolver.getType(uri).orEmpty()
                val bytes = io {
                    resolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 50 * 1024 * 1024) errorNotice("That file is over the 50 MB limit.")
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: errorNotice("Could not read that file.")
                }
                val kind = sniff(bytes, mime, name)
                val cap = if (kind == "pdf") 50 * 1024 * 1024 else 25 * 1024 * 1024
                if (bytes.size > cap) errorNotice("That file is over the limit for a ${kind}.")
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val params = JSONObject().put("session_id", chat.sessionId).put("profile", chat.profile)
                when (kind) {
                    "image" -> rpc(
                        "image.attach_bytes",
                        params.put("content_base64", encoded).put("filename", name).put("ext", ext(name)),
                        60_000,
                    )
                    "pdf" -> rpc(
                        "pdf.attach",
                        params.put("content_base64", encoded).put("filename", name),
                        90_000,
                    )
                    else -> rpc(
                        "file.attach",
                        params.put("data_url", "data:${mime.ifBlank { "application/octet-stream" }};base64,$encoded").put("name", name),
                        60_000,
                    )
                }
                val current = _state.value.chat
                if (current?.sessionId == chat.sessionId) {
                    _state.value = _state.value.copy(chat = current.copy(lines = current.lines + Line("user", "Attached $name")))
                }
            } catch (error: Notice) {
                note(error.message.orEmpty())
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun createBot(name: String, soul: String) {
        val slug = name.trim().lowercase()
        if (!slug.matches(Regex("^[a-z0-9][a-z0-9_-]{0,63}$"))) {
            note("Use a short name: it starts with a letter or digit, then letters, digits, _ or -.")
            return
        }
        if (_state.value.profiles.size >= 20) {
            note("This phone will not create a 21st bot.")
            return
        }
        val line = soul.replace("\n", " ").trim().take(240)
        viewModelScope.launch(safe) {
            try {
                rpc(
                    "profiles.create",
                    JSONObject().put("name", slug).put("share_auth", true).put("clone_from", JSONObject.NULL).put("soul", line),
                    60_000,
                )
                refreshProfiles()
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun deleteBot(name: String) {
        if (name == "default") {
            note("The default bot stays on the computer.")
            return
        }
        viewModelScope.launch(safe) {
            try {
                val result = io { http.delete("$base/api/profiles/${enc(name)}") }
                if (result.optBoolean("settlement_pending")) {
                    note(result.optString("retry_command").ifBlank { "Deleted. The computer is still settling that bot." })
                }
                refreshProfiles()
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun openRoom(id: String) {
        roomJob?.cancel()
        roomJob = viewModelScope.launch(safe) {
            val row = _state.value.rooms.find { it.id == id }
            _state.value = _state.value.copy(
                room = RoomView(id, row?.name ?: id, emptyList(), emptyList(), emptyList(), false, ""),
            )
            // Always rebuild the transcript from the server log: the local cursor only says what was
            // seen, the visible history is not cached, so starting from it opened rooms empty.
            var since = -1
            while (isActive && _state.value.room?.id == id) {
                try {
                    val state = rpc("groups.state", JSONObject().put("room_id", id))
                    val room = state.optJSONObject("room") ?: JSONObject()
                    val driver = state.optJSONObject("driver_status")
                    val working = driver?.optBoolean("working") == true
                    val members = membersOf(room.optJSONArray("members"))
                    if (since < 0) since = (room.optInt("latest_seq", 0) - 400).coerceAtLeast(0)
                    val events = mutableListOf<JSONObject>()
                    for (page in 0 until 5) {
                        val log = rpc(
                            "groups.log",
                            JSONObject().put("room_id", id).put("since_seq", since).put("limit", 200),
                        )
                        val batch = log.optJSONArray("events")?.objects().orEmpty()
                        events += batch
                        batch.forEach { event -> event.optInt("seq").let { if (it > since) since = it } }
                        if (batch.size < 200) break
                    }
                    cursors[id] = since
                    val current = _state.value.room ?: break
                    var list = current.events
                    var active = current.activeTurns
                    var live = current.live
                    var liveWho = current.liveWho
                    events.forEach { event ->
                        try {
                            active = trackTurns(active, event, members)
                            val line = eventLine(event, members) ?: return@forEach
                            if (line.role == "bot" && line.who == liveWho) {
                                live = ""
                                liveWho = ""
                            }
                            list = mergeEvent(list, line)
                        } catch (error: Exception) {
                            JrLog.i("skip room event: ${error.javaClass.simpleName}")
                        }
                    }
                    if (!working) {
                        active = emptyMap()
                        live = ""
                        liveWho = ""
                    }
                    val pending = pendingOf(driver?.optJSONArray("pending_actions"))
                    _state.value = _state.value.copy(
                        room = current.copy(
                            name = room.optString("name", current.name),
                            members = members,
                            events = list.takeLast(500),
                            pending = pending,
                            working = working,
                            live = live,
                            liveWho = liveWho,
                            activeTurns = active,
                            thinking = active.values.distinct(),
                            loaded = true,
                        ),
                        offline = false,
                    )
                    remember()
                    syncRelay()
                    delay(if (working) 1_000 else 4_000)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    note(error.jr())
                    delay(5_000)
                }
            }
        }
    }

    fun sendRoom(text: String) {
        val room = _state.value.room ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        // The local echo carries the event_id sent to the server, and the server's message.user
        // event comes back with that same id, so mergeEvent replaces the echo instead of adding a
        // second copy (the duplicate "you" lines in #2's follow-up).
        val eventId = ident()
        val echo = RoomEvent(
            seq = Int.MAX_VALUE,
            who = "you",
            text = body,
            key = "e-$eventId",
            role = "user",
            eventId = eventId,
            at = System.currentTimeMillis(),
            sending = true,
        )
        _state.value.room?.takeIf { it.id == room.id }?.let { current ->
            _state.value = _state.value.copy(room = current.copy(events = mergeEvent(current.events, echo).takeLast(500)))
        }
        viewModelScope.launch(safe) {
            try {
                rpc(
                    "groups.send",
                    JSONObject().put("room_id", room.id).put("event_id", eventId).put(
                        "payload",
                        JSONObject().put("text", body).put("thread_id", "main"),
                    ),
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value.room?.takeIf { it.id == room.id }?.let { current ->
                    _state.value = _state.value.copy(room = current.copy(events = current.events.filterNot { it.eventId == eventId && it.sending }))
                }
                note("Not sent: ${error.jr()}")
            }
        }
    }

    fun createRoom(name: String, profiles: List<String>) {
        val title = name.trim()
        if (title.isEmpty()) {
            note("Name the room.")
            return
        }
        if (profiles.size !in 2..6) {
            note("A room needs 2 to 6 bots.")
            return
        }
        if (_state.value.rooms.size >= 10) {
            note("This phone will not create an 11th room.")
            return
        }
        viewModelScope.launch(safe) {
            try {
                val members = JSONArray()
                profiles.distinct().forEach { profile ->
                    members.put(
                        JSONObject().put("member_id", "m$profile").put("profile", profile).put("handle", profile).put("display_name", profile),
                    )
                }
                rpc("groups.create", JSONObject().put("room_id", ident()).put("name", title.take(200)).put("members", members))
                refreshRooms()
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun renameRoom(name: String) {
        val room = _state.value.room ?: return
        viewModelScope.launch(safe) {
            try {
                rpc("groups.rename", JSONObject().put("room_id", room.id).put("event_id", ident()).put("name", name.trim().take(200)))
                refreshRooms()
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun stopRoom() {
        val room = _state.value.room ?: return
        viewModelScope.launch(safe) {
            try {
                rpc("groups.stop", JSONObject().put("room_id", room.id).put("cancel_id", ident()))
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun disbandRoom() {
        val room = _state.value.room ?: return
        viewModelScope.launch(safe) {
            try {
                rpc("groups.disband", JSONObject().put("room_id", room.id).put("cancel_id", ident()))
                closeRoom()
                refreshRooms()
            } catch (error: RpcException) {
                if (error.message.orEmpty().contains("stopping", ignoreCase = true)) {
                    try {
                        rpc("groups.stop", JSONObject().put("room_id", room.id).put("cancel_id", ident()))
                    } catch (_: Exception) {
                    }
                    note("Still stopping. Stop the room, then disband it again.")
                } else {
                    note(error.jr())
                }
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun setMembers(profiles: List<String>) {
        val room = _state.value.room ?: return
        if (!_state.value.membersEditable) return
        if (profiles.size !in 2..6) {
            note("A room needs 2 to 6 bots.")
            return
        }
        viewModelScope.launch(safe) {
            try {
                val members = JSONArray()
                profiles.distinct().forEach { profile ->
                    val kept = room.members.find { it.profile == profile }?.id ?: "m$profile"
                    members.put(
                        JSONObject().put("member_id", kept).put("profile", profile).put("handle", profile).put("display_name", profile),
                    )
                }
                rpc("groups.set_members", JSONObject().put("room_id", room.id).put("event_id", ident()).put("members", members))
                openRoom(room.id)
            } catch (error: RpcException) {
                if (error.code == -32601) {
                    _state.value = _state.value.copy(membersEditable = false)
                    note("This computer cannot edit room members yet.")
                } else if (error.data?.optString("reason") == "room_busy") {
                    note("Stop the room, then change members.")
                } else {
                    note(error.jr())
                }
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun approveRoom(action: PendingAction, choice: String) {
        val room = _state.value.room ?: return
        if (choice != "once" && choice != "deny") return
        viewModelScope.launch(safe) {
            try {
                rpc(
                    "groups.approve",
                    JSONObject()
                        .put("room_id", room.id)
                        .put("member_id", action.memberId)
                        .put("task_id", action.taskId)
                        .put("execution_generation", action.generation)
                        .put("choice", choice)
                        .put("request_id", action.requestId),
                )
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun retryRoom(action: PendingAction) {
        val room = _state.value.room ?: return
        viewModelScope.launch(safe) {
            try {
                rpc("groups.retry", JSONObject().put("room_id", room.id).put("task_id", action.taskId))
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun openMcp(profile: String) {
        viewModelScope.launch(safe) {
            _state.value = _state.value.copy(mcp = McpView(profile, emptyList(), emptyList()))
            reloadMcp(profile)
        }
    }

    fun addMcp(name: String, preset: String?, command: String, args: String, url: String) {
        val mcp = _state.value.mcp ?: return
        viewModelScope.launch(safe) {
            try {
                val params = JSONObject().put("name", name.trim()).put("profile", mcp.profile)
                if (!preset.isNullOrBlank()) params.put("preset", preset)
                if (command.isNotBlank() || url.isNotBlank()) {
                    val config = JSONObject()
                    if (url.isNotBlank()) config.put("url", url.trim())
                    if (command.isNotBlank()) {
                        config.put("command", command.trim())
                        config.put("args", JSONArray(args.split(" ").filter { it.isNotBlank() }))
                    }
                    params.put("config", config)
                }
                rpc("mcp.servers.add", params)
                reloadMcp(mcp.profile)
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun removeMcp(name: String) {
        val mcp = _state.value.mcp ?: return
        viewModelScope.launch(safe) {
            try {
                rpc("mcp.servers.remove", JSONObject().put("name", name).put("profile", mcp.profile))
                reloadMcp(mcp.profile)
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun testMcp(name: String) {
        val mcp = _state.value.mcp ?: return
        viewModelScope.launch(safe) {
            try {
                val result = rpc("mcp.servers.test", JSONObject().put("name", name).put("profile", mcp.profile), 60_000)
                note(
                    if (result.optBoolean("ok")) {
                        "Test ok. ${result.optJSONArray("tools")?.length() ?: 0} tools."
                    } else {
                        result.optString("error").ifBlank { "Test failed." }
                    },
                )
                reloadMcp(mcp.profile)
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun setMcpKey(name: String, value: String, env: String) {
        val mcp = _state.value.mcp ?: return
        viewModelScope.launch(safe) {
            try {
                val params = JSONObject().put("name", name).put("profile", mcp.profile).put("value", value)
                if (env.isNotBlank()) params.put("env_var", env.trim())
                rpc("mcp.servers.set_api_key", params)
                reloadMcp(mcp.profile)
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun setMcpEnabled(name: String, enabled: Boolean) {
        val mcp = _state.value.mcp ?: return
        viewModelScope.launch(safe) {
            try {
                io {
                    http.put(
                        "$base/api/mcp/servers/${enc(name)}/enabled",
                        JSONObject().put("enabled", enabled).put("profile", mcp.profile),
                    )
                }
                reloadMcp(mcp.profile)
            } catch (error: RpcException) {
                note(if (error.code == 409) "A plugin owns this server." else error.jr())
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun startOauth(name: String, context: android.content.Context) {
        val mcp = _state.value.mcp ?: return
        viewModelScope.launch(safe) {
            try {
                val started = rpc("mcp.servers.oauth.start", JSONObject().put("name", name).put("profile", mcp.profile))
                val url = started.optString("auth_url")
                if (url.isNotBlank()) {
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                }
                val flow = started.optString("session_id")
                for (attempt in 0 until 20) {
                    delay(2_000)
                    val poll = rpc(
                        "mcp.servers.oauth.poll",
                        JSONObject().put("name", name).put("profile", mcp.profile).put("session_id", flow),
                    )
                    if (poll.optString("status") != "pending") break
                }
                reloadMcp(mcp.profile)
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun answerCard(id: String, result: JSONObject) {
        val card = _state.value.cards.find { it.id == id } ?: return
        if (result.has("choice") && card.choices.isNotEmpty() && result.optString("choice") !in card.choices) return
        rpc.respondResult(id, result)
        _state.value = _state.value.copy(cards = _state.value.cards.filterNot { it.id == id })
        syncRelay()
    }

    fun showScreen(profile: String, metered: Boolean) {
        screenWanted = true
        screenMetered = metered
        screenJob?.cancel()
        screenJob = viewModelScope.launch(safe) { runScreen(profile, metered) }
    }

    fun hideScreen() {
        screenWanted = false
        screenJob?.cancel()
        thumbJob?.cancel()
        viewModelScope.launch(safe) { releaseLeaseAndBridge() }
    }

    fun takeOver() {
        val screen = _state.value.screen
        if (viewerId.isBlank()) return
        viewModelScope.launch(safe) {
            try {
                val result = rpc(
                    "display.lease.acquire",
                    JSONObject().put("profile", screen.profile).put("viewer_id", viewerId),
                )
                val holder = result.optJSONObject("lease")?.optString("holder").orEmpty()
                JrLog.i("display lease $holder")
                _state.value = _state.value.copy(screen = _state.value.screen.copy(controlling = holder == "human", holder = holder))
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun handBack() {
        viewModelScope.launch(safe) { releaseLease() }
    }

    fun startDesktop() {
        val profile = _state.value.screen.profile
        viewModelScope.launch(safe) {
            try {
                rpc("display.start", JSONObject().put("profile", profile), 120_000)
                if (screenWanted) runScreen(profile, screenMetered)
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun installDesktop() {
        viewModelScope.launch(safe) {
            try {
                rpc("display.install", JSONObject().put("profile", _state.value.screen.profile), 30_000)
                note("Install started on the computer.")
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    fun grantComputerUse() {
        viewModelScope.launch(safe) {
            try {
                io { http.post("$base/api/tools/computer-use/permissions/grant", JSONObject()) }
                note("Asked the computer to open its permission prompt.")
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    override fun onCleared() {
        wantSocket = false
        rpc.close(notify = false)
        releaseBridge()
        stopRelay()
        super.onCleared()
    }

    private suspend fun openSocket() {
        val epoch = ++socketEpoch
        readyEpoch = -1
        pingJob?.cancel()
        rpc.close(notify = false)
        _state.value = _state.value.copy(cards = emptyList(), offline = false)
        viewerId = ""
        try {
            val ticket = io { http.post("$base/api/auth/ws-ticket", JSONObject()).getString("ticket") }
            if (epoch != socketEpoch) return
            rpc.connect(base, ticket)
            _state.value = _state.value.copy(busy = false)
            viewModelScope.launch(safe) {
                delay(20_000)
                if (epoch == socketEpoch && wantSocket && readyEpoch != epoch && !_state.value.ready) {
                    val detail = if (rpc.opened) {
                        "the chat connection opened, but the computer never said it was ready (${whereOf("$base/api/ws")})"
                    } else {
                        rpc.lastError.ifBlank { "the chat connection did not open (${whereOf("$base/api/ws")})" }
                    }
                    JrLog.i("ws not ready after 20s opened=${rpc.opened}")
                    _state.value = _state.value.copy(notice = "Signed in, but $detail. Still trying.")
                }
            }
        } catch (error: Exception) {
            _state.value = _state.value.copy(offline = true, busy = false, notice = error.jr())
            if (wantSocket && epoch == socketEpoch) {
                delay(2_000)
                if (wantSocket && epoch == socketEpoch) openSocket()
            }
        }
    }

    private suspend fun onFrame(frame: JSONObject) {
        if (frame.optString("method") != "event") return
        val params = frame.optJSONObject("params") ?: return
        val type = params.optString("type")
        val sid = params.optString("session_id")
        val payload = params.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "gateway.ready" -> onReady(payload)
            "request.cancel" -> {
                val id = payload.optString("id")
                _state.value = _state.value.copy(cards = _state.value.cards.filterNot { it.id == id })
                syncRelay()
            }
            "message.delta" -> appendDraft(sid, payload.optString("text"))
            "reasoning.delta", "thinking.delta" -> appendReasoning(sid, payload.optString("text"))
            "tool.start" -> {
                setActivity(sid, payload.optString("name").ifBlank { "a tool" })
                appendLine(sid, Line("tool", payload.optString("name").ifBlank { "tool" }))
            }
            "tool.complete" -> {
                setActivity(sid, "")
                appendLine(sid, Line("tool", payload.optString("summary").ifBlank { payload.optString("name") }))
            }
            "message.complete", "error" -> finishTurn(sid, type, payload)
            "room.member.activity" -> onRoomActivity(payload)
            "display.status" -> {
                if (!screenWanted) return
                val live = _state.value.screen
                val stillUp = payload.optBoolean("running", live.running)
                if (live.pageUrl.isNotBlank() && stillUp && !screenMetered) return
                val profile = payload.optString("profile").ifBlank { live.profile }
                if (profile.isNotBlank()) runScreen(profile, screenMetered)
            }
            "display.lease" -> {
                if (!screenWanted) return
                val profile = payload.optString("profile_key").ifBlank { _state.value.screen.profile }
                if (profile.isNotBlank()) runScreen(profile, screenMetered)
            }
        }
    }

    private suspend fun onReady(payload: JSONObject) {
        val epoch = socketEpoch
        if (readyEpoch == epoch) return
        readyEpoch = epoch
        heartbeat = payload.optBoolean("heartbeat")
        JrLog.i("gateway.ready heartbeat=$heartbeat")
        val declines = try {
            rpc("client.capabilities", JSONObject().put("server_requests", true)).optBoolean("declines_not_shown", false)
        } catch (error: RpcException) {
            JrLog.i("rpc client.capabilities ${error.code}")
            false
        }
        if (epoch != socketEpoch) return
        rpc.noteCapabilities(declines)
        _state.value = _state.value.copy(ready = true, offline = false, busy = false)
        listOf<Pair<String, suspend () -> Unit>>(
            "bots" to { refreshProfiles() },
            "rooms" to { refreshRooms() },
            "room capabilities" to { loadCapabilities() },
        ).forEach { (what, load) ->
            try {
                load()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                JrLog.i("load $what failed: ${error.javaClass.simpleName}")
                note("Could not load $what: ${error.jr()}")
            }
        }
        val chat = _state.value.chat
        if (chat != null) openBot(chat.profile)
        if (heartbeat) {
            pingJob?.cancel()
            pingJob = viewModelScope.launch(safe) {
                while (isActive && socketEpoch == epoch) {
                    delay(15_000)
                    if (System.currentTimeMillis() - rpc.lastInboundMs > 45_000) {
                        JrLog.i("ws idle")
                        rpc.close(notify = true)
                        break
                    }
                    try {
                        rpc("gateway.ping", JSONObject(), 10_000)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (screenWanted) runScreen(_state.value.screen.profile.ifBlank { "default" }, screenMetered)
    }

    private suspend fun refreshProfiles() {
        val listed = rpc("profiles.list", JSONObject().put("include_sessions", true))
        val bots = listed.optJSONArray("profiles")?.objects().orEmpty().map { row ->
            val canon = row.optJSONObject("canonical_session")
            Bot(
                name = row.optString("name"),
                model = row.optString("model"),
                sessionId = canon?.optString("id").orEmpty(),
                isDefault = row.optBoolean("is_default") || row.optString("name") == "default",
                preview = canon?.optString("preview").orEmpty(),
            )
        }
        val mirror = listed.optJSONArray("profiles")?.objects().orEmpty()
            .flatMap { row -> try { mirrorOf(row) } catch (_: Exception) { emptyList() } }
            .filter { room -> room.id.isNotBlank() && _state.value.rooms.none { it.id == room.id } }
            .distinctBy { it.id }
        _state.value = _state.value.copy(profiles = bots.filter { it.name.isNotBlank() }.distinctBy { it.name }, mirror = mirror)
    }

    private suspend fun refreshRooms() {
        // Before this fix `return@repeat` only skipped to the next of 20 iterations, so the same page
        // was fetched 20 times and every room was listed 20 times. The duplicate "r-<id>" LazyColumn
        // keys then crashed Compose on every start once one room existed (#2).
        val all = mutableListOf<JSONObject>()
        var offset = 0
        for (pageNo in 0 until 20) {
            val page = rpc("groups.list", JSONObject().put("limit", 500).put("offset", offset))
            all += page.optJSONArray("rooms")?.objects().orEmpty()
            if (!page.has("next_offset") || page.isNull("next_offset")) break
            val next = page.optInt("next_offset", -1)
            if (next <= offset) break
            offset = next
        }
        val rows = all.mapNotNull { row ->
            val id = row.optString("room_id").ifBlank { row.optString("id") }
            if (id.isBlank()) null else RoomRow(id, row.optString("name").ifBlank { id }, row.optJSONArray("members")?.length() ?: 0)
        }.distinctBy { it.id }
        _state.value = _state.value.copy(
            rooms = rows,
            mirror = _state.value.mirror.filter { mirror -> rows.none { it.id == mirror.id } },
        )
    }

    private suspend fun loadCapabilities() {
        val editable = try {
            val caps = rpc("groups.capabilities", JSONObject())
            strings(caps.optJSONArray("methods")).contains("groups.set_members")
        } catch (error: RpcException) {
            JrLog.i("rpc groups.capabilities ${error.code}")
            false
        }
        _state.value = _state.value.copy(membersEditable = editable)
    }

    private suspend fun reloadMcp(profile: String) {
        val list = rpc("mcp.servers.list", JSONObject().put("profile", profile))
        val status = try {
            rpc("mcp.servers.status", JSONObject().put("profile", profile))
        } catch (_: Exception) {
            JSONObject()
        }
        val statusByName = status.optJSONArray("servers")?.objects().orEmpty().associateBy { it.optString("name") }
        val servers = list.optJSONArray("servers")?.objects().orEmpty().map { row ->
            val live = statusByName[row.optString("name")]
            val detail = listOf(row.optString("command"), row.optJSONArray("args")?.join(" ").orEmpty(), row.optString("url"))
                .filter { it.isNotBlank() }.joinToString(" ")
            McpServer(
                name = row.optString("name"),
                source = row.optString("source"),
                enabled = row.optBoolean("enabled"),
                detail = detail,
                status = live?.optString("status").orEmpty(),
            )
        }
        val catalog = try {
            rpc("mcp.catalog", JSONObject().put("profile", profile)).optJSONArray("servers")?.objects().orEmpty()
                .map { it.optString("name") to it.optString("description") }
        } catch (_: Exception) {
            emptyList()
        }
        _state.value = _state.value.copy(mcp = McpView(profile, servers, catalog))
    }

    private suspend fun runScreen(profile: String, metered: Boolean) {
        screenMetered = metered
        thumbJob?.cancel()
        try {
            val status = rpc("display.status", JSONObject().put("profile", profile))
            val supported = status.optBoolean("supported")
            val lease = status.optJSONObject("lease")
            var screen = ScreenState(
                profile = profile,
                supported = supported,
                running = status.optBoolean("running"),
                installed = status.optBoolean("installed"),
                holder = lease?.optString("holder").orEmpty(),
                blocker = status.optString("blocker"),
                pageUrl = _state.value.screen.pageUrl,
                controlling = _state.value.screen.controlling && _state.value.screen.profile == profile,
                checks = _state.value.screen.checks,
            )
            if (!supported) {
                releaseBridge()
                screen = screen.copy(pageUrl = "", thumbnail = "", message = "This host keeps its real display. Screenshots show up in the bot chat.")
                screen = screen.copy(checks = computerUseStatus())
            } else if (metered) {
                releaseBridge()
                screen = screen.copy(pageUrl = "", thumbnail = "", paused = true, message = "Paused on a metered network.")
            } else if (screen.pageUrl.isBlank() && status.optBoolean("running")) {
                screen = attachRfb(profile, screen, passViewer = false)
            } else if (!status.optBoolean("running")) {
                releaseBridge()
                screen = screen.copy(pageUrl = "", message = screen.blocker.ifBlank { "The desktop is stopped." })
            }
            _state.value = _state.value.copy(screen = screen)
            JrLog.i("display holder ${screen.holder.ifBlank { "none" }}")
            if (screen.pageUrl.isBlank() && supported && !metered && status.optBoolean("running")) watchThumbnails(profile)
        } catch (error: Exception) {
            _state.value = _state.value.copy(screen = _state.value.screen.copy(profile = profile, message = error.jr()))
        }
    }

    private suspend fun attachRfb(profile: String, screen: ScreenState, passViewer: Boolean): ScreenState {
        val params = JSONObject().put("profile", profile)
        if (passViewer && viewerId.isNotBlank()) params.put("viewer_id", viewerId)
        val observed = rpc("display.observe", params, 60_000)
        viewerId = observed.optString("viewer_id")
        val ticket = observed.optString("ticket")
        if (ticket.isBlank()) return screen.copy(message = "The computer did not open a display ticket.")
        val upstream = base.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/api/display/ws?display_ticket=" + enc(ticket)
        intentionalBridgeClose = true
        val page = bridge.open(upstream)
        intentionalBridgeClose = false
        lastRfbMs = System.currentTimeMillis()
        watchStall(profile)
        return screen.copy(pageUrl = page, thumbnail = "", message = "", paused = false, running = true)
    }

    private fun watchStall(profile: String) {
        viewModelScope.launch(safe) {
            val epoch = socketEpoch
            while (isActive && screenWanted && socketEpoch == epoch && _state.value.screen.pageUrl.isNotBlank()) {
                delay(5_000)
                if (lastRfbMs > 0 && System.currentTimeMillis() - lastRfbMs > 20_000) {
                    JrLog.i("display stalled")
                    releaseBridge()
                    _state.value = _state.value.copy(
                        screen = _state.value.screen.copy(pageUrl = "", message = "The desktop picture stalled."),
                    )
                    watchThumbnails(profile)
                    break
                }
            }
        }
    }

    private fun watchThumbnails(profile: String) {
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch(safe) {
            val epoch = socketEpoch
            while (isActive && screenWanted && socketEpoch == epoch) {
                val screen = _state.value.screen
                if (screen.pageUrl.isNotBlank() || screenMetered || !screen.supported || !screen.running) break
                pollThumbnail(profile)
                delay(1_000)
            }
        }
    }

    private suspend fun pollThumbnail(profile: String) {
        if (!screenWanted || _state.value.screen.pageUrl.isNotBlank() || screenMetered) return
        val shot = try {
            rpc("display.thumbnail", JSONObject().put("profile", profile))
        } catch (_: Exception) {
            return
        }
        val url = shot.optString("data_url")
        _state.value = _state.value.copy(
            screen = _state.value.screen.copy(
                thumbnail = url,
                message = shot.optString("suppressed").ifBlank { _state.value.screen.message },
            ),
        )
    }

    private fun onRfbClosed(code: Int) {
        if (intentionalBridgeClose || !screenWanted) return
        if (code != 4000) {
            releaseBridge()
            _state.value = _state.value.copy(screen = _state.value.screen.copy(pageUrl = "", message = "The desktop picture closed."))
            watchThumbnails(_state.value.screen.profile)
            return
        }
        val now = System.currentTimeMillis()
        while (rfbCloses.isNotEmpty() && now - rfbCloses.first() > 10_000) rfbCloses.removeFirst()
        if (rfbCloses.size >= 3) {
            releaseBridge()
            _state.value = _state.value.copy(screen = _state.value.screen.copy(pageUrl = "", message = "The desktop picture keeps disconnecting."))
            return
        }
        rfbCloses.addLast(now)
        viewModelScope.launch(safe) {
            try {
                val screen = attachRfb(_state.value.screen.profile, _state.value.screen, passViewer = true)
                _state.value = _state.value.copy(screen = screen)
            } catch (error: Exception) {
                note(error.jr())
            }
        }
    }

    private suspend fun releaseLease() {
        val id = viewerId
        val profile = _state.value.screen.profile
        if (id.isNotBlank() && _state.value.screen.controlling) {
            try {
                rpc("display.lease.release", JSONObject().put("profile", profile).put("viewer_id", id))
            } catch (error: Exception) {
                JrLog.i("rpc display.lease.release ${if (error is RpcException) error.code else -1}")
            }
        }
        _state.value = _state.value.copy(screen = _state.value.screen.copy(controlling = false))
    }

    private suspend fun releaseLeaseAndBridge() {
        releaseLease()
        releaseBridge()
        viewerId = ""
        _state.value = _state.value.copy(screen = _state.value.screen.copy(pageUrl = "", controlling = false, thumbnail = ""))
    }

    private fun releaseBridge() {
        intentionalBridgeClose = true
        bridge.close()
        intentionalBridgeClose = false
    }

    private suspend fun computerUseStatus(): String {
        return try {
            val status = io { http.get("$base/api/tools/computer-use/status") }
            val checks = status.optJSONArray("checks")?.objects()?.joinToString("\n") { row ->
                row.optString("name").ifBlank { row.optString("id") } + ": " + row.optString("detail").ifBlank { row.optString("ok") }
            }.orEmpty()
            listOf(status.optString("platform"), if (status.optBoolean("ready")) "ready" else "not ready", checks)
                .filter { it.isNotBlank() }.joinToString("\n")
        } catch (error: Exception) {
            error.jr()
        }
    }

    private fun appendDraft(sid: String, text: String) {
        if (text.isEmpty()) return
        val chat = _state.value.chat ?: return
        if (chat.sessionId != sid) return
        _state.value = _state.value.copy(chat = chat.copy(draft = chat.draft + text, streaming = true))
    }

    private fun appendReasoning(sid: String, text: String) {
        if (text.isEmpty()) return
        val chat = _state.value.chat ?: return
        if (chat.sessionId != sid) return
        _state.value = _state.value.copy(chat = chat.copy(reasoning = (chat.reasoning + text).takeLast(4_000), streaming = true))
    }

    private fun setActivity(sid: String, name: String) {
        val chat = _state.value.chat ?: return
        if (chat.sessionId != sid) return
        _state.value = _state.value.copy(chat = chat.copy(activity = name, streaming = true))
    }

    private fun appendLine(sid: String, line: Line) {
        val chat = _state.value.chat ?: return
        if (chat.sessionId != sid || line.text.isBlank()) return
        _state.value = _state.value.copy(chat = chat.copy(lines = chat.lines + line, streaming = true))
    }

    private fun finishTurn(sid: String, type: String, payload: JSONObject) {
        streaming.remove(sid)
        val chat = _state.value.chat
        if (type == "error") note(payload.optString("message").ifBlank { "The computer reported an error." })
        if (chat?.sessionId == sid) {
            val draft = chat.draft
            val lines = if (draft.isNotBlank() && type != "message.complete") chat.lines + Line("assistant", draft) else chat.lines
            _state.value = _state.value.copy(chat = chat.copy(lines = lines, draft = "", streaming = false, reasoning = "", activity = ""))
            if (type == "message.complete") {
                viewModelScope.launch(safe) {
                    try {
                        val history = rpc("session.history", JSONObject().put("session_id", sid).put("profile", chat.profile))
                        val current = _state.value.chat
                        if (current?.sessionId == sid) {
                            _state.value = _state.value.copy(chat = current.copy(lines = linesFrom(history.optJSONArray("messages")), draft = ""))
                            remember()
                        }
                    } catch (_: Exception) {
                        val current = _state.value.chat
                        if (current?.sessionId == sid && draft.isNotBlank()) {
                            _state.value = _state.value.copy(chat = current.copy(lines = current.lines + Line("assistant", draft), draft = ""))
                        }
                    }
                }
            }
        }
        syncRelay()
    }

    private fun onRoomActivity(payload: JSONObject) {
        val room = _state.value.room ?: return
        if (payload.optString("room_id") != room.id) return
        val memberId = payload.optString("member_id")
        val who = room.members.find { it.id == memberId }?.let { memberName(it) }.orEmpty()
        when (payload.optString("kind")) {
            "message.delta" -> {
                val text = payload.optJSONObject("payload")?.optString("text").orEmpty()
                if (text.isEmpty()) return
                val restart = who.isNotBlank() && who != room.liveWho
                _state.value = _state.value.copy(
                    room = room.copy(live = if (restart) text else room.live + text, liveWho = who.ifBlank { room.liveWho }, working = true),
                )
            }
            "tool.started", "reasoning.delta", "message.interim" -> {
                if (memberId.isBlank() || who.isBlank() || room.activeTurns.containsKey(memberId)) return
                val active = room.activeTurns + (memberId to who)
                _state.value = _state.value.copy(room = room.copy(activeTurns = active, thinking = active.values.distinct(), working = true))
            }
            else -> return
        }
        syncRelay()
    }

    private fun addCard(id: String, method: String, params: JSONObject) {
        if (_state.value.cards.any { it.id == id }) return
        val questions = params.optJSONArray("questions")?.objects().orEmpty().map { question ->
            Question(
                id = question.optString("qid"),
                prompt = question.optString("question"),
                choices = strings(question.optJSONArray("choices")),
                multi = question.optBoolean("multi_select"),
            )
        }
        val choices = strings(params.optJSONArray("choices")).ifEmpty {
            if (method == "approval") listOf("once", "session", "always", "deny") else emptyList()
        }
        val title = when (method) {
            "approval" -> "Approval"
            "sudo", "display.install.sudo" -> "Password"
            "secret" -> "Secret"
            "clarify" -> "Question"
            "vault.unlock_prompt" -> "Unlock"
            "vault.save_login" -> "Save login"
            "vault.code" -> "Code"
            else -> "The computer asks"
        }
        val body = listOf(
            params.optString("description"),
            params.optString("command"),
            params.optString("prompt"),
            params.optString("site"),
            params.optString("origin"),
            params.optString("display_name"),
            params.optString("env_var"),
        ).filter { it.isNotBlank() }.distinct().joinToString("\n")
        _state.value = _state.value.copy(
            cards = _state.value.cards + Card(
                id = id,
                method = method,
                title = title,
                body = body,
                choices = choices,
                questions = questions,
                secret = method == "sudo" || method == "secret" || method == "display.install.sudo" || method.startsWith("vault"),
                saveLogin = method == "vault.save_login",
            ),
        )
    }

    private fun replay(entry: JSONObject) {
        val id = entry.optString("id")
        val method = entry.optString("method")
        val params = entry.optJSONObject("params") ?: JSONObject()
        if (id.isBlank() || method.isBlank()) return
        when {
            method in RpcSocket.WINDOW_METHODS -> if (rpc.declinesNotShown == true) rpc.respondError(id, 4404, "not shown")
            method in RpcSocket.CARD_METHODS -> addCard(id, method, params)
            else -> rpc.respondError(id, -32601, "method not found")
        }
    }

    private fun syncRelay() {
        val waiting = _state.value.cards.isNotEmpty()
        val working = streaming.isNotEmpty() || _state.value.room?.working == true
        val app = getApplication<Application>()
        if (!waiting && !working) {
            if (relayWaiting != null) stopRelay()
            return
        }
        if (relayWaiting == waiting) return
        relayWaiting = waiting
        try {
            RelayService.start(app, waiting)
        } catch (_: Exception) {
            JrLog.i("relay start failed")
        }
    }

    private fun stopRelay() {
        relayWaiting = null
        try {
            RelayService.stop(getApplication())
        } catch (_: Exception) {
        }
    }

    private fun remember() {
        val chat = _state.value.chat
        val transcript = JSONArray().apply {
            chat?.lines?.takeLast(80)?.forEach { put(JSONObject().put("role", it.role).put("text", it.text.take(4_000))) }
        }.toString()
        val rooms = JSONArray().apply {
            _state.value.room?.events?.takeLast(500)?.forEach {
                put(JSONObject().put("seq", it.seq).put("who", it.who).put("text", it.text.take(2_000)))
            }
        }.toString()
        val cursorJson = JSONObject().apply { cursors.forEach { (id, seq) -> put(id, seq) } }.toString()
        viewModelScope.launch(safe) { store.saveCaches(cursorJson, transcript, rooms) }
    }

    private fun parseCursors(raw: String) {
        cursors.clear()
        val json = try {
            JSONObject(raw.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
        json.keys().forEach { key -> cursors[key] = json.optInt(key) }
    }

    private suspend fun rpc(method: String, params: JSONObject, timeoutMs: Long = 30_000): JSONObject {
        JrLog.i("rpc $method")
        return try {
            withContext(Dispatchers.IO) { rpc.call(method, params, timeoutMs) }
        } catch (error: RpcException) {
            JrLog.i("rpc $method ${error.code}")
            if (error.code == 401) {
                wantSocket = false
                tokens.clear()
                _state.value = _state.value.copy(ready = false, signedIn = false, offline = false, notice = "Signed out")
                stopRelay()
            }
            throw error
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun note(text: String) {
        _state.value = _state.value.copy(notice = text.take(400))
    }

    private class Notice(message: String) : Exception(message)

    private fun errorNotice(message: String): Nothing = throw Notice(message)

    private fun Throwable.jr(): String = when (this) {
        is Notice -> message.orEmpty()
        is NetFailure -> "${original.jr().trimEnd('.')} (${where})"
        is RpcException -> message?.take(300)?.ifBlank { null } ?: "The computer rejected that."
        is java.net.SocketTimeoutException -> "The computer did not answer."
        is javax.net.ssl.SSLException -> "HTTPS failed (${message?.take(120) ?: javaClass.simpleName}). If hermes serve runs without TLS, use http:// instead."
        is java.net.ConnectException -> "ConnectException: ${message?.take(160) ?: "could not connect"}. Check the address, the port, and that hermes serve is running"
        is java.net.UnknownHostException -> "Could not find that computer."
        is java.io.IOException -> message?.take(180)?.ifBlank { null } ?: "Could not reach the computer (${javaClass.simpleName})."
        else -> "${javaClass.simpleName}: ${message?.take(200).orEmpty()}".trimEnd(':', ' ')
    }
}

private fun normalizeBase(raw: String): String {
    var text = raw.trim()
    if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://$text"
    val uri = URI(text)
    val host = uri.host ?: text.substringAfter("://").substringBefore("/")
    val port = if (uri.port == -1) 9119 else uri.port
    val scheme = uri.scheme ?: "http"
    val bracketed = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
    return "$scheme://$bracketed:$port"
}

private fun ident(): String = "jr" + UUID.randomUUID().toString().replace("-", "")

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun strings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
}

private fun JSONArray.join(sep: String): String = (0 until length()).joinToString(sep) { optString(it) }

private fun linesFrom(array: JSONArray?): List<Line> {
    if (array == null) return emptyList()
    return array.objects().mapNotNull { row ->
        val role = row.optString("role").ifBlank { row.optString("display_kind") }
        val text = row.optString("text").ifBlank { row.optString("content") }
        if (text.isBlank()) null else Line(role.ifBlank { "note" }, text.take(8_000))
    }
}

private fun membersOf(array: JSONArray?): List<Member> =
    array?.objects().orEmpty().map { Member(it.optString("member_id"), it.optString("profile"), it.optString("handle")) }

/** Name shown for a member: its @handle (what the room addresses it by), else its profile. */
internal fun memberName(member: Member): String =
    member.handle.ifBlank { member.profile }.ifBlank { "bot" }

/** True for opaque machine ids (install:…, gateway ids, hex blobs) that must never be shown as a name. */
internal fun looksLikeId(name: String): Boolean =
    name.contains(':') || name.length >= 24 && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/**
 * Turn bookkeeping for the "is thinking…" indicator: turn.started adds the member, any terminal
 * event (settled / failed / cancelled / deferred / reassigned) or its message removes it.
 */
private fun trackTurns(active: Map<String, String>, event: JSONObject, members: List<Member>): Map<String, String> {
    val payload = event.optJSONObject("payload") ?: return active
    val memberId = payload.optString("member_id").ifBlank { event.optJSONObject("actor")?.optString("id").orEmpty() }
    if (memberId.isBlank()) return active
    return when (event.optString("kind")) {
        "turn.started" -> active + (memberId to (members.find { it.id == memberId }?.let { memberName(it) } ?: "a bot"))
        "turn.settled", "turn.failed", "turn.cancelled", "turn.deferred", "turn.reassigned", "message.member", "member.unavailable" -> active - memberId
        else -> active
    }
}

/**
 * Map one durable room-log event to a transcript line, or null when it is bookkeeping.
 *
 * Shown: message.user, message.member (the conversation), plus a few subtle notices: turn.failed,
 * member.unavailable, room.renamed, room.members_changed, room.stop_requested, room.disbanded.
 * Hidden: turn.started / settled / cancelled / deferred / reassigned, room.activity, room.created,
 * authority.claimed / lost and anything unknown — the same split the server's own discussion
 * transcript uses (only message.user and committed message.member are conversation).
 */
private fun eventLine(event: JSONObject, members: List<Member>): RoomEvent? {
    val kind = event.optString("kind")
    val actor = event.optJSONObject("actor") ?: JSONObject()
    val payload = event.optJSONObject("payload") ?: JSONObject()
    val seq = event.optInt("seq")
    val eventId = event.optString("event_id").ifBlank { "seq$seq" }
    val at = (event.optDouble("created_at", 0.0) * 1000).toLong()
    val memberId = payload.optString("member_id").ifBlank { actor.optString("id") }
    val member = members.find { it.id == memberId }
        ?: members.find { it.profile.isNotBlank() && it.profile == actor.optString("profile") }
    val botName = member?.let { memberName(it) }
        ?: actor.optString("display_name").takeIf { it.isNotBlank() && !looksLikeId(it) }
        ?: actor.optString("profile").takeIf { it.isNotBlank() && !looksLikeId(it) }
        ?: "bot"
    fun notice(text: String) = RoomEvent(seq, "", text.take(400), key = "e-$eventId", role = "notice", eventId = eventId, at = at)
    return when (kind) {
        "message.user" -> {
            val text = payload.optString("text")
            if (text.isBlank()) null else RoomEvent(seq, "you", text.take(8_000), key = "e-$eventId", role = "user", eventId = eventId, at = at)
        }
        "message.member" -> {
            val text = payload.optString("text")
            if (text.isBlank()) null else RoomEvent(
                seq, botName, text.take(16_000), key = "e-$eventId", role = "bot", eventId = eventId, at = at,
                profile = member?.profile ?: actor.optString("profile"),
            )
        }
        "turn.failed" -> notice("@$botName couldn't answer" + payload.optString("error").takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" }.orEmpty())
        "member.unavailable" -> notice("@$botName is unavailable")
        "room.renamed" -> notice("Room renamed" + payload.optString("name").takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty())
        "room.members_changed" -> notice("Members changed")
        "room.stop_requested" -> notice("Stopped")
        "room.disbanded" -> notice("Room disbanded")
        else -> null
    }
}

/** Insert or replace by event id (a server echo replaces its optimistic local copy), kept in seq order. */
internal fun mergeEvent(list: List<RoomEvent>, line: RoomEvent): List<RoomEvent> {
    val index = list.indexOfFirst { it.eventId.isNotBlank() && it.eventId == line.eventId }
    if (index >= 0) {
        val old = list[index]
        return list.toMutableList().also { it[index] = line.copy(at = if (line.at > 0) line.at else old.at) }
            .sortedBy { it.seq }
    }
    if (list.isEmpty() || list.last().seq <= line.seq) return list + line
    return (list + line).sortedBy { it.seq }
}

private fun pendingOf(array: JSONArray?): List<PendingAction> =
    array?.objects().orEmpty().map { row ->
        val choices = strings(row.optJSONArray("choices")).filter { it == "once" || it == "deny" }
        PendingAction(
            kind = row.optString("kind"),
            memberId = row.optString("member_id"),
            taskId = row.optString("task_id"),
            generation = row.optInt("execution_generation"),
            requestId = row.optString("request_id"),
            choices = choices.ifEmpty { if (row.optString("kind") == "approval") listOf("once", "deny") else emptyList() },
            label = row.optString("description").ifBlank { row.optString("command") }.ifBlank { row.optString("kind") },
        )
    }

private fun mirrorOf(profile: JSONObject): List<MirrorRoom> {
    val doc = profile.optJSONObject("ui_meta")?.optJSONObject("hermes-bots-groups") ?: return emptyList()
    if (doc.has("version") && doc.optInt("version") != 3) return emptyList()
    val rooms = doc.optJSONObject("rooms") ?: return emptyList()
    return rooms.keys().asSequence().mapNotNull { key ->
        val room = rooms.optJSONObject(key) ?: return@mapNotNull null
        val log = room.optJSONArray("log")?.objects().orEmpty().map { it.optString("text") }.filter { it.isNotBlank() }
        MirrorRoom(
            id = room.optString("roomId").ifBlank { key },
            name = room.optString("name").ifBlank { key },
            lines = log.takeLast(40),
            omitted = room.optInt("omitted"),
        )
    }.toList()
}

private fun sniff(bytes: ByteArray, mime: String, name: String): String {
    if (bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte()) return "pdf"
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image"
    if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "image"
    if (mime.startsWith("image/")) return "image"
    if (mime == "application/pdf" || name.endsWith(".pdf", true)) return "pdf"
    return "file"
}

private fun ext(name: String): String = name.substringAfterLast('.', "png").lowercase().take(8)
