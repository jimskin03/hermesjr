package com.nousresearch.hermes.jr.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.Build
import android.util.Base64
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.hermes.jr.session.Card
import com.nousresearch.hermes.jr.session.HermesModel
import com.nousresearch.hermes.jr.session.McpServer
import com.nousresearch.hermes.jr.session.PendingAction
import com.nousresearch.hermes.jr.session.UiState
import com.nousresearch.hermes.jr.setup.SetupCopy
import com.nousresearch.hermes.jr.ui.theme.Accent
import com.nousresearch.hermes.jr.ui.theme.Danger
import com.nousresearch.hermes.jr.ui.theme.Elevated
import com.nousresearch.hermes.jr.ui.theme.Field
import com.nousresearch.hermes.jr.ui.theme.Ink
import com.nousresearch.hermes.jr.ui.theme.Muted
import org.json.JSONObject

private enum class Destination(val label: String) {
    Chats("Chats"),
    Bots("Bots"),
    Screen("Screen"),
    More("More"),
}

@Composable
fun HermesJrApp(model: HermesModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (state.booting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Wordmark() }
        return
    }
    if (!state.ready) {
        ConnectScreen(state, model)
        return
    }
    var destination by rememberSaveable { mutableStateOf(Destination.Chats) }
    val overlay = state.chat != null || state.room != null || state.mirrorOpen != null || state.mcp != null
    BackHandler(overlay) {
        when {
            state.chat != null -> model.closeChat()
            state.room != null -> model.closeRoom()
            state.mirrorOpen != null -> model.closeMirror()
            state.mcp != null -> model.closeMcp()
        }
    }
    Scaffold(
        containerColor = Field,
        topBar = { Wordmark() },
        bottomBar = {
            NavigationBar(containerColor = Elevated) {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon(), contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                            indicatorColor = Field,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.offline) Banner("Offline. Reconnecting to the computer.")
            if (state.notice.isNotBlank()) Banner(state.notice, onDismiss = model::dismissNotice)
            Box(Modifier.weight(1f)) {
                when {
                    state.chat != null -> ChatPane(state, model)
                    state.room != null -> RoomPane(state, model)
                    state.mirrorOpen != null -> MirrorPane(state)
                    state.mcp != null -> McpPane(state, model)
                    destination == Destination.Chats -> ChatsPane(state, model)
                    destination == Destination.Bots -> BotsPane(state, model)
                    destination == Destination.Screen -> ScreenPane(state, model)
                    else -> MorePane(state, model)
                }
            }
            if (state.cards.isNotEmpty()) CardHost(state.cards.first(), model)
        }
    }
}

@Composable
private fun ConnectScreen(state: UiState, model: HermesModel) {
    var url by rememberSaveable { mutableStateOf(state.baseUrl) }
    val context = LocalContext.current
    LaunchedEffect(state.baseUrl) { if (url.isBlank()) url = state.baseUrl }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Wordmark()
        Text("The phone does not run Hermes. Paste the address of the computer that does.", color = Muted)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Computer") },
            placeholder = { Text("http://100.x.x.x:9119") },
            singleLine = true,
            colors = fieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.useHost(url) }, enabled = !state.busy, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Field)) {
                Text(if (state.busy) "Working" else "Connect")
            }
            if (state.baseUrl.isNotBlank()) {
                Button(onClick = { model.signIn(context) }, enabled = !state.busy, colors = ButtonDefaults.buttonColors(containerColor = Elevated, contentColor = Ink)) {
                    Text(if (state.signingIn) "Sign in again" else "Sign in")
                }
            }
        }
        if (state.signingIn) {
            Text("Finish signing in in the browser. If you closed it, tap Sign in again.", color = Muted, fontSize = 13.sp)
        } else if (state.signedIn) {
            Text("Signed in. Opening the chat connection to ${state.baseUrl}…", color = Muted, fontSize = 13.sp)
        } else if (state.baseUrl.isNotBlank()) {
            Text("Connected probe saved for ${state.baseUrl}. Sign in to open chats.", color = Muted, fontSize = 13.sp)
        }
        if (state.notice.isNotBlank()) Text(state.notice, color = Danger)
        Text("Keep the computer's gateway up", color = Ink, fontWeight = FontWeight.SemiBold)
        GatewaySetup()
    }
}

