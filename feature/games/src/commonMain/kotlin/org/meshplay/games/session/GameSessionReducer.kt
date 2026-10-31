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

import co.touchlab.kermit.Logger
import org.meshplay.games.engine.GameEngine
import org.meshplay.protocol.GameMessage
import org.meshplay.protocol.GameRequest
import org.meshplay.protocol.GameResult

/**
 * Pure state machine for game sessions: folds decoded protocol messages (local echoes included)
 * into a [GameSession]. Returning null means "no change" — every transition is idempotent so the
 * chat history can be re-ingested at any time without corrupting a game.
 */
object GameSessionReducer {
    /**
     * Applies one message. [session] is the current session for the message's session id, or null
     * when none exists yet. [fromLocal] marks messages this device sent (they come back through
     * the persisted history).
     */
    fun reduce(session: GameSession?, message: GameMessage, fromLocal: Boolean, now: Long): ReduceResult {
        return when (message) {
            is GameMessage.NewGame -> reduceNewGame(session, message, fromLocal, now)
            is GameMessage.Snapshot -> reduceSnapshot(session, message, fromLocal, now)
            is GameMessage.Move -> reduceMove(session, message, fromLocal, now)
            is GameMessage.Request -> reduceRequest(session, message, fromLocal, now)
            is GameMessage.Result -> reduceResult(session, message, fromLocal, now)
        }
    }

    private fun reduceNewGame(
        session: GameSession?,
        message: GameMessage.NewGame,
        fromLocal: Boolean,
        now: Long,
    ): ReduceResult {
        if (fromLocal) {
            // The sender creates the session optimistically before the packet goes out; the echo
            // only refreshes the timestamp.
            return ReduceResult(session?.copy(updatedAt = now), false)
        }
        if (session != null) {
            return ReduceResult(session.copy(updatedAt = now), session.status == SessionStatus.FINISHED)
        }
        val invited =
            GameSession(
                sessionId = message.sessionId,
                contactKey = "",
                kind = message.kind,
                mySide = message.challengerSide.opponent,
                role = SessionRole.RESPONDER,
                status = SessionStatus.INVITED,
                updatedAt = now,
            )
        return ReduceResult(invited, created = true)
    }

    private fun reduceSnapshot(
        session: GameSession?,
        message: GameMessage.Snapshot,
        fromLocal: Boolean,
        now: Long,
    ): ReduceResult {
        if (session == null) return ReduceResult(null, false)
        // Finished games are terminal: a late or replayed snapshot must never resurrect the
        // session (the chat log is re-ingested newest-first on every emission, so an equal-seq
        // snapshot would otherwise flip a just-resigned game back to ACTIVE).
        if (session.isFinished()) return ReduceResult(null, false)
        if (message.seq < session.seq) return ReduceResult(null, false)
        val engine = GameEngine.restore(session.kind, message.state)
        if (engine == null) {
            Logger.w { "Ignoring malformed game snapshot for session ${message.sessionId}" }
            return ReduceResult(null, false)
        }
        val updated =
            session.copy(
                state = message.state,
                seq = message.seq,
                moves = session.moves.take(message.seq),
                status = SessionStatus.ACTIVE,
                drawOfferFromPeer = false,
                updatedAt = now,
            )
        // An accepted challenge flips both sides from waiting to active — persist that.
        return ReduceResult(updated, changed = updated != session, activated = true)
    }

    private fun reduceMove(
        session: GameSession?,
        message: GameMessage.Move,
        fromLocal: Boolean,
        now: Long,
    ): ReduceResult {
        if (session == null || session.status != SessionStatus.ACTIVE) return ReduceResult(null, false)
        if (message.seq <= session.seq) return ReduceResult(null, false)
        if (message.seq > session.seq + 1) {
            // Gap from packet loss — a later snapshot will re-sync us.
            return ReduceResult(null, false)
        }
        val engine = GameEngine.restore(session.kind, session.state) ?: return ReduceResult(null, false)
        val next = engine.apply(message.move)
        if (next.state == engine.state) {
            Logger.w { "Ignoring illegal game move ${message.move} in session ${message.sessionId}" }
            return ReduceResult(null, false)
        }
        val updated =
            session.copy(
                state = next.state,
                seq = message.seq,
                moves = session.moves + message.move,
                status = if (next.isOver) SessionStatus.FINISHED else SessionStatus.ACTIVE,
                result = next.result ?: session.result,
                drawOfferFromPeer = false,
                updatedAt = now,
        )
        return ReduceResult(updated, changed = true)
    }

    private fun reduceRequest(
        session: GameSession?,
        message: GameMessage.Request,
        fromLocal: Boolean,
        now: Long,
    ): ReduceResult {
        if (session == null || session.isFinished()) return ReduceResult(null, false)
        return when (message.request) {
            GameRequest.RESIGN -> {
                if (session.status != SessionStatus.ACTIVE) return ReduceResult(null, false)
                // Whoever sent the resign loses.
                val winner = if (fromLocal) session.peerSide else session.mySide
                val result = winnerResult(winner)
                ReduceResult(session.copy(status = SessionStatus.FINISHED, result = result, updatedAt = now), changed = true)
            }
            GameRequest.DRAW_OFFER -> {
                if (fromLocal || session.status != SessionStatus.ACTIVE) return ReduceResult(null, false)
                ReduceResult(session.copy(drawOfferFromPeer = true, updatedAt = now), changed = true)
            }
        }
    }

    private fun reduceResult(
        session: GameSession?,
        message: GameMessage.Result,
        fromLocal: Boolean,
        now: Long,
    ): ReduceResult {
        if (session == null) return ReduceResult(null, false)
        if (session.isFinished() && session.result == message.result) return ReduceResult(null, false)
        if (session.status != SessionStatus.ACTIVE && session.status != SessionStatus.PENDING) {
            return ReduceResult(null, false)
        }
        return ReduceResult(
            session.copy(status = SessionStatus.FINISHED, result = message.result, drawOfferFromPeer = false, updatedAt = now),
            changed = true,
        )
    }

    private fun winnerResult(winner: org.meshplay.protocol.Side): GameResult =
        when (winner) {
            org.meshplay.protocol.Side.WHITE -> GameResult.WHITE_WINS
            org.meshplay.protocol.Side.BLACK -> GameResult.BLACK_WINS
        }
}

/**
 * The outcome of one [GameSessionReducer.reduce] step.
 *
 * @param session the updated session (or the unchanged/absent one)
 * @param changed true when [session] differs from the input and should be persisted
 * @param created true when a brand-new session row should be inserted for a peer challenge
 * @param activated true when a challenge moved to ACTIVE (the invitation UI should react)
 */
data class ReduceResult(
    val session: GameSession?,
    val changed: Boolean = false,
    val created: Boolean = false,
    val activated: Boolean = false,
)
