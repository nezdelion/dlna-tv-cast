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

- [x] 6.1 Release build signed with a local keystore outside the repo (path via `~/.gradle/gradle.properties`), README with install steps; verify `./gradlew :app:assembleRelease` produces a signed APK and `apksigner verify` passes
  - Keystore `~/.android/dlna-tv-cast-release.jks` (PKCS12, RSA 3072, alias `dlnatvcast`), credentials in `~/.gradle/gradle.properties` (mode 600). `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`; `apksigner verify` passes (v2 scheme, `CN=DLNA TV Cast, O=nezdelion`). The release APK installs and starts on the emulator, without the debug override section.
- [ ] 6.2 Manual acceptance on the real phone against `tv-renderer`: share one video from Gallery (starts without waiting), share five photos and step through, seek, pause from PC keyboard reflected on phone, screen off during a long video, stop from notification, share PDF (app not listed); record results here
  - Not done yet: needs the real phone (no installs on it from this session). Steps:
    1. Phone on the same Wi-Fi as the PC; `tv-renderer` running. `adb install -r app/build/outputs/apk/release/app-release.apk`. Open the app once, allow notifications; the home screen should show the phone's Wi-Fi address.
    2. Gallery → one long video (several minutes, ideally MP4 recorded by the phone) → Share → DLNA TV Cast. First time: picker lists "TV" within ~5 s → tap it. Expect playback on the TV within a few seconds (no full-file wait) and the remote screen on the phone. Result: ____
    3. Drag the seek bar to ~1:30 → TV jumps there. Result: ____
    4. Press Space in the mpv window on the PC → phone shows "Paused" within 2 s; Space again → "Playing". Result: ____
    5. Turn the phone screen off for ≥1 min during the video → TV keeps playing; turn on → state/position current. Result: ____
    6. Pull down the notification → Stop → TV stops, notification disappears. Result: ____
    7. Gallery → select five photos → Share → DLNA TV Cast → first photo on TV without the picker (remembered TV); Next/Previous step through all five; photos do not advance on their own. Result: ____
    8. Two short videos shared together → second starts automatically after the first ends; press `q` in mpv during the first of two videos (far from the end) → phone shows Stopped and the second does not start. Result: ____
    9. Files → a PDF → Share → DLNA TV Cast is not in the list. Result: ____
    10. Optional: mobile data only (Wi-Fi off) → share a photo → "A Wi-Fi connection … is required".

## Review fixes

Codex review (REVIEW.md, not committed): 10 major, 5 minor. Each finding and what it became:

- #1 Stop cleanup could destroy a newer share → lifecycle extracted into `core` `CastCoordinator` with generations; each operation detaches what it replaces. `CastCoordinatorTest` (delayed Stop + new share; replace without Stop).
- #2 `CastSession.close()` not terminal → session-owned job; close marks closing, cancels/joins startup, polls and commands, then Stop; later commands ignored. `CastSessionReviewTest` (close during SetAVTransportURI, pending next, commands after close).
- #3 blocking HTTP defeated deadlines → raw-socket HTTP client with total deadline, cancellation closes the socket; `onDestroy` no longer blocks (server closed synchronously, Stop in a bounded cleanup scope). `HardeningTest` (hung/trickling renderer, resolve budget), `CastCoordinatorTest` (destroy).
- #4 volume reads stalled polling → separate volume loop. `CastSessionReviewTest` (5 s RenderingControl).
- #5 renderer-lost too late → watchdog on time since last answer. `CastSessionReviewTest` (5 s timeouts, screen on/off).
- #6 projection across outages → failed poll drops projection; unknown duration after a gap is not an end. `CastSessionReviewTest`.
- #7 malformed URLBase escaped → URL validation, all description errors become `InvalidRendererException`; discovery skips any failing device. `HardeningTest`.
- #8 no wake ownership → `WAKE_LOCK`, partial wake lock + Wi-Fi lock only while casting, released on every exit path. `CastCoordinatorTest` (keepAwake on each transition); emulator: locks held while casting, released after Stop/End.
- #9 unbounded XML → 256 KiB body limit, depth/element limits, iterative traversal, 4 parallel description fetches. `HardeningTest`.
- #10 idle connections starved I/O → own server threads, 10 s absolute header deadline, pending/active connection limits, token-less connections closed. `MediaServerAbuseTest`.
- #11 DTD hardening ignored on Android → explicit parser-independent `Xml.checkStructure` refuses DOCTYPE/ENTITY. `HardeningTest` (valid renderer + DTD variants). The Android parser itself cannot run in JVM tests.
- #12 locale digits → `Locale.ROOT`. `HardeningTest`, `MediaServerLocaleTest`.
- #13 unknown length advertised seeking → `DLNA.ORG_OP=00`, no `Accept-Ranges` in HTTP and DIDL; media-streaming spec updated. `UnknownLengthCapabilityTest`.
- #14 notification lacked position → `NotificationModel` (5 s steps, progress bar). `NotificationModelTest`; emulator: `TV · Playing · 0:00:10 / 0:00:30`.
- #15 mixed share untested → `ShareIntake` + coordinator intake tests (order, skipped count, no renderer contact). Grant survival after `ShareActivity` finishes is only covered by emulator shares (no automated Android test). 6.2 stays open.

Before the fix, the new tests for #2–#7, #9, #10, #12 (UPnP time) and #13 failed; #1, #8, #11, #14 and #15 test new seams that did not exist (verified by mutating each guard). After the fix: `./gradlew :core:test :app:assembleDebug :app:assembleRelease :app:lintDebug` passes (lint: 0 errors, the same 7 warnings and 1 hint as before the review). Emulator smoke: share → cast, Stop from the notification (renderer Stop, notification and service gone, port closed, wake/Wi-Fi locks released), new share while casting (no Stop, old token 404), End. The opt-in renderer test passed and left the renderer stopped.

