# TurboBox

An Android app that keeps your web pages running like calls: pin a lecture stream or a meeting room, open it with one tap,
run two at once, keep them alive with the screen off, record what you missed, and let a workflow click through them for you.

It is the **web-session half of [Tether](https://github.com/TurboNZL/tether)** (the embedded browser that joins a web call and keeps the
phone's microphone on A2DP) made into an app of its own, on the [TurboNZL/turboengine](https://github.com/TurboNZL/turboengine) base.
Tether keeps its PeerJS call; TurboBox is only the web pages.

## What it does
- **Pinned pages** on the home screen (up to 8): one tap opens the page in a session. Long-press for an icon on the launcher's home screen, and the
  first four are also in the app icon's long-press menu.
- **Two running at once** (slots A and B): tabs, or **Split** to see both. A small floating window (Mini) keeps a page up while you use
  another app.
- **Everything Tether's web session did**: desktop browser emulation and page zoom, the phone's own microphone chosen by you (a Bluetooth
  headset stays in its high-quality mode), a mute the page cannot undo, page volume (0-200%), microphone volume (0-300%) with a
  record-and-play-back test, a hang-up timer (a delay or a clock time), a foreground service with the screen off.
- **Recording buffer**: records what the page shows and plays into short pieces in the cache, keeping the newest N minutes.
  **Step away** marks the moment; **Back** plays what you missed at 1x to 3x until you have caught up. See [`docs/guides/RECORDING_BUFFER.md`](docs/guides/RECORDING_BUFFER.md).
- **Workflows**: "open this page, click Join, type my name, unmute, record, hang up after two hours", at a set time on chosen days,
  with the screen off. See [`docs/guides/WORKFLOWS.md`](docs/guides/WORKFLOWS.md).

> **Status:** written and checked off-device. The logic (workflow steps, schedule, buffer index, update check) has JVM unit tests, the page
> script runs in a real Chromium test, and every Java file compiles against the Android 14 framework, but **it has not been built by Gradle or
> run on a phone yet**: the first CI run is the first real build, and the screen recorder and the background rules are the parts most likely to
> need a fix on a real device. `docs/development/ARCHITECTURE.md` lists what is unproven.

## Install
Download `turbobox-<version>-android.apk` from the [latest release](https://github.com/TurboNZL/turbobox/releases/latest) and allow "install
unknown apps" for your browser once. The app updates itself from the same releases (Settings > App updates). Android 8 or newer.

## Build and test
```
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug        # JVM tests and the APK (JDK 17, Android SDK 34)
node test/session-script.mjs                                              # the page script in Chromium (playwright + a Chromium)
node scripts/gen-icons.mjs                                                # regenerates the logo files from branding/logo.json
```
The app is plain Java and plain Views, like Tether. The pages are shown by the system WebView.

## Releases and updates
Every merge to `main` becomes a release (`vYYYY.M.N`) with `turbobox-<version>-android.apk` attached; installed apps update themselves from it. The APK
is signed with the committed `android/app/debug.keystore` unless the repository has the `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` secrets (use them: this repository is public). Android only installs an update signed with the same key, so
do not switch keys after people have installed.

## Engine
Built on [TurboNZL/turboengine](https://github.com/TurboNZL/turboengine) (`.turboengine.json` says which commit and modules).

## License
MIT, see `LICENSE`.
