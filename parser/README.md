# :parser

Plain Kotlin/JVM, no Android or UI dependency. Turns IPTV provider data into models the apps use.

| File | Purpose |
| --- | --- |
| `M3uParser.kt`, `M3uClassifier.kt` | Parse M3U playlists and sort entries into live channels, films and series |
| `XmltvParser.kt` | Stream-parse XMLTV guides (kxml2) |
| `Xtream.kt`, `ServerUrl.kt`, `StreamUrls.kt` | Xtream Codes API models, server address normalisation, stream URL building |
| `TitleTagger.kt`, `MediaInfo.kt` | Extract quality, language and season/episode tags from release titles |

Package: `com.sokoul.iptv.parser`. Tests: `./gradlew.bat :parser:test` (`RealWorldCheck` and `XtreamLiveCheck` only run
against live services when `SOKOUL_NETWORK_TESTS` / `SOKOUL_XT_*` are set).
