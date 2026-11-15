# home-loading-feedback Specification

## Purpose
What the home page shows while its hero and rows are loading, so that the layout never jumps and the remote is never held up by content that has not arrived.

## Requirements

### Requirement: Placeholders while the home page loads
The home page SHALL show placeholder shapes in the place of the hero and of each row whose data is not yet available, with the same size as the final content.

#### Scenario: First open with an empty cache
- **WHEN** the home page opens and no row has data yet
- **THEN** the hero area and the first rows show placeholders and no row appears or disappears abruptly below them

#### Scenario: A row arrives
- **WHEN** the data of a row arrives
- **THEN** its placeholder is replaced in place and the rows below do not move

#### Scenario: A row ends up with too few titles
- **WHEN** a row loads with too few titles to be shown
- **THEN** its placeholder is removed without leaving an empty gap

### Requirement: Placeholders never block navigation
Placeholders SHALL NOT take focus and SHALL NOT delay the remote from reaching loaded rows.

#### Scenario: Moving down during loading
- **WHEN** the viewer presses down while later rows are still placeholders
- **THEN** focus moves to the next loaded row, skipping placeholders
