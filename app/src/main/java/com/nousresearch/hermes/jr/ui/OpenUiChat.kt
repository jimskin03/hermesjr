package com.nousresearch.hermes.jr.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.nousresearch.hermes.jr.session.HermesModel
import com.nousresearch.hermes.jr.session.UiState
import com.nousresearch.hermes.jr.ui.theme.Accent
import com.nousresearch.hermes.jr.ui.theme.Danger
import com.nousresearch.hermes.jr.ui.theme.Elevated
import com.nousresearch.hermes.jr.ui.theme.Ink
import com.nousresearch.hermes.jr.ui.theme.Muted
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val OPENUI_URL = "https://appassets.androidplatform.net/assets/openui/index.html"

/**
 * Hosts the bundled OpenUI chat page. The page talks only to this process over the JS bridge;
 * WebViewAssetLoader serves local assets and blocks remote document loads.
 */
@Composable
fun OpenUiChatPane(state: UiState, model: HermesModel) {
    val chat = state.chat ?: return
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.attach(uri)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("File", color = Accent) }
            TextButton(onClick = model::closeChat) { Text("Back", color = Muted) }
        }
        OpenUiWebView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            snapshotJson = remember(chat, state.openUiRichDefault) { chatSnapshotJson(state) },
            onSend = { text, rich -> model.sendChat(text, richUi = rich) },
            onStop = model::stopChat,
            onAction = { model.handleOpenUiAction(it) },
        )
    }
}

@Composable
fun OpenUiRoomPane(state: UiState, model: HermesModel) {
    val room = state.room ?: return
    var renaming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(room.name) }
    var disband by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var roundsOpen by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(room.members.map { it.profile }.toSet()) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 4.dp)) {
            TextButton(onClick = model::closeRoom) { Text("Back", color = Muted) }
            TextButton(onClick = model::stopRoom) { Text("Stop", color = Accent) }
            TextButton(onClick = { renaming = true }) { Text("Rename", color = Accent) }
            if (state.membersEditable) TextButton(onClick = { editing = true; picked = room.members.map { it.profile }.toSet() }) {
                Text("Members", color = Accent)
            }
            TextButton(onClick = { roundsOpen = true }) { Text("Rounds ${room.maxRounds}", color = Accent) }
            TextButton(onClick = { disband = true }) { Text("Disband", color = Danger) }
        }
        // Approvals / retries a room turn is waiting on stay native (same row as the Compose room).
        room.pending.distinctBy { it.requestId.ifBlank { it.taskId } }.forEach { action ->
            Column(Modifier.padding(horizontal = 12.dp)) { PendingRow(action, model) }
        }
        OpenUiWebView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            snapshotJson = remember(room, state.openUiRichDefault) { roomSnapshotJson(state) },
            onSend = { text, rich -> model.sendRoom(text, richUi = rich) },
            onStop = model::stopRoom,
            onAction = { model.handleOpenUiAction(it) },
        )
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = Elevated,
            title = { Text("Rename", color = Ink) },
            text = {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, colors = fieldColors())
            },
            confirmButton = { TextButton(onClick = { model.renameRoom(name); renaming = false }) { Text("Save", color = Accent) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = Muted) } },
        )
    }
    if (roundsOpen) {
        var value by remember { mutableStateOf(room.maxRounds) }
        AlertDialog(
            onDismissRequest = { roundsOpen = false },
            containerColor = Elevated,
            title = { Text("Max bot rounds", color = Ink) },
            text = { RoundsStepper(value) { value = it } },
            confirmButton = { TextButton(onClick = { model.setMaxRounds(value); roundsOpen = false }) { Text("Save", color = Accent) } },
            dismissButton = { TextButton(onClick = { roundsOpen = false }) { Text("Cancel", color = Muted) } },
        )
    }
    if (disband) {
        AlertDialog(
            onDismissRequest = { disband = false },
            containerColor = Elevated,
            title = { Text("Disband ${room.name}?", color = Ink) },
            text = { Text("The room is removed on the computer.", color = Muted) },
            confirmButton = { TextButton(onClick = { disband = false; model.disbandRoom() }) { Text("Disband", color = Danger) } },
            dismissButton = { TextButton(onClick = { disband = false }) { Text("Cancel", color = Muted) } },
        )
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = Elevated,
            title = { Text("Members", color = Ink) },
            text = {
                Column {
                    Text("2 to 6 bots.", color = Muted, fontSize = 12.sp)
                    state.profiles.forEach { bot ->
                        val on = bot.name in picked
                        TextButton(onClick = {
                            picked = if (on) picked - bot.name else if (picked.size < 6) picked + bot.name else picked
                        }) { Text(if (on) "✓ ${bot.name}" else bot.name, color = if (on) Accent else Ink) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { model.setMembers(picked.toList()); editing = false }) { Text("Save", color = Accent) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel", color = Muted) } },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OpenUiWebView(
    modifier: Modifier,
    snapshotJson: String,
    onSend: (String, Boolean) -> Unit,
    onStop: () -> Unit,
    onAction: (JSONObject) -> Unit,
) {
    val context = LocalContext.current
    val sendRef = rememberUpdatedState(onSend)
    val stopRef = rememberUpdatedState(onStop)
    val actionRef = rememberUpdatedState(onAction)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val latestSnap = rememberUpdatedState(snapshotJson)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()
            WebView(ctx).apply {
                setBackgroundColor(0xFF0D1117.toInt())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun send(text: String, richUi: Boolean) {
                            post { sendRef.value(text, richUi) }
                        }

                        @JavascriptInterface
                        fun stop() {
                            post { stopRef.value() }
                        }

                        @JavascriptInterface
                        fun action(payloadJson: String) {
                            post {
                                try {
                                    actionRef.value(JSONObject(payloadJson))
                                } catch (_: Exception) {
                                }
                            }
                        }

                        @JavascriptInterface
                        fun openLink(url: String) {
                            post {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {
                                }
                            }
                        }

                        @JavascriptInterface
                        fun ready() {
                            post { pageReady = true }
                        }
                    },
                    "HermesJrHost",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        return loader.shouldInterceptRequest(uri)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        if (url.startsWith("https://appassets.androidplatform.net/")) return false
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) {
                            }
                            return true
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // ready() from JS is the real signal; push once the document exists too.
                        view?.evaluateJavascript(
                            "window.HermesJrChat && window.HermesJrChat.applySnapshot(${JSONObject.quote(latestSnap.value)});",
                            null,
                        )
                    }
                }
                loadUrl(OPENUI_URL)
                webView = this
            }
        },
        update = { webView = it },
    )

    LaunchedEffect(snapshotJson, pageReady, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) {
            // Page may not have called ready yet; still try after a short wait.
            delay(50)
        }
        val quoted = JSONObject.quote(snapshotJson)
        wv.evaluateJavascript("window.HermesJrChat && window.HermesJrChat.applySnapshot($quoted);", null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}

