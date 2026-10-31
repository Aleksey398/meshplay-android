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

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.prefs.di.asAppDataStore
import org.meshplay.games.engine.GameEngine
import org.meshplay.protocol.GameKind
import org.meshplay.protocol.GameMessage
import org.meshplay.protocol.GameMessageCodec
import org.meshplay.protocol.GameRequest
import org.meshplay.protocol.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class GameSessionDismissalTest {
    @Test
    fun `dismissed invitation and finished session stay hidden when history is replayed`() = runTest {
        val tempDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "game-session-dismissal-${Uuid.random()}"
        FileSystem.SYSTEM.createDirectories(tempDir)
        try {
            val dataStore =
                PreferenceDataStoreFactory.createWithPath(
                    scope = backgroundScope,
                    produceFile = { tempDir / "app.preferences_pb" },
                )
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store =
                GameSessionStore(
                    dataStore.asAppDataStore(),
                    CoroutineDispatchers(dispatcher, dispatcher, dispatcher),
                )
            advanceUntilIdle()
            val ingestor = GameSessionIngestor(store)
            val contact = "0!deadbeef"
            val invitation = GameMessageCodec.encode(GameMessage.NewGame(GameKind.CHESS, 101, Side.WHITE))

            ingestor.ingest(contact, GameSessionIngestor.IngestedText(invitation, fromLocal = false, time = 10L))
            advanceUntilIdle()
            assertEquals(1, store.sessions.value.size)

            // This is the same path used by the Decline/Forget controls in the games list.
            store.remove(101, contact)
            advanceUntilIdle()
            assertTrue(store.sessions.value.isEmpty())
            assertTrue(store.isDismissed(101, contact))

            // Re-emitting the full chat feed must not recreate the old invitation.
            ingestor.ingest(contact, GameSessionIngestor.IngestedText(invitation, fromLocal = false, time = 10L))
            advanceUntilIdle()
            assertTrue(store.sessions.value.isEmpty())

            // Completed-session cards use the same persisted dismissal behavior.
            val finishedId = 202
            val finishedInvite =
                GameMessageCodec.encode(GameMessage.NewGame(GameKind.CHESS, finishedId, Side.WHITE))
            val acceptedSnapshot =
                GameMessageCodec.encode(
                    GameMessage.Snapshot(GameKind.CHESS, finishedId, 0, GameEngine.initial(GameKind.CHESS).state),
                )
            val resign =
                GameMessageCodec.encode(GameMessage.Request(GameKind.CHESS, finishedId, GameRequest.RESIGN))
            ingestor.ingest(contact, GameSessionIngestor.IngestedText(finishedInvite, fromLocal = false, time = 20L))
            ingestor.ingest(contact, GameSessionIngestor.IngestedText(acceptedSnapshot, fromLocal = true, time = 21L))
            ingestor.ingest(contact, GameSessionIngestor.IngestedText(resign, fromLocal = false, time = 22L))
            advanceUntilIdle()
            assertEquals(SessionStatus.FINISHED, store.get(finishedId, contact)?.status)

            store.remove(finishedId, contact)
            advanceUntilIdle()
            assertFalse(store.sessions.value.any { it.sessionId == finishedId })

            // Replay newest-to-oldest, as the repository feed does, to cover the actual regression.
            listOf(resign, acceptedSnapshot, finishedInvite).forEachIndexed { index, text ->
                ingestor.ingest(
                    contact,
                    GameSessionIngestor.IngestedText(text, fromLocal = index == 1, time = 22L - index),
                )
            }
            advanceUntilIdle()
            assertFalse(store.sessions.value.any { it.sessionId == finishedId })
            assertTrue(store.isDismissed(finishedId, contact))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(tempDir)
        }
    }
}
