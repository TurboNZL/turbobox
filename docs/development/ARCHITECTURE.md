# Architecture

## Where it came from
`Session` is Tether's `WebSession` (`TurboNZL/tether` at 40b287e) with three changes: it is an instance (two slots) instead of a singleton, listeners are a list, and it can be driven (`evalStep`) and recorded. `session.js`, `VirtualMic`, `InputRoute`, `MicTester`, `MicPreview` and `LevelMeterView` are Tether's, renamed (`__tether` -> `__turbobox`, `TetherBridge` -> `TurboBoxBridge`). The PeerJS call (`CallManager`, `CallService`, `PeerSignal`, the WebRTC library) and Tether's glass UI were left behind.

## Components (`android/app/src/main/java/app/turbobox/`)
| File | Job |
|---|---|
| `Session`, `Sessions` | One running page per slot: WebView (desktop emulation, document-start script), timer, mute lock, native microphone, volume/zoom, recorder, `evalStep`. `Sessions` holds the permanent slots A and B and four temporary ones (C-F) that a workflow borrows when A and B are busy. |
| `SessionService` | Foreground service: notification (Mute, Close all, countdown), wake lock and Wi-Fi lock while anything runs, Standby. Microphone foreground type when Android allows it, media playback otherwise. |
| `SessionActivity`, `SessionSheet` | The screen: tabs, Split, controls, Mini (PiP), settings sheet (mic test, volumes, zoom, info). Pages live in `Session`, so the activity can come and go. |
| `MainActivity`, `Pin`, `PinStore`, `PinGrid`, `Shortcuts` | Home screen, pinned pages (no limit; reorderable), launcher shortcuts. `PinGrid` draws the tiles and the page picker sheet that the workflow editor reuses. |
| `BufferRecorder`, `BufferStore`, `MissedActivity` | The recording buffer and its player (below). |
| `StepTemplates` | Every workflow step as a form (fields -> the line of text). Pure; the editor's "Add a step" buttons are built from it. |
| `AspectFrame`, `PocketLockView` | A box that fits the page to a chosen shape; the pocket lock's face (two circles to hold). |
| `WorkflowParser`, `Workflow`, `WorkflowStore`, `WorkflowScheduler`, `WorkflowReceiver`, `WorkflowRunner`, `WorkflowsActivity`, `WorkflowEditActivity` | Workflows (below). |
| `SettingsActivity`, `AppUpdater`, `Releases`, `HelpActivity` | Settings, self-update, guides. |
| `Prefs`, `Devices`, `Ui`, `LevelMeterView` | Shared helpers. |

Threads: everything touches `Session` on the main thread; the exceptions are `VirtualMic`'s capture thread (posts frames to main), `BufferRecorder`'s encode thread and frame thread (below), and `AppUpdater`'s executor.

## The page script (`assets/session.js`)
Injected before page scripts (`addDocumentStartJavaScript`) and again on page start and finish (idempotent), with the current settings. Native -> page: `window.__turbobox.*` through `evaluateJavascript`. Page -> native: `TurboBoxBridge.post(type, data)` (levels, microphone requests, page info, loopback results, **audio** PCM for the recorder). The two parts new in TurboBox:
- **Audio tap** (`T.setTap`): a `ScriptProcessor` fed by `MediaStreamAudioSource`s of every remote receiver track and the `captureStream()` of every `<audio>`/`<video>` with a normal source; wired to a silent gain, so playback is untouched. 48 kHz decimated to 24 kHz mono 16-bit, posted in 2048-sample chunks.
- **Steps** (`T.step`): `find` (css or words; shadow roots, same-origin frames; visible beats hidden, exact beats partial, buttons beat text), `click` (pointer + mouse events + `click()`), `type` (native value setter + `input`/`change`), `key`, `scroll`, `js`. Synchronous: answers `{ok, msg}`; the native side retries.

