# TurboBox: project handoff

Read this first, then `PROJECT_BRIEF.md`. This file is the running state and decision log: update it whenever a decision or the state changes.

## Engine
This app is pinned to TurboNZL/turboengine (see `.turboengine.json`; `docs/development/ENGINE.md` says which modules and where they live). The engine is a **reference, not a dependency**: its modules are working code copied into apps. Before building something that every app would want (updates, release naming, the theme picker, window chrome, settings layout, guides), look in the engine's `modules/` first and copy from it rather than inventing a new one. If you improve a module here, say so in the session log so it can be ported back to the engine.

## User preferences (how to work with them)
- Don't waste their usage. Fact-check and double-check yourself; use web search liberally.
- They write casually; reply in normal capitalised prose.
- Put effort into verifying against real runs, not only unit tests.
- A merged PR cannot take new work: restart the branch from main for follow-up changes. Commit trailers as the harness gives them.
- They use Tether's **web session** every day (not its PeerJS call); that is the half that became this app, so its behaviour is the reference. Do not "improve" it away.
- Their use: lectures (pause to talk to someone, then catch up), calls over a Bluetooth headset that must stay on A2DP, pages that have to keep running with the screen off.

## Decisions made (don't re-ask)
| Topic | Decision |
|---|---|
| What it is | The web-session half of Tether (`TurboNZL/tether` at 40b287e) as its own Android app. No PeerJS call, no WebRTC library, no signalling. Tether keeps its own. |
| Name / package | TurboBox, `app.turbobox`, repo `TurboNZL/turbobox`, emblem `box` in lime `#a6e22e`. |
| Language / UI | Plain Java and plain Views, like Tether (its Java was copied, not translated). minSdk 26, compile/target 34. No Kotlin: `updaters` is a Java port. |
| Two at once | Two fixed slots (A, B), one `Session` each; a page is opened into the first free slot; a third asks which to replace. |
| Pins | Up to 8, JSON in SharedPreferences; zoom and page volume belong to the pin; microphone, echo cancellation, microphone volume and brightness are global. |
| Recording | Picture: `PixelCopy` of the page's area of the activity window into a Surface-input H.264 encoder (no consent dialog, only our own window). Sound: a tap in `session.js` of the page's remote tracks and media elements, 24 kHz mono PCM to an AAC encoder. 30 s MP4 segments in `cacheDir/buffer/run_*`, newest N minutes kept, last 3 runs kept. Not MediaProjection (needs a consent dialog each time, and records other apps). |
| Workflows | A text DSL (one step per line), parsed by `WorkflowParser` (pure, tested). Steps run from the main thread, retried every 500 ms until they work or time out (not page timers: those are throttled for a page nobody looks at). Timed ones by `AlarmManager` exact alarms. |
| Mic from the background | Android blocks a microphone foreground service started from the background. So the service is started with the microphone type only when allowed, falls back to media playback, and **Standby** (a user setting) keeps a foreground service up that was started from the front. |
| Themes / appearance | Off. The engine's turbothemes themes web pages; this app has none. Own dark UI in `Ui.java`. The engine still has a `themes/turbobox` palette pair for a future web surface. |
| Step away | Freezes the live page on this phone (last picture in a PopupWindow, page audio muted locally) while recording continues; stays frozen behind the replay until you come back. |
| Captions | Speech to text through Android's `SpeechRecognizer` (13+) fed from the recorder's PCM, saved as `transcript.vtt` in the run. Started by a one-time Settings switch, the Rec menu, or the workflow step `transcribe on`. |
| Privacy | Per-pin microphone and camera switches (camera off by default). The page is told on change (fake PermissionStatus); sheet has Reload. |
| Screen-off recording | Page hosted on a private VirtualDisplay (Presentation) read through an ImageReader whenever no window can be copied (screen off/locked/other app/workflow). Soft draw is only the fallback. |
| Recording default | Every page records when it opens (Settings switch, on by default); a pin can be Always/Never/Follow (`Pin.recordMode`). |
| No server | So no port, no `self-hosted-server`, no Cloudflare. |

## Repo layout
```
android/app/src/main/java/app/turbobox/   the app (see docs/development/ARCHITECTURE.md)
android/app/src/main/assets/session.js    injected into every page: emulation, mic, mute, volume, meters, audio tap, workflow steps
android/app/src/test/                      JVM tests: parser, scheduler, buffer index, pins, releases
test/session-script.mjs                    the page script in a real Chromium
docs/guides/                               user guides, bundled as assets and shown in the app (Guides)
docs/development/                          architecture, engine pin
branding/, scripts/                        the logo (engine `logos` module); never edit icon files by hand
.github/workflows/                         auto-pr, check (also on PRs), release (APK only)
```

