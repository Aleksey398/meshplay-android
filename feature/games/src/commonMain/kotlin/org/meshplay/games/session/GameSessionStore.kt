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

    val sessions: StateFlow<List<GameSession>> =
        dataStore.data
            .map { prefs -> decode(prefs[KEY]) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Sessions for one conversation. */
    fun forContact(contactKey: String): Flow<List<GameSession>> = sessions.map { all -> all.filter { it.contactKey == contactKey } }

    suspend fun get(sessionId: Int, contactKey: String): GameSession? =
        sessions.first().firstOrNull { it.sessionId == sessionId && it.contactKey == contactKey }

    /** Inserts or replaces the session with the same id in the same conversation. */
    suspend fun upsert(session: GameSession) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY]).filterNot {
                it.sessionId == session.sessionId && it.contactKey == session.contactKey
            }
            val next = (current + session).sortedByDescending { it.updatedAt }
            prefs[KEY] = json.encodeToString(serializer, next)
        }
    }

    suspend fun remove(sessionId: Int, contactKey: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val next = current.filterNot { it.sessionId == sessionId && it.contactKey == contactKey }
            prefs[KEY] = json.encodeToString(serializer, next)
        }
    }

    private fun decode(raw: String?): List<GameSession> =
        if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString(serializer, raw) }
                .onFailure { Logger.w { "Dropping unreadable game session store" } }
                .getOrDefault(emptyList())
        }

    companion object {
        private val KEY = stringPreferencesKey("meshplay_game_sessions_v1")
    }
}
