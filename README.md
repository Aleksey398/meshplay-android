# MeshPlay

**MeshPlay** is an independent, offline-first mesh messenger with built-in turn-based games —
chess, checkers and tic-tac-toe — played right inside a text conversation over LoRa mesh radios.

> **MeshPlay is an independent fork of [Meshtastic-Android](https://github.com/meshtastic/Meshtastic-Android).**
> Meshtastic® is a registered trademark of Meshtastic LLC. This project is not affiliated with,
> endorsed by, or connected to the Meshtastic project. It works with Meshtastic-compatible nodes
> and firmware.

## What it does

- **Mesh messaging** — direct messages and channel chats over LoRa mesh networks, with the full
  device-configuration tooling inherited from the upstream app (nodes, maps, channels, firmware).
- **Games over text** — challenge a contact to chess, checkers or tic-tac-toe. Moves travel as
  short tagged text messages (`[MG1|…]`) on the ordinary text channel, so they work with the stock
  firmware and are simply shown as text by clients that do not understand the tag. See
  [docs/game-protocol.md](docs/game-protocol.md) for the wire format.
- **Sessions survive restarts** — every game is stored locally and re-synced from periodic
  position snapshots after packet loss.
- **No Google dependency** — the `fdroid` flavor builds and runs without Google Maps, Firebase or
  Datadog keys.

## Playing a game

1. Open a chat with the contact you want to play.
2. Menu (⋮) → **Play a game**.
3. Pick chess, checkers or tic-tac-toe (and a side for the board games).
4. The opponent accepts from their games screen and the table opens; tap a piece, then a target
   square. Draws, resignations and results travel through the same tagged messages.

## Building

The fork builds with the free `fdroid` flavor and needs no external keys:

```bash
./gradlew assembleFdroidRelease
```

APKs land in `androidApp/build/outputs/apk/fdroid/release/`. Debug builds:

```bash
./gradlew assembleFdroidDebug
```

A JDK 25 toolchain and the Android SDK are required (the project bootstraps both through its
Gradle configuration; on CI the SDK is preinstalled on `ubuntu-latest` runners).

## Releases / CI

Every push builds the debug APK and runs the game-layer unit tests
(`.github/workflows/meshplay-ci.yml`). Pushing a tag `v*` additionally builds `fdroidRelease`,
optionally signs it (GitHub Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`) and publishes the APKs to a GitHub Release.

## Games layer

| Module | Purpose |
|---|---|
| `core/game-protocol` | `[MG1|…]` wire format: encode/decode + unit tests |
| `feature/games` | Chess / checkers / tic-tac-toe engines, boards, sessions store |

The engines are original Kotlin code (no extra dependencies); chess supports full legal moves,
castling, en passant, promotions, checkmate/stalemate, the fifty-move rule and insufficient
material, with FEN snapshots. The protocol and the engines are validated by perft tests and
round-trip tests in CI.

## Licensing

This fork is **GPL-3.0-or-later**, exactly like the upstream project. `LICENSE` is unchanged and
every published APK is accompanied by the corresponding source of this repository. Files modified
from upstream carry a `Modified by MeshPlay on <date>` notice; new files are © MeshPlay
contributors under the same license.

Upstream project links (kept for reference): [meshtastic.org](https://meshtastic.org),
[upstream repository](https://github.com/meshtastic/Meshtastic-Android),
[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html).

## Trademark note

“Meshtastic®” is a registered trademark of Meshtastic LLC. MeshPlay uses the name only to
describe compatibility, never as part of the app name, icon or store listing. If you fork or
publish publicly, review the upstream trademark policy and notify `trademark@meshtastic.org`
within 7 days of first public use, as that policy requires.