## The recording buffer
```
page audio --session.js tap--> bridge "audio" --> Session.dispatch --> BufferRecorder.feedAudio (queue)
window pixels --PixelCopy (main tick, fps by quality)--> frame thread --Surface.lockCanvas--> H.264 encoder (surface input)
                         encode thread: feeds AAC (silence by the clock when the page sends none), drains both encoders,
                         cuts a new MP4 at the first key frame after 30 s (or on requestFlush), BufferStore.trim keeps the newest N minutes
```
- Segments are `seg_<startWallMs>_<durationMs>.mp4` in `cacheDir/buffer/run_<startedAt>_s<slot>/`, with `meta.json` and `marks.json`; `BufferStore` is pure and tested. A run that is no longer recording stays until pruned (last N runs).
- **Clock:** audio PTS = `System.nanoTime()/1000` at start + samples fed; video PTS = the surface timestamp (CLOCK_MONOTONIC, the same clock). A segment's time zero is its first key frame; earlier audio is dropped. Audio that arrives more than 1 s ahead of the clock is dropped so the two never drift.
- **No window / screen off:** `PixelCopy` fails or the window is gone: the last picture is drawn again (black before the first), so key frames keep coming and segments keep closing.
- **Player:** `MissedActivity` chains segments through `MediaPlayer`, speed with `PlaybackParams`, the seek bar spans the run's timeline (gaps skipped, `BufferStore.locate/offsetOfWall`). At the end it asks a live recorder to `requestFlush` and picks up newer segments.

## Workflows
`WorkflowParser` (text -> steps, pure) -> `WorkflowRunner.Run` (a state machine on the main handler): ensure a session for the pin (reuse or first free slot, wait for the page to load), then per step: native steps (wait, mute, volume, hangup, record, mark, close, open) or page steps retried every 500 ms until `within` runs out (a 4 s watchdog counts a navigation that ate the answer as a miss). Optional (`?`) steps are skipped on failure; any other failure stops the run, records the result on the workflow and posts a notification. `WorkflowScheduler` plans one `setExactAndAllowWhileIdle` alarm per timed workflow (inexact fallback without the permission), re-planned when it fires, at boot, at app update and at clock changes (`WorkflowReceiver`). The runner holds its own wake lock (6 h cap) and `SessionService` stays up while a run exists.

## Background rules, as designed (check on a device)
- Alarm -> receiver -> `SessionService.ensure` -> `startForegroundService`. An exact alarm exempts the app from the background-start restriction, but **not** from the "while-in-use" rule for the microphone: so `startForegroundCompat` tries microphone+mediaPlayback and falls back to mediaPlayback. With **Standby** on, the service was started from the front and stays up, which is what makes the microphone usable later.
- A WebView with no window is laid out at screen size (`Session.open`) and kept resumed (`keepRunning`).
- Battery optimisation exemption and OEM task killers still apply: the guide says so.

## Unproven (be suspicious here first)
Gradle build; `BufferRecorder` on real encoders (some devices want specific sizes or reject `lockCanvas` on an input surface); sync between picture and sound over long runs; `MediaPlayer` seeking inside segments; the foreground-service fallbacks on Android 12-15; whether exact alarms deliver with the screen off on aggressive OEMs; clicking pages that need trusted events.

