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

    suspend fun wipeSession() {
        store.edit {
            val url = it[URL]
            it.clear()
            if (url != null) it[URL] = url
        }
    }

    private companion object {
        val URL = stringPreferencesKey("base_url")
        val INSTALL = stringPreferencesKey("install_id")
        val CURSORS = stringPreferencesKey("room_cursors")
        val TRANSCRIPT = stringPreferencesKey("transcript")
        val ROOMS = stringPreferencesKey("room_events")
        val ROUNDS = stringPreferencesKey("room_rounds")
    }
}
