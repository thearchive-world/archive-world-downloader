# Archive World Downloader

**Keep the multiplayer worlds you visit.** As you explore a server, Archive World
Downloader saves the terrain, mobs, and maps around you into a singleplayer world you can
open offline, and containers save their contents when you open them. Everything comes from
your own game, so nothing runs on the server.

[![License: LGPL-3.0-or-later](https://img.shields.io/badge/license-LGPL--3.0--or--later-blue.svg)](LICENSE)
![Loaders: Fabric and NeoForge](https://img.shields.io/badge/loaders-Fabric%20%7C%20NeoForge-brightgreen)
![Minecraft 1.21.4 to 26.2](https://img.shields.io/badge/Minecraft-1.21.4%20to%2026.2-brightgreen)
![Environment: client-side](https://img.shields.io/badge/environment-client--side-orange)

> Download from **[Modrinth](https://modrinth.com/mod/wdl)** or
> **[CurseForge](https://www.curseforge.com/minecraft/mc-mods/wdl)**.
> Full documentation lives at
> **[wdl.docs.thearchive.world](https://wdl.docs.thearchive.world/)**.

## Contents

- [What it does](#what-it-does)
- [Supported versions](#supported-versions)
- [Installation](#installation)
- [Usage](#usage)
- [Configuration](#configuration)
- [What it captures, and what it cannot](#what-it-captures-and-what-it-cannot)
- [Compatibility and limitations](#compatibility-and-limitations)
- [Getting help](#getting-help)
- [Contributing](#contributing)
- [License](#license)
- [Disclaimer](#disclaimer)

## What it does

Archive World Downloader rebuilds a singleplayer save from the data your client already
receives while you play on a server. It never asks the server for anything extra and adds
no network traffic of its own beyond an optional update check. As you move, the mod:

- **Saves the terrain you load** into region files, so the world you walked through opens
  offline exactly as you saw it.
- **Saves container contents** (chests, barrels, and the like) for the containers you open.
- **Saves entities** such as mobs, item frames, paintings, and the maps they carry.
- **Saves maps** in your inventory and in item frames.
- **Optionally saves your player data** (inventory, Ender Chest, advancements, statistics),
  each behind its own toggle.
- **Fills the gaps between loaded chunks** with void, flat, or default terrain, your choice,
  so the save is a valid, openable world.

Downloads are named, resumable, and merge-aware: you can continue a download later, and the
mod keeps a backup before it changes an existing folder so a bad merge is recoverable.

## Supported versions

Each Minecraft version has its own build for both loaders. The 1.0 release targets:

| Minecraft | Fabric | NeoForge |
|-----------|:------:|:--------:|
| 1.21.11   | yes    | yes      |
| 1.21.4    | yes    | yes      |
| 26.1.2    | yes    | yes      |
| 26.2      | yes    | yes      |

The Modrinth and CurseForge listings are the live source of truth for exactly which builds
are published. Install the build that matches your Minecraft version and loader.

## Installation

Archive World Downloader is **client-side only**. Install it on your own client. It does not
need to be on the server, and a server does not need to run it.

### Fabric

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api). This mod requires it.
3. Put the `archive-wdl-fabric-*.jar` for your Minecraft version into your `mods` folder.

### NeoForge

1. Install the matching [NeoForge](https://neoforged.net/) version.
2. Put the `archive-wdl-neoforge-*.jar` for your Minecraft version into your `mods` folder.

NeoForge bundles its own APIs, so there is no separate API mod to install for it.

## Usage

Join a multiplayer server, then start a download. There are two ways:

- **Keybinds** (rebind them under Options, Controls, in the "Archive World Downloader"
  category):
  - **Toggle World Download** starts or stops a download.
  - **Open Downloads Screen** opens the download manager.
  - **Peek Detailed HUD** reveals the detailed live status (defaults to Left Alt).
- **Commands:**
  - `/wdl start <name>` begins a new download with that name.
  - `/wdl resume <name>` continues an existing download.
  - `/wdl stop` ends the current download and saves it.

While a download runs, a HUD shows live progress (chunks, entities, containers, and elapsed
time). Move around and open the containers you want saved. When you stop, the mod writes the
save and shows a toast; open the **Downloads** screen to find it and choose **Download This
World** to play it in singleplayer, or **Open Saves Folder** to find it on disk.

## Configuration

Open the settings screen from the **Downloads** screen, or on Fabric from the mod list via
[Mod Menu](https://modrinth.com/mod/modmenu). Settings are grouped into **Interface**,
**World**, and **Download** tabs, and cover:

- **What to capture:** entities, container contents, player inventory, Ender Chest,
  advancements, statistics, and how up to date revisited areas stay.
- **The generated world:** void, flat, or default fill, seed, structures, game rules, game
  mode, and cheats for the saved world.
- **On-screen aids:** the HUD, an outline over containers you have not opened yet, and a chunk
  overlay marking which chunks are already saved.
- **Notifications:** toasts, chat messages, and the optional startup update check.

Every setting has a default and an in-game description; an invalid value self-heals to its
default. There is no need to edit the config file by hand.

## What it captures, and what it cannot

The mod can only save what your client already received. That has honest limits, and knowing
them sets expectations:

- **You have to be there.** Only chunks your client loaded are saved. Areas you never visited
  are not in the download. Explore to fill the world in.
- **Containers save when you open them.** A chest's contents arrive only when you open it, so
  an unopened chest is saved empty. The container outline helps you see which ones still need
  opening.
- **It saves the server's world, not a generated one.** The space between loaded chunks is
  filled with void (default), flat, or default terrain, which will not match the server. Void
  keeps the download honest about what was actually captured.
- **Respect the server's rules.** Some servers do not allow downloading their world. Whether
  you may use the mod on a given server is between you and that server.

## Compatibility and limitations

- **Client-side only.** Works on any server you can join; nothing is installed server-side.
- **Map mods.** The chunk overlay can display on Xaero's and JourneyMap minimaps when those
  are installed. These are optional and not required.
- **Replay mods.** Downloads work while viewing a replay in supported replay mods.
- **Single download at a time.** One download runs at once; resume or restore must finish
  before another starts.

## Getting help

- **Bugs and crashes:** file an issue on the
  [issue tracker](https://github.com/thearchive-world/archive-world-downloader/issues) using
  the report templates. They ask for your Minecraft version, loader, and mod version, which
  the maintainer needs to help.
- **Questions and ideas:**
  [Discussions](https://github.com/thearchive-world/archive-world-downloader/discussions).
- **Security problems:** do not open a public issue. Follow the [security policy](SECURITY.md).

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build the mod, the
conventions the code follows, how to submit a translation, and how to get a change reviewed.

## License

Archive World Downloader is licensed under **LGPL-3.0-or-later**. The full text is in
[LICENSE](LICENSE) (the GNU Lesser General Public License v3), together with the GNU General
Public License v3 it builds on in [GPL-3.0.txt](GPL-3.0.txt).

In short: you may use, study, share, and modify the mod, and changes you distribute to the
mod's own code stay under the same license. Another mod that merely calls Archive World
Downloader's API does not have to adopt this license.

That covers the artwork too. The editable form of the mod icon is the vector drawing in
[art/logo.svg](art/logo.svg); the `icon.png` the mod ships is exported from it.

The license covers copyright, not identity. "Archive World Downloader" and the logo are how
this project is recognized, and nothing here grants permission to use them as the identity of
a different project. GPL-3.0 section 7(e), which this license builds on, expressly allows that
reservation. The artwork stays free to use and modify under the terms above: a fork may ship
it, and presents itself under its own name and its own icon.

## Disclaimer

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
