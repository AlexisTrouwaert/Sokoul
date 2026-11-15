# Visual cartography (Apple TV reference)

The source of truth for Sokoul's look: measurements, type, animations and focus states read from Apple TV captures. Sizes are
in reference pixels of a 1920×1080 capture; one `dp` of the app equals one such pixel (`ui/ScaledRoot.kt`). On phones the
same sizes are scaled so that the short side of the screen is 640 dp ([architecture](../architecture.md#form-factors)).

Captures were taken from a 1920×1080 screen recording and from adb captures of a Google TV. They are not kept in the
repository. Measurements are by eye, about ±2 px; colours are indicative, not sampled. The red pictogram of the screen
recording came from the recording app itself and must **not** be reproduced.

## Rules

- Reproduce the Apple TV feel, **never the Apple logo** or Apple branding.
- Font: **Inter**, the closest to SF Pro (SIL OFL).
- Navigation is Sokoul's own: Home, My List, Movies, Series, Anime, Collections, TV.

## Screens

| Screen | Content |
| --- | --- |
| Home, top | Section pill, full-frame hero, carousel dots, start of *Continue watching* |
| Home, rows | Continue, Top 10 series, Top 10 movies, New, Great movies |
| Floating menu | Profile and time, Search, Home, Library, Settings |
| Search, empty | Grid keyboard, abc/123, Explore |
| Search, results | Suggestion pills, tabs (Most popular, Movies, TV and series), title cards on 3 lines |
| Title page, reduced card | Rounded card, neighbouring titles visible; Left/Right changes title |
| Title page, full frame | Title logo, metadata, synopsis, technical badges, buttons, *Featured* |
| Information modal | Glass panel: title, genre, synopsis, Information, Languages, scroll bar |
| Title page, rows | Seasons and episodes, Trailers, Similar, Cast, How to watch, About |
| Player | Title, progress bar, elapsed/remaining time, Info, subtitle and audio buttons, Subtitles menu |
| Settings | Large icon on the left, list on the right |

## Measurements (1920×1080)

| Element | Rest | Focus |
| --- | --- | --- |
| Portrait poster | 260×389, step 300 | 286×428 (×1.10), white caption under the title logo |
| Landscape card (Continue) | 320×~230, step 360 | not measured |
| Episode card | 410×230, step 450 | 450×253 (×1.10), text block on a translucent background below |
| Trailer card | 410×279 | 450×306 (×1.10) |
| Cast portrait | Circle 260, step 300 | Enlarged |
| *How to watch* card | 560×~160 | 610×174, lighter background |
| Menu item | 320×66 | 328×74, white background, black text |
| Pill button | Height 66, dark fill, hairline border | Height 74, off-white fill, black text |
| Round button | Circle 66 | Circle 74, white fill, black icon |
| Floating menu | x 36, y 36, 356×600, corners ~44 | Translucent glass over the content |
| Modal | x 510, y 80, 900×920, corners ~44 | Frosted glass, dimmed background |
| Section pill | Height ~60, chevron `‹` and round icon 50 | |

- Page margin 80 px; gap between cards 40 px.
- Background about `#2C2C2E` on Home, Search and Library; on the title page a blurred gradient taken from the artwork,
  fading to black at the bottom.
- Card corners ~20 px; dark gradient under inlaid text.

These values live in `theme/Theme.kt` (`Metrics`, `NavMetrics`, `CardMetrics`, `Type`, `PanelType`).

## Typography: Inter (SIL OFL)

Chosen by comparing text of equal width on real captures ([font comparison](font-comparison/)). Inter with optical size
`opsz` 14 is closest for small text; Inter Display is used for large titles. Sizes were fitted by matching the text width
of the capture for the assumed weight.

| Use | Size | Style |
| --- | --- | --- |
| Row title | 31 | Bold; grey at rest, white for the active row |
| Pill button | 28 | SemiBold |
| Title-page metadata | 24 | Medium, white |
| *About* text | 22 | Medium |
| Menu | 26 | Regular; Medium with focus |
| Card caption | 22 | Regular, grey; white with focus |
| Search input | 52 | Bold, grey |

## Animations

The reference clips ran at about 6 frames/s on heavy transitions and 15–17 on average: durations are indicative, ±80 ms at
best, and are tuned by eye against the app.

| Animation | Observation |
| --- | --- |
| Card focus | Scale ×1.10 with a shadow; the row title turns white |
| Row change | Vertical scroll; the focused column is kept |
| Card to title page | Over ~1 s: the background darkens, a rectangle of the dominant colour grows from the card to the centre, the image fades in, then the card reaches its final size with its neighbours at the sides. Text fades in ~1.5 s later |
| Title page: reduced to full frame | Triggered by Down; the card expands and the neighbours leave |
| Trailer | Starts by itself after a few seconds of rest on the title page; the image comes back after a move onto the buttons |
| Hero | Trailer after a few seconds, paused when leaving the hero |
| Menu | Opens with Left at the screen edge, as glass over the content |
| Images | Appear desaturated, then return to full colour |
| Modal | Fade and dimming of the background |

## Menu and focus states

- Active section without focus: translucent grey fill.
- Focus: off-white fill, black text, slight scale-up.
- Others: no fill, icon in a grey circle.

## Capture limits

- `screencap` fails once protected video has started; `screenrecord` works.
- Protected video (trailers, player) comes out **black**: only the interface is visible.
- `uiautomator` exposes neither texts nor positions.
