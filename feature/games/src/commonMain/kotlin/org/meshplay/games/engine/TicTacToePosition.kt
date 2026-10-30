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
 * Tic-tac-toe on a 3×3 grid. Cells are numbered 1–9, row-major from the top left, matching the
 * wire move notation. X plays first and maps to [Side.WHITE], O to [Side.BLACK].
 *
 * The snapshot state is a 9-character string of `.`, `X`, `O`, read left-to-right, top-to-bottom.
 */
data class TicTacToePosition(
    val cells: IntArray,
    val history: List<String> = emptyList(),
) {
    /** X = 1, O = -1, empty = 0; index = cell - 1. */
    private fun cell(index: Int): Int = cells[index]

    val sideToMove: Side
        get() = if (xIsNext()) Side.WHITE else Side.BLACK

    val result: GameResult?
        get() {
            for ((a, b, c) in WINNING_LINES) {
                val sum = cell(a) + cell(b) + cell(c)
                if (sum == 3) return GameResult.WHITE_WINS
                if (sum == -3) return GameResult.BLACK_WINS
            }
            return if (cells.all { it != EMPTY }) GameResult.DRAW else null
        }

    val isOver: Boolean
        get() = result != null

    fun lastMove(): String? = history.lastOrNull()

    fun cellAt(cellNumber: Int): Int = cells.getOrNull(cellNumber - 1) ?: EMPTY

    fun legalMoves(): List<String> =
        if (isOver) {
            emptyList()
        } else {
            (1..9).filter { cells[it - 1] == EMPTY }.map { it.toString() }
        }

    fun applyMove(move: String): TicTacToePosition? {
        val cellNumber = move.toIntOrNull() ?: return null
        if (cellNumber !in 1..9) return null
        if (cells[cellNumber - 1] != EMPTY) return null
        if (isOver) return null
        val next = cells.copyOf()
        next[cellNumber - 1] = if (xIsNext()) 1 else -1
        return TicTacToePosition(next, history + move)
    }

    private fun xIsNext(): Boolean {
        var xs = 0
        var os = 0
        for (value in cells) {
            if (value == 1) xs++ else if (value == -1) os++
        }
        return xs <= os
    }

    fun toBoardString(): String = buildString {
        for (value in cells) {
            append(
                when (value) {
                    1 -> 'X'
                    -1 -> 'O'
                    else -> '.'
                },
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TicTacToePosition && cells.contentEquals(other.cells) && history == other.history

    override fun hashCode(): Int = 31 * cells.contentHashCode() + history.hashCode()

    companion object {
        const val EMPTY = 0

        private val WINNING_LINES =
            listOf(
                Triple(0, 1, 2),
                Triple(3, 4, 5),
                Triple(6, 7, 8),
                Triple(0, 3, 6),
                Triple(1, 4, 7),
                Triple(2, 5, 8),
                Triple(0, 4, 8),
                Triple(2, 4, 6),
            )

        fun initial(): TicTacToePosition = TicTacToePosition(IntArray(9))

        /** Parses the 9-char board string; returns null when malformed. */
        fun fromBoardString(state: String, history: List<String> = emptyList()): TicTacToePosition? {
            val compact = state.filterNot { it.isWhitespace() }
            if (compact.length != 9) return null
            val cells = IntArray(9)
            var xs = 0
            var os = 0
            for (i in compact.indices) {
                cells[i] =
                    when (compact[i]) {
                        '.' -> EMPTY
                        'X' -> 1.also { xs++ }
                        'O' -> -1.also { os++ }
                        else -> return null
                    }
            }
            if (xs < os || xs > os + 1) return null
            return TicTacToePosition(cells, history)
        }
    }
}
