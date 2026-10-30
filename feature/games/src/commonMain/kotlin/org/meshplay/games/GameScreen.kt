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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import org.meshtastic.core.resources.games_accept_draw
import org.meshtastic.core.resources.games_chess
import org.meshtastic.core.resources.games_checkers
import org.meshtastic.core.resources.games_decline
import org.meshtastic.core.resources.games_moves_label
import org.meshtastic.core.resources.games_offer_draw
import org.meshtastic.core.resources.games_peer_offered_draw
import org.meshtastic.core.resources.games_peer_turn
import org.meshtastic.core.resources.games_play_black
import org.meshtastic.core.resources.games_play_white
import org.meshtastic.core.resources.games_play_x
import org.meshtastic.core.resources.games_promotion_title
import org.meshtastic.core.resources.games_resign
import org.meshtastic.core.resources.games_result_black_wins
import org.meshtastic.core.resources.games_result_draw
import org.meshtastic.core.resources.games_result_white_wins
import org.meshtastic.core.resources.games_tictactoe
import org.meshtastic.core.resources.games_title
import org.meshtastic.core.resources.games_waiting_peer
import org.meshtastic.core.resources.games_your_turn
import org.meshtastic.core.ui.component.MainAppBar
import org.meshplay.games.component.ChessBoard
import org.meshplay.games.component.CheckersBoard
import org.meshplay.games.component.TicTacToeBoard
import org.meshplay.games.component.TicTacToeCells
import org.meshplay.games.engine.ChessEngine
import org.meshplay.games.engine.CheckersEngine
import org.meshplay.games.engine.TicTacToeEngine
import org.meshplay.games.session.SessionStatus
import org.meshplay.protocol.GameKind
import org.meshplay.protocol.GameResult
import org.meshplay.protocol.Side

/** The game table: board, status, actions, and move history for one session. */
@Composable
fun GameScreen(
    viewModel: GameViewModel,
    onNavigateUp: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
        val ui = state
        if (ui == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.games_waiting_peer),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = gameKindLabel(ui.session.kind),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = sideLabel(ui.session.mySide, ui.session.kind),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText(ui),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                ui.session.status == SessionStatus.INVITED -> InvitationPanel(
                    onAccept = viewModel::acceptInvite,
                    onDecline = viewModel::declineInvite,
                )
                else -> {
                    BoardForState(ui = ui, onMove = viewModel::onMoveTried)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (ui.session.status == SessionStatus.ACTIVE) {
                        ActionRow(ui = ui, viewModel = viewModel)
                    }
                    if (ui.session.drawOfferFromPeer && ui.session.status == SessionStatus.ACTIVE) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.games_peer_offered_draw),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { viewModel.declineDraw() }) {
                                Text(stringResource(Res.string.games_decline))
                            }
                            TextButton(onClick = { viewModel.acceptDraw() }) {
                                Text(stringResource(Res.string.games_accept_draw))
                            }
                        }
                    }
                    ui.session.moves.takeIf { it.isNotEmpty() }?.let { moves ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(Res.string.games_moves_label),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val rendered =
                            moves
                                .foldIndexed(StringBuilder()) { index, acc, move ->
                                    if (index % 2 == 0) {
                                        if (index > 0) acc.append('\n')
                                        acc.append("${index / 2 + 1}. ")
                                    } else {
                                        acc.append(' ')
                                    }
                                    acc.append(move)
                                }
                                .toString()
                        Text(
                            text = rendered,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        ui.pendingPromotion?.let { pending ->
            AlertDialog(
                onDismissRequest = viewModel::onPromotionDismissed,
                title = { Text(stringResource(Res.string.games_promotion_title)) },
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (piece in charArrayOf('q', 'r', 'b', 'n')) {
                            TextButton(onClick = { viewModel.onPromotionChosen(piece) }) {
                                Text(piece.uppercaseChar().toString(), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {},
            )
        }
    }
}

@Composable
private fun InvitationPanel(onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onDecline) {
            Text(stringResource(Res.string.games_decline))
        }
        TextButton(onClick = onAccept) {
            Text(stringResource(Res.string.games_accept))
        }
    }
}

@Composable
private fun ActionRow(ui: GameUiState, viewModel: GameViewModel) {
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = viewModel::offerDraw, enabled = ui.myTurn) {
            Text(stringResource(Res.string.games_offer_draw))
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = viewModel::resign) {
            Text(stringResource(Res.string.games_resign))
        }
    }
}

@Composable
private fun BoardForState(ui: GameUiState, onMove: (String) -> Unit) {
    when (val engine = ui.engine) {
        is ChessEngine ->
            ChessBoard(
                position = engine.position,
                orientation = ui.session.mySide,
                legalMoves = ui.legalMoves,
                onMove = onMove,
            )
        is CheckersEngine ->
            CheckersBoard(
                position = engine.position,
                orientation = ui.session.mySide,
                legalMoves = ui.legalMoves,
                onMove = onMove,
            )
        is TicTacToeEngine ->
            TicTacToeBoard(
                cells = TicTacToeCells(engine.position),
                enabled = ui.myTurn,
                onCell = { cell -> onMove(cell.toString()) },
            )
        null -> {}
    }
}

@Composable
private fun gameKindLabel(kind: GameKind): String =
    when (kind) {
        GameKind.CHESS -> stringResource(Res.string.games_chess)
        GameKind.CHECKERS -> stringResource(Res.string.games_checkers)
        GameKind.TIC_TAC_TOE -> stringResource(Res.string.games_tictactoe)
    }

@Composable
private fun sideLabel(side: Side, kind: GameKind): String =
    when {
        kind == GameKind.TIC_TAC_TOE && side == Side.WHITE -> stringResource(Res.string.games_play_x)
        kind == GameKind.TIC_TAC_TOE -> stringResource(Res.string.games_you_are_o)
        side == Side.WHITE -> stringResource(Res.string.games_play_white)
        else -> stringResource(Res.string.games_play_black)
    }

@Composable
private fun statusText(ui: GameUiState): String =
    when (ui.session.status) {
        SessionStatus.PENDING -> stringResource(Res.string.games_waiting_peer)
        SessionStatus.INVITED -> stringResource(Res.string.games_waiting_peer)
        SessionStatus.ACTIVE ->
            if (ui.myTurn) {
                stringResource(Res.string.games_your_turn)
            } else {
                stringResource(Res.string.games_peer_turn)
            }
        SessionStatus.FINISHED ->
            when (ui.session.result) {
                GameResult.WHITE_WINS -> stringResource(Res.string.games_result_white_wins)
                GameResult.BLACK_WINS -> stringResource(Res.string.games_result_black_wins)
                GameResult.DRAW -> stringResource(Res.string.games_result_draw)
                null -> stringResource(Res.string.games_title)
            }
    }
