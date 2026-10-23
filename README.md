# DLNA TV Cast

Android app that appears in the system Share sheet for photos, videos and music and plays
them on a DLNA/UPnP TV or renderer. Files are streamed straight from the phone over HTTP
with byte ranges, so playback starts without copying the whole file first.

- Share one or several items from Gallery, Files or any other app, pick **DLNA TV Cast**.
- The last TV is remembered; the next share starts on it right away.
- Remote control: play/pause, stop, seek, previous/next, volume; media notification with
  play/pause, next and stop that keeps working with the screen off.
- No ads, no accounts, no storage permission (items are read through the share grant).

Requirements: Android 10 (API 29) or newer, phone and TV on the same Wi-Fi/Ethernet network.

## Project layout

- `core/` – pure Kotlin/JVM: HTTP range server, SSDP, device description, SOAP,
  DIDL-Lite, cast session state machine. Unit-tested without a device.
- `app/` – Android shell: share entry point, foreground service, notification, Compose UI.
- `openspec/` – proposal, design, specs and tasks of the change that built the app.

## Build

JDK 17+ and the Android SDK (platform 36) are needed; Gradle comes with the wrapper.

```sh
./gradlew :core:test            # unit tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew build                 # everything, including lint
```

Optional integration test against a real renderer (shows short test videos on it, needs
`ffmpeg` with libx264, always ends with Stop):

```sh
./gradlew :core:test --tests '*RealRendererTest*' \
    -PwithRenderer=http://192.168.50.23:49494/description.xml
```

## Release build

The release keystore is kept outside the repository. Create one once:

```sh
keytool -genkeypair -keystore ~/.android/dlna-tv-cast-release.jks -storetype PKCS12 \
    -alias dlnatvcast -keyalg RSA -keysize 3072 -validity 10000
```

and add to `~/.gradle/gradle.properties` (never commit these values):

```properties
DLNATVCAST_RELEASE_STORE_FILE=/home/<you>/.android/dlna-tv-cast-release.jks
DLNATVCAST_RELEASE_STORE_PASSWORD=...
DLNATVCAST_RELEASE_KEY_ALIAS=dlnatvcast
DLNATVCAST_RELEASE_KEY_PASSWORD=...
```

Then:

```sh
./gradlew :app:assembleRelease
~/Android/Sdk/build-tools/36.1.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Without these properties the release APK is built unsigned.

## Continuous integration

- `.github/workflows/ci.yml` runs on every push to `main` and on pull requests: core tests, debug build and lint. The debug APK and reports are attached to the run as artifacts.
- `.github/workflows/release.yml` runs on tags `v*`: builds the signed release APK and attaches it to the GitHub release. It needs four repository secrets: `RELEASE_KEYSTORE_BASE64` (`base64 -w0 your.jks`), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, then `git tag v0.2.0 && git push origin v0.2.0`.

## Install

Enable USB debugging on the phone, connect it and run:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

(or the debug APK). Remove with `adb uninstall io.github.nezdelion.dlnatvcast`.

## Troubleshooting

- **No TV found**: check both devices are on the same network; some routers block multicast
  or isolate Wi-Fi clients. Use **Add manually** with the renderer's description URL, e.g.
  `http://192.168.50.23:49494/description.xml`.
- **"Wi-Fi connection required"**: the app only streams over Wi-Fi or Ethernet.
- **UPnP error shown**: the TV rejected the item (often an unsupported format).

## Emulator testing (debug builds only)

The emulator is behind NAT, so the renderer cannot reach it directly. Debug builds have an
"advertised host override" on the home screen: set host `127.0.0.1:8099`, port `8099`, run
`adb forward tcp:8099 tcp:8099`, and add the renderer manually as
`http://10.0.2.2:<port>/description.xml`. The override does not exist in release builds.

## License

MIT, see [LICENSE](LICENSE).
