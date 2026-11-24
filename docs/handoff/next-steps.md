# Next steps — handoff to Codex

State on 2026-11-18. Work is uncommitted on branch `publish` (53 changed files). Every card is small and verifiable on its own. Do them in order. Codex: read `docs/handoff/rules.md` first (`AGENTS.md` points to it), then **one** card. Start a new Codex session for each card.

Line anchors below were checked against the current checkout on 2026-11-18. They locate an edit; after an earlier card changes a file, re-open and re-search it before editing rather than relying on a stale line number.

How every card ends (copy this checklist into your answer):
- [ ] Compiled: `./gradlew.bat :app:compileKotlin --console=plain` → BUILD SUCCESSFUL
- [ ] Card's tests ran and passed (name them, with the counts from `build-output/app/test-results/test/TEST-*.xml`)
- [ ] Translations: `python tools/i18n-extract.py` + `python tools/i18n-check.py es de nl it pt tr ar` → `missing 0`, `placeholder mismatch 0` (only if user-visible text changed)
- [ ] Render looked at (only if a screen changed)
- [ ] The card is ticked in the named `openspec/changes/*/tasks.md`, with commands and results

---

## Card 0 — Save the current state (the user does this)

So that any mistake can be undone:

```powershell
git switch -c rework-audio-languages
git add -A
git commit -m "Rework sources, any-language audio and nine interface languages"
```

---

## Card 1 — "Starting a film" becomes "Starting playback"

Why: the setting applies to films and series (settings audit item 8).

Steps:
1. In `app/src/main/kotlin/com/sokoul/pc/i18n/Strings.kt`, at lines 214 and 460, `Text.PrefStart` changes from `"Starting a film"` to `"Starting playback"` and from `"Démarrage d'un film"` to `"Démarrage de la lecture"`.
2. Translations (see `docs/handoff/rules.md`, "Translations"): the old key disappears from `source.tsv` and `Starting playback` appears as missing. In each of the 7 catalogues, replace the line starting `Starting a film<TAB>` by `Starting playback<TAB>` + translation: es `Inicio de la reproducción`, de `Start der Wiedergabe`, nl `Afspelen starten`, it `Avvio della riproduzione`, pt `Início da reprodução`, tr `Oynatmayı başlatma`, ar `بدء التشغيل`.
3. Tests: `TranslationsTest`, `SettingsTest`.

Prompt to paste:
> Do Card 1 of docs/handoff/next-steps.md. Follow docs/handoff/rules.md. Show me the exact lines you change before editing. Then run the translation check and the two tests, and report their output.

---

## Card 2 — Button "Forget source failures"

Why: a version that failed for a lasting reason stays last for 30 days with no way to undo it (settings audit item 4). Tracked as lot 4.

Steps:
1. `app/src/main/kotlin/com/sokoul/pc/playback/sources/SourceRules.kt`, `SourceMemory` at lines 117–165: add `fun forgetFailures()` beside `clearTransientFailures`. It must set `failure = null, until = 0` on every fact and keep `language`, `height`, `codes`, then call `save()` only if a fact changed.
2. Find `SourceMemory(` in `app/src/main/kotlin/com/sokoul/pc/App.kt` at line 285. Keep that instance in a `val` so it can be reached, and add to `AppDeps` a `var forgetSourceFailures: () -> Unit = {}` beside `clearSearchCache`. Set it next to the memory creation.
3. `library/SettingsSections.kt`: add `val onForgetSourceFailures: () -> Unit = {}` to `SettingsHooks` at lines 76–93; in `AboutPane` around lines 367–375, right after the clear-cache `ConfirmRow`, add `ConfirmRow(L("Forget source failures", "Oublier les échecs des sources").text(), "about-forget-failures", done, hooks.onForgetSourceFailures)`. Wire the hook where `onClearCache` is already wired in `App.kt` around lines 738–760.
4. Test in `app/src/test/kotlin/com/sokoul/pc/SourceRulesTest.kt`: a fact with a failure and `codes = listOf("fr")` → after `forgetFailures()`, `failure(key)` is null and `fact(key)?.codes == listOf("fr")`.
5. Translations for `Forget source failures`: es `Olvidar los fallos de las fuentes`, de `Quellenfehler vergessen`, nl `Bronfouten vergeten`, it `Dimentica gli errori delle fonti`, pt `Esquecer as falhas das fontes`, tr `Kaynak hatalarını unut`, ar `نسيان أخطاء المصادر`.

Prompt:
> Do Card 2 of docs/handoff/next-steps.md. Before editing, open SourceRules.kt, App.kt and SettingsSections.kt and quote the lines you will change. Do not invent names: search for each one. Then compile, run SourceRulesTest, SettingsTest and TranslationsTest, and report results.

---

## Card 3 — Quality and size say they are preferences