## Added after the first release
- **Freeze (Step away).** `Session.setFrozen` mutes every media element on the page (`T.setLocalMute`; the audio tap reads tracks and captured streams, not the speaker, so the recording is unchanged: `test/session-script.mjs` proves it) and `SessionActivity` covers the pane with its last picture in a **PopupWindow**, a window of its own, so `PixelCopy` of the activity window (the recorder's source) still sees the live page. `catchingUp` keeps it frozen behind the replay until the activity resumes.
- **No window to copy** (a workflow started the page, the app is in the background): `BufferRecorder.softFrame` draws the WebView into a bitmap (`View.draw`). Hardware-layer video is blank in that path. Manual workflow runs open `SessionActivity` so there is a window. Unproven on a device whether a never-attached WebView draws at all.
- **Resolution and frame rate** are separate (`BufferRecorder.width/fps/bitrate`; bitrate is 0.2 bits per pixel per frame, clamped). The old single quality setting is still read when the new ones are unset. A recording can **stop at its limit** instead of rolling.
- **Privacy switches** are per pin (`allowMic`, `allowCamera`): `onPermissionRequest` grants per switch, `getUserMedia` in `session.js` throws `NotAllowedError` when the microphone is off, and a blocked microphone is folded into the mute lock (`Session.silenced`).
- **Small windows.** `session.js` scales the viewport to `cfg.viewW`, the page's real width in CSS px, pushed from `Session` on every layout change (Mini, split, rotation); it used to assume the whole screen.
- **Favicons** (`Favicons`): found from `<link rel=icon>` (largest, apple-touch first, SVG skipped) else `/favicon.ico`, and from `WebChromeClient.onReceivedIcon`; PNGs in `filesDir/favicons`.
- **Live captions** (`Transcriber`, `Vtt`): the recorder's PCM goes through a pipe to `SpeechRecognizer` (`EXTRA_AUDIO_SOURCE`, Android 13+), restarted after every phrase with queued audio kept; cues go to `transcript.vtt` in the run folder with times from the run's start. Unproven on a device: how the Google recognizer treats `EXTRA_AUDIO_SOURCE`, and its start beeps.

## Recording with the screen off (second round)
Copying a window cannot work when the screen is off, locked, or another app is in front, so `BufferRecorder.grab` has three picture sources, chosen each tick: **window** (`PixelCopy`; only while `PowerManager.isInteractive()`, the keyguard is unlocked, the activity window is set and the view is shown), **offscreen** (`OffscreenHost`), and **soft draw** (`View.draw`, last resort). `OffscreenHost` creates a private `VirtualDisplay` (flags PRESENTATION | OWN_CONTENT_ONLY) whose surface is an `ImageReader`, shows a `Presentation` on it, and moves the page's WebView into that. The virtual display is composited whatever the physical screen does, the browser sees a visible window (no throttling), and `latest()` returns the newest frame as a bitmap, which is drawn into the encoder like the other sources. The display is sized to the recording with a scaled density so the page keeps its CSS layout width. `SessionActivity.onStart/onResume` call `rebuild()`, which takes the WebView back out of the presentation. Unproven: that every phone allows a Presentation on an app-owned virtual display from a foreground service with the screen off; the failure is reported through `Listener.onNotice` and the recorder falls back to soft draw.

## Local mute, and permissions, in more detail
`session.js` locks `HTMLMediaElement.muted` (page value kept separately, real value = page OR local mute), routes every live `AudioNode.connect(destination)` through a per-context master gain, and passes state to other frames with `postMessage` (`localMute`, `cfg`, `hello`). The Permissions API for microphone and camera is replaced by a fake `PermissionStatus` that follows the privacy switches and fires `change`. `getUserMedia` refuses the camera until allowed. Site rule for permission requests: roots of the pinned page and of every page it navigates to (`Session.allowedRoots`); the old rule (the pinned host's root only) refused call apps that redirect to another domain.

## Local mute and the recorder (third round)
CI's Chromium 131 failed the test "the tap still records the page while it is locally muted" that a newer local Chromium passed: in some versions an element's muted state also silences `captureStream()`, which is what the recorder reads. So `session.js` no longer relies on it: an element with a `blob:`, `data:`, same-origin or CORS-enabled source is moved once into Web Audio (`createMediaElementSource`, our context) and reaches the speaker only through the context's master gain (0 while away); the recorder taps the source node before the gain. Elements that cannot be moved (a call's MediaStream, cross-origin media without CORS) keep the muted lock; for a call the recorder reads the receiver track, which muting an element does not touch. A routed element stays routed after the page comes back (the browser allows it only once) and plays through the master gain at 1. If the context is not running at that moment nothing is routed, so a stuck context can never make a page inaudible.

## Page audio tap B (fourth round)
Owner report: no audio in the recording after Step away. The recorder only heard tracks and media elements (tap A); sound a page makes with its own Web Audio graph never reached it, and a page that routes its element through `createMediaElementSource` itself leaves nothing for `captureStream` to capture. Every live context's destination connection now goes page nodes -> `pre` gain -> master gain (local mute) -> destination (`preFor`), and tap B is a ScriptProcessor on `pre` in each page context (not our own, `__tbOwn`), resampled to 24 kHz. `deliver()` uses A while A had sound in the last 3 s, else B if B had, so the two are never summed (tested: one tone's level, not two). Elements the page routed itself are left unmuted during Step away (`__tbPageRouted`): their sound already ends at our master gain. The listener on `pre` is before the mute, so the recording is unaffected by it.