internal fun chatSnapshotJson(state: UiState): String {
    val chat = state.chat ?: return emptySnap("chat", "")
    val messages = JSONArray()
    chat.lines.forEachIndexed { index, line ->
        if (line.text.isBlank() || line.role == "system") return@forEachIndexed
        messages.put(
            JSONObject()
                .put("id", "c$index-${line.role}")
                .put("role", when (line.role) {
                    "user" -> "user"
                    "tool" -> "tool"
                    else -> "bot"
                })
                .put("who", if (line.role == "user") "you" else chat.profile)
                .put("text", line.text),
        )
    }
    if (chat.draft.isNotBlank()) {
        messages.put(
            JSONObject()
                .put("id", "live")
                .put("role", "bot")
                .put("who", chat.profile)
                .put("text", chat.draft)
                .put("live", true),
        )
    }
    return JSONObject()
        .put("kind", "chat")
        .put("title", chat.profile)
        .put("messages", messages)
        .put("streaming", chat.streaming)
        .put("thinking", JSONArray())
        .put("activity", chat.activity)
        .put("reasoning", chat.reasoning)
        .put("richUiDefault", state.openUiRichDefault)
        .toString()
}

internal fun roomSnapshotJson(state: UiState): String {
    val room = state.room ?: return emptySnap("room", "")
    val messages = JSONArray()
    val events = room.events.distinctBy { it.key.ifBlank { "s${it.seq}-${it.who}" } }
    events.forEach { event ->
        messages.put(
            JSONObject()
                .put("id", event.key.ifBlank { "s${event.seq}-${event.who}" })
                .put("role", when (event.role) {
                    "user" -> "user"
                    "notice" -> "notice"
                    else -> "bot"
                })
                .put("who", if (event.role == "user") "you" else event.who)
                .put("text", event.text)
                .put("at", event.at)
                .put("pending", event.sending),
        )
    }
    if (room.live.isNotBlank()) {
        messages.put(
            JSONObject()
                .put("id", "live")
                .put("role", "bot")
                .put("who", room.liveWho.ifBlank { "bot" })
                .put("text", room.live)
                .put("live", true),
        )
    }
    val members = JSONArray()
    room.members.forEach { m ->
        members.put(JSONObject().put("handle", m.handle).put("profile", m.profile))
    }
    // Rooms: Rich UI always off by default (other clients would see the prompt).
    return JSONObject()
        .put("kind", "room")
        .put("title", room.name)
        .put("messages", messages)
        .put("streaming", room.live.isNotBlank())
        // Like the Compose room: a member whose reply is already streaming is not "thinking".
        .put("thinking", JSONArray(room.thinking.filter { it != room.liveWho || room.live.isBlank() }))
        .put("activity", "")
        .put("reasoning", "")
        .put("richUiDefault", false)
        .put("members", members)
        .put("maxRounds", room.maxRounds)
        .put("working", room.working)
        .toString()
}

private fun emptySnap(kind: String, title: String): String =
    JSONObject()
        .put("kind", kind)
        .put("title", title)
        .put("messages", JSONArray())
        .put("streaming", false)
        .put("thinking", JSONArray())
        .put("activity", "")
        .put("reasoning", "")
        .put("richUiDefault", false)
        .toString()