Why: they only order versions; a version above the limit is not removed (settings audit item 6).

Steps:
1. `Strings.kt` at lines 217–218 changes `Text.PrefMaxQuality` to `"Prefer quality up to"` and `Text.PrefMaxSize` to `"Prefer size up to"`; French lines 463–464 become `"Préférer une qualité jusqu'à"` and `"Préférer une taille jusqu'à"`.
2. Catalogues (replace the old lines): es `Preferir calidad hasta` / `Preferir tamaño hasta`; de `Qualität bevorzugt bis` / `Größe bevorzugt bis`; nl `Kwaliteit bij voorkeur tot` / `Grootte bij voorkeur tot`; it `Preferisci qualità fino a` / `Preferisci dimensione fino a`; pt `Preferir qualidade até` / `Preferir tamanho até`; tr `Tercih edilen en yüksek kalite` / `Tercih edilen en büyük boyut`; ar `تفضيل جودة حتى` / `تفضيل حجم حتى`.
3. Render `-PrenderMode=languages` and check that the German line fits.

Prompt:
> Do Card 3 of docs/handoff/next-steps.md, same rules as before. Render the languages mode and tell me if any text is cut.

---

## Card 4 — Versions panel: a "Your languages" filter

Why: the fixed "VF" filter ignores MULTI files that hold French, and knows nothing of Italian or Japanese viewers (sources audit item 7). The labels VF/MULTI/VOSTFR/VO stay as descriptions; the new chip uses the viewer's ordered languages.

Steps:
1. `playback/session/PlaybackSession.kt`, near `languageNotice` at line 159 and `lacksWantedLanguage` at lines 1184–1193: add a public `fun holdsWantedLanguage(v: PlayVersion): Boolean = AudioMatch.verdict(v, prefs().intent, memory?.fact(memoryKey(v)), workLanguage).matches`.
2. `playback/ui/SourceBrowser.kt`, lines 77–108 and 143–157: `SourceBrowserContent` gets `wanted: (PlayVersion) -> Boolean = { true }`; `SourceBrowser` passes `session::holdsWantedLanguage`. Add `wantedOnly` reset with `searchRevision`, put a chip first in the language row with tag `versions-language-wanted`, and include it in reset and `visible`.
3. Test: in `app/src/test/kotlin/com/sokoul/pc/VersionsPanelTest.kt` add a test with `SourceBrowserContent` directly (no session): three versions, `wanted = { it.key == "a" }`, click `versions-language-wanted`, expect one row. Note: the existing test in that file already fails on the original code (see rework-sources-audio-start tasks section 6); leave it as it is in this card.
4. Translations for `Your languages`: es `Tus idiomas`, de `Deine Sprachen`, nl `Jouw talen`, it `Le tue lingue`, pt `Seus idiomas`, tr `Dillerin`, ar `لغاتك`.
5. Render `-PrenderMode=panels` (image `panel-sources-*`).

