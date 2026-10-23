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

- [x] 4.1 `CastSession` queue + commands (play item, pause/resume, stop, seek, next/prev, volume) over a renderer client interface; verify with a fake renderer
- [x] 4.2 Poll loop with foreground/background intervals, state flow, renderer-lost after 15 s, auto-advance rule from design §5; verify state-machine tests for natural end vs user stop vs image items vs lost renderer
- [x] 4.3 Opt-in integration test against the real `tv-renderer` (`-PwithRenderer=...`): generated test video served from the PC, full play/seek/pause/stop cycle, and two-item auto-advance; verify it passes against `http://192.168.50.23:49494/description.xml` and leaves the renderer stopped
  - Run: `./gradlew :core:test --tests '*RealRendererTest*' -PwithRenderer=http://192.168.50.23:49494/description.xml` (needs `ffmpeg` with libx264). Passed twice in a row (~15 s each), renderer left `STOPPED`. One earlier run failed: right after `Play` the renderer reported `PAUSED_PLAYBACK` on its own (journal shows no `Pause` action), likely a leftover mpv pause state; not reproduced afterwards.

## 5. Android shell

- [x] 5.1 `ContentUriByteSource` (AssetFileDescriptor + channel positioning, skip fallback, size/MIME/display name lookup) and Wi-Fi address/multicast-lock helpers; verify instrumented or Robolectric-free unit seams compile and a debug screen lists the address on the emulator
  - Emulator (API 36.1): home screen shows `Phone address: 10.0.2.16 (wlan0)`. `ContentUriByteSource` random access exercised by real shares below (renderer fetched photos/video through it).
- [x] 5.2 `ShareActivity` with SEND/SEND_MULTIPLE filters for image/video/audio, URI grant hand-off to the service, skip-and-report unreadable items; verify on the emulator via `adb shell am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM <uri>` and that PDF shares do not list the app (`adb shell cmd package query-activities`)
  - `cmd package query-activities`: app listed for SEND/SEND_MULTIPLE `image/jpeg`, `video/mp4`, `audio/mpeg`; not listed for `application/pdf`, `text/plain`.
  - `am start -a SEND -t image/jpeg --eu android.intent.extra.STREAM content://media/external/images/media/41` reaches `ShareActivity`, but the shell cannot grant MediaProvider URIs, so the item is unreadable: the app shows "The shared item could not be opened." and contacts no renderer (this found and fixed a crash when forwarding an ungranted URI). The readable path was verified through the real share sheet of the Files app: SEND_MULTIPLE with two photos ("2 items ready to cast") and SEND of one video. The "some items skipped" message is not reachable from the emulator (no app shares a mix of granted and ungranted URIs); it is covered by the same code path as the all-unreadable case.
- [x] 5.3 `CastService` foreground service (`mediaPlayback`), media-style notification with play/pause/next/stop, session lifecycle and server shutdown; verify on the emulator that the notification appears, Stop ends the session and the port closes
  - Notification: MediaStyle, title = item, text `TV · 2/2 · Playing`, actions Pause/Stop (Next only when there is a next item); service `isForeground=true types=0x2` (mediaPlayback). Tapping Stop in the shade: renderer got `AVTransport.Stop`, notification and service gone, port 8099 no longer accepts connections (curl 200 before, failure after).
- [x] 5.4 Renderer picker (SSDP results, rescan, empty state, manual-add dialog with validation) and persistence of the last renderer (UDN + location, re-resolution by UDN); verify on the emulator using manual entry `http://10.0.2.2:49494/description.xml`
  - Manual add of `http://10.0.2.2:49494/description.xml` (through a host-side TCP relay to 192.168.50.23:49494, since `tv-renderer` listens on the LAN address only): wrong URL `…/nope.xml` rejected with "HTTP 404", valid URL listed as "TV". Picking it stored UDN + location; the next share went straight to the remote screen without the picker. SSDP on the emulator also found "TV" at 192.168.50.23. Re-resolution by UDN (new address) is covered by `RendererDiscoveryTest`.
- [x] 5.5 Remote-control screen (play/pause, stop, seek bar, prev/next, volume, lost-connection banner with Retry/End); verify on the emulator against the PC `tv-renderer` using the debug-only advertised-host override and port forwarding, and that the override is absent from the release variant
  - Debug override `127.0.0.1:8099` + `adb forward tcp:8099 tcp:8099`; `tv-renderer` played photos and a 30 s video served by the emulator. Next/Previous stepped photos; seek bar hidden for photos, drag to the middle sent `Seek` (renderer at 0:00:16); volume −/+ and Mute/Unmute changed renderer volume/mute (restored to 100). Renderer-side Pause/Play (SOAP from the PC) showed on the phone after 0.6–1.5 s over six toggles. Lost banner appeared ~16 s after the relay was stopped (not at 10 s), Retry cleared it, End ended the session. Release dex has no override strings or logic (only a stub returning null).
- [x] 5.6 Wi-Fi-required check and error messages (no network, no renderer, UPnP fault); verify on the emulator with network disabled
  - Wi-Fi and mobile data disabled: sharing a photo shows "A Wi-Fi connection to the TV's network is required." and the renderer journal shows no requests; home and picker show the same message. UPnP fault: a fake renderer answering `SetAVTransportURI` with error 714 made the remote screen show "UPnP error 714: Illegal MIME-type", stay on item 1, and send `SetAVTransportURI` only once. Not exercised: the picker's "No TV found" empty state, because the real TV answers SSDP even from the emulator.

## 6. Delivery and acceptance

- [ ] 6.1 Release build signed with a local keystore outside the repo (path via `~/.gradle/gradle.properties`), README with install steps; verify `./gradlew :app:assembleRelease` produces a signed APK and `apksigner verify` passes
- [ ] 6.2 Manual acceptance on the real phone against `tv-renderer`: share one video from Gallery (starts without waiting), share five photos and step through, seek, pause from PC keyboard reflected on phone, screen off during a long video, stop from notification, share PDF (app not listed); record results here
