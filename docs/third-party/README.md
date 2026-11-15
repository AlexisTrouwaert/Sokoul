# Third-party code and licences

| Component | Licence | Where it is used | Notice |
| --- | --- | --- | --- |
| Movix (web, TV app, API, extension) | CC BY-NC 4.0 | Backend services in `API/`, the browser extension and userscript, the extraction runtime, PNG-wrapped MPEG-TS handling, segmented seasons | [MOVIX-LICENSE](MOVIX-LICENSE), `extractor/LICENSE-MOVIX`, `tv/src/main/kotlin/com/sokoul/desktop/player/THIRD_PARTY.md` |
| quickjs-kt 1.0.15 | Apache 2.0 | `:extractor` | `extractor/LICENSE-QUICKJS-KT` |
| Inter, Inter Display | SIL OFL 1.1 | App font | [../design/font-comparison/Inter-OFL.txt](../design/font-comparison/Inter-OFL.txt) |
| libVLC 3.0.21 | LGPL 2.1+ | PC playback (downloaded by `tools/fetch-vlc.ps1`, not versioned) | VideoLAN |
| curl-impersonate | See its bundled LICENSE files | Android HTTP transport (fetched by `tools/prepare-curl-impersonate.py`) | Copied to `licenses/curl-impersonate` in the build output |

The Movix licence is non-commercial: Sokoul and its derivatives may not be used commercially.
