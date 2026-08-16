# Changelog

User-facing changes to Archive World Downloader, newest first.

The release pipeline ships the section whose heading matches the release version (a line `## <version>`, where
`<version>` is the mod_version core) as the notes on CurseForge, Modrinth, and the GitHub release. Write each
version's notes here before tagging; a release with no matching section fails rather than shipping empty notes.

## 1.2.0

### Added

- Support for Minecraft 1.20.1, 1.20.2, 1.20.4, 1.20.6, and 1.21.1.
- Forge loader support on Minecraft 1.20.1 and 1.20.2.

### Fixed

- Minecraft 1.21.10: worlds no longer save as incomplete (missing level.dat) after looking at certain angles.
- Minecraft 1.21.3-1.21.5: download list tooltips no longer clip at the list edge.

## 1.1.0

### Added

- Villagers save their trades when you open them to trade.
- Support for Minecraft 1.21.3, 1.21.5, 1.21.8, and 1.21.10.

### Fixed

- Containers no longer save the wrong contents after you right-click a mob or place a block.
- Invisible mobs no longer show a download outline.

## 1.0.0

- Archive World Downloader saves the multiplayer worlds you visit as singleplayer worlds you can open offline. As you explore a server, it saves the terrain, mobs, and maps around you, and containers save their contents when you open them. Everything comes from your own client, so nothing runs on the server.
