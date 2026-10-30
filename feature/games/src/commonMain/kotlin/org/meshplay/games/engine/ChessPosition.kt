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

import org.meshplay.protocol.GameResult
import org.meshplay.protocol.Side
import kotlin.math.abs

/**
 * Immutable chess position with full legal-move generation (castling, en passant, promotions)
 * and standard FEN serialization, so a mesh snapshot can always restore a game after packet loss.
 *
 * Squares are `rank * 8 + file` with 0 = a1 … 63 = h8. White pieces are positive, black negative.
 * Draws covered: fifty-move rule and insufficient material. Threefold repetition is not tracked
 * (documented simplification — every legal position is also recoverable from the FEN snapshot).
 */
data class ChessPosition(
    val board: IntArray,
    val whiteToMove: Boolean,
    val castlingRights: Int,
    val enPassantTarget: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
    val history: List<String> = emptyList(),
) {
    val sideToMove: Side
        get() = if (whiteToMove) Side.WHITE else Side.BLACK

    /** Result once no more legal moves exist, or null while the game continues. */
    val result: GameResult?
        get() {
            if (halfmoveClock >= FIFTY_MOVE_CLOCK) return GameResult.DRAW
            if (insufficientMaterial()) return GameResult.DRAW
            if (legalMovesUci().isNotEmpty()) return null
            return if (whiteToMove) GameResult.BLACK_WINS else GameResult.WHITE_WINS
        }

    val isOver: Boolean
        get() = result != null

    fun lastMoveUci(): String? = history.lastOrNull()

    /** All legal moves in UCI notation (`e2e4`, `e7e8q`, castling `e1g1`/`e1c1`). */
    fun legalMovesUci(): List<String> = generateLegalMoves().map { it.uci }

    /** Applies a legal UCI move; returns null when [uci] is not legal here. */
    fun applyUci(uci: String): ChessPosition? {
        val move = generateLegalMoves().firstOrNull { it.uci == uci } ?: return null
        return apply(move)
    }

    /** Applies an already-validated move. */
    fun apply(move: ChessMove): ChessPosition {
        val next = board.copyOf()
        val piece = next[move.from]
        val captured = next[move.to]
        var rights = castlingRights
        var half = halfmoveClock + 1

        next[move.to] = piece
        next[move.from] = EMPTY

        // En passant: the captured pawn is beside the mover, not on the destination square.
        if (abs(piece) == PAWN && move.to == enPassantTarget && captured == EMPTY) {
            val capturedSq = if (piece == WHITE_PAWN) move.to - 8 else move.to + 8
            next[capturedSq] = EMPTY
            half = 0
        }
        if (captured != EMPTY) half = 0
        if (abs(piece) == PAWN) half = 0

        // Castling: the king moved two files; carry the rook along.
        if (abs(piece) == KING && kotlin.math.abs(fileOf(move.to) - fileOf(move.from)) == 2) {
            when (move.to) {
                G1 -> {
                    next[F1] = next[H1]
                    next[H1] = EMPTY
                }
                C1 -> {
                    next[D1] = next[A1]
                    next[A1] = EMPTY
                }
                G8 -> {
                    next[F8] = next[H8]
                    next[H8] = EMPTY
                }
                C8 -> {
                    next[D8] = next[A8]
                    next[A8] = EMPTY
                }
            }
        }

        // Promotion.
        if (move.promotion != null) {
            val color = if (piece > 0) 1 else -1
            next[move.to] = color * when (move.promotion) {
                'q' -> QUEEN
                'r' -> ROOK
                'b' -> BISHOP
                'n' -> KNIGHT
                else -> QUEEN
            }
        }

        // Castling rights follow the king and the rooks.
        if (abs(piece) == KING) {
            rights =
                if (piece == WHITE_KING) {
                    rights and (RIGHT_WK or RIGHT_WQ).inv()
                } else {
                    rights and (RIGHT_BK or RIGHT_BQ).inv()
                }
        }
        if (piece == WHITE_ROOK || piece == BLACK_ROOK) {
            rights = rights and rookRightBit(move.from).inv()
        }
        if (captured == WHITE_ROOK || captured == BLACK_ROOK) {
            rights = rights and rookRightBit(move.to).inv()
        }

        // En passant target for the next position.
        val nextEp =
            if (abs(piece) == PAWN && kotlin.math.abs(move.to - move.from) == 16) {
                (move.from + move.to) / 2
            } else {
                NO_SQUARE
            }

        return ChessPosition(
            board = next,
            whiteToMove = !whiteToMove,
            castlingRights = rights,
            enPassantTarget = nextEp,
            halfmoveClock = half,
            fullmoveNumber = if (whiteToMove) fullmoveNumber + 1 else fullmoveNumber,
            history = history + move.uci,
        )
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val piece = board[rank * 8 + file]
                if (piece == EMPTY) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(pieceChar(piece))
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        val rights = buildString {
            if (castlingRights and RIGHT_WK != 0) append('K')
            if (castlingRights and RIGHT_WQ != 0) append('Q')
            if (castlingRights and RIGHT_BK != 0) append('k')
            if (castlingRights and RIGHT_BQ != 0) append('q')
        }
        sb.append(if (rights.isEmpty()) "-" else rights)
        sb.append(' ')
        sb.append(if (enPassantTarget == NO_SQUARE) "-" else squareName(enPassantTarget))
        sb.append(' ')
        sb.append(halfmoveClock)
        sb.append(' ')
        sb.append(fullmoveNumber)
        return sb.toString()
    }

    private fun insufficientMaterial(): Boolean {
        val nonKings = board.filter { it != EMPTY && abs(it) != KING }
        if (nonKings.isEmpty()) return true
        // Only a single bishop or knight cannot force mate on their own; a pawn, rook or queen
        // (or several pieces) keeps the game alive.
        if (nonKings.size == 1 && (abs(nonKings[0]) == BISHOP || abs(nonKings[0]) == KNIGHT)) return true
        return false
    }

    private fun generateLegalMoves(): List<ChessMove> {
        val moverIsWhite = whiteToMove
        return generatePseudoLegal().filter { move ->
            val next = applyForLegality(move)
            // The mover's own king must be safe after the move.
            val kingSq = next.board.indexOf(if (moverIsWhite) WHITE_KING else BLACK_KING)
            kingSq >= 0 && !next.isAttacked(kingSq, byWhite = !moverIsWhite)
        }
    }

    /** Apply without history bookkeeping — only used inside legality filtering. */
    private fun applyForLegality(move: ChessMove): ChessPosition = apply(move).copy(history = history)

    private fun generatePseudoLegal(): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        val us = if (whiteToMove) 1 else -1
        for (sq in 0..63) {
            val piece = board[sq]
            if (piece == EMPTY || piece * us <= 0) continue
            when (abs(piece)) {
                PAWN -> generatePawnMoves(sq, moves)
                KNIGHT -> generateStepMoves(sq, KNIGHT_DELTAS, moves)
                KING -> generateKingMoves(sq, moves)
                BISHOP -> generateSlideMoves(sq, BISHOP_DIRS, moves)
                ROOK -> generateSlideMoves(sq, ROOK_DIRS, moves)
                QUEEN -> generateSlideMoves(sq, ROOK_DIRS + BISHOP_DIRS, moves)
            }
        }
        return moves
    }

    private fun generatePawnMoves(sq: Int, moves: MutableList<ChessMove>) {
        val white = whiteToMove
        val dir = if (white) 8 else -8
        val startRank = if (white) 1 else 6
        val promoRank = if (white) 7 else 0
        val one = sq + dir
        if (one in 0..63 && board[one] == EMPTY) {
            addPawnMove(sq, one, rankOf(one) == promoRank, moves)
            val two = sq + 2 * dir
            if (rankOf(sq) == startRank && board[two] == EMPTY) {
                moves.add(ChessMove(sq, two))
            }
        }
        for (delta in intArrayOf(dir - 1, dir + 1)) {
            val target = sq + delta
            if (target !in 0..63) continue
            if (kotlin.math.abs(fileOf(target) - fileOf(sq)) != 1) continue
            val occupant = board[target]
            val enemy = occupant != EMPTY && occupant * us() < 0
            if (enemy) {
                addPawnMove(sq, target, rankOf(target) == promoRank, moves)
            } else if (target == enPassantTarget && occupant == EMPTY) {
                moves.add(ChessMove(sq, target))
            }
        }
    }

    private fun addPawnMove(from: Int, to: Int, promotes: Boolean, moves: MutableList<ChessMove>) {
        if (promotes) {
            for (promo in charArrayOf('q', 'r', 'b', 'n')) {
                moves.add(ChessMove(from, to, promo))
            }
        } else {
            moves.add(ChessMove(from, to))
        }
    }

    private fun generateKingMoves(sq: Int, moves: MutableList<ChessMove>) {
        generateStepMoves(sq, KING_DELTAS, moves)
        // Castling.
        val white = whiteToMove
        val kingFrom = if (white) E1 else E8
        if (sq != kingFrom || isAttacked(sq, byWhite = !white)) return
        val enemy = !white
        val kingsideRight = if (white) RIGHT_WK else RIGHT_BK
        val queensideRight = if (white) RIGHT_WQ else RIGHT_BQ
        val rookK = if (white) H1 else H8
        val rookQ = if (white) A1 else A8
        val throughK = if (white) intArrayOf(F1, G1) else intArrayOf(F8, G8)
        val throughQ = if (white) intArrayOf(D1, C1, B1) else intArrayOf(D8, C8, B8)
        if (castlingRights and kingsideRight != 0 &&
            board[rookK] == rookPiece(white) &&
            throughK.all { board[it] == EMPTY } &&
            !isAttacked(throughK[0], enemy) &&
            !isAttacked(throughK[1], enemy)
        ) {
            moves.add(ChessMove(sq, throughK[1]))
        }
        if (castlingRights and queensideRight != 0 &&
            board[rookQ] == rookPiece(white) &&
            throughQ.all { board[it] == EMPTY } &&
            !isAttacked(throughQ[0], enemy) &&
            !isAttacked(throughQ[1], enemy)
        ) {
            moves.add(ChessMove(sq, throughQ[1]))
        }
    }

    private fun rookPiece(white: Boolean): Int = if (white) WHITE_ROOK else BLACK_ROOK

    private fun generateStepMoves(sq: Int, deltas: IntArray, moves: MutableList<ChessMove>) {
        for (delta in deltas) {
            val target = sq + delta
            if (target !in 0..63) continue
            if (kotlin.math.abs(fileOf(target) - fileOf(sq)) > 2) continue
            val occupant = board[target]
            if (occupant == EMPTY || occupant * us() < 0) moves.add(ChessMove(sq, target))
        }
    }

    private fun generateSlideMoves(sq: Int, dirs: IntArray, moves: MutableList<ChessMove>) {
        for (dir in dirs) {
            var target = sq + dir
            while (target in 0..63 && fileOf(target) - fileOf(target - dir) == fileStep(dir)) {
                val occupant = board[target]
                if (occupant == EMPTY) {
                    moves.add(ChessMove(sq, target))
                } else {
                    if (occupant * us() < 0) moves.add(ChessMove(sq, target))
                    break
                }
                target += dir
            }
        }
    }

    /** True when any piece of [byWhite] attacks [sq]. */
    fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
        val pawn = if (byWhite) WHITE_PAWN else BLACK_PAWN
        val pawnFrom = if (byWhite) intArrayOf(sq - 9, sq - 7) else intArrayOf(sq + 7, sq + 9)
        for (from in pawnFrom) {
            if (from in 0..63 && board[from] == pawn && kotlin.math.abs(fileOf(from) - fileOf(sq)) == 1) {
                return true
            }
        }
        val knight = if (byWhite) WHITE_KNIGHT else BLACK_KNIGHT
        for (delta in KNIGHT_DELTAS) {
            val from = sq + delta
            if (from in 0..63 && board[from] == knight && kotlin.math.abs(fileOf(from) - fileOf(sq)) <= 2) {
                return true
            }
        }
        val king = if (byWhite) WHITE_KING else BLACK_KING
        for (delta in KING_DELTAS) {
            val from = sq + delta
            if (from in 0..63 && board[from] == king && kotlin.math.abs(fileOf(from) - fileOf(sq)) <= 1) {
                return true
            }
        }
        val bishop = if (byWhite) WHITE_BISHOP else BLACK_BISHOP
        val rook = if (byWhite) WHITE_ROOK else BLACK_ROOK
        val queen = if (byWhite) WHITE_QUEEN else BLACK_QUEEN
        for (dir in ROOK_DIRS) {
            var from = sq + dir
            while (from in 0..63 && fileOf(from) - fileOf(from - dir) == fileStep(dir)) {
                val occupant = board[from]
                if (occupant != EMPTY) {
                    if (occupant == rook || occupant == queen) return true
                    break
                }
                from += dir
            }
        }
        for (dir in BISHOP_DIRS) {
            var from = sq + dir
            while (from in 0..63 && fileOf(from) - fileOf(from - dir) == fileStep(dir)) {
                val occupant = board[from]
                if (occupant != EMPTY) {
                    if (occupant == bishop || occupant == queen) return true
                    break
                }
                from += dir
            }
        }
        return false
    }

    private fun us(): Int = if (whiteToMove) 1 else -1

    private fun rookRightBit(square: Int): Int =
        when (square) {
            A1 -> RIGHT_WQ
            H1 -> RIGHT_WK
            A8 -> RIGHT_BQ
            H8 -> RIGHT_BK
            else -> 0
        }

    override fun equals(other: Any?): Boolean =
        other is ChessPosition &&
            board.contentEquals(other.board) &&
            whiteToMove == other.whiteToMove &&
            castlingRights == other.castlingRights &&
            enPassantTarget == other.enPassantTarget &&
            halfmoveClock == other.halfmoveClock &&
            fullmoveNumber == other.fullmoveNumber &&
            history == other.history

    override fun hashCode(): Int {
        var result = board.contentHashCode()
        result = 31 * result + whiteToMove.hashCode()
        result = 31 * result + castlingRights
        result = 31 * result + enPassantTarget
        result = 31 * result + halfmoveClock
        result = 31 * result + fullmoveNumber
        result = 31 * result + history.hashCode()
        return result
    }

    companion object {
        const val EMPTY = 0
        const val PAWN = 1
        const val KNIGHT = 2
        const val BISHOP = 3
        const val ROOK = 4
        const val QUEEN = 5
        const val KING = 6

        const val WHITE_PAWN = PAWN
        const val WHITE_KNIGHT = KNIGHT
        const val WHITE_BISHOP = BISHOP
        const val WHITE_ROOK = ROOK
        const val WHITE_QUEEN = QUEEN
        const val WHITE_KING = KING
        const val BLACK_PAWN = -PAWN
        const val BLACK_KNIGHT = -KNIGHT
        const val BLACK_BISHOP = -BISHOP
        const val BLACK_ROOK = -ROOK
        const val BLACK_QUEEN = -QUEEN
        const val BLACK_KING = -KING

        const val RIGHT_WK = 1
        const val RIGHT_WQ = 2
        const val RIGHT_BK = 4
        const val RIGHT_BQ = 8

        const val NO_SQUARE = -1
        private const val FIFTY_MOVE_CLOCK = 100

        const val A1 = 0
        const val C1 = 2
        const val D1 = 3
        const val E1 = 4
        const val F1 = 5
        const val G1 = 6
        const val H1 = 7
        const val A8 = 56
        const val B1 = 1
        const val B8 = 57
        const val C8 = 58
        const val D8 = 59
        const val E8 = 60
        const val F8 = 61
        const val G8 = 62
        const val H8 = 63

        private val KNIGHT_DELTAS = intArrayOf(6, 10, 15, 17, -6, -10, -15, -17)
        private val KING_DELTAS = intArrayOf(1, -1, 8, -8, 7, 9, -7, -9)
        private val ROOK_DIRS = intArrayOf(1, -1, 8, -8)
        private val BISHOP_DIRS = intArrayOf(7, 9, -7, -9)

        const val INITIAL_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun initial(): ChessPosition = fromFen(INITIAL_FEN)!!.copy(history = emptyList())

        /** Parses a FEN; returns null when [fen] is not a well-formed position. */
        fun fromFen(fen: String, history: List<String> = emptyList()): ChessPosition? {
            val parts = fen.trim().split(' ')
            if (parts.size < 4) return null
            val rows = parts[0].split('/')
            if (rows.size != 8) return null
            val board = IntArray(64)
            for ((i, row) in rows.withIndex()) {
                val rank = 7 - i
                var file = 0
                for (c in row) {
                    when {
                        c.isDigit() -> file += c.digitToInt()
                        pieceFromChar(c) != null -> {
                            if (file > 7) return null
                            board[rank * 8 + file] = pieceFromChar(c) ?: EMPTY
                            file++
                        }
                        else -> return null
                    }
                }
                if (file != 8) return null
            }
            val whiteToMove = when (parts[1]) {
                "w" -> true
                "b" -> false
                else -> return null
            }
            var rights = 0
            if (parts[2] != "-") {
                for (c in parts[2]) {
                    rights = rights or when (c) {
                        'K' -> RIGHT_WK
                        'Q' -> RIGHT_WQ
                        'k' -> RIGHT_BK
                        'q' -> RIGHT_BQ
                        else -> return null
                    }
                }
            }
            val ep = if (parts[3] == "-") NO_SQUARE else squareFromName(parts[3]) ?: return null
            val half = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val full = parts.getOrNull(5)?.toIntOrNull() ?: 1
            if (half < 0 || full < 1) return null
            return ChessPosition(board, whiteToMove, rights, ep, half, full, history)
        }

        fun pieceFromChar(c: Char): Int? =
            when (c.lowercaseChar()) {
                'p' -> PAWN
                'n' -> KNIGHT
                'b' -> BISHOP
                'r' -> ROOK
                'q' -> QUEEN
                'k' -> KING
                else -> null
            }.let { it?.let { piece -> if (c.isUpperCase()) piece else -piece } }

        fun pieceChar(piece: Int): Char {
            val c =
                when (kotlin.math.abs(piece)) {
                    PAWN -> 'p'
                    KNIGHT -> 'n'
                    BISHOP -> 'b'
                    ROOK -> 'r'
                    QUEEN -> 'q'
                    KING -> 'k'
                    else -> '?'
                }
            return if (piece > 0) c.uppercaseChar() else c
        }

        fun squareName(sq: Int): String = "${'a' + fileOf(sq)}${rankOf(sq) + 1}"

        fun squareFromName(name: String): Int? {
            if (name.length != 2) return null
            val file = name[0] - 'a'
            val rank = name[1] - '1'
            if (file !in 0..7 || rank !in 0..7) return null
            return rank * 8 + file
        }

        fun fileOf(sq: Int): Int = sq and 7

        /** File delta of one step along [dir]; guards the ray walks against board wrap. */
        private fun fileStep(dir: Int): Int = when (dir) {
            1, 9, -7 -> 1
            -1, -9, 7 -> -1
            else -> 0
        }

        fun rankOf(sq: Int): Int = sq shr 3
    }
}

/** A single chess move between squares with an optional promotion piece. */
data class ChessMove(val from: Int, val to: Int, val promotion: Char? = null) {
    val uci: String
        get() {
            val base = ChessPosition.squareName(from) + ChessPosition.squareName(to)
            return if (promotion != null) base + promotion else base
        }
}
