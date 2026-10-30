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
 * The turn-based games MeshPlay can play over a plain text channel.
 *
 * Each kind maps to a short wire tag so game messages stay tiny: the whole payload budget of a
 * mesh text packet is ~200 bytes.
 */
enum class GameKind(val wireTag: String, val displayName: String) {
    CHESS("CH", "Chess"),
    CHECKERS("CK", "Checkers"),
    TIC_TAC_TOE("TT", "Tic-tac-toe"),
    ;

    companion object {
        fun fromWireTag(tag: String): GameKind? = entries.firstOrNull { it.wireTag == tag }
    }
}

/** Which side of the board a player commands. Tic-tac-toe ignores it. */
enum class Side(val wireChar: Char) {
    WHITE('w'),
    BLACK('b'),
    ;

    val opponent: Side
        get() = if (this == WHITE) BLACK else WHITE

    companion object {
        fun fromWireChar(c: Char): Side? = entries.firstOrNull { it.wireChar == c }
    }
}

/** A player-initiated request carried inside a game conversation. */
enum class GameRequest(val wireChar: Char) {
    RESIGN('X'),
    DRAW_OFFER('D'),
    ;

    companion object {
        fun fromWireChar(c: Char): GameRequest? = entries.firstOrNull { it.wireChar == c }
    }
}

/**
 * The final result of a session. `W`/`B` mean white/black wins for board games; for tic-tac-toe
 * they mean X/O wins. `D` is a draw in every game.
 */
enum class GameResult(val wireChar: Char) {
    WHITE_WINS('W'),
    BLACK_WINS('B'),
    DRAW('D'),
    ;

    val winner: Side?
        get() =
            when (this) {
                WHITE_WINS -> Side.WHITE
                BLACK_WINS -> Side.BLACK
                DRAW -> null
            }

    companion object {
        fun fromWireChar(c: Char): GameResult? = entries.firstOrNull { it.wireChar == c }
    }
}