@Composable
private fun GatewaySetup() {
    var segment by rememberSaveable { mutableStateOf("Linux") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf("") }
    var custom by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val needsSignIn = segment != "Logout only"
    val ready = !needsSignIn || (username.isNotBlank() && password.isNotBlank() && secret.isNotBlank())
    val generated = SetupCopy.script(segment, username, password, secret)
    val current = if (custom && saved.isNotBlank()) saved else generated
    LaunchedEffect(segment, username, password, secret) {
        custom = false
        if (editing) draft = generated
    }
    val note = when (segment) {
        "macOS" -> SetupCopy.MAC_NOTE
        "Logout only" -> SetupCopy.TMUX_NOTE
        else -> SetupCopy.LINUX_NOTE
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Linux", "macOS", "Logout only").forEach { label ->
            TextButton(onClick = {
                segment = label
                editing = false
            }) {
                Text(label, color = if (segment == label) Accent else Muted)
            }
        }
    }
    if (needsSignIn) {
        Text("Paste the sign-in values. On the computer, create the secret with openssl rand -base64 32.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(
            username,
            { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Username") },
            singleLine = true,
            colors = fieldColors(),
        )
        OutlinedTextField(
            password,
            { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fieldColors(),
        )
        OutlinedTextField(
            secret,
            { secret = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Secret") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fieldColors(),
        )
    }
    if (!ready) {
        Text("Username, password, and secret are all required before the setup can be copied.", color = Muted, fontSize = 13.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { copy(context, current) }, enabled = ready && !editing) {
            Text("Copy", color = if (ready && !editing) Accent else Muted)
        }
        if (!editing) {
            TextButton(
                onClick = {
                    draft = current
                    editing = true
                },
                enabled = ready,
            ) { Text("Edit", color = if (ready) Accent else Muted) }
        }
    }
    if (editing) {
        OutlinedTextField(
            draft,
            { draft = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
            label = { Text("Setup") },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Ink,
            ),
            colors = fieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    saved = draft
                    custom = true
                    copy(context, draft)
                    editing = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Field),
            ) { Text("Save & copy") }
            TextButton(onClick = { editing = false }) { Text("Close", color = Muted) }
        }
    }
    Text(note, color = Muted, fontSize = 13.sp)
    Text(SetupCopy.WINDOWS, color = Muted, fontSize = 13.sp)
}

@Composable
private fun ChatsPane(state: UiState, model: HermesModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Button(
                onClick = { creating = true },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Field),
            ) { Text("New room") }
        }
        item { Section("Bots") }
        if (state.profiles.isEmpty()) item { Text("No bots yet.", color = Muted, modifier = Modifier.padding(vertical = 8.dp)) }
        items(state.profiles.distinctBy { it.name }, key = { "b-" + it.name }) { bot ->
            RowButton(bot.name, bot.preview.ifBlank { bot.model }) { model.openBot(bot.name) }
        }
        item { Section("Rooms") }
        if (state.rooms.isEmpty()) item { Text("No group rooms yet.", color = Muted, modifier = Modifier.padding(vertical = 8.dp)) }
        items(state.rooms.distinctBy { it.id }, key = { "r-" + it.id }) { room ->
            RowButton(room.name, "${room.members} bots") { model.openRoom(room.id) }
        }
        if (state.mirror.isNotEmpty()) item { Section("Desktop rooms, read only") }
        items(state.mirror.distinctBy { it.id }, key = { "m-" + it.id }) { room ->
            RowButton(room.name, "Saved on the desktop") { model.openMirror(room) }
        }
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = Elevated,
            title = { Text("New room", color = Ink) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, colors = fieldColors())
                    Text("Pick 2 to 6 bots. ${state.rooms.size} rooms so far.", color = Muted, fontSize = 12.sp)
                    state.profiles.forEach { bot ->
                        val on = bot.name in picked
                        TextButton(onClick = {
                            picked = if (on) picked - bot.name else if (picked.size < 6) picked + bot.name else picked
                        }) { Text(if (on) "✓ ${bot.name}" else bot.name, color = if (on) Accent else Ink) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { model.createRoom(name, picked.toList()); creating = false }) { Text("Create", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel", color = Muted) } },
        )
    }
}

