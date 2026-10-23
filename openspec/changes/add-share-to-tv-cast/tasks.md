## 1. Project setup

- [x] 1.1 Create Gradle wrapper project with `core` (Kotlin/JVM) and `app` (Android, Compose, minSdk 29, target/compile 36, package `io.github.nezdelion.dlnatvcast`) modules, `.gitignore`, version catalog; verify `./gradlew build` succeeds and produces a debug APK
- [x] 1.2 Add JUnit 5 + kotlinx-coroutines-test to `core`; verify a placeholder test runs in `./gradlew :core:test`

## 2. Core: HTTP streaming

- [x] 2.1 Implement `ByteSource` and the session-scoped HTTP server (GET/HEAD, keep-alive, token paths, 404 elsewhere, bind to a given address); verify with socket-level tests for 200/HEAD/404 responses
- [x] 2.2 Implement single-range handling (`a-b`, `a-`, `-n`, 416) and DLNA headers; verify tests covering each range form, 416, `Accept-Ranges`, `contentFeatures.dlna.org` on request
- [x] 2.3 Verify end-of-file-first access pattern: test that requests the last 1 MiB then byte 0 of a 20 MB source and checks bytes and statuses; verify server stop closes the port

## 3. Core: UPnP

- [x] 3.1 SSDP M-SEARCH client and response parser (behind a socket abstraction); verify parser tests with captured `tv-renderer` responses and a fake responder
- [x] 3.2 Device description fetch/parse (friendly name, UDN, AVTransport/RenderingControl control URLs, relative URL resolution); verify tests against `tv-renderer`'s description XML and a vendor-style sample with URLBase
- [x] 3.3 SOAP client for the actions in design §4 with fault parsing; verify tests for envelope, argument escaping, out-arg parsing and `UPnPError` mapping
- [x] 3.4 DIDL-Lite builder (object class by MIME, title, `res` with protocolInfo/size, XML escaping); verify tests including titles with `&<>"`

## 4. Core: session

- [ ] 4.1 `CastSession` queue + commands (play item, pause/resume, stop, seek, next/prev, volume) over a renderer client interface; verify with a fake renderer
- [ ] 4.2 Poll loop with foreground/background intervals, state flow, renderer-lost after 15 s, auto-advance rule from design §5; verify state-machine tests for natural end vs user stop vs image items vs lost renderer
- [ ] 4.3 Opt-in integration test against the real `tv-renderer` (`-PwithRenderer=...`): generated test video served from the PC, full play/seek/pause/stop cycle, and two-item auto-advance; verify it passes against `http://192.168.50.23:49494/description.xml` and leaves the renderer stopped

## 5. Android shell

- [ ] 5.1 `ContentUriByteSource` (AssetFileDescriptor + channel positioning, skip fallback, size/MIME/display name lookup) and Wi-Fi address/multicast-lock helpers; verify instrumented or Robolectric-free unit seams compile and a debug screen lists the address on the emulator
- [ ] 5.2 `ShareActivity` with SEND/SEND_MULTIPLE filters for image/video/audio, URI grant hand-off to the service, skip-and-report unreadable items; verify on the emulator via `adb shell am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM <uri>` and that PDF shares do not list the app (`adb shell cmd package query-activities`)
- [ ] 5.3 `CastService` foreground service (`mediaPlayback`), media-style notification with play/pause/next/stop, session lifecycle and server shutdown; verify on the emulator that the notification appears, Stop ends the session and the port closes
- [ ] 5.4 Renderer picker (SSDP results, rescan, empty state, manual-add dialog with validation) and persistence of the last renderer (UDN + location, re-resolution by UDN); verify on the emulator using manual entry `http://10.0.2.2:49494/description.xml`
- [ ] 5.5 Remote-control screen (play/pause, stop, seek bar, prev/next, volume, lost-connection banner with Retry/End); verify on the emulator against the PC `tv-renderer` using the debug-only advertised-host override and port forwarding, and that the override is absent from the release variant
- [ ] 5.6 Wi-Fi-required check and error messages (no network, no renderer, UPnP fault); verify on the emulator with network disabled

## 6. Delivery and acceptance

- [ ] 6.1 Release build signed with a local keystore outside the repo (path via `~/.gradle/gradle.properties`), README with install steps; verify `./gradlew :app:assembleRelease` produces a signed APK and `apksigner verify` passes
- [ ] 6.2 Manual acceptance on the real phone against `tv-renderer`: share one video from Gallery (starts without waiting), share five photos and step through, seek, pause from PC keyboard reflected on phone, screen off during a long video, stop from notification, share PDF (app not listed); record results here
