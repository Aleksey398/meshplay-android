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

import kotlinx.serialization.Serializable
import org.meshplay.protocol.GameKind
import org.meshplay.protocol.GameResult
import org.meshplay.protocol.Side

/** Whether this device opened the challenge or received it. */
enum class SessionRole { CHALLENGER, RESPONDER }

/** Lifecycle of one game session. */
enum class SessionStatus {
    /** Challenge sent; waiting for the peer's acceptance snapshot. */
    PENDING,

    /** Challenge received; waiting for the local player's accept/decline. */
    INVITED,

    /** Both sides playing. */
    ACTIVE,

    /** Ended with [GameSession.result] (or a draw agreement). */
    FINISHED,
}

/**
 * A game session between this device and one contact, persisted locally so a game survives app
 * restarts. [state] is the engine snapshot (see [org.meshplay.games.engine.GameEngine]) and
 * [seq] is the number of moves folded into it; moves and snapshots on the wire carry their own
 * seq so late or duplicated packets cannot corrupt the session.
 */
@Serializable
data class GameSession(
    val sessionId: Int,
    val contactKey: String,
    val kind: GameKind,
    /** The side this device plays; the peer holds the other one. */
    val mySide: Side,
    val role: SessionRole,
    /** Engine snapshot; empty until the challenge is accepted. */
    val state: String = "",
    /** Moves applied to [state]; equals the highest applied move seq. */
    val seq: Int = 0,
    val status: SessionStatus = SessionStatus.PENDING,
    val result: GameResult? = null,
    /** A draw offer is on the table from the peer. */
    val drawOfferFromPeer: Boolean = false,
    /** Wire notations of the moves applied so far, for the history list. */
    val moves: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val peerSide: Side
        get() = mySide.opponent

    fun isFinished(): Boolean = status == SessionStatus.FINISHED
}
