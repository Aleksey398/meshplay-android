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

/**
 * Encoder/decoder for the MeshPlay game wire format.
 *
 * Wire format (one text message = one game event):
 *
 * ```
 * [MG1|<KIND>|<TYPE>|<PAYLOAD>|<SESSION>]
 * ```
 *
 * - `MG1` — MeshGame v1 tag. Unknown tags must be treated as plain text.
 * - `KIND` — [GameKind.wireTag]: `CH`, `CK` or `TT`.
 * - `TYPE` — `N` new game, `M` move, `S` snapshot, `R` request, `E` end/result.
 * - `PAYLOAD` — type-specific, `-` when empty (the `|` separator itself cannot carry an empty field).
 * - `SESSION` — decimal session id, 0–99999.
 *
 * Payload conventions: move = `<seq>:<move>`; snapshot = `<seq>:<state>` with spaces in the state
 * replaced by `_`; request/result = a single wire char. States and moves never contain `|`, `:`
 * or whitespace, which keeps parsing unambiguous without escaping.
 *
 * Messages longer than [MAX_ENCODED_BYTES] (the conservative client-side text limit) are refused:
 * [encode] throws [GameMessageTooLongException] rather than producing a packet the radio or other
 * clients would truncate mid-tag.
 */
object GameMessageCodec {
    /** Version tag of the wire format. */
    const val TAG = "MG1"

    /**
     * The conservative per-message text budget used by mesh clients (~200 bytes of the 233-byte
     * packet payload). Encoded game messages must fit inside it.
     */
    const val MAX_ENCODED_BYTES = 200

    /** Prefix every game message starts with; useful for filtering chat logs. */
    const val PREFIX = "[$TAG|"

    /** Placeholder for an empty payload field (empty fields would collapse the separators). */
    private const val EMPTY_FIELD = "-"

    private const val FIELD_COUNT = 5

    private const val TYPE_NEW = 'N'
    private const val TYPE_MOVE = 'M'
    private const val TYPE_SNAPSHOT = 'S'
    private const val TYPE_REQUEST = 'R'
    private const val TYPE_RESULT = 'E'

    private const val MAX_SESSION_ID = 99_999

    /** True when [text] is (even plausibly) a game message. Cheap pre-filter before [decode]. */
    fun looksLikeGameMessage(text: String): Boolean = text.startsWith(PREFIX)

    /** Serialize [message] to its wire form. */
    @Throws(GameMessageTooLongException::class)
    fun encode(message: GameMessage): String {
        val kind = message.kind.wireTag
        val session = message.sessionId
        require(session in 0..MAX_SESSION_ID) { "session id out of range: $session" }
        val body =
            when (message) {
                is GameMessage.NewGame -> "$TYPE_NEW|${message.challengerSide.wireChar}"
                is GameMessage.Move -> "$TYPE_MOVE|${message.seq}:${message.move}"
                is GameMessage.Snapshot -> "$TYPE_SNAPSHOT|${message.seq}:${message.state.replace(' ', '_')}"
                is GameMessage.Request -> "$TYPE_REQUEST|${message.request.wireChar}"
                is GameMessage.Result -> "$TYPE_RESULT|${message.result.wireChar}"
            }
        val payload = body.substringAfter('|')
        val type = body.substringBefore('|')
        val encoded = "[$TAG|$kind|$type|$payload|$session]"
        if (encoded.encodeToByteArray().size > MAX_ENCODED_BYTES) {
            throw GameMessageTooLongException(encoded.encodeToByteArray().size)
        }
        return encoded
    }

    /**
     * Parse a wire message. Returns null for anything that is not a well-formed `[MG1|...]`
     * message — callers then display the text as an ordinary chat message.
     */
    fun decode(text: String): GameMessage? {
        if (!looksLikeGameMessage(text)) return null
        if (!text.endsWith("]")) return null
        val inner = text.substring(PREFIX.length, text.length - 1)
        val fields = inner.split('|')
        if (fields.size != FIELD_COUNT) return null
        val kind = GameKind.fromWireTag(fields[0]) ?: return null
        val sessionId = fields[4].toIntOrNull() ?: return null
        if (sessionId !in 0..MAX_SESSION_ID) return null
        val type = fields[2]
        if (type.isEmpty()) return null
        return when (type[0]) {
            TYPE_NEW -> decodeNewGame(kind, sessionId, fields[3])
            TYPE_MOVE -> decodeMove(kind, sessionId, fields[3])
            TYPE_SNAPSHOT -> decodeSnapshot(kind, sessionId, fields[3])
            TYPE_REQUEST -> decodeRequest(kind, sessionId, fields[3])
            TYPE_RESULT -> decodeResult(kind, sessionId, fields[3])
            else -> null
        }
    }

    /** Decode or null, for call sites that only want a filter. */
    fun decodeOrNull(text: String): GameMessage? = decode(text)

    private fun decodeNewGame(kind: GameKind, sessionId: Int, payload: String): GameMessage? {
        val side = payload.singleOrNull()?.let(Side::fromWireChar) ?: return null
        return GameMessage.NewGame(kind, sessionId, side)
    }

    private fun decodeMove(kind: GameKind, sessionId: Int, payload: String): GameMessage? {
        val sep = payload.indexOf(':')
        if (sep <= 0 || sep == payload.lastIndex) return null
        val seq = payload.substring(0, sep).toIntOrNull() ?: return null
        val move = payload.substring(sep + 1)
        if (seq !in 0..MAX_SESSION_ID || !isWireToken(move)) return null
        return GameMessage.Move(kind, sessionId, seq, move)
    }

    private fun decodeSnapshot(kind: GameKind, sessionId: Int, payload: String): GameMessage? {
        val sep = payload.indexOf(':')
        if (sep <= 0 || sep == payload.lastIndex) return null
        val seq = payload.substring(0, sep).toIntOrNull() ?: return null
        val state = payload.substring(sep + 1).replace('_', ' ')
        if (seq !in 0..MAX_SESSION_ID || state.isEmpty()) return null
        return GameMessage.Snapshot(kind, sessionId, seq, state)
    }

    private fun decodeRequest(kind: GameKind, sessionId: Int, payload: String): GameMessage? {
        val request = payload.singleOrNull()?.let(GameRequest::fromWireChar) ?: return null
        return GameMessage.Request(kind, sessionId, request)
    }

    private fun decodeResult(kind: GameKind, sessionId: Int, payload: String): GameMessage? {
        val result = payload.singleOrNull()?.let(GameResult::fromWireChar) ?: return null
        return GameMessage.Result(kind, sessionId, result)
    }

    /** Wire tokens are short and free of separators/whitespace; anything else is not our message. */
    private fun isWireToken(token: String): Boolean =
        token.isNotEmpty() &&
            token.length <= MAX_ENCODED_BYTES &&
            token.all { it.isLetterOrDigit() || it in "-+=/" }
}

/** Thrown when an encoded game message would exceed [GameMessageCodec.MAX_ENCODED_BYTES]. */
class GameMessageTooLongException(val encodedBytes: Int) :
    IllegalArgumentException("encoded game message is $encodedBytes bytes (limit ${GameMessageCodec.MAX_ENCODED_BYTES})")
