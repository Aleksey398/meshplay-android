/*
 * Copyright (c) 2026 MeshPlay contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshplay.games.session

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.prefs.di.AppDataStore

/**
 * Persists [GameSession] rows in the app preferences DataStore as a JSON list. Sessions are small
 * (a FEN plus a move list) and always rewritten whole, which keeps the store trivial and free of
 * schema migrations. Replacing this with Room tables is a documented follow-up.
 */
@Single
class GameSessionStore(private val dataStore: AppDataStore, dispatchers: CoroutineDispatchers) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(GameSession.serializer())
    private val dismissedSerializer = ListSerializer(DismissedSession.serializer())

    val sessions: StateFlow<List<GameSession>> =
        dataStore.data
            .map { prefs -> decode(prefs[KEY]) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Sessions for one conversation. */
    fun forContact(contactKey: String): Flow<List<GameSession>> = sessions.map { all -> all.filter { it.contactKey == contactKey } }

    suspend fun get(sessionId: Int, contactKey: String): GameSession? =
        sessions.first().firstOrNull { it.sessionId == sessionId && it.contactKey == contactKey }

    /** True after the player dismissed this session, including across app restarts. */
    suspend fun isDismissed(sessionId: Int, contactKey: String): Boolean {
        val key = DismissedSession(sessionId, contactKey)
        return decodeDismissed(dataStore.data.first()[DISMISSED_KEY]).contains(key)
    }

    /** Inserts or replaces the session with the same id in the same conversation. */
    suspend fun upsert(session: GameSession) {
        dataStore.edit { prefs ->
            // Keep a dismissal authoritative even if an in-flight replay read this session before
            // the user removed it. Checking and writing in the same edit makes that race atomic.
            if (isDismissed(prefs, session.sessionId, session.contactKey)) return@edit
            val current = decode(prefs[KEY]).filterNot {
                it.sessionId == session.sessionId && it.contactKey == session.contactKey
            }
            val next = (current + session).sortedByDescending { it.updatedAt }
            prefs[KEY] = json.encodeToString(serializer, next)
        }
    }

    /**
     * Removes a session and records a tombstone. The chat history remains in the message database,
     * so without the tombstone every feed refresh would fold the old invitation/results back in.
     */
    suspend fun remove(sessionId: Int, contactKey: String) {
        val dismissed = DismissedSession(sessionId, contactKey)
        dataStore.edit { prefs ->
            val next =
                decode(prefs[KEY]).filterNot {
                    it.sessionId == sessionId && it.contactKey == contactKey
                }
            prefs[KEY] = json.encodeToString(serializer, next)

            val dismissedSessions = decodeDismissed(prefs[DISMISSED_KEY]).toMutableSet()
            dismissedSessions.add(dismissed)
            prefs[DISMISSED_KEY] = json.encodeToString(dismissedSerializer, dismissedSessions.toList())
        }
    }

    private fun isDismissed(prefs: Preferences, sessionId: Int, contactKey: String): Boolean =
        DismissedSession(sessionId, contactKey) in decodeDismissed(prefs[DISMISSED_KEY])

    private fun decode(raw: String?): List<GameSession> =
        if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString(serializer, raw) }
                .onFailure { Logger.w { "Dropping unreadable game session store" } }
                .getOrDefault(emptyList())
        }

    private fun decodeDismissed(raw: String?): List<DismissedSession> =
        if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString(dismissedSerializer, raw) }
                .onFailure { Logger.w { "Dropping unreadable dismissed game sessions" } }
                .getOrDefault(emptyList())
        }

    companion object {
        private val KEY = stringPreferencesKey("meshplay_game_sessions_v1")
        private val DISMISSED_KEY = stringPreferencesKey("meshplay_game_sessions_dismissed_v1")
    }
}

@Serializable
private data class DismissedSession(val sessionId: Int, val contactKey: String)
