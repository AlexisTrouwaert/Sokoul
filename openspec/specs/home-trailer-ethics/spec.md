# home-trailer-ethics Specification

## Purpose
When a hero trailer may start, whether it makes sound, and how the viewer stops it, so that browsing never turns into unwanted playback.

## Requirements

### Requirement: Trailers are silent unless asked
A hero trailer SHALL play without sound unless the viewer has turned hero sound on.

#### Scenario: Default settings
- **WHEN** the hero trailer starts with default settings
- **THEN** no audio is output

### Requirement: Trailers wait for steady focus
A hero trailer SHALL start only after the hero has kept focus for a short, fixed delay, and SHALL stop when focus leaves.

#### Scenario: Passing over the hero
- **WHEN** focus crosses the hero for less than the delay
- **THEN** no trailer starts

#### Scenario: Leaving the hero
- **WHEN** a trailer plays and focus moves to a row
- **THEN** the trailer stops and releases its resources

### Requirement: Trailers can be stopped and disabled
The viewer SHALL be able to stop a playing trailer with one remote action, and to disable automatic trailers in Settings.

#### Scenario: Automatic trailers off
- **WHEN** automatic trailers are disabled
- **THEN** the hero stays on its still image and slides change on the timer

### Requirement: Audio focus when sound is on
On Android TV, when hero sound is on, the app SHALL request audio focus before playing and SHALL pause or lower the trailer when focus is lost.

#### Scenario: Another app takes audio
- **WHEN** another application takes audio focus during a trailer
- **THEN** the trailer pauses or lowers its volume and does not overlap the other audio
