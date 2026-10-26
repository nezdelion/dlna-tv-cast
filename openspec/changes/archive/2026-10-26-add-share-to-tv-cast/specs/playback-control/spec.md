## Purpose

Drives playback of the shared queue on the selected renderer and keeps the phone's controls in sync with what the TV is doing.

## ADDED Requirements

### Requirement: Start an item on the renderer
To play a queue item, the app SHALL send the item URL with DIDL-Lite metadata (title, UPnP class matching image/video/audio, and `res` with protocolInfo and size) via AVTransport `SetAVTransportURI`, followed by `Play`. If the renderer returns a UPnP error, the app SHALL show it to the user and MUST NOT retry silently in a loop.

#### Scenario: Play a video
- **WHEN** the first queue item is a video and the renderer is selected
- **THEN** the renderer receives `SetAVTransportURI` with the item URL and then `Play`, and reports `PLAYING`

#### Scenario: Renderer rejects the media
- **WHEN** the renderer answers `SetAVTransportURI` with a UPnP fault
- **THEN** the app shows an error with the fault description and stays on the current item

### Requirement: Remote controls
The remote-control screen SHALL offer play/pause, stop, seek by dragging a position bar, previous/next item when the queue has more than one item, and volume up/down/mute when the renderer exposes RenderingControl. Controls not applicable to the current item SHALL be hidden or disabled (for example, seek for images).

#### Scenario: Seek in a video
- **WHEN** the user drags the position bar to 1:30 during playback
- **THEN** the app sends `Seek` with unit `REL_TIME` and target `0:01:30`, and playback continues from there

#### Scenario: Next photo
- **WHEN** the queue holds five photos, the second is shown, and the user taps Next
- **THEN** the third photo is sent to the renderer

### Requirement: State synchronisation
While a session is active, the app SHALL poll the renderer at least once per second while the screen is on, and at least every 5 seconds otherwise, using `GetTransportInfo` and `GetPositionInfo`, and SHALL reflect transport state, position and duration in the remote-control screen and the notification. Changes made on the renderer side (for example pausing with the PC keyboard) SHALL appear on the phone within 2 seconds while the screen is on.

#### Scenario: Paused on the PC
- **WHEN** the user presses Space in the mpv window on the PC
- **THEN** the phone shows the paused state within 2 seconds

### Requirement: Queue advance
When a video or audio item finishes on the renderer (transport goes to `STOPPED` after having played, and the position was near the end or the renderer reports the end), the app SHALL automatically start the next item. Images SHALL stay on screen until the user moves to another item. After the last item the session SHALL remain open with playback stopped.

#### Scenario: Two videos shared together
- **WHEN** the first of two shared videos ends
- **THEN** the second video starts on the renderer without user action

#### Scenario: User stops on the PC
- **WHEN** the user presses `q` on the PC during the first of two videos, far from the end
- **THEN** the app shows the stopped state and does not start the second video

### Requirement: Foreground session with notification
While a session is active, the app SHALL run a foreground service showing a media-style notification with the current item title, play/pause, next (when applicable) and a stop action. The stop action SHALL send `Stop` to the renderer, end the session and close the HTTP server.

#### Scenario: Stop from the notification
- **WHEN** the user taps Stop in the notification
- **THEN** the renderer stops playback, the notification disappears and the phone no longer serves the items

### Requirement: Renderer lost
If the renderer stops responding for 15 seconds during a session, the app SHALL show that the connection to the TV was lost and SHALL offer to retry or end the session; it MUST NOT end the session silently.

#### Scenario: PC goes to sleep
- **WHEN** the PC running the renderer suspends during playback
- **THEN** within 15 seconds the phone shows "connection to TV lost" with Retry and End options
