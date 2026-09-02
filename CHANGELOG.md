# Changelog

User-facing changes to Archive World Downloader, newest first.

The release pipeline ships the section whose heading matches the release version (a line `## <version>`, where
`<version>` is the mod_version core) as the notes on CurseForge, Modrinth, and the GitHub release. Write each
version's notes here before tagging; a release with no matching section fails rather than shipping empty notes.

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
