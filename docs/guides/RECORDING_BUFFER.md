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
1. **Step away** adds a mark and keeps recording. The button changes to **Back - play what I missed**.
2. **Back** opens the player at the moment you stepped away. It first finishes the piece that was being written (a few seconds), then plays on.
3. Pick a speed (1x, 1.25x, 1.5x, 2x, 2.5x, 3x) with the speed button. When playback reaches the newest piece it asks the recorder for what has been written since and goes on, until it says you are up to date.
4. Seek anywhere in the bar, jump 10 seconds either way, open **Marks**, or tap **Latest**.

**Recordings** on the home screen lists the last few recordings (3 by default; Settings changes it) so you can watch a lecture again after it ended.

## What it can and cannot record
- **The picture** is what is on the screen, taken from the TurboBox window, so it needs the screen on and TurboBox in front (a floating Mini window counts). While the screen is off or another app is in front, the buffer repeats the last picture and keeps recording the sound, so there is no hole in the recording, only a frozen picture.
- **The sound** is what the page plays: the other side of a call, and any `<audio>` or `<video>` that plays a normal source. It is **not** your microphone, sound a page generates itself with Web Audio, or protected (DRM) video, which Android and the browser do not hand out: for those the picture is recorded and the sound is silent.
- Two pages can be recorded at once; each has its own recording.
- Quality (Settings): Low 640 px at 4 pictures a second, Medium 960 px at 6, High 1280 px at 10. A minute is about 2 MB (Low) to 9 MB (High); slides compress to almost nothing.
- It is stored in the app's **cache**: Android may clear it when storage runs low, and clearing the app's cache deletes it.

Before you rely on it for something important, try it once: start the buffer on a page, step away for a minute, tap Back.
