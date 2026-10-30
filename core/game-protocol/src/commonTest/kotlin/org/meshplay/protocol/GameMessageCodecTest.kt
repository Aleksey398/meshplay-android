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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameMessageCodecTest {
    @Test
    fun `encodes a tic-tac-toe challenge in the documented shape`() {
        val encoded = GameMessageCodec.encode(GameMessage.NewGame(GameKind.TIC_TAC_TOE, 789, Side.WHITE))
        assertEquals("[MG1|TT|N|w|789]", encoded)
    }

    @Test
    fun `encodes a chess move like the spec example`() {
        val encoded = GameMessageCodec.encode(GameMessage.Move(GameKind.CHESS, 123, 1, "e2e4"))
        assertEquals("[MG1|CH|M|1:e2e4|123]", encoded)
    }

    @Test
    fun `round-trips every message type`() {
        val messages =
            listOf(
                GameMessage.NewGame(GameKind.CHESS, 1, Side.BLACK),
                GameMessage.NewGame(GameKind.CHECKERS, 0, Side.WHITE),
                GameMessage.Move(GameKind.CHECKERS, 42, 7, "e2c3e5"),
                GameMessage.Move(GameKind.TIC_TAC_TOE, 5, 3, "5"),
                GameMessage.Move(GameKind.CHESS, 99_999, 12, "e7e8q"),
                GameMessage.Snapshot(
                    GameKind.CHESS,
                    123,
                    13,
                    "rnbqkb1r/pppp1ppp/5n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
                ),
                GameMessage.Snapshot(GameKind.TIC_TAC_TOE, 8, 0, "X-O-X---O-"),
                GameMessage.Request(GameKind.CHESS, 3, GameRequest.RESIGN),
                GameMessage.Request(GameKind.CHECKERS, 3, GameRequest.DRAW_OFFER),
                GameMessage.Result(GameKind.CHESS, 3, GameResult.WHITE_WINS),
                GameMessage.Result(GameKind.TIC_TAC_TOE, 3, GameResult.DRAW),
            )
        for (message in messages) {
            val encoded = GameMessageCodec.encode(message)
            assertEquals(message, GameMessageCodec.decode(encoded), "round trip failed for $encoded")
        }
    }

    @Test
    fun `snapshot spaces survive as underscores on the wire`() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val encoded = GameMessageCodec.encode(GameMessage.Snapshot(GameKind.CHESS, 1, 0, fen))
        assertFalse(encoded.contains(' '), "wire form must not contain raw spaces: $encoded")
        assertTrue(encoded.contains("_"))
        val decoded = GameMessageCodec.decode(encoded) as GameMessage.Snapshot
        assertEquals(fen, decoded.state)
    }

    @Test
    fun `rejects oversized messages at encode time`() {
        val hugeState = "x".repeat(300)
        assertFailsWith<GameMessageTooLongException> {
            GameMessageCodec.encode(GameMessage.Snapshot(GameKind.CHESS, 1, 1, hugeState))
        }
    }

    @Test
    fun `encoded chess snapshots fit the client byte budget`() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val encoded = GameMessageCodec.encode(GameMessage.Snapshot(GameKind.CHESS, 4321, 12, fen))
        assertTrue(
            encoded.encodeToByteArray().size <= GameMessageCodec.MAX_ENCODED_BYTES,
            "kiwipete-class FEN must fit: ${encoded.encodeToByteArray().size} bytes",
        )
    }

    @Test
    fun `plain chat text is never treated as a game message`() {
        assertNull(GameMessageCodec.decode("hello mesh"))
        assertNull(GameMessageCodec.decode("[MG2|CH|M|1:e2e4|1]"))
        assertNull(GameMessageCodec.decode("[MG1|XX|M|1:e2e4|1]"))
        assertNull(GameMessageCodec.decode("prefix [MG1|CH|M|1:e2e4|1]"))
        assertFalse(GameMessageCodec.looksLikeGameMessage("prefix [MG1|CH|M|1:e2e4|1]"))
    }

    @Test
    fun `malformed game messages decode to null instead of throwing`() {
        val malformed =
            listOf(
                "[MG1|",
                "[MG1|CH]",
                "[MG1|CH|M|1:e2e4|1",
                "[MG1|CH|M||1]",
                "[MG1|CH|M|notanumber:e2e4|1]",
                "[MG1|CH|M|1:|1]",
                "[MG1|CH|M|1:e2 e4|1]",
                "[MG1|CH|N|q|1]",
                "[MG1|CH|R|Z|1]",
                "[MG1|CH|E|Q|1]",
                "[MG1|CH|S|5:|1]",
                "[MG1|TT|M|1:5|100000]",
                "[MG1|TT|M|1:5|-1]",
                "[]",
                "[MG1|CH|M|1:e2e4|1] trailing",
                "",
            )
        for (text in malformed) {
            assertNull(GameMessageCodec.decode(text), "expected null for: $text")
        }
    }

    @Test
    fun `decode tolerates long but well-formed tokens without throwing`() {
        // A token beyond our own vocabulary (e.g. a future extension) is still structurally valid.
        val futureMove = GameMessage.Move(GameKind.CHESS, 1, 1, "e2e4x")
        val decoded = GameMessageCodec.decode(GameMessageCodec.encode(futureMove))
        assertEquals(futureMove, decoded)
    }
}
