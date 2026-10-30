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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckersAndTicTacToeEngineTest {
    @Test
    fun `initial checkers position allows 7 moves`() {
        val position = CheckersPosition.initial()
        assertEquals(7, position.legalMoves().size)
    }

    @Test
    fun `checkers quiet move round-trips through the board string`() {
        val position = CheckersPosition.initial()
        val moved = position.applyMove("c3d4")!!
        assertEquals(Side.BLACK, moved.sideToMove)
        val restored = CheckersPosition.fromBoardString(moved.toBoardString(), whiteToMove = false)!!
        assertEquals(moved.board.toList(), restored.board.toList())
        assertEquals(moved.legalMoves(), restored.legalMoves())
    }

    @Test
    fun `checkers mandatory capture takes precedence over quiet moves`() {
        // White c3 can capture the black man on d4, landing on e5; quiet c3-b4 also exists.
        val board =
            "........" +
                "........" +
                "........" +
                "........" +
                "...b...." +
                "..w....." +
                "........" +
                "........"
        val position = CheckersPosition.fromBoardString(board, whiteToMove = true)!!
        assertEquals(listOf("c3e5"), position.legalMoves())
        val after = position.applyMove("c3e5")!!
        // Black is to move with no pieces left, so black loses.
        assertEquals(GameResult.WHITE_WINS, after.result, "black has no pieces left")
    }

    @Test
    fun `checkers multi-capture is a single turn`() {
        // White man b2 double-jumps c3 and e5: b2xc3 lands d4, d4xe5 lands f6 — chain b2d4f6.
        val board =
            "........" +
                "........" +
                "........" +
                "....b..." +
                "........" +
                "..b....." +
                ".w......" +
                "........"
        val position = CheckersPosition.fromBoardString(board, whiteToMove = true)!!
        val moves = position.legalMoves()
        assertEquals(listOf("b2d4f6"), moves)
        val after = position.applyMove("b2d4f6")!!
        val f6 = 5 * 8 + 5
        assertEquals(CheckersPosition.WHITE_MAN, after.board[f6])
        assertTrue(after.board.none { it == CheckersPosition.BLACK_MAN }, "both black men are captured")
    }

    @Test
    fun `checkers crowning ends the turn even mid-capture`() {
        // White man b6 jumps the black man on c7 and crowns on d8; the turn then passes.
        // Row 1 is rank 7 and row 2 is rank 6 (rank 8 first).
        val board =
            "........" +
                "..b....." +
                ".w......" +
                "........" +
                "........" +
                "........" +
                "........" +
                "........"
        val position = CheckersPosition.fromBoardString(board, whiteToMove = true)!!
        assertEquals(listOf("b6d8"), position.legalMoves())
        val after = position.applyMove("b6d8")!!
        val d8 = 7 * 8 + 3
        assertEquals(CheckersPosition.WHITE_KING, after.board[d8])
        assertEquals(Side.BLACK, after.sideToMove, "crowning ends the turn")
    }

    @Test
    fun `checkers engine facade restores state strings`() {
        val engine = GameEngine.initial(GameKind.CHECKERS)
        val moved = engine.apply("c3d4")
        val restored = GameEngine.restore(GameKind.CHECKERS, moved.state)!!
        assertEquals(moved.state, restored.state)
        assertEquals(moved.legalMoves(), restored.legalMoves())
        assertNull(GameEngine.restore(GameKind.CHECKERS, "not-a-board"))
    }

    @Test
    fun `tic-tac-toe plays to a draw`() {
        var position = TicTacToePosition.initial()
        for (move in listOf("2", "1", "4", "6", "3", "7", "5", "8", "9")) {
            assertTrue(position.legalMoves().contains(move), "move $move should be legal")
            position = position.applyMove(move)!!
        }
        assertEquals(GameResult.DRAW, position.result)
        assertTrue(position.isOver)
        assertTrue(position.legalMoves().isEmpty())
        assertEquals("OXXXXOOOX", position.toBoardString())
    }

    @Test
    fun `tic-tac-toe detects a win for X`() {
        var position = TicTacToePosition.initial()
        for (move in listOf("1", "4", "2", "5", "3")) {
            position = position.applyMove(move)!!
        }
        assertEquals(GameResult.WHITE_WINS, position.result)
    }

    @Test
    fun `tic-tac-toe rejects occupied cells and out-of-range moves`() {
        val position = TicTacToePosition.initial().applyMove("5")!!
        assertNull(position.applyMove("5"))
        assertNull(position.applyMove("0"))
        assertNull(position.applyMove("10"))
        assertNull(position.applyMove("abc"))
    }

    @Test
    fun `tic-tac-toe restore keeps piece counts valid`() {
        assertNotNull(TicTacToePosition.fromBoardString("XXO.OX..O"))
        assertNull(TicTacToePosition.fromBoardString("XXX......"))
        assertNull(TicTacToePosition.fromBoardString("short"))
        val engine = GameEngine.restore(GameKind.TIC_TAC_TOE, "X.O...X.O")!!
        assertEquals(Side.WHITE, engine.sideToMove)
    }

    @Test
    fun `chess engine facade round-trips a move`() {
        var engine = GameEngine.initial(GameKind.CHESS)
        assertTrue(engine.legalMoves().contains("e2e4"))
        engine = engine.apply("e2e4")
        val restored = GameEngine.restore(GameKind.CHESS, engine.state)!!
        assertEquals(engine.state, restored.state)
        assertEquals(Side.BLACK, restored.sideToMove)
    }

    @Test
    fun `unknown states restore to null`() {
        assertNull(GameEngine.restore(GameKind.CHESS, "garbage"))
        assertNull(GameEngine.restore(GameKind.TIC_TAC_TOE, "XXOXX"))
    }
}