@Composable
private fun BotsPane(state: UiState, model: HermesModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var soul by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = { creating = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Field)) {
                Text("New bot")
            }
        }
        items(state.profiles.distinctBy { it.name }, key = { it.name }) { bot ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(bot.name, color = Ink, fontWeight = FontWeight.SemiBold)
                if (bot.model.isNotBlank()) Text(bot.model, color = Muted, fontSize = 13.sp)
                Row {
                    TextButton(onClick = { model.openBot(bot.name) }) { Text("Chat", color = Accent) }
                    TextButton(onClick = { model.openMcp(bot.name) }) { Text("MCP", color = Accent) }
                    if (!bot.isDefault) TextButton(onClick = { confirmDelete = bot.name }) { Text("Delete", color = Danger) }
                }
            }
        }
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = Elevated,
            title = { Text("New bot", color = Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = fieldColors())
                    OutlinedTextField(soul, { soul = it.replace("\n", " ") }, label = { Text("One line of soul") }, colors = fieldColors())
                    Text("${state.profiles.size} bots. The 21st create is refused.", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { model.createBot(name, soul); creating = false; name = ""; soul = "" }) { Text("Create", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel", color = Muted) } },
        )
    }
    if (confirmDelete.isNotBlank()) {
        val target = confirmDelete
        AlertDialog(
            onDismissRequest = { confirmDelete = "" },
            containerColor = Elevated,
            title = { Text("Delete $target?", color = Ink) },
            text = { Text("This removes the bot from the computer.", color = Muted) },
            confirmButton = { TextButton(onClick = { model.deleteBot(target); confirmDelete = "" }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = "" }) { Text("Cancel", color = Muted) } },
        )
    }
}

@Composable
private fun ChatPane(state: UiState, model: HermesModel) {
    val chat = state.chat ?: return
    var text by rememberSaveable(chat.profile) { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.attach(uri)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Text(chat.profile, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat.lines.size) { index ->
                val line = chat.lines[index]
                Text(line.text, color = if (line.role == "user") Accent else Ink)
                if (line.role == "tool") Text(line.role, color = Muted, fontSize = 11.sp)
            }
            if (chat.draft.isNotBlank()) item { Text(chat.draft, color = Ink) }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text,
                { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                colors = fieldColors(),
            )
            TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("File", color = Accent) }
            if (chat.streaming) {
                TextButton(onClick = model::stopChat) { Text("Stop", color = Danger) }
            } else {
                TextButton(onClick = { model.sendChat(text); text = "" }) { Text("Send", color = Accent) }
            }
        }
    }
}

