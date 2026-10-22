# Recording buffer: watch what you missed

You are in a lecture stream and someone starts talking to you. Tap **Step away**, talk, tap **Back**: TurboBox plays what the page showed and played while you were away, faster if you like, until you are caught up.

## How it works
While the buffer is on, TurboBox takes a picture of the page a few times a second and records the sound the page plays (the lecturer, the other people in the call), and writes both into 30-second video pieces in the phone's cache. Only the newest N minutes are kept (Settings > Recording buffer, 15 minutes to 4 hours, 60 by default), so it is a rolling window, not an ever-growing file. Nothing leaves the phone.

Turn it on for a page in four ways:
- **Rec** on the page's bar (tap again for a menu: add a mark, play the recording, stop).
- **Step away**, which starts it if it is not already running (it cannot record the time before you tapped).
- Tick **Start the recording buffer when it opens** on the pinned page: best for lectures.
- A workflow step, `record start` ([WORKFLOWS](WORKFLOWS.md)).

## Step away and Back
1. **Step away** adds a mark and keeps recording. On your phone the page **freezes on its last picture and goes silent** (a banner says so), so you can talk to someone without the lecture running over you; behind the freeze the recording keeps taking the live picture and sound. The button changes to **Back - play what I missed**.
2. **Back** opens the player at the moment you stepped away. It first finishes the piece that was being written (a few seconds), then plays on. The live page stays frozen and silent behind the player, and comes back (picture and sound) when you leave the player.
3. Pick a speed (1x, 1.25x, 1.5x, 2x, 2.5x, 3x) with the speed button. When playback reaches the newest piece it asks the recorder for what has been written since and goes on, until it says you are up to date.
4. Seek anywhere in the bar, jump 10 seconds either way, open **Marks**, or tap **Latest**.

**Recordings** on the home screen lists the last few recordings (3 by default; Settings changes it) so you can watch a lecture again after it ended.

## What it can and cannot record
- **The picture** is what is on the screen, taken from the TurboBox window, so for the best picture (including video) the screen should be on with TurboBox in front (a floating Mini window counts). When no window can be copied (a workflow started the page and nothing shows it, or another app is in front), TurboBox draws the page onto the recording itself: slides, pages and call controls come out, but video the browser puts on its own layer may stay black, and with the screen off the picture can be stale. The sound is recorded either way, so there is never a hole.
- **Run now** on a workflow brings the page to the front for exactly this reason: a recording made while you watch has the full picture.
- **The sound** is what the page plays: the other side of a call, and any `<audio>` or `<video>` that plays a normal source. It is **not** your microphone, sound a page generates itself with Web Audio, or protected (DRM) video, which Android and the browser do not hand out: for those the picture is recorded and the sound is silent.
- Two pages can be recorded at once; each has its own recording.
- **Resolution and frame rate are separate settings** (Settings > Recording buffer): 480, 640, 960, 1280 or 1920 px wide, and 1, 2, 4, 6, 10, 15, 24 or 30 pictures a second. Settings shows the rough size per minute for the pair you chose. Slides need 1-4 per second and compress to almost nothing; a lecturer's video wants 15 or more.
- **Length:** *Record up to* 15 minutes to 12 hours. *When that is reached*: keep recording and drop the oldest (a rolling window), or stop recording.
- It is stored in the app's **cache**: Android may clear it when storage runs low, and clearing the app's cache deletes it.

Before you rely on it for something important, try it once: start the buffer on a page, step away for a minute, tap Back.

## Live captions (speech to text)
TurboBox can turn what the page says into text while it records and save it as a **WebVTT subtitle file** (`.vtt`) with the recording. It uses Android's own speech recognition, so it needs **Android 13 or newer** and a speech service on the phone (Google's, on most phones).

Start it any of these ways:
- Settings > **Live transcription** > *Transcribe the next recording*: one time, it switches itself off when that recording starts.
- On the page, tap **Rec** while recording > *Start live captions*.
- In a workflow: `transcribe on` (before or after `record start`) and `transcribe off`.

While you replay a recording its captions show under the picture. **Recordings** > *Share captions* sends the `.vtt` file anywhere (the times are from the start of the recording). Set the language in Settings (the phone's language by default). Be realistic: it is speech recognition on a lecture, so names and jargon will be wrong sometimes; times are approximate; a recognizer that beeps when it starts may beep.
