# home-row-curation Specification

## Purpose
How much a home row offers at once, and the single suggestion offered to a viewer who cannot decide.

## Requirements

### Requirement: Readable row length
Each home row SHALL show at most a fixed number of titles (between seven and ten), with a way to open the full list.

#### Scenario: A long source
- **WHEN** a row has more titles than the cap
- **THEN** only the capped number is shown, followed by an entry that opens the full list

### Requirement: A single suggestion for the undecided
The home page SHALL offer one "Tonight" suggestion, chosen from the profile's history and list, with a short reason.

#### Scenario: Profile with history
- **WHEN** the viewer opens the suggestion
- **THEN** one title is shown with a reason such as "Because you watched X", and a way to ask for another

#### Scenario: Profile without history
- **WHEN** the profile has no history and an empty list
- **THEN** the suggestion is hidden
