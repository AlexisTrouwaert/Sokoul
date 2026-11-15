# home-ambient-backdrop Specification

## Purpose
The faint colour a title page takes from its artwork, and the limits that keep it free on a TV's graphics processor.

## Requirements

### Requirement: Poster-tinted backdrop
The title page SHALL tint its background with a muted colour taken from the title's artwork, over Sokoul's dark base.

#### Scenario: Changing title
- **WHEN** the viewer opens a different title
- **THEN** the backdrop colour changes to match its artwork, together with the artwork and without a colour animation, and the text stays readable

### Requirement: Frame budget on TV
The backdrop SHALL NOT make navigation on the reference TV device drop below its normal frame rate, and SHALL fall back to a flat colour when it would.

#### Scenario: Measured on TV
- **WHEN** the title page is navigated on the TV with the backdrop on
- **THEN** the frame-time measurement stays within the baseline measured without it

### Requirement: No full-screen blur on TV
Large-area live blur SHALL NOT be used on TV; panels SHALL use a pre-darkened translucent fill instead.

#### Scenario: Panel over the hero
- **WHEN** a side panel opens over the page on TV
- **THEN** the page behind is dimmed without a live blur pass
