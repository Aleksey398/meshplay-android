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
import kotlinx.coroutines.flow.combine
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
import org.meshplay.games.engine.ChessPosition
import org.meshplay.games.engine.GameEngine
import org.meshplay.games.session.GameSession
import org.meshplay.games.session.GameSessionIngestor
import org.meshplay.games.session.GameSessionStore
import org.meshplay.games.session.SessionStatus
import org.meshplay.protocol.GameKind
import org.meshplay.protocol.GameMessage
import org.meshplay.protocol.GameMessageCodec
import org.meshplay.protocol.GameRequest
import org.meshplay.protocol.GameResult

/** A pawn (or man) that reached the last rank and needs the player to pick a promotion piece. */
data class PendingPromotion(val from: String, val to: String)

/** Immutable view of one open game, ready for the board UI. */
data class GameUiState(
    val session: GameSession,
    val engine: GameEngine?,
    val myTurn: Boolean,
    val legalMoves: List<String>,
    val pendingPromotion: PendingPromotion? = null,
)

/**
 * ViewModel for a single game table: ingests tagged chat traffic for the session, exposes the
 * board state, and sends moves/requests through the ordinary text send path.
 */
@KoinViewModel
class GameViewModel(
    private val store: GameSessionStore,
    packetRepository: PacketRepository,
    private val nodeRepository: NodeRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val ingestor = GameSessionIngestor(store)
    private val contactKeyFlow = MutableStateFlow<String?>(null)
    private val sessionIdFlow = MutableStateFlow(-1)
    private val pendingPromotionFlow = MutableStateFlow<PendingPromotion?>(null)

    /** Set by the navigation entry before the first frame. */
    fun setSession(contactKey: String, sessionId: Int) {
        val firstAttach = contactKeyFlow.value == null
        contactKeyFlow.update { contactKey }
        sessionIdFlow.update { sessionId }
        if (firstAttach) {
            viewModelScope.launch { sendResyncSnapshot() }
        }
    }

    val uiState: StateFlow<GameUiState?> =
        combine(
            store.sessions,
            sessionIdFlow,
            pendingPromotionFlow,
        ) { sessions, sessionId, pending ->
            val session = sessions.firstOrNull { it.sessionId == sessionId && it.contactKey == contactKeyFlow.value }
            if (session == null) {
                null
            } else {
                val engine = session.state.ifEmpty { null }?.let { GameEngine.restore(session.kind, it) }
                val myTurn = engine != null && session.status == SessionStatus.ACTIVE &&
                    engine.sideToMove == session.mySide
                GameUiState(
                    session = session,
                    engine = engine,
                    myTurn = myTurn,
                    legalMoves = if (myTurn) engine!!.legalMoves() else emptyList(),
                    pendingPromotion = pending,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val messageFeed: Flow<List<Message>> =
        contactKeyFlow.flatMapLatest { key ->
            if (key == null) flowOf(emptyList()) else packetRepository.getMessagesFrom(key, getNode = ::getNode)
        }

    init {
        viewModelScope.launch(dispatchers.default) {
            messageFeed.collect { messages -> ingestGameMessages(messages) }
        }
    }

    private suspend fun ingestGameMessages(messages: List<Message>) {
        val contactKey = contactKeyFlow.value ?: return
        val sessionId = sessionIdFlow.value
        for (message in messages) {
            if (!ingestor.isProtocolText(message.text)) continue
            val decoded = GameMessageCodec.decode(message.text) ?: continue
            if (decoded.sessionId != sessionId) continue
            val updated =
                ingestor.ingest(
                    contactKey,
                    GameSessionIngestor.IngestedText(message.text, message.fromLocal, message.displayTime),
                )
            if (updated != null) {
                pendingPromotionFlow.update { current ->
                    if (updated.status != SessionStatus.ACTIVE) null else current
                }
            }
        }
    }

    /** On open, publish our snapshot: a newer one from the peer overrides it, an older one theirs. */
    private suspend fun sendResyncSnapshot() {
        val contactKey = contactKeyFlow.value ?: return
        val sessionId = sessionIdFlow.value
        val session = store.get(sessionId, contactKey) ?: return
        if (session.state.isEmpty() || session.status != SessionStatus.ACTIVE) return
        val encoded =
            GameMessageCodec.encode(GameMessage.Snapshot(session.kind, session.sessionId, session.seq, session.state))
        sendMessageUseCase(encoded, contactKey)
    }

    /** Handles a board tap: selects a piece or moves to [move] when it is legal. */
    fun onMoveTried(move: String) {
        val state = uiState.value ?: return
        if (!state.myTurn || state.session.status != SessionStatus.ACTIVE) return
        if (state.session.kind == GameKind.CHESS && isPromotionMove(state, move)) {
            pendingPromotionFlow.update { PendingPromotion(move.take(2), move.substring(2, 4)) }
            return
        }
        sendMove(move)
    }

    fun onPromotionChosen(piece: Char) {
        val pending = pendingPromotionFlow.value ?: return
        pendingPromotionFlow.value = null
        sendMove(pending.from + pending.to + piece)
    }

    fun onPromotionDismissed() {
        pendingPromotionFlow.value = null
    }

    private fun isPromotionMove(state: GameUiState, move: String): Boolean {
        if (move.length != 4) return false
        val engine = state.engine as? ChessEngine ?: return false
        val fromSquare = ChessPosition.squareFromName(move.substring(0, 2)) ?: return false
        val piece = engine.position.board[fromSquare]
        val isPawn = piece == ChessPosition.WHITE_PAWN || piece == ChessPosition.BLACK_PAWN
        val toRank = move[3].digitToIntOrNull() ?: return false
        return isPawn && (toRank == 1 || toRank == 8)
    }

    fun resign() {
        sendRequest(GameRequest.RESIGN, applyLocally = true)
    }

    /** Accepts an invitation opened straight from the game table. */
    fun acceptInvite() {
        val session = uiState.value?.session ?: return
        if (contactKeyFlow.value == null) return
        val initial = GameEngine.initial(session.kind)
        val encoded = GameMessageCodec.encode(GameMessage.Snapshot(session.kind, session.sessionId, 0, initial.state))
        sendRaw(encoded)
    }

    /** Declines an invitation: the session disappears locally, nothing goes on the wire. */
    fun declineInvite() {
        val session = uiState.value?.session ?: return
        val contactKey = contactKeyFlow.value ?: return
        viewModelScope.launch { store.remove(session.sessionId, contactKey) }
    }

    /** Declines a draw offer: the flag is cleared locally; the peer simply sees no reply. */
    fun declineDraw() {
        val session = uiState.value?.session ?: return
        viewModelScope.launch {
            store.upsert(session.copy(drawOfferFromPeer = false, updatedAt = nowMillis()))
        }
    }

    fun offerDraw() {
        sendRequest(GameRequest.DRAW_OFFER, applyLocally = false)
    }

    fun acceptDraw() {
        val session = uiState.value?.session ?: return
        sendRaw(GameMessageCodec.encode(GameMessage.Result(session.kind, session.sessionId, GameResult.DRAW)))
    }

    private fun sendMove(move: String) {
        val session = uiState.value?.session ?: return
        val seq = session.seq + 1
        val encoded = GameMessageCodec.encode(GameMessage.Move(session.kind, session.sessionId, seq, move))
        sendRaw(encoded)
        // Periodic snapshot so a peer that dropped packets converges without waiting for a reopen.
        if (seq % SNAPSHOT_EVERY == 0) {
            val engine = session.state.ifEmpty { null }?.let { GameEngine.restore(session.kind, it) }
            if (engine != null) {
                val next = engine.apply(move)
                if (next.state != engine.state) {
                    val snapshot =
                        GameMessageCodec.encode(GameMessage.Snapshot(session.kind, session.sessionId, seq, next.state))
                    sendRaw(snapshot, applyLocally = false)
                }
            }
        }
    }

    private fun sendRequest(request: GameRequest, applyLocally: Boolean) {
        val session = uiState.value?.session ?: return
        sendRaw(GameMessageCodec.encode(GameMessage.Request(session.kind, session.sessionId, request)), applyLocally)
    }

    private fun sendRaw(encoded: String, applyLocally: Boolean = true) {
        val contactKey = contactKeyFlow.value ?: return
        viewModelScope.launch {
            sendMessageUseCase(encoded, contactKey)
            if (applyLocally) {
                ingestor.ingest(
                    contactKey,
                    GameSessionIngestor.IngestedText(encoded, fromLocal = true, time = nowMillis()),
                )
            }
        }
    }

    private fun getNode(userId: String?): Node = nodeRepository.getNode(userId ?: NodeAddress.ID_BROADCAST)

    companion object {
        /** After every N applied moves the sender also pushes a full snapshot. */
        private const val SNAPSHOT_EVERY = 5
    }
}
