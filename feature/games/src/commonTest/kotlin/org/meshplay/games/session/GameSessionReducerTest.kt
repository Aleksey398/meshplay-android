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

import org.meshplay.games.engine.GameEngine
import org.meshplay.protocol.GameKind
import org.meshplay.protocol.GameMessage
import org.meshplay.protocol.GameRequest
import org.meshplay.protocol.GameResult
import org.meshplay.protocol.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameSessionReducerTest {
    @Test
    fun `peer challenge creates an invited session with the opposite side`() {
        val result =
            GameSessionReducer.reduce(
                session = null,
                message = GameMessage.NewGame(GameKind.CHESS, 7, Side.BLACK),
                fromLocal = false,
                now = 10L,
            )
        assertTrue(result.created)
        val session = assertNotNull(result.session)
        assertEquals(SessionStatus.INVITED, session.status)
        assertEquals(Side.WHITE, session.mySide)
        assertEquals(SessionRole.RESPONDER, session.role)
    }

    @Test
    fun `accepting snapshot activates the session and moves apply in order`() {
        var session = invitedSession()
        val initial = GameEngine.initial(GameKind.TIC_TAC_TOE).state
        val accepted =
            GameSessionReducer.reduce(
                session,
                GameMessage.Snapshot(GameKind.TIC_TAC_TOE, session.sessionId, 0, initial),
                fromLocal = false,
                now = 1L,
            ).session!!
        assertEquals(SessionStatus.ACTIVE, accepted.status)

        session = GameSessionReducer.reduce(
            accepted,
            GameMessage.Move(GameKind.TIC_TAC_TOE, session.sessionId, 1, "5"),
            fromLocal = true,
            now = 2L,
        ).session!!
        assertEquals(1, session.seq)
        assertEquals(listOf("5"), session.moves)

        // Duplicates and replays are ignored (null session means "no change").
        val replayed =
            GameSessionReducer.reduce(
                session,
                GameMessage.Move(GameKind.TIC_TAC_TOE, session.sessionId, 1, "5"),
                fromLocal = false,
                now = 3L,
            )
        assertFalse(replayed.changed)
        assertNull(replayed.session)
    }

    @Test
    fun `gaps wait for a snapshot instead of corrupting state`() {
        val active = invitedSession().activate()
        val gap =
            GameSessionReducer.reduce(
                active,
                GameMessage.Move(GameKind.TIC_TAC_TOE, active.sessionId, 4, "9"),
                fromLocal = false,
                now = 1L,
            )
        assertFalse(gap.changed)

        val board = "X..O....."
        val synced =
            GameSessionReducer.reduce(
                active,
                GameMessage.Snapshot(GameKind.TIC_TAC_TOE, active.sessionId, 4, board),
                fromLocal = false,
                now = 2L,
            ).session!!
        assertEquals(4, synced.seq)
        assertEquals(board, synced.state)
    }

    @Test
    fun `illegal moves are rejected`() {
        val active = invitedSession().activate()
        val illegal =
            GameSessionReducer.reduce(
                active,
                GameMessage.Move(GameKind.TIC_TAC_TOE, active.sessionId, 1, "99"),
                fromLocal = false,
                now = 1L,
            )
        assertFalse(illegal.changed)
    }

    @Test
    fun `resign from the local player loses`() {
        val active = invitedSession().activate()
        val finished =
            GameSessionReducer.reduce(
                active,
                GameMessage.Request(GameKind.TIC_TAC_TOE, active.sessionId, GameRequest.RESIGN),
                fromLocal = true,
                now = 1L,
            ).session!!
        assertEquals(SessionStatus.FINISHED, finished.status)
        assertEquals(GameResult.BLACK_WINS, finished.result)
    }

    @Test
    fun `resign from the peer loses for the peer`() {
        val active = invitedSession().activate()
        val finished =
            GameSessionReducer.reduce(
                active,
                GameMessage.Request(GameKind.TIC_TAC_TOE, active.sessionId, GameRequest.RESIGN),
                fromLocal = false,
                now = 1L,
            ).session!!
        assertEquals(GameResult.WHITE_WINS, finished.result)
    }

    @Test
    fun `finished session ignores replayed and newer snapshots`() {
        // Regression: the chat log is re-ingested newest-first on every emission, so a snapshot
        // at the same seq as the resign used to flip the session back to ACTIVE ("in progress").
        val active = invitedSession().activate()
        val finished =
            GameSessionReducer.reduce(
                active,
                GameMessage.Request(GameKind.TIC_TAC_TOE, active.sessionId, GameRequest.RESIGN),
                fromLocal = false,
                now = 2L,
            ).session!!
        assertEquals(SessionStatus.FINISHED, finished.status)

        val equalSeq =
            GameSessionReducer.reduce(
                finished,
                GameMessage.Snapshot(GameKind.TIC_TAC_TOE, finished.sessionId, finished.seq, finished.state),
                fromLocal = false,
                now = 3L,
            )
        assertFalse(equalSeq.changed)
        assertNull(equalSeq.session)

        val newerSeq =
            GameSessionReducer.reduce(
                finished,
                GameMessage.Snapshot(GameKind.TIC_TAC_TOE, finished.sessionId, finished.seq + 5, "XXX......"),
                fromLocal = false,
                now = 4L,
            )
        assertFalse(newerSeq.changed)
        assertNull(newerSeq.session)
    }

    @Test
    fun `draw offer is flagged and a peer result settles it`() {
        val active = invitedSession().activate()
        val offered =
            GameSessionReducer.reduce(
                active,
                GameMessage.Request(GameKind.TIC_TAC_TOE, active.sessionId, GameRequest.DRAW_OFFER),
                fromLocal = false,
                now = 1L,
            ).session!!
        assertTrue(offered.drawOfferFromPeer)

        val settled =
            GameSessionReducer.reduce(
                offered,
                GameMessage.Result(GameKind.TIC_TAC_TOE, active.sessionId, GameResult.DRAW),
                fromLocal = false,
                now = 2L,
            ).session!!
        assertEquals(SessionStatus.FINISHED, settled.status)
        assertEquals(GameResult.DRAW, settled.result)
    }

    @Test
    fun `a finished game reached by move applies the result`() {
        // X plays 1,3,5 for the win via peer moves after our move 2.
        var session = invitedSession().activate()
        for ((seq, move) in listOf(1 to "1", 2 to "4", 3 to "2", 4 to "7", 5 to "3")) {
            session =
                GameSessionReducer.reduce(
                    session,
                    GameMessage.Move(GameKind.TIC_TAC_TOE, session.sessionId, seq, move),
                    fromLocal = seq % 2 == 1,
                    now = seq.toLong(),
                ).session!!
        }
        assertEquals(SessionStatus.FINISHED, session.status)
        assertEquals(GameResult.WHITE_WINS, session.result)
    }

    private fun invitedSession(): GameSession =
        GameSession(
            sessionId = 42,
            contactKey = "0!deadbeef",
            kind = GameKind.TIC_TAC_TOE,
            mySide = Side.WHITE,
            role = SessionRole.RESPONDER,
            status = SessionStatus.INVITED,
        )

    private fun GameSession.activate(): GameSession =
        GameSessionReducer.reduce(
            this,
            GameMessage.Snapshot(kind, sessionId, 0, GameEngine.initial(kind).state),
            fromLocal = false,
            now = 1L,
        ).session!!
}
