## Context

Greenfield Android app. Motivation is in proposal.md; behaviour is defined in `specs/`. Environment on the dev machine: Android SDK at `~/Android/Sdk` (platforms 35–36.1, build-tools up to 36.1, emulator + `Medium_Phone` AVD on API 36.1 Play image), JDK 21, no system Gradle (use the wrapper). The primary renderer under test is the user's `tv-renderer` (`~/.local/bin/tv-renderer`, Python, UPnP AV 1.0 DMR driving mpv, LAN `192.168.50.23:49494`, friendly name `TV`); it implements AVTransport, RenderingControl, ConnectionManager and accepts `http-get:*:*:*`.

## Goals / Non-Goals

**Goals:**
- Protocol logic testable on the JVM without a device: HTTP range server, SSDP, SOAP, DIDL-Lite, queue/advance state machine.
- Few dependencies; no third-party UPnP stack.

**Non-Goals:**
- Media library browsing, Chromecast/AirPlay, transcoding, playlists from network servers, multi-renderer playback, GENA event subscriptions (polling is enough at this scale), IPv6.
- Play Store publishing; distribution is a debug/release APK sideloaded via `adb install`.

## Decisions

### 1. Two Gradle modules: `core` (pure Kotlin/JVM) and `app` (Android)
`core` holds the HTTP server, SSDP client, SOAP client/parsers, DIDL-Lite builder, renderer model and the session state machine; it depends only on the Kotlin stdlib and kotlinx-coroutines. `app` holds activities, the foreground service, notification, persistence and the Android adapters (content URI → byte source, Wi-Fi address, multicast lock).
Alternative: single module — faster to start, but protocol tests would need Robolectric or a device.

### 2. Byte source abstraction for served items
`core` serves items through an interface `ByteSource { length: Long?; mime: String; open(offset): InputStream }`. On Android it is backed by `ContentResolver.openAssetFileDescriptor(uri, "r")` + `FileInputStream.channel.position(offset)`, which gives random access for MediaStore/SAF URIs without copying. Fallback when a provider returns a non-seekable stream: skip `offset` bytes (correct, slower). Length from `AssetFileDescriptor.length` or `OpenableColumns.SIZE`.
Alternative: copy shared files into app cache first — simple but delays playback by the whole file, which is what we are avoiding.

### 3. Minimal HTTP/1.1 server on raw sockets
A small coroutine-based server in `core`: `ServerSocket` bound to the Wi-Fi IPv4 address on an ephemeral port, one coroutine per connection, keep-alive, `GET`/`HEAD`, single-range parsing, DLNA headers, streaming with a fixed buffer and `Dispatchers.IO`. URL layout: `http://<ip>:<port>/<sessionToken>/<index>/<sanitised-name>.<ext>`; token = 128-bit `SecureRandom`, hex. Connection work runs on the server's own threads (not `Dispatchers.IO`). A new connection must deliver its request headers within an absolute 10 s deadline; at most 16 connections may be waiting for a first request (the oldest is dropped to admit a new one) and at most 16 may be serving items (further ones get 503); a request without the session token gets its 404 and the connection is closed. Items of unknown length are served whole and advertise `DLNA.ORG_OP=00`, no `Accept-Ranges`.
Alternative: NanoHTTPD or Ktor server — NanoHTTPD is unmaintained and its range handling is DIY anyway; Ktor adds several MB and Netty/CIO complexity for ~300 lines of needed behaviour.

