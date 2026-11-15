# Source repository integration audit

Reference: local `Sokoul a adapter/API`, inspected 2026-11-15. Code/text comparison excluded dependency trees, caches, private configuration and database snapshots. Of 291 reference files, 267 were identical and 24 differed at inspection; no reference code/text file was absent. Differences include intentional native-backend health/LAN adaptations, the current Anime-Sama implementation, pinned dependency removal and updated tests. File presence alone does not establish native client consumption or upstream availability.

| Provider | Native input and output | Integration considerations |
| --- | --- | --- |
| PurStream | TMDB movie/TV IDs, `sources[]` | Existing native route; direct signed media |
| FStream | TMDB movie or numbered season/episode, `players` / `episodes[N].languages` | Existing native route and selected extraction |
| Cpasmal | TMDB movie/TV IDs, `organized` / numbered episode | Cold response can be 202; bounded polling |
| Wiflix / CineStream | TMDB movie/TV IDs | CineStream already feeds Wiflix; adding a separate identical scraper duplicates work |
| Coflix | `/api/tmdb/:type/:id`, movie `player_links`, TV `current_episode.player_links` | TV requires exact `season` and `episode` |
| Omega / FrenchStream | `/api/omega/:type/:tmdbId`, movie `player_links`, TV `current_episode.languages` | New cached TMDB-to-IMDb adapter; exact episode selection copied after catalogue caching |
| J1F | `/api/j1f/movie/:id`, `/api/j1f/tv/:id/season/:s?episode=N` | Existing unique-player filtering; movie `players`, TV language map |
| SwiftFlow | `/api/swiftflow/movie/:id`, `/api/swiftflow/tv/:id/season/:s?episode=N` | Requires configured base/key; custom MP4 resolver and partner player may need separate handling |
| VoirDrama | `/api/drama/tv/:tmdbId?season=S&episode=E`, `data[]` | TV only, selected episode, 503 retry backoff |
| KissKH | `/api/kisskh/movie/:id`, `/api/kisskh/tv/:id?season=S&episode=E`, `sources[]`, `subtitles[]` | Dedicated signed proxy, fallback capabilities and encrypted subtitle descriptors; cold202; media type hls/mp4 |
| DarkiWorld | `/api/darkiworld/download/:type/:titleId` | Provider title identity differs from TMDB; returns downloads/season packs requiring identity-aware episode/debrid selection |
| France.tv | `/api/ftv/search`, signed tokens through `/episodes` and `/info` | Uses programme/episode matching and DRM extraction, rather than a TMDB-only VOD player route |
| Streamed | `/api/livetv` catalogue, streams, selected VIP native endpoint | Existing backend wired; native decoder dependency missing, pinned upstream tarball404 |
| Host-link debrid tools | `/api/media/debrid/providers`, `/api/media/debrid/unlock` | Native manual-link utility for enabled Deepbrid/Real-Debrid/DebridR; BestDebrid uses the reference client-origin request after a gated ephemeral key lookup |

Catalogue extraction on ordinary VOD routes remains explicit (`resolve=1`) and VIP verified. Selected media fields are added after cache reads; a signed relay or per-caller authorization flag must never enter long-lived source caches. Native fallback requires the explicit authorization status supplied by the extraction helper, rather than interpreting a failed request as authorization.

The debrid utility corresponds to the reference `src/pages/DebridPage.tsx`, rather than additional torrent-library discovery. BestDebrid's request shape is copied from that local implementation: `GET https://bestdebrid.com/api/v1/generateLink?auth=...&link=...`, with candidate payload at the root, `data`, or the first object of an array. The native client never logs or persists that key or generated links, and forwards no Sokoul access key to the external service. A file marked as an archive is offered for downloading, never automatically opened as video. Provider configuration and account availability are external requirements; new live unlock success is not asserted.

## Streamed dependency limitation

The reference pins `streamed-pk-hls-stream-resolver` to commit `7d918be84d47a924373a9304d7c8856a9daba4c6`. The [pinned archive](https://codeload.github.com/sharoon7171/streamed-pk-hls-stream-resolver/tar.gz/7d918be84d47a924373a9304d7c8856a9daba4c6) and raw package/license returned HTTP404 during inspection. No cached installation was found. The installed backend therefore cannot execute its worker. The package was not re-added with a broken URL or replaced by an unreviewed package. Docker now includes `curl`, required by the existing transport. Recovering the pinned source and license, then verifying its real handshake, is still required before claiming Streamed native playback.

## Verification

- Targeted TMDB/Coflix/Omega freshness, cache integrity, exact episode selection and TMDB coalescing: 42 tests passed.
- Explicit source extraction authorization, denial and unavailable verification: 2 tests passed.
- KissKH route contracts, VIP mode, strict descriptors, progress/error behavior and capability consumption: 20 tests passed after adding response-authority adaptation.
- These are deterministic checks; no new live playback or external provider availability is asserted by this audit.
