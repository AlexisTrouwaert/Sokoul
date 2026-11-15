# home-focus-navigation Specification

## Purpose
How remote-control focus behaves on the home page: coming back into a row, pressing toward a dead end, and staying clear of the TV's edges.

## Requirements

### Requirement: Return to the last focused card
When focus re-enters a home row, it SHALL land on the card that had focus when the viewer left that row, if it still exists.

#### Scenario: Down then up
- **WHEN** the viewer moves to the tenth card of a row, goes down one row, then up
- **THEN** focus returns to the tenth card of the first row, not its first card

#### Scenario: The remembered card is gone
- **WHEN** the row content changed and the remembered card no longer exists
- **THEN** focus lands on the nearest remaining card

### Requirement: Feedback at a dead end
When the viewer presses a direction with nothing to move to, focus SHALL stay where it is and the focused element SHALL give a brief movement in that direction.

#### Scenario: End of a row
- **WHEN** focus is on the last card of a row and the viewer presses right
- **THEN** focus stays on that card and it moves briefly toward the right and back

### Requirement: Safe margins on TV
On a TV, no focusable element SHALL be drawn closer to a screen edge than the overscan margin.

#### Scenario: Focused card at the left edge
- **WHEN** the first card of a row is focused at its largest scale
- **THEN** it stays inside the horizontal safe margin (about 5% of the width)