### 4. SSDP and SOAP by hand
SSDP: `MulticastSocket` M-SEARCH for `urn:schemas-upnp-org:device:MediaRenderer:1` (MX=2, sent 3 times), collect unicast `LOCATION` responses, fetch and parse the device description with the namespace-aware `javax.xml.parsers` DOM parser, which exists both on the JVM and on Android, so `core` needs no parser interface (DOCTYPE/external entities disabled). The datagram I/O sits behind an `SsdpTransport` interface so discovery can be tested without multicast. Android needs a `WifiManager.MulticastLock` while searching.
SOAP: POST with `SOAPACTION` header and envelope, response parsed for `out` arguments or `UPnPError`. Descriptions and SOAP go through a minimal HTTP/1.1 client on raw sockets (plain `http` only, Content-Length/chunked/close-delimited bodies, 256 KiB body limit): each request has a total deadline and cancellation closes the socket, so a hung device can never hold up shutdown or the 3 s remembered-renderer budget (`HttpURLConnection.disconnect()` blocks while another thread reads). XML from the network passes a parser-independent structure check first: any `<!DOCTYPE`/`<!ENTITY` is refused (Android's DOM factory silently ignores the hardening features), depth ≤ 32, ≤ 5000 elements. Invalid service URLs (bad `URLBase`, non-http control URLs) make the device an invalid renderer, and discovery fetches at most 4 descriptions at a time, skipping any device that fails. Protocol strings are formatted with `Locale.ROOT`. Actions used: `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetTransportInfo`, `GetPositionInfo`, `GetVolume`, `SetVolume`, `GetMute`, `SetMute`.
Alternative: Cling/jUPnP — heavy, Cling is abandoned, jUPnP pulls Jetty/OSGi-era dependencies.

### 5. Session state machine in `core`
`CastSession` owns the queue, current index, renderer client and a poll loop (1 s foreground / 5 s background, switched by the service on screen on/off). Polling is fixed-rate (the interval runs from the start of a poll) and `GetTransportInfo`/`GetPositionInfo` are sent in parallel, so a slow network does not push renderer-side changes past the 2 s target (on the emulator every TCP connect costs ~0.5–1 s). Auto-advance rule: transition `PLAYING → STOPPED` (or `NO_MEDIA_PRESENT`) for audio/video where the last known position was within 3 s of the duration, or duration unknown and the stop was not user-initiated from the app → start next. A `STOPPED` seen while the last command from the app was `Stop`, or far from the end, is treated as a user stop (matches the `q` scenario). The last polled position is projected forward by the time elapsed since that poll (while the item was playing), so the 5 s background interval does not hide an end that happened between polls. Projection is dropped after any failed poll, and after such a gap an unknown duration no longer counts as an end, so a stop during an outage never advances. Images never auto-advance. Renderer lost = no answer for 15 s since the last successful poll, published by a separate watchdog so request timeouts do not delay it; polling continues and the flag clears when the renderer answers again or the user taps Retry. Volume/mute are refreshed by a separate loop (every 5 s) so a slow RenderingControl never delays transport polls. The session owns a job: startup, polls and commands run in it, `close()` marks it closing, cancels and joins that work, then sends the final Stop; later commands are ignored. Exposed as `StateFlow<SessionState>` consumed by UI and notification.

### 6. Android shell
- `ShareActivity` (exported, `Theme.NoDisplay` trampoline, SEND / SEND_MULTIPLE for `image/*`, `video/*`, `audio/*`) takes persistable-less URI grants, starts `CastService` with the URIs it actually holds a grant for (passing `FLAG_GRANT_READ_URI_PERMISSION` + `ClipData` so the service keeps access; forwarding a URI without a grant would throw `SecurityException`, so such URIs are only counted as skipped), then opens `MainActivity`, which shows preparing / picker / remote screens from the service state.
- `CastService`: foreground service type `mediaPlayback`, owns `CastSession` and the HTTP server, `MediaSessionCompat`-free media-style notification (plain `NotificationCompat.MediaStyle` with actions) to avoid hijacking phone media buttons; stops itself when the session ends.
- UI: Jetpack Compose, two screens (renderer picker with manual-add dialog, remote control). Material 3.
- Persistence: `SharedPreferences` (last renderer UDN + location, manual renderers list).
- Manifest extras beyond the proposal: `ACCESS_NETWORK_STATE` (needed to find the Wi-Fi/Ethernet network and its IPv4 address through `ConnectivityManager`) and `usesCleartextTraffic="true"` (device descriptions and SOAP are plain HTTP on the LAN).
- Remembered-renderer lookup (`RendererDiscovery.resolve`) runs the old description URL and SSDP in parallel and takes the first answer with the same UDN within 3 s.
- `minSdk 29` (Android 10), `targetSdk 36`, `compileSdk 36`. Package `io.github.nezdelion.dlnatvcast`.

### 7. Testing strategy
- `core` unit tests (JUnit 5): range parsing and responses against a real socket, DIDL-Lite escaping, SOAP envelope/fault parsing, SSDP response parsing, state-machine transitions with a fake renderer.
- `core` integration test (tagged, opt-in via `-PwithRenderer=http://192.168.50.23:49494/description.xml`): runs the HTTP server on the PC and drives the real `tv-renderer` through a full play/seek/pause/stop cycle using a generated test video, then cleans up (`Stop`).
- Emulator smoke test of the Android shell: emulator networking is NAT'ed, so SSDP to the LAN does not work there; use manual renderer entry pointing at `10.0.2.2:49494` plus a debug-only "advertised host" override + `adb forward`/emulator `redir` so the PC renderer can reach the emulator's server. The override MUST NOT exist in release builds.
- Real-phone acceptance is manual (see tasks).

## Risks / Trade-offs

- [Some content providers return non-seekable streams] → skip-based fallback in `ByteSource`; slow seeks only for those providers.
- [Wi-Fi client isolation / multicast filtering on routers] → manual renderer entry; clear error text.
- [Strict DLNA TVs reject `http-get:*:*:*` or need exact profiles] → send DLNA headers and protocolInfo with real MIME; deeper profile matching is out of scope.
- [Android kills the service under memory pressure] → foreground service with `mediaPlayback` type; renderer keeps playing what it already buffered, phone shows reconnect on return.
- [Serving files to the LAN] → random per-session token paths, bind only to the Wi-Fi address, server closed with the session; no directory listing.
- [Polling cost on battery] → 5 s interval with screen off; poll stops with the session.

## Migration Plan

New app; nothing to migrate. Install with `adb install -r app/build/outputs/apk/release/app-release.apk` (release signed with a local keystore kept outside the repo) or the debug APK. Rollback = uninstall.

## Open Questions

- App icon — cosmetic, can change any time. (Resolved: the app name on the launcher and share sheet is "DLNA TV Cast".)
