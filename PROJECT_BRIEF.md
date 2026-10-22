# TurboBox: project brief

The long-lived "why and what": goals, who it is for, rules the app must keep, architecture, roadmap with done-criteria. `CLAUDE.md` is the running log; this file changes rarely.

## Goal and user
One person's phone as a quiet, reliable web-call and lecture box. The owner used Tether's *web session* (an embedded browser that joins a web call and keeps running with the screen off, with the phone's microphone on A2DP so a Bluetooth headset stays in high quality) every day, and the PeerJS call never. TurboBox is that web session as its own app, with three additions the owner asked for: pinned pages with two running at once, a recording buffer so a paused lecture can be caught up, and workflows that click through a page at a set time even with the screen off.

## Feature inventory
Everything Tether's web session had (desktop emulation, zoom, native microphone choice, mute lock, volume, mic volume with a playback test, timer by delay or clock time, PiP, foreground service), plus: pinned pages (up to 8) with launcher shortcuts; two sessions at once with tabs and a split view; the recording buffer (picture + page sound, rolling N minutes, Step away / Back, player with 1-3x speed, marks, past recordings); workflows (text steps, timed by exact alarms, run-now with a live log, failure notifications); Standby; app updates from GitHub releases; in-app guides.

## Rules that must not be broken
1. **A page never un-mutes the call.** Mute is a lock; keep every route (`addTrack`, `addTransceiver`, `replaceTrack`, `clone`, re-captured microphone, the native path) hooked.
2. **Never put Android in call mode.** Microphone capture is the plain MIC source; Bluetooth must stay on A2DP unless the user picks an SCO input.
3. **Leaving the screen never pauses a page.** Only Close, the hang-up timer, a workflow or the notification end one.
4. **Nothing leaves the phone.** Recordings are in the cache folder, no analytics, no accounts. The only network traffic is the pages themselves and the update check.
5. **Workflow steps are driven natively**, never by page timers, so they run with nobody looking.
6. **The update key never changes** once people have installed (committed keystore, or the secrets).
7. Engine rules: versions `vYYYY.M.N`, lowercase asset names, one logo family (regenerate, never hand-edit), `docs/guides` vs `docs/development`.

## Architecture
See `docs/development/ARCHITECTURE.md`. In short: `Session` (one per slot) owns a WebView, its timer, mute, microphone and recorder; `SessionService` keeps the process alive; `WorkflowRunner` drives sessions from alarms or by hand; `BufferRecorder` + `BufferStore` + `MissedActivity` are the buffer.

## Roadmap (with done-criteria)
1. **First real build and run.** Done when CI's Gradle build is green and, on a phone: a pin opens, the page keeps running with the screen off for an hour, the microphone reaches a call page, mute holds.
2. **Recording proven.** Done when a 10-minute lecture recorded with Step away / Back plays back with picture and sound in sync, and a 2-hour recording stays at the configured size in the cache.
3. **Workflows proven.** Done when a timed workflow started by an alarm with the phone locked joins a real page, clicks the right buttons, and (with Standby on) the page hears the microphone.
4. **Native tap step** (`tap` with real touch events) for pages that ignore script clicks; **export a recording** to the gallery; **record the microphone side** as an option.
5. A web surface (settings on a page) is NOT planned; if one appears, enable the engine's `themes/turbobox` palettes via `appearance`.

## Glossary
- **Pin**: a saved page (name, address, zoom, volume, auto-record).
- **Slot / session**: A or B; one running page with its timer, mute, microphone and recording.
- **Run**: one recording (a folder of 30 s segments) in the buffer.
- **Standby**: a user setting that keeps the foreground service up so scheduled calls can use the microphone.
- **Step**: one line of a workflow.
