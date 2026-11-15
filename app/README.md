# :app

The shared screens and the Windows app (Compose Desktop, libVLC through vlcj). The Android build compiles the same
`src/main/kotlin` through `syncShared` ([architecture](../docs/architecture.md#one-codebase-two-platforms)).

| Path | Content |
| --- | --- |
| `src/main/kotlin/com/sokoul/pc` | Screens, navigation, playback, live TV, data and UI components ([package map](../docs/architecture.md#packages-of-app)) |
| `src/main/kotlin/com/sokoul/desktop` | Database, preferences, IPTV sync, recordings, player contract and the libVLC player |
| `src/main/resources` | Fonts, app icon, saga lists, anime categories |
| `src/dev/kotlin` | Development tools (source set `dev`), never shipped |
| `src/test/kotlin` | Unit and Compose UI tests |
| `app-resources/windows/vlc` | libVLC, downloaded by `tools/fetch-vlc.ps1` (not versioned) |
| `icon/` | Windows icon |

## Commands

| Task | Command |
| --- | --- |
| Compile | `./gradlew.bat :app:compileKotlin` |
| Run | `./gradlew.bat :app:run` |
| Distribution | `./gradlew.bat :app:createDistributable` |
| Tests | `./gradlew.bat :app:test --tests "com.sokoul.pc.<Test>"` |
| Screen renders | `./gradlew.bat :app:renderScreens -PrenderOutput=renders -PrenderMode=<mode>` |
| Dev tools | `sagaHarvest`, `sagaCategorize`, `logoAudit`, `trailerQuality`, `trailerTrial`, `rdTrial`, `setupCheck`, `validatePrivateKeys` |