## Features added in the third round (owner list of 2026-10-23; none of it run on a device)
- **Pins are unlimited** (`PinStore.MAX` is gone) and can be reordered (`PinStore.move`, arrows on the tiles in a Reorder mode). `PinGrid` is the one tile layout; the workflow editor's page picker is `PinGrid.pick`.
- **Workflows run on several pages**: `Workflow.pinIds`; `WorkflowRunner` keeps one `Run` per workflow and page (key `workflowId:pinId`), the result of a start is reported once by a `Group` when the last run ends. A run takes the page's open session, else a free permanent slot, else a **temporary slot** (`Sessions.firstFreeAnySlot`, slots C-F); the temporary page is closed when its run finishes OK unless a hang-up timer is armed (a failed run leaves it open). `Session.Listener.onOpened` makes an open screen add the tab.
- **`hangup` and `close` are one step**: no time (or 0) closes now, a time is the old timer, `off` cancels it.
- **Step buttons**: `StepTemplates` is the single table of steps with their questions; `StepTemplatesTest` parses every line it can produce, so the parser and the buttons cannot drift apart.
- **Sign-in pages**: `X-Requested-With` is no longer sent (`WebSettingsCompat.setRequestedWithHeaderOriginAllowList(empty)`; it carries the package name and Google's sign-in refuses embedded browsers by it), and `session.js` gives the page desktop Chrome's `window.chrome` members and `pdfViewerEnabled`. Not verified on Google or Twitch themselves (needs a real sign-in on a phone).
- **Full screen**: `WebChromeClient.onShowCustomView` hands the page's view to `Session.Listener.onFullscreen`; `SessionActivity` shows it in `fullscreenHolder` (above the page, below the brightness veil), hides the bars and turns with the sensor; Back leaves it.
- **Video with the screen off**: two layers. `session.js` reports the page as visible (`document.hidden`, `visibilityState`, `hasFocus`, and drops `visibilitychange`; Chromium-tested) and `Session.hostWithoutScreen` moves the WebView onto a private `OffscreenHost` display (drained, never read) whenever no activity window shows it and the recorder is not already hosting it. Before this the page was only kept visible while recording, which is why Step away + record kept YouTube playing and a plain screen-off did not. `Prefs.keepPlaying` (Settings) turns both off.
- **Aspect ratio**: `Pin.aspect`, `AspectFrame` (centred, exact-size child, so the page lays out to the box). The recorder letterboxes whatever shape it is given.
- **History box** in the page settings (`Session.goBack/goForward`, enabled state follows `doUpdateVisitedHistory`).
- **Mini window**: a microphone `RemoteAction` (same toggle as the notification, `Sessions.toggleAllMuted`; a dynamic receiver while the activity is started) and a two-page layout (`Prefs.pipLayout`: front page / side by side 21:9 / stacked 8:9, Android only allows shapes up to 2.39:1).
- **Pocket lock**: a `Dialog` of its own above the activity (so it is above the freeze cover and never in the recording), swallowing every touch and Back; unlock = hold two circles together for 1.5 s (`PocketLockView`). The flag is static so it outlives the activity, and the dialog stays up across the screen going off.
- **Floating bar** (`Prefs.floatingBar`): `chromeBox` leaves the layout and is shown in a `PopupWindow` opened by a draggable round `PopupWindow` button, both windows of their own so a recording never shows them.
- **Notification**: idle = ongoing, `IMPORTANCE_MIN` channel "Standby" (the user can hide the channel in Android while the service runs); anything open or running = the "Pages and workflows" channel, with `A | Name [muted] [recording] [hangs up in 42 min]` lines, the letter only when two or more pages are open.

## Fourth round (owner feedback of the same day, still no device)
- **Four pages**: `Sessions.PERMANENT = 4`, `COUNT = 8` (four temporary). The letter is the order of opening (`Session.openedSeq`, `Sessions.letter`), not the slot, so the first open page is always "A".
- **Tabs and Split**: `SessionActivity.splitSet` holds the slots shown together (2 to 4; `SplitLayout.rows` decides the arrangement). With Split on, a tap on a tab adds it, focuses it, or (on the focused one) removes it. A page that is open but not on screen is moved to a private display (`Session.hostWithoutScreen`) instead of being left detached, which is what stopped playback when switching tabs.
- **Mini window** only shows several pages while Split is on (`pipPages`); layouts in `SplitLayout` (like the split, side by side, stacked, overlay with the second page as a small inset). Window shape from `SplitLayout.shape`, clamped to Android's 1:2.39 to 2.39:1.
- **Bar**: always the docked bar; `Hide bar` sets `Prefs.barHidden` and shows a draggable round `PopupWindow` button that brings it back (the floating-panel mode of the first round is gone).
- **Full screen** fills the page's pane (`onFullscreen` adds the view to the pane), no immersive mode and no rotation.
- **Desktop or mobile** per pin (`Pin.mobile`, default desktop): `Session.setMobile` swaps the user agent and client hints and re-registers the document-start script, which reads `window.__tbMobile` to leave the platform and viewport alone.
- **Page settings from the home screen**: `PinSheet` (long-press a pin).
- Close no longer asks.