@Composable
private fun RoomPane(state: UiState, model: HermesModel) {
    val room = state.room ?: return
    var text by rememberSaveable(room.id) { mutableStateOf("") }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(room.name) }
    var disband by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(room.members.map { it.profile }.toSet()) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Text(room.name, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Text("An empty @ addresses everyone.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = model::stopRoom) { Text("Stop", color = Accent) }
            TextButton(onClick = { renaming = true }) { Text("Rename", color = Accent) }
            if (state.membersEditable) TextButton(onClick = { editing = true }) { Text("Members", color = Accent) }
            TextButton(onClick = { disband = true }) { Text("Disband", color = Danger) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(room.events.distinctBy { it.key.ifBlank { "s${it.seq}-${it.who}" } }, key = { it.key.ifBlank { "s${it.seq}-${it.who}" } }) { event ->
                Text(event.who, color = Muted, fontSize = 12.sp)
                Text(event.text, color = if (event.who == "you") Accent else Ink)
            }
            if (room.live.isNotBlank()) item { Text(room.live, color = Ink) }
            items(room.pending.distinctBy { it.requestId.ifBlank { it.taskId } }, key = { it.requestId.ifBlank { it.taskId } }) { action ->
                PendingRow(action, model)
            }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            room.members.forEach { member ->
                TextButton(onClick = { text += "@${member.handle} " }) { Text("@${member.handle}", color = Accent) }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message the room") }, colors = fieldColors())
            TextButton(onClick = { model.sendRoom(text); text = "" }) { Text("Send", color = Accent) }
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = Elevated,
            title = { Text("Rename", color = Ink) },
            text = { OutlinedTextField(name, { name = it }, colors = fieldColors()) },
            confirmButton = { TextButton(onClick = { model.renameRoom(name); renaming = false }) { Text("Save", color = Accent) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = Muted) } },
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

@Composable
private fun PendingRow(action: PendingAction, model: HermesModel) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(action.label, color = Ink)
        Row {
            if (action.kind == "retry") {
                TextButton(onClick = { model.retryRoom(action) }) { Text("Retry", color = Accent) }
            }
            action.choices.forEach { choice ->
                TextButton(onClick = { model.approveRoom(action, choice) }) {
                    Text(choice, color = if (choice == "deny") Danger else Accent)
                }
            }
        }
    }
}

@Composable
private fun MirrorPane(state: UiState) {
    val room = state.mirrorOpen ?: return
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(room.name, color = Ink, fontWeight = FontWeight.SemiBold) }
        item { Text("Read only. This is the desktop's saved copy, not the live room.", color = Muted) }
        if (room.omitted > 0) item { Text("${room.omitted} lines omitted on the desktop.", color = Muted) }
        items(room.lines) { line -> Text(line, color = Ink) }
    }
}

