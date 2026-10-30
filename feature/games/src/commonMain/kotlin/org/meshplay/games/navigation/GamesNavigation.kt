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
package org.meshplay.games.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.navigation.GamesRoute
import org.meshplay.games.GameScreen
import org.meshplay.games.GameViewModel
import org.meshplay.games.GamesPickerScreen
import org.meshplay.games.GamesViewModel

/**
 * Game destinations. Opened from the conversation screen's overflow menu; the contact key rides
 * along so every session stays bound to the chat it was created in.
 */
fun EntryProviderScope<NavKey>.gamesGraph(
    backStack: NavBackStack<NavKey>,
) {
    entry<GamesRoute.Pick> { args ->
        val viewModel: GamesViewModel = koinViewModel(key = "games-${args.contactKey}")
        viewModel.setContactKey(args.contactKey)
        GamesPickerContent(
            viewModel = viewModel,
            onOpenGame = { sessionId ->
                backStack.add(GamesRoute.Game(args.contactKey, sessionId))
            },
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
        )
    }

    entry<GamesRoute.Game> { args ->
        val viewModel: GameViewModel = koinViewModel(key = "game-${args.contactKey}-${args.sessionId}")
        viewModel.setSession(args.contactKey, args.sessionId)
        GameContent(
            viewModel = viewModel,
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
        )
    }
}

@Composable
@NonRestartableComposable
private fun GamesPickerContent(
    viewModel: GamesViewModel,
    onOpenGame: (Int) -> Unit,
    onNavigateUp: () -> Unit,
) {
    GamesPickerScreen(viewModel = viewModel, onOpenGame = onOpenGame, onNavigateUp = onNavigateUp)
}

@Composable
@NonRestartableComposable
private fun GameContent(
    viewModel: GameViewModel,
    onNavigateUp: () -> Unit,
) {
    GameScreen(viewModel = viewModel, onNavigateUp = onNavigateUp)
}
