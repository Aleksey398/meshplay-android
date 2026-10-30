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
package org.meshplay.games.engine

import org.meshplay.protocol.GameKind
import org.meshplay.protocol.GameResult
import org.meshplay.protocol.Side

/**
 * A game-agnostic view over one of the three engines, keyed by [GameKind]. `state` is the
 * snapshot string exchanged over the wire (FEN for chess; board string plus mover for the
 * others), so a session can always be restored from its last snapshot.
 */
interface GameEngine {
    val kind: GameKind

    /** Snapshot string for the wire protocol / persistence. */
    val state: String

    val sideToMove: Side

    val isOver: Boolean

    val result: GameResult?

    /** The most recent move in wire notation, or null at the start of a game. */
    val lastMove: String?

    /** Legal moves in wire notation for the side to move. */
    fun legalMoves(): List<String>

    /** Applies a legal move; returns null when [move] is not legal in this position. */
    fun apply(move: String): GameEngine

    companion object {
        fun initial(kind: GameKind): GameEngine =
            when (kind) {
                GameKind.CHESS -> ChessEngine(ChessPosition.initial())
                GameKind.CHECKERS -> CheckersEngine(CheckersPosition.initial())
                GameKind.TIC_TAC_TOE -> TicTacToeEngine(TicTacToePosition.initial())
            }

        /** Restores from a snapshot string; returns null when the state is malformed. */
        fun restore(kind: GameKind, state: String): GameEngine? =
            when (kind) {
                GameKind.CHESS -> ChessPosition.fromFen(state)?.let { ChessEngine(it) }
                GameKind.CHECKERS -> {
                    val parts = state.trim().split(' ')
                    if (parts.size != 2 || parts[1].length != 1) return null
                    val whiteToMove = when (parts[1][0]) {
                        'w' -> true
                        'b' -> false
                        else -> return null
                    }
                    CheckersPosition.fromBoardString(parts[0], whiteToMove)?.let { CheckersEngine(it) }
                }
                GameKind.TIC_TAC_TOE -> TicTacToePosition.fromBoardString(state)?.let { TicTacToeEngine(it) }
            }
    }
}

/** Chess backed by [ChessPosition]; `state` is the standard FEN. */
class ChessEngine(val position: ChessPosition) : GameEngine {
    override val kind: GameKind
        get() = GameKind.CHESS

    override val state: String
        get() = position.toFen()

    override val sideToMove: Side
        get() = position.sideToMove

    override val isOver: Boolean
        get() = position.isOver

    override val result: GameResult?
        get() = position.result

    override val lastMove: String?
        get() = position.lastMoveUci()

    override fun legalMoves(): List<String> = position.legalMovesUci()

    override fun apply(move: String): GameEngine {
        val next = position.applyUci(move) ?: return this
        return ChessEngine(next)
    }
}

/** English draughts backed by [CheckersPosition]. */
class CheckersEngine(val position: CheckersPosition) : GameEngine {
    override val kind: GameKind
        get() = GameKind.CHECKERS

    override val state: String
        get() = "${position.toBoardString()} ${if (position.whiteToMove) 'w' else 'b'}"

    override val sideToMove: Side
        get() = position.sideToMove

    override val isOver: Boolean
        get() = position.isOver

    override val result: GameResult?
        get() = position.result

    override val lastMove: String?
        get() = position.lastMove()

    override fun legalMoves(): List<String> = position.legalMoves()

    override fun apply(move: String): GameEngine {
        val next = position.applyMove(move) ?: return this
        return CheckersEngine(next)
    }
}

/** Tic-tac-toe backed by [TicTacToePosition]. */
class TicTacToeEngine(val position: TicTacToePosition) : GameEngine {
    override val kind: GameKind
        get() = GameKind.TIC_TAC_TOE

    override val state: String
        get() = position.toBoardString()

    override val sideToMove: Side
        get() = position.sideToMove

    override val isOver: Boolean
        get() = position.isOver

    override val result: GameResult?
        get() = position.result

    override val lastMove: String?
        get() = position.lastMove()

    override fun legalMoves(): List<String> = position.legalMoves()

    override fun apply(move: String): GameEngine {
        val next = position.applyMove(move) ?: return this
        return TicTacToeEngine(next)
    }
}
