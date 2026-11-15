# TurboBox: project handoff

Read this first, then `PROJECT_BRIEF.md`. This file is the running state and decision log: update it whenever a decision or the state changes.

## Engine
This app is pinned to TurboNZL/turboengine (see `.turboengine.json`; `docs/development/ENGINE.md` says which modules and where they live). The engine is a **reference, not a dependency**: its modules are working code copied into apps. Before building something that every app would want (updates, release naming, the theme picker, window chrome, settings layout, guides), look in the engine's `modules/` first and copy from it rather than inventing a new one. If you improve a module here, say so in the session log so it can be ported back to the engine.


## PRIVACY RULE (read before writing anything; imperative)
TurboBox is a privacy-first app, and the repository is **public**. Therefore:
- **Never write the owner's use case, habits, workplace, school, employer, schedule, accounts, devices, phone model, location or contacts** into any tracked file: code, comments, tests, docs, guides, this file, commit messages, branch names, PR titles or bodies, issue text or workflow logs. Not even "the owner asked for...".
- **Never name the specific services, meetings, courses or sites the owner uses** to explain a decision. Describe the technical behaviour instead ("a site that refuses embedded browsers", "a page that opens a custom-scheme link"). Use neutral example addresses (`example.com`) and neutral example names ("Notes", "Recording").
- **Do not pass the owner's details to outside services** (search queries, URLs, headers, payloads).
- **Session notes stay out of the repository.** Keep a private log outside the repo (or in the chat) and put only technical decisions that every contributor needs into the log below, phrased generically. Do not push anything the owner has not asked to be pushed.
- Pasted configuration dumps, Page info, screenshots and error text from the owner are private: do not copy them into files. If a bug needs a regression test, use invented data.
- If unsure whether something reveals the owner's use, leave it out.

## How to work with the owner
- Don't waste their usage. Fact-check and double-check yourself; use web search liberally.
- They write casually; reply in normal capitalised prose.
- Put effort into verifying against real runs, not only unit tests.
- A merged PR cannot take new work: restart the branch from main for follow-up changes. Commit trailers as the harness gives them.
- Use generic examples in the app and docs (a video, a stream, a document, a check-in).
- The embedded browser session is the reference behaviour. Do not "improve" it away.
- Needs the app must meet, stated generically: pages that keep running with the screen off, a recording that can be caught up on, a Bluetooth headset that stays on its high-quality profile, and no microphone or camera use unless the user allows it.

## Decisions made (don't re-ask)
| Topic | Decision |
|---|---|
| What it is | An embedded-browser Android app. No PeerJS call, no WebRTC library, no signalling. |
| Name / package | TurboBox, `app.turbobox`, repo `TurboNZL/turbobox`, emblem `box` in lime `#a6e22e`. |
| Language / UI | Plain Java and plain Views. minSdk 26, compile/target 35 (see the decision log: Play Protect warns when the target is more than two Android versions below the phone). No Kotlin: `updaters` is a Java port. |
| Several at once | Four fixed slots, one `Session` each; a page is opened into the first free slot; a fifth asks which to replace. A workflow that finds all four busy borrows a temporary slot. The letter (order of opening, so the first page open is A) is only shown when two or more pages are open (`A | Name`). Split shows 2-4 chosen tabs. Sites are desktop by default, `Pin.mobile` per pin. |
| Pins | No limit, reorderable, JSON in SharedPreferences; zoom, page volume and aspect ratio belong to the pin; microphone, echo cancellation, microphone volume and brightness are global. |
| Recording | Picture: `PixelCopy` of the page's area of the activity window into a Surface-input H.264 encoder (no consent dialog, only our own window). Sound: a tap in `session.js` of the page's remote tracks and media elements, 24 kHz mono PCM to an AAC encoder. 30 s MP4 segments in `cacheDir/buffer/run_*`, newest N minutes kept, last 3 runs kept. Not MediaProjection (needs a consent dialog each time, and records other apps). |
| Workflows | A text DSL (one step per line), parsed by `WorkflowParser` (pure, tested). Steps run from the main thread, retried every 500 ms until they work or time out (not page timers: those are throttled for a page nobody looks at). Timed ones by `AlarmManager` exact alarms. |
| Mic from the background | Android blocks a microphone foreground service started from the background. So the service is started with the microphone type only when allowed, falls back to media playback, and **Standby** (a user setting) keeps a foreground service up that was started from the front. |
| Themes / appearance | Off. The engine's turbothemes themes web pages; this app has none. Own dark UI in `Ui.java`. The engine still has a `themes/turbobox` palette pair for a future web surface. |
| Step away | Freezes the live page on this phone (last picture in a PopupWindow, page audio muted locally) while recording continues; stays frozen behind the replay until you come back. |
| Captions | Speech to text through Android's `SpeechRecognizer` (13+) fed from the recorder's PCM, saved as `transcript.vtt` in the run. Started by a one-time Settings switch, the Rec menu, or the workflow step `transcribe on`. |
| Privacy | Per-pin microphone and camera switches (camera off by default). The page is told on change (fake PermissionStatus); sheet has Reload. |
| Screen-off recording | Page hosted on a private VirtualDisplay (Presentation) read through an ImageReader whenever no window can be copied (screen off/locked/other app/workflow). Soft draw is only the fallback. |
| Recording default | Every page records when it opens: a Settings switch, **off by default** (changed 2026-10-23); a pin can be Always/Never/Follow (`Pin.recordMode`). |
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
(Each was a real bug.)
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

