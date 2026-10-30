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
package org.meshplay.games.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshplay.games.engine.ChessPosition
import org.meshplay.games.engine.CheckersPosition
import org.meshplay.protocol.Side

/**
 * Shared tap-to-move interaction for the 8×8 boards: the first tap selects a piece (or extends a
 * multi-capture path), the second tap moves. Callbacks receive wire notation.
 */
internal class BoardSelection {
    var path: String by mutableStateOf("")

    fun onSquareTapped(
        square: String,
        legalMoves: List<String>,
        onMove: (String) -> Unit,
    ) {
        if (path.isEmpty()) {
            if (legalMoves.any { it.startsWith(square) }) {
                path = square
            }
            return
        }
        val attempt = path + square
        when {
            legalMoves.contains(attempt) -> {
                path = ""
                onMove(attempt)
            }
            legalMoves.any { it.startsWith(attempt) } -> path = attempt
            legalMoves.any { it.startsWith(square) } -> path = square
            else -> path = ""
        }
    }
}

/** Maps board squares to screen cells, flipping the board for the black side. */
private fun screenCell(sq: Int, orientation: Side): Pair<Int, Int> {
    val file = ChessPosition.fileOf(sq)
    val rank = ChessPosition.rankOf(sq)
    return if (orientation == Side.WHITE) {
        file to (7 - rank)
    } else {
        (7 - file) to rank
    }
}

/** Square under a tap at canvas [x]/[y], honoring [orientation]. */
private fun tappedSquare(x: Float, y: Float, canvasSize: Float, orientation: Side): String {
    val file = ((x / canvasSize) * 8).toInt().coerceIn(0, 7)
    val rankFromTop = ((y / canvasSize) * 8).toInt().coerceIn(0, 7)
    val rank = if (orientation == Side.WHITE) 7 - rankFromTop else rankFromTop
    val actualFile = if (orientation == Side.WHITE) file else 7 - file
    return ChessPosition.squareName(rank * 8 + actualFile)
}

