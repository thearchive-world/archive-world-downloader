# Changelog

User-facing changes to Archive World Downloader, newest first.

The release pipeline ships the section whose heading matches the release version (a line `## <version>`, where
`<version>` is the mod_version core) as the notes on CurseForge, Modrinth, and the GitHub release. Write each
version's notes here before tagging; a release with no matching section fails rather than shipping empty notes.

## 1.3.0

### Added

- Support for Minecraft 1.10.2, on Forge.
- The download report names what a partial download lost, not just that it lost something.
- Minecraft 1.11.2-1.15.2: the "..." button beside Download This World shows its "Settings" label on hover.

### Fixed

- A download ended by an unexpected disconnect is far less likely to lose your inventory, game mode, advancements, and spawn point.
- A download that could not save your character reports as partial instead of completed.
- A failure while recording the server's details no longer costs you the whole download.
- Minecraft 1.11.2-1.21.11: a download that ends before it can save your character no longer erases the one an earlier download saved.
- Minecraft 1.12.2 and above: a download that ends before it can save your advancements no longer erases the ones an earlier download saved.
- Minecraft 1.16.5 and above: downloads from a server joined by direct connect no longer come out empty.
- Minecraft 1.11.2-1.20.4: a saved donkey, mule, or llama chest no longer loses the items in its first two slots and shifts the rest two slots out of place.
- Minecraft 26.1.2 and 26.2: a download interrupted by a disconnect no longer loses track of its character, so resuming it keeps your ender chest and your mount.
- Minecraft 26.1.2 and 26.2: downloading two worlds around the same time no longer leaves one of them incomplete (missing level.dat) and absent from your singleplayer world list.

## 1.2.0

### Added

- Support for Minecraft 1.11.2, 1.12.2, 1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.17.1, 1.18.2, 1.19.4, 1.20.1, 1.20.2, 1.20.4, 1.20.6, and 1.21.1.
- Forge loader support, on Minecraft 1.11.2 through 1.20.2. Minecraft 1.11.2, 1.12.2, and 1.13.2 are Forge only; Fabric starts at 1.14.4.

### Fixed

- The download button no longer goes missing from the pause menu when another mod adds a button of its own there.
- Items carrying data that cannot be written to a save no longer cost you the container, mob, or worn equipment holding them.
- Villagers no longer lose their whole trade list when one offered item cannot be saved.
- One mob that fails to save no longer takes the rest of its chunk's mobs down with it.
- The settings list no longer overlaps the buttons beneath it in short windows.
- The Defaults button now judges only the settings the screen actually shows.
- The outline line width setting is hidden on the versions where it never had any effect.
- Minecraft 1.21.3 and 1.21.4: saddled horses, donkeys, mules, and camels save with their saddle.
- Minecraft 1.21.3 and 1.21.4: experience orbs no longer go missing from the save.
- Minecraft 1.21.3 and 1.21.4: with map locking turned off, a map you are still filling in no longer saves a torn image.
- Minecraft 1.21.3-1.21.5: download list tooltips no longer clip at the list edge.
- Minecraft 1.21.10: worlds no longer save as incomplete (missing level.dat) after looking at certain angles.

## 1.1.0

### Added

- Villagers save their trades when you open them to trade.
- Support for Minecraft 1.21.3, 1.21.5, 1.21.8, and 1.21.10.

### Fixed

- Containers no longer save the wrong contents after you right-click a mob or place a block.
- Invisible mobs no longer show a download outline.

## 1.0.0

- Archive World Downloader saves the multiplayer worlds you visit as singleplayer worlds you can open offline. As you explore a server, it saves the terrain, mobs, and maps around you, and containers save their contents when you open them. Everything comes from your own client, so nothing runs on the server.
