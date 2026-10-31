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

import org.meshplay.protocol.GameMessage
import org.meshplay.protocol.GameMessageCodec

/**
 * Folds a conversation's chat log into game sessions: decodes `[MG1|...]` texts and runs each
 * through the [GameSessionReducer], persisting every change. Re-ingesting the same log is a no-op
 * because every transition is idempotent.
 */
class GameSessionIngestor(private val store: GameSessionStore) {
    data class IngestedText(val text: String, val fromLocal: Boolean, val time: Long)

    /** Cheap pre-filter used by the UI to hide protocol chatter from the chat list. */
    fun isProtocolText(text: String): Boolean = GameMessageCodec.looksLikeGameMessage(text)

    /** Folds one chat item; returns the session touched by it (may be unchanged). */
    suspend fun ingest(contactKey: String, item: IngestedText): GameSession? {
        val message: GameMessage = GameMessageCodec.decode(item.text) ?: return null
        // Dismissed invitations/finished sessions must not be reconstructed by replaying the
        // retained chat log when its flow emits again (or after the app restarts).
        if (store.isDismissed(message.sessionId, contactKey)) return null
        val existing = store.get(message.sessionId, contactKey)
        val result =
            GameSessionReducer.reduce(
                session = existing,
                message = message,
                fromLocal = item.fromLocal,
                now = item.time,
            )
        val session = result.session ?: return null
        val stamped = if (session.contactKey.isEmpty()) session.copy(contactKey = contactKey) else session
        if (result.changed || result.created) {
            store.upsert(stamped)
        }
        return stamped
    }
}
