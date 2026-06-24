# Amusic

Research notes and starter code for adding an Apple Music provider to [AllMusic](https://github.com/coloryr/AllMusic) using the public Cider source as a MusicKit reference.

## Contents

- [`docs/apple-music-allmusic-api.md`](docs/apple-music-allmusic-api.md): analysis of AllMusic's provider/playback pipeline, Cider's MusicKit-based approach, and a safe API shape that AllMusic can implement.
- [`src/main/java/com/coloryr/allmusic/server/core/api/applemusic/AppleMusicApi.java`](src/main/java/com/coloryr/allmusic/server/core/api/applemusic/AppleMusicApi.java): an `IMusicApi` implementation for Apple Music catalog search and metadata lookup.

## Current implementation status

`AppleMusicApi` can be copied into AllMusic's `server/src/main/java` tree and registered like other `IMusicApi` providers. It implements catalog song search, song metadata lookup, Apple Music ID parsing, empty lyrics, busy-state protection, and a `getPlaybackRef` helper for client-side MusicKit playback.

`getPlayUrl` intentionally returns `null` because Apple Music subscription tracks are protected and should be played through a MusicKit-authorized client bridge rather than extracted as direct audio URLs.