Prompt:
> Do Card 4 of docs/handoff/next-steps.md. First show me the current SourceBrowserContent signature and filter code, then your plan, then edit. Run VersionsPanelTest (only your new test must pass; report the old one's result too), TranslationsTest, and render the panels.

---

## Card 5 — "Ask me" when no wanted language is in the file

Why: design D6 (`openspec/changes/rework-sources-audio-start/design.md`) has `AudioFallback.Ask`, but nothing uses it yet (tasks 7.6).

Behaviour: with `Ask`, if the file has none of the wanted languages and no other version can be tried before activation, the player keeps the sound held (the picture plays muted) and shows the existing language notice; "Keep Watching" releases the sound (`engine.endPreview()`), "See Versions" opens the versions. With `EngineDefault` nothing changes.

Steps:
1. `PlaybackSession.attempt()` at lines 1068–1069 and `resolveAudio()` at lines 1081–1099: after `resolveAudio` returns, when `prefs().intent.fallback == AudioFallback.Ask` and `AudioMatch.missingWanted(tracks, intent, workLanguage)`, set `languageNotice` and **do not** call `endPreview()`; add `fun listenAnyway()` beside `dismissLanguageNotice()` at lines 159–162 that calls `engine?.endPreview()` and clears the notice.
2. `playback/ui/PlayerScreen.kt` around lines 840–848: replace the banner's current keep action with `session.listenAnyway()` and keep `onVersions` unchanged.
3. `library/AudioLanguagesScreen.kt` at lines 112–115: under the language list, add the fallback `ChoiceRow` with `AudioFallback.entries`, labels `L("Play the file's own track", "Lire la piste du fichier")` and `L("Ask me", "Me demander")`, and save `prefs.audioIntent = intent.copy(fallback = it)`.
4. Tests in `PlaybackTest`: with `AudioFallback.Ask` and a FakeEngine whose tracks are English only (see `FakeEngine(tracks = …)` in `TestSupport.kt`), the session shows `languageNotice` and `engine.calls` has no `endPreview`; after `listenAnyway()` it has. `FakeEngine` (`app/src/test/kotlin/com/sokoul/pc/TestSupport.kt`) does not override `endPreview` today (the interface default in `playback/session/Playback.kt` does nothing): add `override fun endPreview() { calls += "endPreview" }`, after checking how `FakeEngine` names its call list.
5. Translations for the three new texts.

Prompt:
> Do Card 5 of docs/handoff/next-steps.md. This one touches the playback session: read PlaybackSession.attempt, resolveAudio and watch fully before proposing changes, and show me the plan first. Keep EngineDefault behaviour identical. Run PlaybackTest and AudioMatchTest and report counts.

---

## Card 6 — Choose the search services

Why: `pc.debrid.services` exists (`DesktopDebrid.SERVICES`, `DesktopDebrid.services()`), but no screen sets it (settings audit item 2).

Steps:
1. `desktop/data/DesktopPrefs.kt`, beside `audioIntent` around lines 289–301: add `var searchServices: Set<com.sokoul.iptv.data.debrid.SourceAddon>`. Read/write `DesktopDebrid.SERVICES` with `DebridAddons.parse` and `joinToString(",") { it.name }`; both services are the default when the key is absent.
2. `library/SettingsSections.kt`, `PlaybackPane` at lines 303–315: before `SourcePriorityRows`, add `GroupTitle(L("Search services", "Services de recherche").text())` and one `ChoiceRow` per `SourceAddon` (`it.label`, choices ON/OFF). Never allow an empty set.
3. Test in `SettingsTest`: turn Comet off → `prefs.searchServices == setOf(SourceAddon.TORRENTIO)`; try to turn Torrentio off too → still contains it.
4. Translations for `Search services`.

Prompt:
> Do Card 6 of docs/handoff/next-steps.md. Check DesktopDebrid.SERVICES and DebridAddons.parse exist before using them. Run SettingsTest, MultiSourceSearchTest and TranslationsTest.

---

## Card 7 — Arabic on the other screens

Why: interface-languages tasks 4.3. Compose `Modifier.offset(x = …)` already mirrors in right-to-left; `absoluteOffset` does not. Only fix what the render shows wrong.

Steps:
1. `app/src/dev/kotlin/com/sokoul/pc/dev/PanelRenders.kt` line 91, `listOf("fr", "en")` becomes `listOf("fr", "en", "ar")`.
2. Render `-PrenderMode=panels` and `-PrenderMode=languages`; open every `*-ar-*` / `lang_ar_*` image.
3. For each element on the wrong side, find its modifier; prefer `Alignment.TopEnd`/`Start`/`End` and `padding(start/end)` over absolute values. Change nothing that already reads right to left.
4. Record in `openspec/changes/interface-languages/tasks.md` 4.3 what was fixed and what still looks wrong.

Prompt:
> Do Card 7 of docs/handoff/next-steps.md. Render first and describe each Arabic image before changing anything. Fix only clear mistakes, one screen at a time, re-rendering after each.

---

## Card 8 — The interface tests that already failed

Why: `openspec/changes/rework-sources-audio-start/tasks.md` section 6. These failures exist on the original commit `3b51af7`; two test fixes and one hang fix were made but not re-run.

Steps:
1. Run only: `./gradlew.bat :app:test --console=plain --continue --tests "com.sokoul.pc.InterfaceTest" --tests "com.sokoul.pc.ControlTest" --tests "com.sokoul.pc.VersionsPanelTest" *> build-output\ui-tests.log`. If it runs more than 10 minutes, stop it (Ctrl+C, then `./gradlew.bat --stop`) and report which test was last `STARTED` in the log.
2. For each failure, read the assertion message (the tests now print what was on screen: `tagsOnScreen`, `rowTags`). Decide: is the test outdated (fix the test) or is the app wrong (fix the app)? Write the reason in the task line.
3. Never delete a test to make the suite pass.

Prompt:
> Do Card 8 of docs/handoff/next-steps.md. Run the three test classes only, then for each failure quote the message and tell me whether the test or the app is wrong before changing anything.

---

## Card 9 — Install and check for real (the user does this)

```powershell
./gradlew.bat :app:createDistributable
powershell -NoProfile -File tools/install-desktop.ps1
powershell -NoProfile -File tools/build-tv.ps1
adb connect 192.168.0.108:5555
adb -s 192.168.0.108:5555 install -r build-output/tv/outputs/apk/release/tv-release.apk
```

Then in Sokoul: Settings › Language › Español; open a film page (Spanish title and summary); Settings › Playback › Audio languages (Spanish first) and play *Frozen* (Spanish track); switch to العربية and look at the home page and the player; switch back to Système. Report anything in English or on the wrong side.
