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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessPositionTest {
    @Test
    fun `initial position has 20 legal moves`() {
        val position = ChessPosition.initial()
        assertEquals(20, position.legalMovesUci().size)
    }

    @Test
    fun `initial FEN round-trips`() {
        val position = ChessPosition.initial()
        assertEquals(ChessPosition.INITIAL_FEN, position.toFen())
    }

    @Test
    fun `kiwipete perft counts match the reference`() {
        // Position 2 from the classic perft suite — exercises castling, en passant and promotions.
        val kiwipete = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val position = ChessPosition.fromFen(kiwipete)!!
        assertEquals(48, position.legalMovesUci().size)
        assertEquals(2039, perft(position, 2))
    }

    @Test
    fun `perft counts match for the position 3 suite`() {
        val fen = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"
        val position = ChessPosition.fromFen(fen)!!
        assertEquals(14, position.legalMovesUci().size)
        assertEquals(191, perft(position, 2))
    }

    @Test
    fun `perft counts match for the position 4 suite`() {
        val fen = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"
        val position = ChessPosition.fromFen(fen)!!
        assertEquals(6, position.legalMovesUci().size)
    }

    @Test
    fun `scholars mate produces a white win result`() {
        var position = ChessPosition.initial()
        for (uci in listOf("f2f3", "e7e5", "g2g4", "d8h4")) {
            position = position.applyUci(uci)!!
        }
        assertEquals(GameResult.BLACK_WINS, position.result)
        assertTrue(position.isOver)
    }

    @Test
    fun `castling rights are lost after the king moves`() {
        var position = ChessPosition.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")!!
        position = position.applyUci("e1e2")!!
        val fen = position.toFen()
        assertTrue(fen.contains(" b "), "side passes to black: $fen")
        position = position.applyUci("e8e7")!!
        position = position.applyUci("e2e1")!!
        val after = position.toFen()
        // Neither rook has moved, but both kings did: no castling rights remain for either side.
        assertEquals("r6r/4k3/8/8/8/8/8/R3K2R b - - 3 3", after)
        assertNull(position.applyUci("e1g1"), "castling must be illegal after the right was lost")
    }

    @Test
    fun `en passant capture works and is reflected in the FEN`() {
        var position = ChessPosition.initial()
        position = position.applyUci("e2e4")!!
        position = position.applyUci("a7a6")!!
        position = position.applyUci("e4e5")!!
        position = position.applyUci("d7d5")!!
        assertTrue(position.toFen().contains(" d6 "))
        val after = position.applyUci("e5d6")!!
        assertEquals(0, after.board[ChessPosition.squareFromName("d5")!!], "captured pawn must leave the board")
    }

    @Test
    fun `illegal moves are rejected`() {
        val position = ChessPosition.initial()
        assertNull(position.applyUci("e2e5"))
        assertNull(position.applyUci("a1a3"))
        assertNull(position.applyUci("not-a-move"))
    }

    @Test
    fun `promotion is applied from FEN`() {
        val fen = "8/P6k/8/8/8/8/8/K7 w - - 0 1"
        val position = ChessPosition.fromFen(fen)!!
        val promoted = position.applyUci("a7a8q")!!
        assertEquals('Q', ChessPosition.pieceChar(promoted.board[ChessPosition.squareFromName("a8")!!]))
        // Black king escapes to g7, so the game continues despite the check.
        assertNull(promoted.result)
    }

    private fun perft(position: ChessPosition, depth: Int): Int {
        if (depth == 0) return 1
        var nodes = 0
        for (uci in position.legalMovesUci()) {
            val next = position.applyUci(uci)
            if (next != null) {
                nodes += perft(next, depth - 1)
            }
        }
        return nodes
    }
}
