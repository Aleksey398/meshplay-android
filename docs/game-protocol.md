# MeshPlay game protocol (MG1)

Game traffic rides the ordinary text channel (`TEXT_MESSAGE_APP`, PortNum 1) as a single tagged
line. Clients that do not know the tag display it as a normal message; nothing about the firmware
or the protobuf schema changes.

## Wire format

```
[MG1|<KIND>|<TYPE>|<PAYLOAD>|<SESSION>]
```

| Field | Values | Meaning |
|---|---|---|
| `MG1` | fixed | MeshGame format version 1 |
| `KIND` | `CH` chess · `CK` checkers · `TT` tic-tac-toe | game kind |
| `TYPE` | `N` new · `M` move · `S` snapshot · `R` request · `E` end | event type |
| `PAYLOAD` | type-specific, `-` when empty | see below |
| `SESSION` | `0`–`99999` | session id, chosen by the challenger |

Rules for parsers:

- Messages longer than **200 bytes** (UTF-8) are never sent — clients already truncate mesh text
  near that figure, and a half-cut tag would leak into the chat as garbage.
- Payloads never contain `|`, `:` or whitespace; snapshot states replace spaces with `_`.
- Anything that does not parse cleanly (`[MG2|…]`, unknown kind, bad field count, …) must be
  treated as **plain chat text**. Unknown tags are forward-compatible by design.

## Event payloads

### `N` — new game / challenge

`PAYLOAD` is the **challenger's side**: `w` (white / X) or `b` (black / O).

```
[MG1|CH|N|w|123]     I challenge you to chess, I play White, session 123
[MG1|TT|N|w|789]     Tic-tac-toe challenge (challenger is X)
```

The peer accepts by sending the initial snapshot (`S` with `seq 0`) or simply ignores the
challenge. Declining sends nothing.

### `M` — move

`PAYLOAD` is `<seq>:<move>`. `seq` counts applied moves and starts at 1. A receiver applies a
move only when `seq == current + 1`; duplicates are dropped and gaps wait for the next snapshot.

Move notation:

- **Chess** — UCI: `e2e4`, captures indistinguishable from quiet moves, castling as `e1g1` /
  `e1c1` (black `e8g8` / `e8c8`), promotions with a piece letter: `e7e8q`.
- **Checkers** — `from` plus landing squares: `e2e4` quiet, `e2c3e5` for a double jump.
- **Tic-tac-toe** — cell number `1`–`9`, row-major from the top left.

```
[MG1|CH|M|12:e2e4|123]
[MG1|TT|M|3:5|789]
```

### `S` — snapshot

`PAYLOAD` is `<seq>:<state>`:

- **Chess** — standard FEN with spaces replaced by `_`:
  `[MG1|CH|S|0:rnbqkbnr_pppppppp_8/8/8/8/PPPPPPPP/RNBQKBNR_w_KQkq_-_0_1|123]`
  (the first snapshot of a game carries the initial position at `seq 0`).
- **Checkers** — 64-char board string (rank 8 first, `.`/`w`/`W`/`b`/`B`) plus the mover:
  `[MG1|CK|S|5:.b.b.b.b b.b.b.b./…|123]`.
- **Tic-tac-toe** — 9-char board of `.`/`X`/`O`.

A snapshot with `seq >= current` **replaces** local state (and truncates the local move list to
`seq`); older snapshots are ignored. Senders push an extra snapshot after every 5th move and
whenever a game screen opens, which bounds divergence after packet loss to a few moves.

### `R` — request

`PAYLOAD` is a single letter:

- `X` — resign (the sender loses)
- `D` — draw offer; the peer answers with `E|D` to accept

### `E` — end / result

`PAYLOAD` is `W` (White / X wins), `B` (Black / O wins) or `D` (draw).

## Session lifecycle

1. Challenger sends `N` and stores the session as *pending*.
2. Responder's client shows an invitation; **accept** sends `S` at `seq 0` (both sides flip to
   *active*), **decline** is silent.
3. Moves flow as `M`; engines validate legality locally and the reducer ignores anything that
   does not fit the current state.
4. The game ends with `E` (checkmate/stalemate/draw inference, resignation or agreed draw).

## Compatibility notes

- Official firmware and clients are untouched: this is plain UTF-8 text on PortNum 1.
- The tag is short on purpose — the payload budget of a mesh text packet is 233 bytes and clients
  cut text near 200.
- Version bumps (`MG2`, …) must keep `MG1` decoders working; a `MG1` receiver treats anything it
  does not recognize as ordinary text.