/** Tic-tac-toe grid; [cells] uses engine encoding (1 = X, -1 = O, 0 = empty). */
@Composable
fun TicTacToeBoard(
    cells: IntArray,
    enabled: Boolean,
    onCell: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().aspectRatio(1f),
        verticalArrangement = Arrangement.Center,
    ) {
        for (row in 0..2) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0..2) {
                    val index = row * 3 + col
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .pointerInput(enabled) {
                                    detectTapGestures {
                                        if (enabled && cells[index] == 0) onCell(index + 1)
                                    }
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = cells[index].let { if (it > 0) "✕" else if (it < 0) "◯" else "" },
                            style = MaterialTheme.typography.displayMedium,
                            color =
                                if (cells[index] > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Draughts board with filled discs; kings get a center ring. */
@Composable
fun CheckersBoard(
    position: CheckersPosition,
    orientation: Side,
    legalMoves: List<String>,
    onMove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = remember { BoardSelection() }
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    val dark = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val target = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(legalMoves, orientation) {
                    detectTapGestures { offset ->
                        val square = tappedSquare(offset.x, offset.y, size.width.toFloat(), orientation)
                        selection.onSquareTapped(square, legalMoves, onMove)
                    }
                },
    ) {
        val cell = size.width / 8
        for (sq in 0..63) {
            val (col, row) = screenCell(sq, orientation)
            val lightSquare = (ChessPosition.fileOf(sq) + ChessPosition.rankOf(sq)) % 2 == 1
            drawRect(
                color = if (lightSquare) light else dark,
                topLeft = Offset(col * cell, row * cell),
                size = Size(cell, cell),
            )
            val square = ChessPosition.squareName(sq)
            if (selection.path.isNotEmpty() && square == selection.path.substring(0, 2)) {
                drawRect(
                    color = highlight,
                    topLeft = Offset(col * cell, row * cell),
                    size = Size(cell, cell),
                )
            }
            val piece = position.board[sq]
            if (piece != CheckersPosition.EMPTY) {
                val cx = col * cell + cell / 2
                val cy = row * cell + cell / 2
                val radius = cell * 0.34f
                val isWhite = piece > 0
                val fill = if (isWhite) Color.White else Color(0xFF2B2F3A)
                val edge = if (isWhite) Color(0xFFB9BEC9) else Color.Black
                drawCircle(color = fill, radius = radius, center = Offset(cx, cy))
                drawCircle(
                    color = edge,
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(width = cell * 0.05f),
                )
                if (kotlin.math.abs(piece) == CheckersPosition.WHITE_KING) {
                    drawCircle(
                        color = if (isWhite) Color(0xFF2B2F3A) else Color.White,
                        radius = radius * 0.45f,
                        center = Offset(cx, cy),
                        style = Stroke(width = cell * 0.06f),
                    )
                }
            }
        }
        drawSelectionMarkers(cell = cell, selection = selection, legalMoves = legalMoves, orientation = orientation, highlight = highlight, target = target)
    }
}

/** Chess board with Unicode pieces. */
@Composable
fun ChessBoard(
    position: ChessPosition,
    orientation: Side,
    legalMoves: List<String>,
    onMove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = remember { BoardSelection() }
    val light = MaterialTheme.colorScheme.surfaceContainerLowest
    val dark = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val target = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val lastMoveColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)
    val textMeasurer = rememberTextMeasurer()
    val pieceStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(legalMoves, orientation) {
                    detectTapGestures { offset ->
                        val square = tappedSquare(offset.x, offset.y, size.width.toFloat(), orientation)
                        selection.onSquareTapped(square, legalMoves, onMove)
                    }
                },
    ) {
        val cell = size.width / 8
        val lastUci = position.lastMoveUci()
        val lastFrom = lastUci?.substring(0, 2)
        val lastTo = lastUci?.substring(2, 4)
        for (sq in 0..63) {
            val (col, row) = screenCell(sq, orientation)
            val square = ChessPosition.squareName(sq)
            val lightSquare = (ChessPosition.fileOf(sq) + ChessPosition.rankOf(sq)) % 2 == 1
            drawRect(
                color = if (lightSquare) light else dark,
                topLeft = Offset(col * cell, row * cell),
                size = Size(cell, cell),
            )
            if (square == lastFrom || square == lastTo) {
                drawRect(
                    color = lastMoveColor,
                    topLeft = Offset(col * cell, row * cell),
                    size = Size(cell, cell),
                )
            }
            if (selection.path.isNotEmpty() && square == selection.path.substring(0, 2)) {
                drawRect(
                    color = highlight,
                    topLeft = Offset(col * cell, row * cell),
                    size = Size(cell, cell),
                )
            }
            val piece = position.board[sq]
            if (piece != ChessPosition.EMPTY) {
                val glyph = pieceGlyph(piece)
                val layout = textMeasurer.measure(glyph, pieceStyle)
                val topLeft =
                    Offset(
                        col * cell + (cell - layout.size.width) / 2,
                        row * cell + (cell - layout.size.height) / 2,
                    )
                if (piece > 0) {
                    // Light pieces get a dark drop shadow so they read on both square colors.
                    drawText(layout, topLeft = Offset(topLeft.x + 1.5f, topLeft.y + 1.5f), color = Color(0xFF1C1F26))
                    drawText(layout, topLeft = topLeft, color = Color(0xFFF8F9FB))
                } else {
                    drawText(layout, topLeft = topLeft, color = Color(0xFF1C1F26))
                }
            }
        }
        drawSelectionMarkers(cell = cell, selection = selection, legalMoves = legalMoves, orientation = orientation, highlight = highlight, target = target)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionMarkers(
    cell: Float,
    selection: BoardSelection,
    legalMoves: List<String>,
    orientation: Side,
    highlight: Color,
    target: Color,
) {
    if (selection.path.isEmpty()) return
    val from = selection.path.substring(0, 2)
    val targets =
        legalMoves
            .filter { it.startsWith(selection.path) && it.length > selection.path.length }
            .map { it.substring(selection.path.length, selection.path.length + 2) }
            .distinct()
    for (targetSquare in targets + from) {
        val sq = ChessPosition.squareFromName(targetSquare) ?: continue
        val (col, row) = screenCell(sq, orientation)
        val cx = col * cell + cell / 2
        val cy = row * cell + cell / 2
        if (targetSquare == from) {
            drawCircle(
                color = highlight,
                radius = cell * 0.42f,
                center = Offset(cx, cy),
                style = Stroke(width = cell * 0.07f),
            )
        } else {
            drawCircle(color = target, radius = cell * 0.14f, center = Offset(cx, cy))
        }
    }
}

private fun pieceGlyph(piece: Int): String =
    when (piece) {
        ChessPosition.WHITE_KING -> "♔"
        ChessPosition.WHITE_QUEEN -> "♕"
        ChessPosition.WHITE_ROOK -> "♖"
        ChessPosition.WHITE_BISHOP -> "♗"
        ChessPosition.WHITE_KNIGHT -> "♘"
        ChessPosition.WHITE_PAWN -> "♙"
        ChessPosition.BLACK_KING -> "♚"
        ChessPosition.BLACK_QUEEN -> "♛"
        ChessPosition.BLACK_ROOK -> "♜"
        ChessPosition.BLACK_BISHOP -> "♝"
        ChessPosition.BLACK_KNIGHT -> "♞"
        ChessPosition.BLACK_PAWN -> "♟"
        else -> ""
    }

/** Board cell values for the tic-tac-toe engine's position encoding. */
fun TicTacToeCells(position: org.meshplay.games.engine.TicTacToePosition): IntArray =
    IntArray(9) { position.cellAt(it + 1) }
