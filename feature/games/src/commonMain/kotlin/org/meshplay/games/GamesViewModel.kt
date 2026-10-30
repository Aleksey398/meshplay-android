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
package org.meshplay.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshplay.games.engine.GameEngine
import org.meshplay.games.session.GameSession
import org.meshplay.games.session.GameSessionIngestor
import org.meshplay.games.session.GameSessionStore
import org.meshplay.games.session.SessionRole
import org.meshplay.games.session.SessionStatus
import org.meshplay.protocol.GameKind
import org.meshplay.protocol.GameMessage
import org.meshplay.protocol.GameMessageCodec
import org.meshplay.protocol.Side
import kotlin.random.Random

/**
 * ViewModel for the per-contact games hub: the session list plus the challenge flow. Incoming
 * chat messages tagged `[MG1|...]` are folded into sessions via [GameSessionIngestor].
 */
@KoinViewModel
class GamesViewModel(
    private val store: GameSessionStore,
    packetRepository: PacketRepository,
    private val nodeRepository: NodeRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val ingestor = GameSessionIngestor(store)
    private val contactKeyFlow = MutableStateFlow<String?>(null)

    /** Set by the navigation entry before the first frame. */
    fun setContactKey(contactKey: String) {
        contactKeyFlow.update { contactKey }
    }

    val sessions: StateFlow<List<GameSession>> =
        contactKeyFlow
            .flatMapLatest { key -> if (key == null) flowOf(emptyList()) else store.forContact(key) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messageFeed: Flow<List<Message>> =
        contactKeyFlow.flatMapLatest { key ->
            if (key == null) {
                flowOf(emptyList())
            } else {
                packetRepository.getMessagesFrom(key, getNode = ::getNode)
            }
        }

    init {
        viewModelScope.launch(dispatchers.default) {
            messageFeed.collect { messages -> ingestGameMessages(messages) }
        }
    }

    private suspend fun ingestGameMessages(messages: List<Message>) {
        val contactKey = contactKeyFlow.value ?: return
        for (message in messages) {
            if (!ingestor.isProtocolText(message.text)) continue
            ingestor.ingest(
                contactKey,
                GameSessionIngestor.IngestedText(
                    text = message.text,
                    fromLocal = message.fromLocal,
                    time = message.displayTime,
                ),
            )
        }
    }

    /**
     * Opens a new challenge: creates the local session and sends the `[MG1|...|N|...]` invite.
     * Returns the new session id, or null when the invite could not be queued.
     */
    fun startChallenge(kind: GameKind, mySide: Side): Int? {
        val contactKey = contactKeyFlow.value ?: return null
        val sessionId = Random.nextInt(0, MAX_SESSION_ID + 1)
        val session =
            GameSession(
                sessionId = sessionId,
                contactKey = contactKey,
                kind = kind,
                mySide = mySide,
                role = SessionRole.CHALLENGER,
                status = SessionStatus.PENDING,
                updatedAt = now(),
            )
        val encoded =
            GameMessageCodec.encode(
                GameMessage.NewGame(kind, sessionId, mySide),
            )
        viewModelScope.launch {
            store.upsert(session)
            sendMessageUseCase(encoded, contactKey)
        }
        return sessionId
    }

    /** Accepts an invitation: seeds the initial snapshot and queues it for the peer. */
    fun acceptChallenge(session: GameSession) {
        val contactKey = session.contactKey.ifEmpty { contactKeyFlow.value ?: return }
        val initial = GameEngine.initial(session.kind)
        val encoded =
            GameMessageCodec.encode(
                GameMessage.Snapshot(session.kind, session.sessionId, 0, initial.state),
            )
        viewModelScope.launch {
            sendMessageUseCase(encoded, contactKey)
            ingestor.ingest(
                contactKey,
                GameSessionIngestor.IngestedText(encoded, fromLocal = true, time = now()),
            )
        }
    }

    /** Ignores an invitation (no wire message; the peer simply never gets an acceptance). */
    fun declineChallenge(session: GameSession) {
        viewModelScope.launch { store.remove(session.sessionId, session.contactKey) }
    }

    /** Removes a finished or abandoned session locally. */
    fun forgetSession(session: GameSession) {
        viewModelScope.launch { store.remove(session.sessionId, session.contactKey) }
    }

    private fun getNode(userId: String?): Node = nodeRepository.getNode(userId ?: NodeAddress.ID_BROADCAST)

    private fun now(): Long = nowMillis()

    companion object {
        private const val MAX_SESSION_ID = 99_999
    }
}