## What exists and is tested
- Verified here: JVM unit tests (`WorkflowParserTest`, `WorkflowSchedulerTest`, `BufferStoreTest`, `PinTest`, `ReleasesTest`, `VttTest`, `FaviconsTest`: 35 tests, run with javac + JUnit in the sandbox); `node test/session-script.mjs` (workflow steps across shadow roots and frames, audio tap delivering real PCM, in Chromium); every Java file compiles against the Android 14 framework jar (`org.robolectric:android-all` from Maven Central; androidx stubbed).
- **Not verified, never run on a device:** the whole Gradle build (the sandbox cannot reach Google's Android SDK downloads; the first CI run is the first Gradle build), the screen recorder (`BufferRecorder`: `PixelCopy` into an encoder surface, rotation of segments, the audio/video clock), the player (`MissedActivity`), foreground-service rules on Android 12-15, alarms with the screen off, and the WebView behaviour of a page that no screen shows. Expect fixes after the first real run; keep this list honest. Also unproven: the offscreen VirtualDisplay/Presentation path on real phones, the freeze cover staying out of the recording, the soft-draw recording of a detached WebView, live captions.

## Hard-won gotchas
(Carried from Tether's web session; each was a real bug there.)
- A page can flip its own track's `enabled`, so mute is a lock on the property, not a write; the native microphone path also sends silence while muted.
- Moving between native inputs must not replace the track the call is sending (it renegotiates and breaks calls); only the audio fed into it changes.
- `enumerateDevices` can list no usable microphone in a WebView; `session.js` always lists one named microphone.
- The microphone is captured with the plain MIC source and never switches Android to call mode, so a Bluetooth headset stays on A2DP.
- Page timers are slowed in the background; anything that must be on time is driven from the native side (`evaluateJavascript` is not throttled).
New in TurboBox:
- Two `AudioRecord`s on the same source in one app are allowed since Android 10; mute is per session.
- Never keep a `Handler` in a static field of a class the JVM tests load (`new Handler` throws in the unit-test android.jar): create it where it is used (`Favicons`).
- A WebView that no activity shows has zero size, so every element measures zero; `Session.open` gives it a screen-sized layout first.
- `startForeground` must be called within seconds of `startForegroundService` even when the service is about to stop (`SessionService.apply(true)`).
- `PixelCopy` needs a visible window; the recorder repeats the last picture otherwise so segments (which are cut at key frames) keep closing.

## Session log
- 2026-10-22: created from Tether's web session (`TurboNZL/tether` 40b287e), on the engine, in one session (see the decisions above). Nothing built by Gradle yet.
- 2026-10-22 (after the first release, PR #1 merged): owner feedback applied: AM/PM follows the phone's clock setting; settings sheet has a free right strip for scrolling; per-page microphone/camera privacy switches; recording limit (rolling or stop) up to 12 h; Step away freezes and silences the live page; workflow recordings no longer black (soft draw, and Run now shows the page); Mini view lays out to its real width; pin favicons; resolution and frame rate separate; live captions to WebVTT.
- 2026-10-22 (PR #2 merged): owner reported: freeze did not silence audio (fixed: muted-lock on all media elements incl. detached ones, Web Audio master gain, frames via postMessage; Chromium-tested); captions hard to find (CC button, Captions viewer in Recordings); privacy toggles ignored by Teams/Snapchat (page now told via Permissions API, camera/mic gated in getUserMedia, permission site rule follows redirects, Reload button; cause not reproduced); recording with the screen off skipped (now an offscreen virtual display); "pictures per second" renamed fps.
- 2026-10-22 (PR #3 merged): owner: recording on by default (global switch + per-pin override); Captions button now always on a recording (explains how to turn captions on when there are none); workflow steps `step away` / `step back`. Not done: sound that a page makes with Web Audio is still not recorded (would double up with the track tap on pages that route both; needs a real page to test).
- 2026-10-22 (PR #4 merged): CI's page-script test failed on the older Chromium (muted element silenced captureStream): local mute now routes elements through Web Audio instead (see ARCHITECTURE, third round). Lesson: the CI Chromium (playwright 1.49.1, Chromium 131) is older than the sandbox's; a pass here is not a pass there.
- 2026-10-22 (PR #5 merged): owner: audio not recorded after Step away. Cause not reproduced (call loopback and element cases passed in Chromium); closed the known gap instead: tap B records sound a page makes with its own Web Audio graph (and elements the page routed itself), used only while the track/element tap is silent so nothing doubles. If it still fails on a phone, find out which page and whether it is DRM (nothing can capture that) or a cross-origin media element without CORS (captureStream is silent for it).
