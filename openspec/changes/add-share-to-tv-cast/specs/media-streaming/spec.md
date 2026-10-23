## Purpose

Makes shared items reachable by the renderer over the local network as ordinary HTTP URLs, with random access so playback starts without transferring the whole file first.

## ADDED Requirements

### Requirement: HTTP access to queue items
While a cast session is active, the app SHALL serve each queue item over HTTP on the phone's Wi-Fi IPv4 address. Responses SHALL carry the item's MIME type and, when known, its length. The served URL SHALL be what the app hands to the renderer.

#### Scenario: Renderer fetches a photo
- **WHEN** the renderer issues `GET` for the URL of a queued JPEG
- **THEN** the phone responds `200` with `Content-Type: image/jpeg`, the full content and a correct `Content-Length`

#### Scenario: HEAD request
- **WHEN** the renderer issues `HEAD` for a queued item URL
- **THEN** the phone responds with the same status and headers as for `GET`, without a body

### Requirement: Byte-range support
The server SHALL honour single byte-range requests (`Range: bytes=a-b`, `bytes=a-`, `bytes=-n`) with `206 Partial Content` and a correct `Content-Range`, and SHALL advertise `Accept-Ranges: bytes`. Unsatisfiable ranges SHALL get `416` with `Content-Range: bytes */<length>`. Multi-range requests MAY be answered with the full content.

#### Scenario: MP4 with index at the end
- **WHEN** the renderer first requests the last 1 MiB of a queued video and then requests from byte 0
- **THEN** both requests are answered with `206` and the matching bytes, and playback can start before the rest of the file has been transferred

#### Scenario: Range past the end
- **WHEN** the renderer requests `bytes=999999999-` for a 10 MB file
- **THEN** the phone responds `416` with `Content-Range: bytes */10485760`

### Requirement: DLNA-compatible response headers
Responses for media items SHALL include `transferMode.dlna.org` (`Streaming` for audio/video, `Interactive` for images) when the request asks for it, and `contentFeatures.dlna.org` indicating byte-seek support, so that strict DLNA TVs accept the stream.

#### Scenario: Strict TV asks for DLNA headers
- **WHEN** the renderer sends `getcontentFeatures.dlna.org: 1`
- **THEN** the response includes a `contentFeatures.dlna.org` header with `DLNA.ORG_OP=01`

### Requirement: Access limited to the current session
Only items of the current queue SHALL be reachable, each under a path containing an unguessable random token generated per session. Requests for any other path SHALL get `404`. When the session ends, the server SHALL stop listening.

#### Scenario: Guessing a path
- **WHEN** another device on the network requests `/` or a path with a wrong token
- **THEN** the phone responds `404` and reveals no file names

#### Scenario: Session ended
- **WHEN** the user stops casting
- **THEN** the HTTP port is closed and previous item URLs no longer connect

### Requirement: Streaming continues in the background
Serving SHALL continue while the app is in the background or the screen is off, as long as the cast session is active.

#### Scenario: Screen turns off during a movie
- **WHEN** a video is playing on the renderer and the phone screen turns off
- **THEN** the renderer keeps receiving data and playback continues
