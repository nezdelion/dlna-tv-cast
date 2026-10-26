## Why

Casting a photo or video from an Android phone to a DLNA TV/renderer today needs a third-party controller such as BubbleUPnP, whose free tier is capped at a few playbacks, or YAACC, which only accepts shared URLs, not media files. The common workflow — open Gallery or Files, tap Share, see it on the TV — is not served by any free, ad-free app. We want a small app that does exactly that, streaming the file directly from the phone so playback starts immediately.

## What Changes

- New Android app "DLNA TV Cast" (Kotlin, package `io.github.nezdelion.dlnatvcast`) that appears in the system Share sheet for images, videos and audio, single or multiple items.
- The app streams shared items from the phone over HTTP with byte-range support, so renderers can start playback without waiting for the full file.
- The app discovers DLNA/UPnP MediaRenderers on the local network, remembers the last one used, and allows adding a renderer manually by its description URL.
- The app sends shared items to the selected renderer and provides a remote-control screen: play/pause, seek, stop, previous/next within the shared batch, volume.
- A foreground service with a media notification keeps the stream alive and the controls reachable while the screen is off or the app is in the background.

## Capabilities

### New Capabilities
- `share-intake`: receiving media from other apps through the Android Share sheet and building a playback queue.
- `media-streaming`: serving shared items to renderers over HTTP on the local network, with range requests and access limited to the current session.
- `renderer-discovery`: finding, selecting and remembering DLNA MediaRenderers, including manual addition.
- `playback-control`: driving playback on the renderer and reflecting its state in the app and notification.

### Modified Capabilities

## Impact

- New repository content: Android app module plus a pure-Kotlin core module (no Android dependencies) for protocol logic.
- Runtime permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE` (+ media playback type), `POST_NOTIFICATIONS`. No storage permission; shared content is read through the URIs granted by the sharing app.
- Network exposure: while a session is active the phone listens on a TCP port on the Wi-Fi interface; only items from the current share are reachable, behind unguessable paths.
- Primary acceptance target is the user's `tv-renderer` (mpv-based DMR on the PC); behaviour must stay within standard UPnP AV 1.0 so other DLNA TVs work too.
