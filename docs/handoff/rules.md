# Handoff rules — Sokoul

For the agent continuing the work (Codex). Read this before every card, then the card you are given in `docs/handoff/next-steps.md`. Do only that card, then stop and report.

## Before any edit

- Quote or cite the exact current lines of every file you will change.
- Search for every function, class, enum, key and test tag before using it.
- Follow the card only: a nearby TODO or OpenSpec task is not permission to implement another card.
- A plan is not implementation. Show the plan for Cards 5 and 8 before changing code.
- Card 0 is a user-only Git procedure. Never switch branches or commit unless the user explicitly requests it in the current message.

## What Sokoul is

Kotlin media centre: Compose Desktop app for Windows (`app/`, libVLC through vlcj) and an Android TV app (`tv/`, Media3) that compiles `app/src/main/kotlin` too (minus files listed in `tv/build.gradle.kts` → `syncShared`). `core/` and `parser/` are plain Kotlin. Full rules: `CLAUDE.md`, `AGENTS.md`. Design: Apple TV look (`theme/Theme.kt`, `docs/design/cartography.md`).

## Rules you must follow

1. **Never guess a file, function or field.** Before using a name, open the file and find it (`grep`/search). If it does not exist, say so and stop. Do not invent APIs.
2. **One task card at a time.** Small diffs. Do not refactor what the card does not ask for. Do not touch unrelated files.
3. **Code, comments, logs, tests in English.** User-visible text is written `L("English", "Français")` (or a `Text` entry in `pc/i18n/Strings.kt`). Never hard-code another language.
4. **Every new or changed user-visible English text needs translations** (see "Translations" below). A text without translation shows in English: acceptable for a moment, never leave it at the end of a card.
5. **Reuse existing components**: rows `SettingsRow`, `ChoiceRow`, `GroupTitle`, `ConfirmRow` (`library/LibraryAndSettings.kt`, `library/SettingsSections.kt`); player banners like `UpgradeBanner`/`LanguageNoticeBanner` (`playback/ui/PlayerPanels.kt`); reorder screen pattern `library/HomeRowsScreen.kt` / `library/AudioLanguagesScreen.kt`. No new visual style.
6. **Audio logic lives in one place**: `playback/sources/AudioMatch.kt` (verdict, `pickTrack`, `missingWanted`, `pickSubtitle`). Preferences: `AudioIntent` in `desktop/data/DesktopPrefs.kt`. Do not add language rules anywhere else, and never special-case French: every language goes through the ordered list (`AudioIntent.languages`, `"original"` = the work's language).
7. **Never write to the user's real data** (`%APPDATA%\SokoulPC\sokoul.db`) and never drive the user's running app with keys. Tests use temporary databases (see `SettingsTest`, `AudioMatchTest`).
8. **Never commit, push, or change git branches** unless the user asks in that message.
9. **Never print or log keys** (`Redact` masks them).

## How to work on a card

1. Read the card, then open every file it names and find every function it names. Quote the lines you will change.
2. If something the card names does not exist or looks different, stop and say so. Do not adapt the card on your own.
3. Edit, then run the card's checks yourself. Never report a check you did not run.
4. End with the card's checklist, each line with the command and its result (test counts from the XML).

## How to verify (say which commands really ran, and their result)

Run from the repository root in PowerShell:

| Change | Command |
| --- | --- |
| Kotlin compiles | `./gradlew.bat :app:compileKotlin --console=plain` |
| Targeted tests | `./gradlew.bat :app:test --console=plain --continue --tests "com.sokoul.pc.<TestClass>"` |
| Core tests | `./gradlew.bat :core:test --console=plain` |
| A screen | `./gradlew.bat :app:renderScreens -PrenderOutput=$env:TEMP\sokoul-renders -PrenderMode=<mode>` then open the PNGs (modes: `panels`, `rows`, `settings`, `languages`, see `docs/testing.md`) |
| TV build | `powershell -NoProfile -File tools/build-tv.ps1` |
| Translations | `python tools/i18n-extract.py` then `python tools/i18n-check.py es de nl it pt tr ar` |

Test results: `build-output/app/test-results/test/TEST-*.xml` (`failures="0" errors="0"`). A compile does not prove a screen looks right: render it.

## Known traps (each one happened already)

- **Do not run the full `:app:test`** (487 tests, several minutes, one UI test hung before). Run the test classes of your card only.
- **PowerShell + Gradle output**: `2>&1 | Tee-Object` buffers and shows nothing. Use `--console=plain` and redirect to a file: `*> build-output\run.log`.
- **"Unable to delete directory …test-results\test\binary"** = a previous test run still holds the file. Run `./gradlew.bat --stop`, delete `build-output\app\test-results\test\binary`, run again.
- **UI tests that never reach idle**: an endless loop in a composable (`while (true) { delay() }`) must run in `withContext(Dispatchers.Default)` (see `home/Screensaver.kt`, `collections/SagaScreen.kt`).
- **Tests must not depend on the machine's language**: build preferences explicitly, e.g. `PlaybackPrefs(intent = AudioIntent(listOf("fr", AudioIntent.ORIGINAL)))`. `PlaybackPrefs()` alone follows the system locale.
- **Python helper scripts**: write them to a file and run `python -I file.py`; inline heredocs have mangled backslashes (`\b` became a control character).
- **`L(...)` only accepts two literals for extraction**. If you declare English/French pairs elsewhere (enum fields, helper parameters), add a pattern to `OTHER_PAIRS` in `tools/i18n-extract.py`, or the text will never be translated.

## Translations

Catalogues: `app/src/main/resources/i18n/<code>.tsv` (`en` is the key, `fr` stays in the code). Languages: `es de nl it pt tr ar`. Format: one line per text, `English<TAB>translation`; `\n` and `\t` escaped; values `{1}` `{2}` (from `$x` in `L()`), `%s` `%d` `%1$s` must be kept exactly. Steps after any text change:

1. `python tools/i18n-extract.py` (rewrites `source.tsv`; never edit it by hand).
2. `python tools/i18n-check.py es de nl it pt tr ar` → it lists `missing` keys.
3. Add one line per missing key to **each** of the 7 files, copying the English key exactly from `source.tsv`. Use the vocabulary already in that file (look at neighbouring lines: Settings = Ajustes / Einstellungen / Instellingen / Impostazioni / Ajustes / Ayarlar / الإعدادات).
4. Run the check again: every language must read `missing 0 | placeholder mismatch 0`, and `TranslationsTest` must pass.

## Where the work stands

Two OpenSpec changes hold the decisions and the task lists — read the relevant one before coding:
- `openspec/changes/rework-sources-audio-start/` (sources, audio in any language, session): design D1–D6, `tasks.md`.
- `openspec/changes/interface-languages/` (nine interface languages, RTL): `design.md`, `tasks.md`.
When you finish a card, tick it in the right `tasks.md` with the commands you ran and their result, and run `openspec validate <change> --strict`.