## Decision log (technical, generic)
Older, more detailed entries were removed from this file on purpose: they described the owner's use. The engineering substance is kept here.
- **Build and target:** compile/target SDK 35. Play Protect warns when the target is more than two API levels below the device, so the target must keep rising. Target 35 needs the edge-to-edge opt-out theme (`res/values-v35/theme.xml`); target 36 needs real inset handling instead. Not run on a device unless said so; CI is the Gradle build.
- **Recording:** `PixelCopy` or a private virtual display into an H.264 encoder, 30 s MP4 pieces in `cacheDir/buffer`, AAC sound from the page tap. A piece still being written keeps the service up (`BufferRecorder.busy()`); encoder loss is recovered; pruning never touches a live run.
- **Captions and recaps:** on-device or system recogniser fed from the recording's own sound (never a microphone when "Never use the microphone" is on: `MicGuard` refuses every `AudioRecord`); recaps and cuts through optional AI providers (key per service, opt-in).
- **Pages and display:** a page can live on a private display (`OffscreenHost`, `MirrorView`); the Mini window shows a mirrored crop (`PipStage`); layouts are live-arranged (`MiniLayout`, `FloatLayer`).
- **Audio:** pages see one virtual microphone, one speaker; page volume is a Web Audio bus; volume above 100% ducks the other pages.
- **Workflows:** text DSL, retried steps, exact alarms, per-page schedules, a master switch (`Prefs.workflowsOn`) that cancels all alarms and blocks every non-manual start.
- **Site compatibility:** `X-Requested-With` is not sent; the user agent is a desktop Chrome by default; a per-page browser identity (Chrome Linux/Windows/Mac, Edge Windows) changes only what the browser says; Settings > Debugging has a config copier (secrets hidden) and a site test that loads an address once per identity in a throwaway profile. Some sites refuse phone browsers on the server side whatever is sent; disguises and in-page scripts cannot change the connection itself, so do not add more.
- **Links to other apps:** a navigation to a non-web scheme is dropped with a notice and the page stays (do not remove: WebView would otherwise show an unknown-scheme error page). Popups are followed only after a tap (`Prefs.newWindows`).
- **Site data:** per-site clearing is best effort (cookies can only be read per address); "Clear everything in <profile>" wipes cookies, storage, cache and history for a whole store.
- **Page script:** overscroll is clamped (`OVER_SCROLL_NEVER`, `overscroll-behavior`), refit/resize logic runs for desktop-layout pages only.
- **Idea parked:** running another app inside this one (an app-virtualization layer). Guests would share this app's user ID (so could read its data), the frameworks rely on restricted hidden APIs, and it has not been built. Do not start without the owner confirming a feasibility test first.
