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
A small coroutine-based server in `core`: `ServerSocket` bound to the Wi-Fi IPv4 address on an ephemeral port, one coroutine per connection, keep-alive, `GET`/`HEAD`, single-range parsing, DLNA headers, streaming with a fixed buffer and `Dispatchers.IO`. URL layout: `http://<ip>:<port>/<sessionToken>/<index>/<sanitised-name>.<ext>`; token = 128-bit `SecureRandom`, hex.
Alternative: NanoHTTPD or Ktor server — NanoHTTPD is unmaintained and its range handling is DIY anyway; Ktor adds several MB and Netty/CIO complexity for ~300 lines of needed behaviour.

### 4. SSDP and SOAP by hand
SSDP: `MulticastSocket` M-SEARCH for `urn:schemas-upnp-org:device:MediaRenderer:1` (MX=2, sent 3 times), collect unicast `LOCATION` responses, fetch and parse the device description with the namespace-aware `javax.xml.parsers` DOM parser, which exists both on the JVM and on Android, so `core` needs no parser interface (DOCTYPE/external entities disabled). The datagram I/O sits behind an `SsdpTransport` interface so discovery can be tested without multicast. Android needs a `WifiManager.MulticastLock` while searching.
SOAP: `HttpURLConnection` POST with `SOAPACTION` header and envelope, response parsed for `out` arguments or `UPnPError`. Actions used: `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetTransportInfo`, `GetPositionInfo`, `GetVolume`, `SetVolume`, `GetMute`, `SetMute`.
Alternative: Cling/jUPnP — heavy, Cling is abandoned, jUPnP pulls Jetty/OSGi-era dependencies.

### 5. Session state machine in `core`
`CastSession` owns the queue, current index, renderer client and a poll loop (1 s foreground / 5 s background, switched by the app). Auto-advance rule: transition `PLAYING → STOPPED` (or `NO_MEDIA_PRESENT`) for audio/video where the last known position was within 3 s of the duration, or duration unknown and the stop was not user-initiated from the app → start next. A `STOPPED` seen while the last command from the app was `Stop`, or far from the end, is treated as a user stop (matches the `q` scenario). Renderer lost = 15 s of consecutive failed polls. Exposed as `StateFlow<SessionState>` consumed by UI and notification.

### 6. Android shell
- `ShareActivity` (exported, translucent, SEND / SEND_MULTIPLE for `image/*`, `video/*`, `audio/*`) takes persistable-less URI grants, starts `CastService` with the URIs (passing `FLAG_GRANT_READ_URI_PERMISSION` + `ClipData` so the service keeps access), then opens the remote screen or the picker.
- `CastService`: foreground service type `mediaPlayback`, owns `CastSession` and the HTTP server, `MediaSessionCompat`-free media-style notification (plain `NotificationCompat.MediaStyle` with actions) to avoid hijacking phone media buttons; stops itself when the session ends.
- UI: Jetpack Compose, two screens (renderer picker with manual-add dialog, remote control). Material 3.
- Persistence: `SharedPreferences` (last renderer UDN + location, manual renderers list).
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
