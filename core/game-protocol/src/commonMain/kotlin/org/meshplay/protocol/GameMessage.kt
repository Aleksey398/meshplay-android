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
package org.meshplay.protocol

/**
 * A decoded `[MG1|...]` game message.
 *
 * Game traffic rides the ordinary text channel as a tagged string (see [GameMessageCodec]), so any
 * client that does not understand the tag just shows it as a normal message. The schema is
 * documented in `docs/game-protocol.md`.
 */
sealed interface GameMessage {
    val kind: GameKind

    /** Identifier of the game session, unique per challenge pair (0–99999). */
    val sessionId: Int

    /**
     * Opens a session. The challenger announces the game and the side it will play; the peer
     * accepts by answering with the initial [Snapshot].
     */
    data class NewGame(
        override val kind: GameKind,
        override val sessionId: Int,
        val challengerSide: Side,
    ) : GameMessage

    /**
     * A single move. [seq] counts moves applied in the session and starts at 1; the receiver
     * applies a move only when it is exactly one ahead of its own state, which makes reordering
     * and duplicates harmless.
     *
     * [move] is game-specific coordinate notation: UCI for chess (`e2e4`, `e7e8q`, castling as
     * `e1g1`), `from+landings` for checkers (`e2e4`, or `e2c3e5` for a multi-capture), or a cell
     * number 1–9 for tic-tac-toe.
     */
    data class Move(
        override val kind: GameKind,
        override val sessionId: Int,
        val seq: Int,
        val move: String,
    ) : GameMessage

    /**
     * A full state snapshot (FEN for chess, compact board strings for the other games). Used to
     * accept a challenge (seq 0 is the initial position) and to periodically re-sync after packet
     * loss. A snapshot at seq >= the receiver's current seq replaces local state outright.
     */
    data class Snapshot(
        override val kind: GameKind,
        override val sessionId: Int,
        val seq: Int,
        val state: String,
    ) : GameMessage

    /** Resign or offer a draw. The peer settles a draw offer with a [Result] of [GameResult.DRAW]. */
    data class Request(
        override val kind: GameKind,
        override val sessionId: Int,
        val request: GameRequest,
    ) : GameMessage

    /** Ends the session with a final result. */
    data class Result(
        override val kind: GameKind,
        override val sessionId: Int,
        val result: GameResult,
    ) : GameMessage
}
