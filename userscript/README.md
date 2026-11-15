# Userscript

The Tampermonkey variant of the browser extension, adapted from Movix (CC BY-NC 4.0). It shares the extraction
algorithms of [`../extension/runtime`](../extension/README.md) and adds a compatibility layer for `GM_xmlhttpRequest` and
Tampermonkey storage, loaded at `document-start`.

| File | Role |
| --- | --- |
| `build-userscript.mjs` | Generator; also imported by `extension/scripts/build-extractors.mjs` |
| `src/compat-prefix.js`, `src/compat-suffix.js` | GM request/storage bridge and message handlers |
| `movix.user.js` | Generated script installed in Tampermonkey; never edit it by hand |

Rebuild it with the extension (`npm run build:extractors` in `extension/`), or run `node userscript/build-userscript.mjs`
from the repository root after rebuilding the QuickJS bundle. The `@match`, `@grant` and `@connect` metadata matter as much
as the code.

Sokoul's apps and Mainapi run the same algorithms natively and never need Tampermonkey.
