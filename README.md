# Archive World Downloader

**Keep the multiplayer worlds you visit.** As you explore, Archive World Downloader saves the terrain, mobs, and maps around you into a singleplayer world you can open offline. Opening a container saves what is inside it. Everything comes from your own game, so nothing runs on the server.

[![CI](https://github.com/thearchive-world/archive-world-downloader/actions/workflows/ci.yml/badge.svg?branch=dev)](https://github.com/thearchive-world/archive-world-downloader/actions/workflows/ci.yml)
[![CurseForge](https://img.shields.io/curseforge/dt/1554389?label=CurseForge)](https://www.curseforge.com/minecraft/mc-mods/wdl)
[![License: LGPL-3.0-or-later](https://img.shields.io/badge/license-LGPL--3.0--or--later-blue.svg)](LICENSE)

> Get it on **[Modrinth](https://modrinth.com/mod/wdl)** or **[CurseForge](https://www.curseforge.com/minecraft/mc-mods/wdl)**. Full documentation is at **[wdl.docs.thearchive.world](https://wdl.docs.thearchive.world/)**.

## What it does

Archive World Downloader rebuilds a singleplayer save from the data your client already receives while you play on a server. Nothing runs on the server, and it adds no network traffic of its own beyond an optional update check.

The terrain you explore is always saved. Alongside it, each behind its own toggle:

- **Containers you open,** with their contents at the moment you open them. A container you never opened is saved empty.
- **Mobs, item frames, and paintings,** as far as the server sends them to you.
- **Filled maps you saw,** so they show their picture rather than blank.
- **Your player data:** inventory, Ender Chest, advancements, and statistics.

One download covers the overworld, the Nether, and the End. Downloads are named and resumable, and what you get is a normal singleplayer world that opens in the vanilla game with the mod uninstalled.

## Supported versions

Archive World Downloader ships builds for these exact Minecraft versions, not a continuous range:

- **Forge:** 1.10.2, 1.11.2, 1.12.2, 1.13.2
- **Fabric and Forge:** 1.14.4, 1.15.2, 1.16.5, 1.17.1, 1.18.2, 1.19.4, 1.20.1, 1.20.2
- **Fabric and NeoForge:** 1.20.4, 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1.2, 26.2

The [GitHub releases](https://github.com/thearchive-world/archive-world-downloader/releases) and the CurseForge listing are the live source of truth for what is published.

## Installation

Archive World Downloader is client-side only; install it on your own client, not the server. Use a launcher's mod browser (Prism Launcher, the CurseForge App), or drop the matching `archive-wdl-<loader>-*.jar` into your `mods` folder: Fabric and Quilt also need [Fabric API](https://modrinth.com/mod/fabric-api), while Forge and NeoForge need nothing extra. Each build runs on the Java version its Minecraft version already needs. The [install guide](https://wdl.docs.thearchive.world/get-started/install/) has the full walkthrough.

## Usage

Join a multiplayer server first; there is no download button in singleplayer or a LAN world you host. Open the pause menu, click **Download This World**, name the download, and play as you normally would; click **Stop Download** to finish, and the world appears in your singleplayer list. Containers are saved only when you open them, so open the ones you want as you explore. The [first-download guide](https://wdl.docs.thearchive.world/get-started/first-download/) covers keybinds, commands, configuration, and the rest.

## Getting help

- **Chat and community:** join the [Discord](https://discord.gg/eDUscXhtUa) for help, discussion, and release news.
- **Bugs and crashes:** file an issue on the [issue tracker](https://github.com/thearchive-world/archive-world-downloader/issues) using the report templates.
- **Usage and configuration:** read the [documentation](https://wdl.docs.thearchive.world/).
- **Security problems:** do not open a public issue. Follow the [security policy](SECURITY.md).

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build the mod, the conventions the code follows, how to submit a translation, and how to get a change reviewed.

## License

Licensed under **LGPL-3.0-or-later** ([LICENSE](LICENSE), built on [GPL-3.0.txt](GPL-3.0.txt)). You may use, study, share, and modify the mod; changes you distribute to its own code stay under the same license, while a mod that only calls its API does not. The mod icon's editable source is [art/logo.svg](art/logo.svg). The license covers copyright, not identity: the name and logo identify this project, so a fork presents itself under its own name and icon.

## Disclaimer

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
