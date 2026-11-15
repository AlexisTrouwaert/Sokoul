# Browser extension and shared extraction runtime

Adapted from the Movix browser extension (CC BY-NC 4.0). This folder matters to Sokoul mainly for its **shared extraction
runtime**, which three consumers use:

| Consumer | How |
| --- | --- |
| `:extractor` Gradle module (PC and TV apps) | Runs the QuickJS bundle built from `runtime/` (`extractor/build.gradle.kts`) |
| Mainapi native extraction | Bundle built by `scripts/build-native-extractors.mjs`, packaged by `API/prepare-build-context.js` |
| Browser extension and userscript | Generated `Chrome/`, `Firefox/` and `../userscript/movix.user.js` |

## Layout

| Path | Content |
| --- | --- |
| `runtime/extractors-core.js` | Canonical implementation of the host extractors; edit this, never the generated copies |
| `runtime/fsvid-vidzy-quickjs-entry.js` | Entry of the QuickJS bundle that decodes dynamic players |
| `scripts/` | `build-quickjs.mjs`, `build-extractors.mjs` (browsers and userscript), `build-native-extractors.mjs` (Mainapi) |
| `tests/` | `native-extractors.test.mjs` and its fixtures |
| `Chrome/`, `Firefox/` | Unpacked extensions (manifest, background, content and injected scripts, popup); `extractors.js` and the QuickJS files are generated |

## Commands

```powershell
cd extension
npm ci --no-audit --no-fund
npm run build:extractors    # QuickJS bundle, Chrome, Firefox and the userscript
npm run test:extractors
node scripts/build-native-extractors.mjs --output <temporary-directory>
```

The native builder exports `buildNativeExtractors(outputDirectory)`: a self-contained CommonJS adapter, an isolated
worker, the shared algorithms, the bundled QuickJS runtime, a pinned local WASM and dependency licences. Production
containers never read the browser folders or download WASM from a CDN. QuickJS 0.32.0 and esbuild 0.28.1 are locked in
`package-lock.json`.

Native `extract(url, { fetch, timeoutMs, signal })` returns `{ success, url, format, headers, subtitles, source }` or
`{ success: false, source, error }`; `detectHoster(url)` and `supportedHosts` are exported too. The caller supplies a
fetch-compatible transport using the same outbound route as playback (Mainapi uses proxiesembed's `native-fetch`); without
it extraction returns `transport_unavailable`. Response size, redirects, worker lifetime and QuickJS memory, stack and
deadline are bounded, and errors are stable codes without signed URLs.

Some hosts bind tokens to IP, user agent, language or HTTP fingerprint: matching fixtures proves parser compatibility, not
availability.

## Loading the extension during development

- Chrome / Edge / Brave: `chrome://extensions`, developer mode, *Load unpacked*, choose `extension/Chrome/`.
- Firefox: `about:debugging#/runtime/this-firefox`, *Load temporary add-on*, choose `extension/Firefox/manifest.json`.

The two manifests differ; compare both folders after a change. Permissions (`declarativeNetRequest`, `storage`, broad
host permissions) let the extension normalise headers and run extractions in the right context.
