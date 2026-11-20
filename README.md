# Crow Android Project — Server-Driven 1.5

Standalone Kotlin Android host for Crow with a Go release/content server.

- Android build and release signing: [README_FA.md](README_FA.md)
- Go server installation and operations: [crow-server/README_FA.md](crow-server/README_FA.md)
- Server-delivered season pack authoring: [CROW_SEASON_PACKS_FA.md](CROW_SEASON_PACKS_FA.md)
- Initial 16-case server seed: `crow-server/seed/Crow_Initial_Season.zip`
- Upload-ready replacement example: `Crow_Season_Template.zip`

The APK contains the game engine and rendering assets, not playable case records. The server supplies every playable stage, beginning with the original 16 cases. The server keeps one active season; a new upload replaces the previous season, and clients fetch the new pack on their next launch. The last successfully downloaded pack is cached on-device for temporary offline play.
