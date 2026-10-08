package com.nousresearch.hermes.jr.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.jrStore by preferencesDataStore(name = "hermes-jr")

class JrStore(context: Context) {
    private val store = context.applicationContext.jrStore

    data class Saved(
        val baseUrl: String = "",
        val installId: String = "",
        val cursors: String = "{}",
        val transcript: String = "",
        val roomEvents: String = "",
        /** JSON object room_id -> max bot rounds (this phone's bot-to-bot follow-up budget). */
        val roomRounds: String = "{}",
        /**
         * Where the computer serves the noVNC viewer files (`websockify --web=/usr/share/novnc`).
         * Blank means the default: the gateway's host on port 6080.
         */
        val novncUrl: String = "",
        /** When true, 1:1 and room chats use the OpenUI WebView (phases 0–3). Native stays as fallback. */
        val openUiChat: Boolean = true,
        /** Default for the Rich UI composer toggle in 1:1 chats. Rooms always start off. */
        val openUiRichDefault: Boolean = false,
    )

    suspend fun read(): Saved {
        val prefs = store.data.first()
        return Saved(
            baseUrl = prefs[URL].orEmpty(),
            installId = prefs[INSTALL].orEmpty(),
            cursors = prefs[CURSORS] ?: "{}",
            transcript = prefs[TRANSCRIPT].orEmpty(),
            roomEvents = prefs[ROOMS].orEmpty(),
            roomRounds = prefs[ROUNDS] ?: "{}",
            novncUrl = prefs[NOVNC].orEmpty(),
            openUiChat = prefs[OPENUI] != "0",
            openUiRichDefault = prefs[RICH] == "1",
        )
    }

    suspend fun saveUrl(url: String) {
        store.edit { it[URL] = url }
    }

    suspend fun saveInstall(id: String) {
        store.edit { it[INSTALL] = id }
    }

    suspend fun saveCaches(cursors: String, transcript: String, roomEvents: String) {
        store.edit {
            it[CURSORS] = cursors
            it[TRANSCRIPT] = transcript.take(180_000)
            it[ROOMS] = roomEvents.take(180_000)
        }
    }

    suspend fun saveRoomRounds(json: String) {
        store.edit { it[ROUNDS] = json }
    }

    suspend fun saveNovncUrl(url: String) {
        store.edit { it[NOVNC] = url }
    }

    suspend fun saveOpenUiChat(enabled: Boolean) {
        store.edit { it[OPENUI] = if (enabled) "1" else "0" }
    }

    suspend fun saveOpenUiRichDefault(enabled: Boolean) {
        store.edit { it[RICH] = if (enabled) "1" else "0" }
    }

    /** Clears the session but keeps the device settings: the computer address and its noVNC viewer. */
    suspend fun wipeSession() {
        store.edit {
            val url = it[URL]
            val novnc = it[NOVNC]
            val openUi = it[OPENUI]
            val rich = it[RICH]
            it.clear()
            if (url != null) it[URL] = url
            if (novnc != null) it[NOVNC] = novnc
            if (openUi != null) it[OPENUI] = openUi
            if (rich != null) it[RICH] = rich
        }
    }

    private companion object {
        val URL = stringPreferencesKey("base_url")
        val INSTALL = stringPreferencesKey("install_id")
        val CURSORS = stringPreferencesKey("room_cursors")
        val TRANSCRIPT = stringPreferencesKey("transcript")
        val ROOMS = stringPreferencesKey("room_events")
        val ROUNDS = stringPreferencesKey("room_rounds")
        val NOVNC = stringPreferencesKey("novnc_url")
        val OPENUI = stringPreferencesKey("openui_chat")
        val RICH = stringPreferencesKey("openui_rich_default")
    }
}
