# Architecture

## Where it came from
`Session` is Tether's `WebSession` (`TurboNZL/tether` at 40b287e) with three changes: it is an instance (two slots) instead of a singleton, listeners are a list, and it can be driven (`evalStep`) and recorded. `session.js`, `VirtualMic`, `InputRoute`, `MicTester`, `MicPreview` and `LevelMeterView` are Tether's, renamed (`__tether` -> `__turbobox`, `TetherBridge` -> `TurboBoxBridge`). The PeerJS call (`CallManager`, `CallService`, `PeerSignal`, the WebRTC library) and Tether's glass UI were left behind.

## Components (`android/app/src/main/java/app/turbobox/`)
| File | Job |
|---|---|
| `Session`, `Sessions` | One running page per slot: WebView (desktop emulation, document-start script), timer, mute lock, native microphone, volume/zoom, recorder, `evalStep`. `Sessions` holds slots A and B. |
| `SessionService` | Foreground service: notification (Mute, Close all, countdown), wake lock and Wi-Fi lock while anything runs, Standby. Microphone foreground type when Android allows it, media playback otherwise. |
| `SessionActivity`, `SessionSheet` | The screen: tabs, Split, controls, Mini (PiP), settings sheet (mic test, volumes, zoom, info). Pages live in `Session`, so the activity can come and go. |
| `MainActivity`, `Pin`, `PinStore`, `Shortcuts` | Home screen, pinned pages, launcher shortcuts. |
| `BufferRecorder`, `BufferStore`, `MissedActivity` | The recording buffer and its player (below). |
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
