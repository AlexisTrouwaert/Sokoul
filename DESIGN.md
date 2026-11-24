# DESIGN.md — Sokoul

Visual authority for every Sokoul surface (TV, tablet, PC). Binding direction from PRODUCT.md: Apple TV / tvOS, not Material.
Figma: "Sokoul — Réglages" file, pages "Sokoul · Système" and "Sokoul · Écrans". Code: `app/src/main/kotlin/com/sokoul/pc/theme/Theme.kt`.

## 1. World

Sokoul is a living room at night. The interface is a dark, quiet frame; the films bring the colour. Nothing in the chrome
competes with a poster.

- **Monochrome chrome.** Interface elements use only black, greys and white. Colour exists in exactly three places:
  artwork (heroes, posters, stills, channel logos as supplied), the live dot (system red `#FF3B30`, a state, never decoration),
  and the image-derived tint of a hero's lower edge. No coloured icon tiles, no coloured category chips, no team-colour
  cards, no blue switches.
- **The image leads.** Every browsing page opens on full-bleed artwork. Chrome sits on it with scrims, never in boxes.
- **Glass is for overlays only.** Panels, the navigation pill and menus are glass because they float over moving pictures.
  Pages themselves are never glass.
- **Focus is light.** The focused element fills white (`#F5F5F7`) with dark ink, lifts (scale), and casts a soft shadow
  below. Nothing else changes colour on focus.

## 2. Frame and grid

| Context | Frame (dp) | Margin | Gutter | Notes |
|---|---|---|---|---|
| TV / tablet landscape | 1920 × 1080 / 1920 × 1200 | 80 | 40 | `ScaledRoot` maps 1 dp to 1 px of a 1920 width |
| Tablet portrait | 1200 × 1920 | 64 | 32 | same physical scale as landscape (ScaledRoot must scale by the short side) |
| Phone portrait | 1080 × 1920 | 48 | 24 | sections move to the bottom bar |

- One text edge per surface: every title, row and caption on a page starts at the margin.
- Vertical rhythm on an 8 dp grid. Space above a section title (56) is larger than below it (20).
- Rows of cards bleed past the right margin to show that they scroll; never past the left one.

## 3. Colour tokens (Figma collection "Sokoul", code `Palette`/`Glass`)

| Token | Value | Use |
|---|---|---|
| `fond/canvas` | `#000000` | every page behind artwork |
| `surface/plaque` | `#1C1C1E` | rows at rest in lists, search field, inactive pills |
| `surface/pilule` | `#2C2C2E` | round buttons at rest, segmented controls |
| `surface/verre` | `#1C1C1E` @ 72 % + background blur 60 + top sheen 8 % | panels, nav pill, menus, toasts |
| `focus/fond` | `#F5F5F7` | focused element fill |
| `focus/encre` | `#0B0B0C` | text and icons on focus |
| `texte/principal` | `#FFFFFF` | titles, rows |
| `texte/secondaire` | `#FFFFFF` @ 60 % | metadata, values, captions (≥ 4.5:1 on black) |
| `texte/tertiaire` | `#FFFFFF` @ 38 % | disabled, timestamps in the past |
| `bordure/filet` | `#FFFFFF` @ 12 % | the one hairline a glass surface may carry |
| `etat/direct` | `#FF3B30` | live dot and LIVE label only |
| `ombre/levee` | `#000000` @ 50 %, y 18, blur 40 | focused element |

## 4. Typography (Inter; Inter Display for ≥ 48)

Floors from PRODUCT.md: body ≥ 29, secondary ≥ 25, titles ≥ 48 on the 1920 frame.

| Style | Size / line | Weight | Tracking | Use |
|---|---|---|---|---|
| Titre héros | 96 / 96 | Bold (Display) | −2 % | hero title when no logo art exists |
| Grand titre | 64 / 72 | Bold (Display) | −1.5 % | page titles (Recherche, Réglages, Qui regarde ?) |
| Titre | 48 / 56 | Semi Bold (Display) | −1 % | panel titles, section heads in settings |
| Titre de rangée | 36 / 44 | Semi Bold | −0.5 % | rails ("Continuer à regarder") |
| Corps fort | 31 / 40 | Semi Bold | 0 | list rows, card titles, buttons |
| Corps | 29 / 40 | Regular | 0 | synopsis, descriptions |
| Secondaire | 25 / 32 | Medium | 0 | metadata, values, card subtitles |
| Légende | 25 / 32 | Semi Bold, caps | +4 % | section labels in lists (never as an eyebrow above a heading) |
| Badge | 20 / 24 | Bold | +2 % | 4K, HDR, VF, MULTI in outlined capsules |

Metadata is one line of Secondaire separated by " · ". Never more than one weight step between neighbours.

## 5. Icons

The Sokoul set (Figma "Icône Sokoul/*"): the eight `NavIcons.kt` paths unchanged plus 26 drawn to match: filled, soft
corners, 24 grid, round strokes 2–2.4. Always monochrome (white at rest, ink on focus, 60 % when secondary). Sizes 28
(inline), 34 (buttons), 40 (player transport), 96–160 (settings hero glyph). Never inside a coloured tile.

## 6. Components

| Component | Rest | Focus | Notes |
|---|---|---|---|
| Onglet (nav) | icon + label white 70 % | white fill, ink, scale 1.06 | active section: white 18 % fill |
| Barre de navigation | glass pill, avatar left, search right | — | hides after 1.2 s on TV, always visible on touch |
| Barre du bas (portrait) | glass bar, 7 sections, icon 30 + label 20 | — | active: white; others 55 % |
| Bouton principal | white capsule 72, ink label, play glyph | scale 1.08 + shadow | one per surface |
| Bouton rond | 72 circle `surface/pilule`, white glyph | white fill, ink glyph, scale 1.1 | secondary actions |
| Affiche | 2:3, radius 16, no border | scale 1.08, shadow, specular glint | title under only in grids |
| Vignette | 16:9, radius 16, progress 4 dp white on 30 % | scale 1.06 + shadow | title + meta below |
| Ligne de liste | 88 tall, radius 18, `surface/plaque` at 0 % | white fill, ink, scale 1.02 | value right in Secondaire, chevron 60 % |
| Valeur on/off | text "Activé" / "Désactivé" | — | tvOS style: no switch controls |
| Panneau | glass, radius 40, padding 48 | — | title 48, rows below |
| Badge | 1.5 px outline 60 %, radius 6 | — | never filled with colour |

## 7. Motion (kept from the app)

- Panels open in 220 ms, curve (0.2, 0, 0, 1): fade + 0.97 → 1, or slide 40 dp from their edge.
- Focus changes in 140 ms (rows, chips); cards and buttons scale on a spring (damping 0.8, stiffness 400).
- Page change: the hero cross-fades 300 ms; the next page's title slides 24 dp up while fading in.
- The navigation bar fades in 220 ms and hides after 1.2 s on TV (0.7 s in the guide).
- Dead end: nudge 10 dp toward the key in 90 ms, then spring back.
- Loading: skeletons in `surface/plaque` with a 1.4 s shimmer; never a spinner over content.
- Touch: a tap activates and leaves no highlight; a press darkens the element 8 % while held.
