# :core

Plain Kotlin/JVM shared by the PC and TV apps (depends on `:parser`). Network clients and domain logic with no UI.

| Package | Purpose |
| --- | --- |
| `com.sokoul.iptv.data.net` | TMDB, Xtream and IntroDB clients; IPv4-first DNS |
| `com.sokoul.iptv.data.debrid` | Real-Debrid API, release name parsing and ranking |
| `com.sokoul.iptv.data.anime` | Anime ids, offline index, episode mapping |
| `com.sokoul.iptv.data.repo` | Backup encryption, VOD grouping |
| `com.sokoul.iptv.discovery` | Taste profile, discovery rows, year review |
| `com.sokoul.iptv.domain` | Playback source model |
| `com.sokoul.desktop.player` | `PngWrappedMpegTs`: strips PNG envelopes from MPEG-TS segments (adapted from Movix, see `docs/third-party`) |

Tests: `./gradlew.bat :core:test`.