@Composable
private fun McpPane(state: UiState, model: HermesModel) {
    val mcp = state.mcp ?: return
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }
    var args by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var keyFor by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var env by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("MCP · ${mcp.profile}", color = Ink, fontWeight = FontWeight.SemiBold) }
        items(mcp.servers.distinctBy { it.name }, key = { it.name }) { server -> McpRow(server, model, context) { keyFor = it } }
        item { Text("Catalog", color = Muted) }
        items(mcp.catalog.distinctBy { it.first }, key = { it.first }) { (preset, description) ->
            Column {
                Text(preset, color = Ink)
                if (description.isNotBlank()) Text(description, color = Muted, fontSize = 13.sp)
                TextButton(onClick = { confirm = preset to description }) { Text("Add", color = Accent) }
            }
        }
        item { TextButton(onClick = { custom = true }) { Text("Add by command or URL", color = Accent) } }
    }
    confirm?.let { (preset, description) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Elevated,
            title = { Text("Add $preset?", color = Ink) },
            text = { Text(description.ifBlank { "This server runs on the computer." }, color = Muted) },
            confirmButton = { TextButton(onClick = { model.addMcp(preset, preset, "", "", ""); confirm = null }) { Text("Add", color = Accent) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = Muted) } },
        )
    }
    if (custom) {
        AlertDialog(
            onDismissRequest = { custom = false },
            containerColor = Elevated,
            title = { Text("Add server", color = Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, colors = fieldColors())
                    OutlinedTextField(command, { command = it }, label = { Text("Command") }, colors = fieldColors())
                    OutlinedTextField(args, { args = it }, label = { Text("Args") }, colors = fieldColors())
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, colors = fieldColors())
                    Text("The computer will run this command or connect to this URL.", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { model.addMcp(name, null, command, args, url); custom = false }) { Text("Add", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel", color = Muted) } },
        )
    }
    if (keyFor.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { keyFor = "" },
            containerColor = Elevated,
            title = { Text("API key", color = Ink) },
            text = {
                Column {
                    OutlinedTextField(env, { env = it }, label = { Text("Env var") }, colors = fieldColors())
                    OutlinedTextField(
                        key,
                        { key = it },
                        label = { Text("Value") },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { model.setMcpKey(keyFor, key, env); key = ""; keyFor = "" }) { Text("Save", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { keyFor = "" }) { Text("Cancel", color = Muted) } },
        )
    }
}

@Composable
private fun McpRow(server: McpServer, model: HermesModel, context: Context, onKey: (String) -> Unit) {
    Column {
        Text(server.name, color = Ink)
        if (server.detail.isNotBlank()) Text(server.detail, color = Muted, fontSize = 12.sp)
        if (server.status.isNotBlank()) Text(server.status, color = Muted, fontSize = 12.sp)
        Row {
            if (server.source == "plugin") {
                Text("Plugin", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            } else {
                TextButton(onClick = { model.setMcpEnabled(server.name, !server.enabled) }) {
                    Text(if (server.enabled) "Disable" else "Enable", color = Accent)
                }
            }
            TextButton(onClick = { model.testMcp(server.name) }) { Text("Test", color = Accent) }
            TextButton(onClick = { model.startOauth(server.name, context) }) { Text("OAuth", color = Accent) }
            TextButton(onClick = { onKey(server.name) }) { Text("Key", color = Accent) }
            TextButton(onClick = { model.removeMcp(server.name) }) { Text("Remove", color = Danger) }
        }
    }
}

@Composable
private fun ScreenPane(state: UiState, model: HermesModel) {
    val context = LocalContext.current
    var profile by rememberSaveable { mutableStateOf(state.profiles.firstOrNull()?.name ?: "default") }
    val metered = remember {
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
    }
    DisposableEffect(profile, metered) {
        model.showScreen(profile, metered)
        onDispose { model.hideScreen() }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.profiles.forEach { bot ->
                TextButton(onClick = { profile = bot.name }) {
                    Text(bot.name, color = if (profile == bot.name) Accent else Muted)
                }
            }
        }
        val screen = state.screen
        if (screen.message.isNotBlank()) Text(screen.message, color = Muted)
        if (screen.blocker.isNotBlank()) Text(screen.blocker, color = Muted)
        if (screen.holder.isNotBlank()) Text("Held by ${screen.holder}", color = Muted, fontSize = 12.sp)
        if (screen.checks.isNotBlank()) Text(screen.checks, color = Ink, fontSize = 13.sp)
        Row {
            if (screen.supported && !screen.running) {
                TextButton(onClick = model::startDesktop) { Text("Start", color = Accent) }
            }
            if (screen.supported && !screen.installed) {
                TextButton(onClick = model::installDesktop) { Text("Install", color = Accent) }
            }
            if (screen.pageUrl.isNotBlank() && !screen.controlling) {
                TextButton(onClick = model::takeOver) { Text("Take over", color = Accent) }
            }
            if (screen.controlling) TextButton(onClick = model::handBack) { Text("Hand back", color = Accent) }
            if (!screen.supported) TextButton(onClick = model::grantComputerUse) { Text("Grant access", color = Accent) }
        }
        if (screen.pageUrl.isNotBlank()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        setBackgroundColor(0xFF0D1117.toInt())
                    }
                },
                update = { view ->
                    if (view.tag != screen.pageUrl) {
                        view.tag = screen.pageUrl
                        view.loadUrl(screen.pageUrl)
                    }
                    view.evaluateJavascript("if (window.jrSetControl) window.jrSetControl(${screen.controlling});", null)
                },
            )
        } else if (screen.thumbnail.startsWith("data:")) {
            val bitmap = remember(screen.thumbnail) {
                val raw = screen.thumbnail.substringAfter(",", "")
                val bytes = Base64.decode(raw, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
            if (bitmap != null) {
                Image(bitmap, contentDescription = "Host desktop", modifier = Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun MorePane(state: UiState, model: HermesModel) {
    var url by rememberSaveable { mutableStateOf(state.baseUrl) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(state.baseUrl, color = Ink)
        OutlinedTextField(url, { url = it }, label = { Text("Computer") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        Button(onClick = { model.signOut(); model.useHost(url) }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Field)) {
            Text("Use this computer")
        }
        TextButton(onClick = model::signOut) { Text("Sign out", color = Danger) }
        Text("Keep the computer's gateway up", color = Ink, fontWeight = FontWeight.SemiBold)
        GatewaySetup()
        Text("Lost phone", color = Ink, fontWeight = FontWeight.SemiBold)
        Text(SetupCopy.LOST_PHONE, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun CardHost(card: Card, model: HermesModel) {
    var secret by remember(card.id) { mutableStateOf("") }
    var identifier by remember(card.id) { mutableStateOf("") }
    var confirmAlways by remember(card.id) { mutableStateOf(false) }
    val answers = remember(card.id) { mutableStateOf(card.questions.associate { it.id to "" }) }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(card.title, color = Accent, fontWeight = FontWeight.SemiBold)
        if (card.body.isNotBlank()) Text(card.body, color = Ink)
        Text("The host gives up after about five minutes.", color = Muted, fontSize = 12.sp)
        card.questions.forEach { question ->
            Text(question.prompt, color = Ink)
            if (question.choices.isEmpty()) {
                OutlinedTextField(
                    answers.value[question.id].orEmpty(),
                    { answers.value = answers.value + (question.id to it) },
                    colors = fieldColors(),
                )
            } else {
                Row {
                    question.choices.forEach { choice ->
                        val selected = answers.value[question.id].orEmpty().split("||").contains(choice)
                        TextButton(onClick = {
                            val next = if (!question.multi) choice else {
                                val set = answers.value[question.id].orEmpty().split("||").filter { it.isNotBlank() }.toMutableSet()
                                if (choice in set) set.remove(choice) else set.add(choice)
                                set.joinToString("||")
                            }
                            answers.value = answers.value + (question.id to next)
                        }) { Text(if (selected) "✓ $choice" else choice, color = Accent) }
                    }
                }
            }
        }
        if (card.saveLogin) {
            OutlinedTextField(identifier, { identifier = it }, label = { Text("Identifier") }, colors = fieldColors())
            OutlinedTextField(secret, { secret = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
        } else if (card.secret) {
            OutlinedTextField(
                secret,
                { secret = it },
                label = { Text("Value") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = fieldColors(),
            )
        }
        Row {
            if (card.questions.isNotEmpty()) {
                TextButton(onClick = {
                    val body = JSONObject()
                    answers.value.forEach { (id, value) -> body.put(id, value.replace("||", ", ")) }
                    model.answerCard(card.id, JSONObject().put("answers", body))
                }) { Text("Send", color = Accent) }
            }
            card.choices.forEach { choice ->
                TextButton(onClick = {
                    if (choice == "always" && !confirmAlways) {
                        confirmAlways = true
                    } else {
                        model.answerCard(card.id, JSONObject().put("choice", choice))
                    }
                }) { Text(if (choice == "always" && confirmAlways) "Confirm always" else choice, color = if (choice == "deny") Danger else Accent) }
            }
            if (card.secret && !card.saveLogin) {
                TextButton(onClick = { model.answerCard(card.id, JSONObject().put("value", secret)) }) { Text("Send", color = Accent) }
                TextButton(onClick = { model.answerCard(card.id, JSONObject().put("value", "")) }) { Text("Skip", color = Muted) }
            }
            if (card.saveLogin) {
                TextButton(onClick = {
                    val packed = JSONObject().put("identifier", identifier).put("password", secret).toString()
                    model.answerCard(card.id, JSONObject().put("value", packed))
                }) { Text("Save", color = Accent) }
                TextButton(onClick = { model.answerCard(card.id, JSONObject().put("value", "")) }) { Text("Skip", color = Muted) }
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = Ink, fontWeight = FontWeight.SemiBold)) { append("Hermes") }
            append(" ")
            withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.SemiBold)) { append("Jr.") }
        },
        fontSize = 22.sp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun Banner(text: String, onDismiss: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Ink, modifier = Modifier.weight(1f), fontSize = 14.sp)
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Hide", color = Muted) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Muted, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun RowButton(title: String, subtitle: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, color = Ink)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Muted,
    cursorColor = Accent,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Muted,
)

private fun Destination.icon() = when (this) {
    Destination.Chats -> Icons.Outlined.ChatBubbleOutline
    Destination.Bots -> Icons.Outlined.SmartToy
    Destination.Screen -> Icons.Outlined.DesktopWindows
    Destination.More -> Icons.Outlined.MoreHoriz
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("hermes", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
