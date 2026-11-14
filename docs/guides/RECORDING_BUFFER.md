# Recording buffer: watch what you missed

You are watching a stream and someone starts talking to you. Tap **Step away**, talk, tap **Back**: TurboBox plays what the page showed and played while you were away, faster if you like, until you are caught up.

## How it works
While the buffer is on, TurboBox takes a picture of the page a few times a second and records the sound the page plays (the stream, the other people in a call), and writes both into 30-second video pieces in the phone's cache. Only the newest N minutes are kept (Settings > Recording buffer, 15 minutes to 4 hours, 60 by default), so it is a rolling window, not an ever-growing file. Nothing leaves the phone.

**It is off by default**: a page records when you press Rec, a workflow says so, or the page is set to always record. Settings > Recording buffer > *Record every page when it opens* turns it on for all pages (so Step away always has something to replay), and a pinned page can override it either way (long-press it > Page settings > Recording: follow the default / always / never).

You can also start or stop it by hand:
- **Rec** on the page's bar (tap again for a menu: add a mark, play the recording, stop).
- **Step away**, which starts it if it is not already running (it cannot record the time before you tapped).
- A workflow step, `record start` ([WORKFLOWS](WORKFLOWS.md)).

## Step away and Back
1. **Pause and record** (the button with both icons) is exactly a pause followed by a record: the Pause button shows the pause, Record shows the recording, and letting go of the pause (the Pause button) leaves the recording alone. **Step away** adds a mark and keeps recording. On your phone the page **freezes on its last picture and goes silent** (a banner says so), so you can talk to someone without the page running over you; behind the freeze the recording keeps taking the live picture and sound. The button changes to **Back - play what I missed**.
2. **Back** opens the player at the moment you stepped away. It first finishes the piece that was being written (a few seconds), then plays on. The live page stays frozen and silent behind the player, and comes back (picture and sound) when you leave the player.
3. Pick a speed (1x, 1.25x, 1.5x, 2x, 2.5x, 3x) with the speed button. When playback reaches the newest piece it asks the recorder for what has been written since and goes on, until it says you are up to date.
4. Seek anywhere in the bar, jump 10 seconds either way, open **Marks**, or tap **Latest**.

**Recordings** on the home screen lists the last few recordings (3 by default; Settings changes it) so you can watch something again after it ended.

## What it can and cannot record
- **The picture, screen on:** taken from the TurboBox window while it is in front (a floating Mini window counts).
- **The picture, screen off (or locked, or another app in front, or a workflow with no screen):** TurboBox moves the page onto a private virtual display of its own, which keeps rendering whether or not the real screen is on, and records that. So a video or call is recorded with the phone locked in your pocket, picture and sound, and the page is not slowed down. When you unlock and open TurboBox the page comes back into its place. If a phone refuses the virtual display, you get a notice and TurboBox falls back to drawing the page onto the recording (slides and controls, but not video), with the sound still recorded.
- **Run now** on a workflow brings the page to the front for exactly this reason: a recording made while you watch has the full picture.
- **The sound** is what the page plays: the other side of a call, any `<audio>` or `<video>`, and sound the page makes with its own Web Audio (some call apps and players play everything that way). It is **not** your microphone, and not protected (DRM) video, which Android and the browser do not hand out: for those the picture is recorded and the sound is silent. If a page produces both kinds at once, only one is recorded at a time (the first while it has sound) so nothing is doubled.
- Two pages can be recorded at once; each has its own recording.
- **Resolution and frame rate are separate settings** (Settings > Recording buffer): 480, 640, 960, 1280 or 1920 px wide, and 1, 2, 4, 6, 10, 15, 24 or 30 frames per second (fps). Settings shows the rough size per minute for the pair you chose. Slides need 1-4 per second and compress to almost nothing; a moving picture wants 15 or more.
- **Length:** *Record up to* 15 minutes to 12 hours. *When that is reached*: keep recording and drop the oldest (a rolling window), or stop recording.
- It is stored in the app's **cache**: Android may clear it when storage runs low, and clearing the app's cache deletes it.

Step away freezes the page and silences it everywhere it can play sound: media elements (also ones the page keeps out of sight), Web Audio, and pages inside frames. A page cannot un-mute itself during it. What the recording hears is unchanged.

Before you rely on it for something important, try it once: start the buffer on a page, step away for a minute, tap Back.

## Live captions (speech to text)
TurboBox can turn what the page says into text while it records and save it as a **WebVTT subtitle file** (`.vtt`) with the recording. It uses Android's own speech recognition, so it needs **Android 13 or newer** and a speech service on the phone (Google's, on most phones).

**Every recording gets captions** (there is no CC button and no step for it any more). Settings > **Live transcription** > *Turn off captions while recording* switches them off for recordings that start afterwards. If the recogniser stops answering (the app was in the background, the speech service restarted) it is started again by itself, and a long stretch of speech without a pause is cut into lines about every nine seconds so lines keep coming.

For a recording that exists already, **Recordings** > your recording > **Transcribe** makes the captions now. It feeds the sound to the recogniser faster than it was spoken (Settings > Live transcription > *Captioning a finished recording*: as spoken is the most exact, three times as fast takes a third of the time but can miss a word where a phrase starts). It needs Android 13 or newer, like live captions.

While you replay a recording its captions show under the picture. **Recordings** (home screen) > your recording > **Captions** shows the text with times and has *Share .vtt file* to send it anywhere (the times are from the start of the recording). Set the language in Settings (the phone's language by default). Be realistic: it is speech recognition on long speech, so names and jargon will be wrong sometimes; times are approximate; a recognizer that beeps when it starts may beep.

## Captions, cutting and watching live
- **Export captions** (Recordings) saves the captions as `.vtt`, as plain text with times, and as a sheet for an AI, into Downloads/TurboBox.
- **Cut with AI**: a long recording is split into parts that each fit a free AI chat (a 4 hour one is about 8). For each part tap Copy, paste it into an AI chat, copy the AI's reply (the stretches to keep, like `0:00 - 4:35`), then tap Paste answer. Your answers are remembered, so you can do it over several sittings; a part you skip is kept whole. Cut writes one video with only what was kept. A kept stretch can start up to 2 seconds early.
- **Watch** (Running list, Recordings, or the notification) shows what the recording sees right now and the words being captioned, without opening the page or the player. While you watch, the recording notes it, and the AI sheet marks those stretches so they can be cut if they do not matter.
- **Settings > Privacy** can turn the microphone and camera off for the whole app. You can still give Android's microphone permission for captions: with the switch on, the app refuses to open any microphone, and captions are made from the recording's sound, not a microphone.
- **Automatic cutting with Gemini** (Settings > AI for cutting): choose the Google account, get a key from Google's key page (the app opens it for that account and takes the key from the clipboard), and allow sending captions. Then Cut with AI has a "Cut automatically" button; it shows how much would be kept and cut before you tap Cut. Only the caption text is sent. On Google's free tier it may be used to improve Google's products. A stretch the AI did not answer for is kept whole.
- **Recap** (Recordings > Make recap): a title, the important stuff at the top, chapters, notes, and the stretches worth watching as a short video. The video stays one size at the top (or bottom) while you read; tap a time to play from there. Add a key for Groq, OpenRouter or Gemini first (Settings > AI for cutting and recaps); several keys are tried in turn when one is at its free limit. It carries on in the background, and a long recording takes minutes on a free tier.
- **Page shape**: choosing 16:9 (or another shape) for a page now gives the page that shape itself, so a recording started afterwards has the same shape.

