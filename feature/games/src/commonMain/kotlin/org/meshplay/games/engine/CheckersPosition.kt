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

import org.meshplay.protocol.GameResult
import org.meshplay.protocol.Side

/**
 * English (American) draughts on an 8×8 board: men move and capture forward only, kings move and
 * capture one square diagonally in any direction, captures are mandatory, and a multi-capture
 * continues in the same turn. Crowning ends the turn.
 *
 * Squares are `rank * 8 + file` with 0 = a1 … 63 = h8; only dark squares (even file+rank) hold
 * pieces. White moves toward rank 8. The board string used for snapshots is 64 characters,
 * `.`/`w`/`W`/`b`/`B`, rank 8 first (matching FEN reading order).
 *
 * Move notation: `from` + landing squares, e.g. `e2e4`, or `e2c3e5` for a double jump.
 */
data class CheckersPosition(
    val board: IntArray,
    val whiteToMove: Boolean,
    val history: List<String> = emptyList(),
    val quietPly: Int = 0,
) {
    val sideToMove: Side
        get() = if (whiteToMove) Side.WHITE else Side.BLACK

    /** A side with no pieces or no legal move loses; a long quiet stretch is a draw. */
    val result: GameResult?
        get() {
            if (quietPly >= QUIET_DRAW_PLY) return GameResult.DRAW
            if (generateMoves().isNotEmpty()) return null
            return if (whiteToMove) GameResult.BLACK_WINS else GameResult.WHITE_WINS
        }

    val isOver: Boolean
        get() = result != null

    fun lastMove(): String? = history.lastOrNull()

    fun legalMoves(): List<String> = generateMoves().map { it.notation }

    /** Applies a legal move; returns null when [notation] is not legal here. */
    fun applyMove(notation: String): CheckersPosition? {
        val move = generateMoves().firstOrNull { it.notation == notation } ?: return null
        return apply(move)
    }

    fun apply(move: CheckersMove): CheckersPosition {
        val next = board.copyOf()
        var piece = next[move.from]
        next[move.from] = EMPTY
        val lastLanding = move.landings.last()
        // Remove every captured piece along the chain.
        var from = move.from
        for (landing in move.landings) {
            val jumped = (from + landing) / 2
            if (kotlin.math.abs(landing - from) == 16) {
                next[jumped] = EMPTY
            }
            from = landing
        }
        // Crowning ends the turn, even mid-capture.
        if (piece == WHITE_MAN && rankOf(lastLanding) == 7) {
            piece = WHITE_KING
        } else if (piece == BLACK_MAN && rankOf(lastLanding) == 0) {
            piece = BLACK_KING
        }
        next[lastLanding] = piece
        val captured = move.landings.size > 1 || kotlin.math.abs(lastLanding - move.from) == 16
        return CheckersPosition(
            board = next,
            whiteToMove = !whiteToMove,
            history = history + move.notation,
            quietPly = if (captured) 0 else quietPly + 1,
        )
    }

    fun toBoardString(): String = buildString {
        for (rank in 7 downTo 0) {
            for (file in 0..7) {
                append(
                    when (board[rank * 8 + file]) {
                        WHITE_MAN -> 'w'
                        WHITE_KING -> 'W'
                        BLACK_MAN -> 'b'
                        BLACK_KING -> 'B'
                        else -> '.'
                    },
                )
            }
        }
    }

    private fun generateMoves(): List<CheckersMove> {
        val captures = mutableListOf<CheckersMove>()
        for (sq in 0..63) {
            val piece = board[sq]
            if (piece == EMPTY) continue
            if (whiteToMove && piece < 0) continue
            if (!whiteToMove && piece > 0) continue
            collectCaptures(sq, piece, listOf(sq), emptyList(), emptySet(), captures)
        }
        if (captures.isNotEmpty()) return captures
        val quiet = mutableListOf<CheckersMove>()
        for (sq in 0..63) {
            val piece = board[sq]
            if (piece == EMPTY) continue
            if (whiteToMove && piece < 0) continue
            if (!whiteToMove && piece > 0) continue
            collectQuiet(sq, piece, quiet)
        }
        return quiet
    }

    private fun collectCaptures(
        sq: Int,
        piece: Int,
        path: List<Int>,
        landings: List<Int>,
        captured: Set<Int>,
        out: MutableList<CheckersMove>,
    ) {
        val dirs = captureDirs(piece)
        var extended = false
        for ((df, dr) in dirs) {
            val mid = sq + dr * 8 + df
            val landing = sq + 2 * (dr * 8 + df)
            if (mid !in 0..63 || landing !in 0..63) continue
            if (kotlin.math.abs(fileOf(mid) - fileOf(sq)) != 1) continue
            if (kotlin.math.abs(fileOf(landing) - fileOf(sq)) != 2) continue
            val victim = board[mid]
            if (mid in captured || victim == EMPTY || isOwnPiece(victim, piece)) continue
            if (board[landing] != EMPTY) continue
            // Crowning ends the turn even mid-capture.
            val crowns =
                (piece == WHITE_MAN && rankOf(landing) == 7) || (piece == BLACK_MAN && rankOf(landing) == 0)
            extended = true
            if (crowns) {
                out.add(CheckersMove(path.first(), landings + landing))
            } else {
                collectCaptures(landing, piece, path + landing, landings + landing, captured + mid, out)
            }
        }
        if (!extended && landings.isNotEmpty()) {
            out.add(CheckersMove(path.first(), landings))
        }
    }

    private fun collectQuiet(sq: Int, piece: Int, out: MutableList<CheckersMove>) {
        for ((df, dr) in quietDirs(piece)) {
            val target = sq + dr * 8 + df
            if (target !in 0..63) continue
            if (kotlin.math.abs(fileOf(target) - fileOf(sq)) != 1) continue
            if (board[target] == EMPTY) {
                out.add(CheckersMove(sq, listOf(target)))
            }
        }
    }

    private fun isOwnPiece(victim: Int, mover: Int): Boolean =
        if (mover > 0) victim > 0 else victim < 0

    private fun captureDirs(piece: Int): List<Pair<Int, Int>> =
        when (piece) {
            WHITE_MAN -> listOf(1 to 1, -1 to 1)
            BLACK_MAN -> listOf(1 to -1, -1 to -1)
            else -> ALL_DIAGS
        }

    private fun quietDirs(piece: Int): List<Pair<Int, Int>> = captureDirs(piece)

    private fun rankOf(sq: Int): Int = sq shr 3

    override fun equals(other: Any?): Boolean =
        other is CheckersPosition &&
            board.contentEquals(other.board) &&
            whiteToMove == other.whiteToMove &&
            history == other.history &&
            quietPly == other.quietPly

    override fun hashCode(): Int {
        var result = board.contentHashCode()
        result = 31 * result + whiteToMove.hashCode()
        result = 31 * result + history.hashCode()
        result = 31 * result + quietPly
        return result
    }

    companion object {
        const val EMPTY = 0
        const val WHITE_MAN = 1
        const val WHITE_KING = 2
        const val BLACK_MAN = -1
        const val BLACK_KING = -2

        /** 80 plies (40 moves per side) without a capture or man move ends the game as a draw. */
        private const val QUIET_DRAW_PLY = 80

        private val ALL_DIAGS = listOf(1 to 1, -1 to 1, 1 to -1, -1 to -1)

        fun fileOf(sq: Int): Int = sq and 7

        fun initial(): CheckersPosition = fromBoardString(INITIAL_BOARD)!!

        /**
         * The standard opening array as a 64-char board string, rank 8 first.
         * Black on ranks 6–8, white on ranks 1–3, on dark squares.
         */
        const val INITIAL_BOARD = ".b.b.b.b" +
            "b.b.b.b." +
            ".b.b.b.b" +
            "........" +
            "........" +
            "w.w.w.w." +
            ".w.w.w.w" +
            "w.w.w.w."

        /** Parses a 64-char board string; returns null when malformed. */
        fun fromBoardString(state: String, history: List<String> = emptyList()): CheckersPosition? {
            val compact = state.filterNot { it.isWhitespace() }
            if (compact.length != 64) return null
            val board = IntArray(64)
            var whites = 0
            var blacks = 0
            for (i in 0..63) {
                val rank = 7 - (i / 8)
                val file = i % 8
                val piece =
                    when (compact[i]) {
                        '.' -> EMPTY
                        'w' -> WHITE_MAN.also { whites++ }
                        'W' -> WHITE_KING.also { whites++ }
                        'b' -> BLACK_MAN.also { blacks++ }
                        'B' -> BLACK_KING.also { blacks++ }
                        else -> return null
                    }
                board[rank * 8 + file] = piece
            }
            if (whites == 0 || blacks == 0) return null
            // White moves first; side derives from piece counts parity is unreliable mid-game, so
            // keep an explicit flag — the string encodes position only, callers pass the mover.
            return CheckersPosition(board, whiteToMove = true, history = history)
        }

        /**
         * Restores from a board string plus the side to move. The snapshot wire format pairs the
         * board string with `w`/`b` (see [org.meshplay.games.engine.GameEngine]).
         */
        fun fromBoardString(state: String, whiteToMove: Boolean, history: List<String> = emptyList()): CheckersPosition? =
            fromBoardString(state, history)?.copy(whiteToMove = whiteToMove)
    }
}

/** A checkers move: [from] square plus the chain of landing squares. */
data class CheckersMove(val from: Int, val landings: List<Int>) {
    val notation: String
        get() = buildString {
            append(CheckersPosition.fileName(from))
            for (landing in landings) {
                append(CheckersPosition.fileName(landing))
            }
        }
}

private fun CheckersPosition.Companion.fileName(sq: Int): String = "${'a' + fileOf(sq)}${(sq shr 3) + 1}"
