# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
- Primary (confirmed 2026-11-20): a broad public, not only the owner. People who watch films, series, anime and live TV, and who arrive with some of: an IPTV subscription (Xtream or M3U), a Real-Debrid account, a Sokoul account, or nothing at all. Most do not know what an "API key", "Xtream" or "debrid" is.
- Situations: the couch with a remote (Android TV / Google TV), a touch tablet held in the hand (portrait or landscape), and the PC app (Compose Desktop).
- The owner also uses the app daily to test Sokoul on a Galaxy Tab S7 FE (LineageOS 23.2) and on Google TV.

## Product Purpose
Sokoul gathers what a person already has access to (IPTV channels and VOD, Real-Debrid, web sources found through the Sokoul server, anime sources) into one catalogue with TMDB metadata, and plays it. Success: from first launch, a person reaches something playable without typing a long key on a remote.

## Positioning
One interface over the person's own sources, with the catalogue and artwork of a premium streaming app, on the TV, the tablet and the PC, with a Sokoul account that carries profiles and progress between devices.

## Operating Context
- Android app module `tv` (com.sokoul.tv) shares its Compose UI with the desktop `app` module; `Platform.IS_TV` is true on every Android device. `MainActivity` picks a `FormFactor`; today a tablet (smallest width ≥ 600 dp) is treated as `Tv` and drawn as a remote-driven 1920-px layout.
- Startup today: animated intro (`SokoulIntro`) → `SetupScreen` (IPTV Xtream fields, API keys, anime backend) → optional `ProfilesScreen` → main interface. `SetupController` validates every credential against the real service before storing anything.
- The Sokoul server (`API/Mainapi`, Node + MySQL + Redis) already provides accounts (BIP39 passphrase, Google, Discord), sessions, profiles, sync, VIP, and a TV link flow (`/api/tv/link`: `/start` → user code + QR URL → `/confirm` on the website → `/poll` returns a JWT once).
- The Sokoul server also runs on the owner's PC (default `http://127.0.0.1:25565`); a TV or tablet needs the PC's LAN address and access key.

## Capabilities and Constraints
- IPTV: Xtream (server, user, password, optional XMLTV guide) and M3U playlists (`PlaylistType`).
- Hosters / services with keys today: TMDB, Real-Debrid, OMDb, fanart.tv, TheTVDB, TVmaze, Watchmode, TasteDive, Movie of the Night, OpenSubtitles, Subdl, Sokoul server address + key, AniProject backend. Built-in keys may exist per build (`DesktopKeys`).
- Credentials are never shown in full, never logged; saved values appear only as a mask (`•••• 1234`).
- Undecided: whether the public site that serves the TV-link page (`PUBLIC_SITE_URL`, default points to an upstream site) is a Sokoul-owned site; which hosters beyond Real-Debrid (AllDebrid, Premiumize, TorBox…) Sokoul will support.

## Brand Commitments
- Binding visual language (owner, 2026-11-15): Apple TV / tvOS HIG, not Material. Single text edge per surface, focus = fill + slight lift, generous type (body ≥ 29, secondary ≥ 25, titles ≥ 48 on the 1920 frame), glass panels from `ui/Panels.kt`, tokens in `theme/Theme.kt`. Generic "settings form" pages were rejected as "brouillon".
- Never expose raw provider data (prefixes, raw codes) in browsing UI.
- Name: Sokoul. Logo asset: `sokoul.png`; animated intro from the Figma file (`SokoulIntro`).

## Evidence on Hand
- Real device for review: Galaxy Tab S7 FE (1600×2560, 340 dpi) over wireless ADB; Google TV.
- No testimonials, user counts or ratings exist; none may be invented.

## Product Principles
1. Nothing to type on a remote when a phone can do it: codes, QR and links before keyboards.
2. Every source is optional; the app is useful with whatever the person has, and says plainly what each source adds.
3. Validate before storing, explain failures in plain words, never lose what was already typed.
4. One account carries profiles and progress across TV, tablet and PC.
5. Credentials stay private: masked on screen, absent from logs.

## Accessibility & Inclusion
- Must work with a D-pad alone (TV), with touch alone (tablet/phone), and with a hardware keyboard.
- Large text by default; respect the system font scale on touch devices.
