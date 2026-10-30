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
package org.meshplay.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.games_accept
import org.meshtastic.core.resources.games_chess
import org.meshtastic.core.resources.games_checkers
import org.meshtastic.core.resources.games_decline
import org.meshtastic.core.resources.games_forget
import org.meshtastic.core.resources.games_in_progress
import org.meshtastic.core.resources.games_invited_status
import org.meshtastic.core.resources.games_invited_title
import org.meshtastic.core.resources.games_no_sessions
import org.meshtastic.core.resources.games_new_challenge
import org.meshtastic.core.resources.games_play_black
import org.meshtastic.core.resources.games_play_white
import org.meshtastic.core.resources.games_result_black_wins
import org.meshtastic.core.resources.games_result_draw
import org.meshtastic.core.resources.games_result_white_wins
import org.meshtastic.core.resources.games_sessions
import org.meshtastic.core.resources.games_tictactoe
import org.meshtastic.core.resources.games_title
import org.meshtastic.core.resources.games_waiting_peer
import org.meshtastic.core.resources.games_your_turn
import org.meshtastic.core.ui.component.MainAppBar
import org.meshplay.games.session.GameSession
import org.meshplay.games.session.SessionStatus
import org.meshplay.protocol.GameKind
import org.meshplay.protocol.GameResult
import org.meshplay.protocol.Side

/**
 * The per-chat games hub: existing sessions on top, the new-challenge picker below. Challenges
 * always belong to the conversation they were opened from.
 */
@Composable
fun GamesPickerScreen(
    viewModel: GamesViewModel,
    onOpenGame: (Int) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.games_title),
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                actions = {},
                onClickChip = {},
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.games_sessions),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.games_no_sessions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sessions, key = { "${it.contactKey}:${it.sessionId}" }) { session ->
                SessionCard(
                    session = session,
                    onOpen = { onOpenGame(session.sessionId) },
                    onAccept = { viewModel.acceptChallenge(session) },
                    onDecline = { viewModel.declineChallenge(session) },
                    onForget = { viewModel.forgetSession(session) },
                )
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.games_new_challenge),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item { ChallengePicker(viewModel = viewModel, onStarted = onOpenGame) }
        }
    }
}

@Composable
private fun ChallengePicker(viewModel: GamesViewModel, onStarted: (Int) -> Unit) {
    var expandedKind by remember { mutableStateOf<GameKind?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GameKind.entries.forEach { kind ->
            Card(
                modifier =
                    Modifier.fillMaxWidth().clickable {
                        if (kind == GameKind.TIC_TAC_TOE) {
                            viewModel.startChallenge(kind, Side.WHITE)?.let(onStarted)
                        } else {
                            expandedKind = if (expandedKind == kind) null else kind
                        }
                    },
            ) {
                Text(
                    text = kindLabel(kind),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (expandedKind == kind) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        viewModel.startChallenge(kind, Side.WHITE)?.let(onStarted)
                    }) {
                        Text(stringResource(Res.string.games_play_white))
                    }
                    TextButton(onClick = {
                        viewModel.startChallenge(kind, Side.BLACK)?.let(onStarted)
                    }) {
                        Text(stringResource(Res.string.games_play_black))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: GameSession,
    onOpen: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onForget: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = kindLabel(session.kind),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusLabel(session),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.status == SessionStatus.INVITED) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onForget) {
                        Text(stringResource(Res.string.games_decline))
                    }
                    TextButton(onClick = onAccept) {
                        Text(stringResource(Res.string.games_accept))
                    }
                }
            }
            if (session.isFinished()) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onForget) {
                        Text(stringResource(Res.string.games_forget))
                    }
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: GameKind): String =
    when (kind) {
        GameKind.CHESS -> stringResource(Res.string.games_chess)
        GameKind.CHECKERS -> stringResource(Res.string.games_checkers)
        GameKind.TIC_TAC_TOE -> stringResource(Res.string.games_tictactoe)
    }

@Composable
private fun statusLabel(session: GameSession): String =
    when (session.status) {
        SessionStatus.PENDING -> stringResource(Res.string.games_waiting_peer)
        SessionStatus.INVITED -> stringResource(Res.string.games_invited_title, kindLabel(session.kind))
        SessionStatus.ACTIVE -> stringResource(Res.string.games_in_progress)
        SessionStatus.FINISHED -> resultLabel(session.result)
    }

@Composable
private fun resultLabel(result: GameResult?): String =
    when (result) {
        GameResult.WHITE_WINS -> stringResource(Res.string.games_result_white_wins)
        GameResult.BLACK_WINS -> stringResource(Res.string.games_result_black_wins)
        GameResult.DRAW -> stringResource(Res.string.games_result_draw)
        null -> stringResource(Res.string.games_invited_status)
    }
