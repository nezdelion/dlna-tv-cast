## Purpose

Lets the user send photos, videos and audio from any app (Gallery, Files, messengers) to a TV through the standard Android Share sheet.

## ADDED Requirements

### Requirement: Share sheet entry for media
The app SHALL appear in the Android Share sheet for single and multiple items whose MIME type is `image/*`, `video/*` or `audio/*`. The app MUST NOT appear for other types (for example `text/plain` or `application/pdf`).

#### Scenario: Share one video from Gallery
- **WHEN** the user selects a video in Gallery and taps Share
- **THEN** the app is listed as a share target

#### Scenario: Share several photos
- **WHEN** the user selects five photos in Gallery and taps Share
- **THEN** the app is listed as a share target and receives all five items

#### Scenario: Unsupported type
- **WHEN** the user shares a PDF document
- **THEN** the app is not listed as a share target

### Requirement: Playback queue from a share
Each share SHALL create a new playback queue containing the shared items in the order provided by the sharing app, replacing any previous queue. Items the app cannot open SHALL be skipped and the user SHALL be told how many were skipped. If no item can be opened, the app SHALL show an error and MUST NOT contact the renderer.

#### Scenario: Mixed share with an unreadable item
- **WHEN** the user shares three items and one of them cannot be opened
- **THEN** the queue contains the two readable items in their original order and the app reports that one item was skipped

#### Scenario: New share replaces the queue
- **WHEN** a queue is playing and the user shares a different photo
- **THEN** the previous queue is discarded and the new photo is sent to the renderer

### Requirement: Fast path to the TV
After a share, if a previously used renderer is available on the network, the app SHALL start playback of the first queue item on it without additional user interaction and SHALL show the remote-control screen. If no previously used renderer is available, the app SHALL show the renderer picker first.

#### Scenario: Remembered renderer is online
- **WHEN** the user shares a video and the last used renderer "TV" responds on the network
- **THEN** the video starts on "TV" and the remote-control screen opens, with no picker shown

#### Scenario: First use
- **WHEN** the user shares a photo and no renderer has been used before
- **THEN** the renderer picker is shown, and after the user picks a renderer the photo is sent to it
