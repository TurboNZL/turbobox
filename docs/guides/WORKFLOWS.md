# Workflows

A workflow opens one or more of your pinned pages and follows steps you write: click this, type that, wait, record, hang up. It can start **by itself at a time you choose, on the days you choose, with the screen off**, or when you tap **Run now**.

A workflow does not belong to a page. Make it once, name it, and then in a **page's settings > Workflows** switch it on for that page and choose the days and time it runs there (a 2-hour lecture on one page, a morning check-in on another). The same links can be changed from the workflow itself (the pages it runs on), so use whichever is closer.

Home > Workflows > **New workflow**. Give it a name, choose the pages (tap tiles in the same layout as the home screen; pick several to run the steps on each page at once), optionally switch on **Start by itself at a set time**, and write the steps.

## The steps
Tap **+ Add a step** in the editor and pick one from the buttons: it asks what it needs (how long to wait, which words to click...) and writes the line after the one your cursor is on. Or type them yourself, one per line. Blank lines and lines starting with `#` are ignored.

| Step | Does |
|---|---|
| `open` | Loads the pinned page again. (The page is opened for you before the first step, or reused if it is already open.) |
| `wait 30s` | Waits. `30`, `30s`, `5m`, `2h`, `1h30m` all work. |
| `waitfor Leave` | Waits until the target is on the page (up to 60 s; `within 3m` changes that). |
| `click Join now` | Clicks the target, trying again for up to 20 s while it is not there yet. |
| `type css:#name = My Name` | Puts the text in a field (and fires the events the page listens for). A `<select>` picks the option with that text. |
| `key Enter` | Presses a key: `Enter`, `Escape`, `Tab`, `Backspace`, `ArrowDown`... Enter in a form field submits the form. |
| `scroll down 600` | `up`, `down` (pixels), `top`, `bottom`. |
| `mute` / `unmute` | Your outgoing audio, with the same lock as the Mute button. |
| `volume 80` | The page's volume, 0 to 200 (%). |
| `hangup 1h30m` | Closes the page after that long (`hangup off` cancels). `hangup` or `close` with no time closes it now: they are the same step. |
| `record start` / `record stop` | The [recording buffer](RECORDING_BUFFER.md). |
| `step away` / `step back` | Like the Step away button: freezes and silences the page on the phone while it keeps recording (it starts the recording if needed). `step back` lets go, without opening the replay. |
| `transcribe on` / `transcribe off` | Old workflows only: captions are automatic with every recording now (Settings turns them off), so these do nothing. |
| `mark Chapter 2 starts` | A bookmark in the recording. |
| `js document.title` | Runs a script on the page. |

Start a line with `?` and it is optional: if it fails the workflow carries on (`? click Accept cookies within 5s`).

## Targets
- **The words on it**: `click Join now` finds the button, link or field whose visible text, accessible label (`aria-label`), `title`, placeholder or button value matches. An exact match wins over a partial one, and buttons and links win over plain text. Case does not matter.
- **`css:SELECTOR`**: `click css:button.join`, `type css:#email = me@example.com`. Use this when the words are ambiguous. To find a selector: open the page in a desktop browser, right-click the element, Inspect.
- **`text:WORDS`** is the same as the bare words; use it when the words start with `css:`.

Elements inside open shadow roots and same-origin frames are found too. A page from another origin inside a frame cannot be reached (browsers forbid it).

## When it runs
- **Timed**: pick the time (shown the way your phone shows time, with AM/PM unless it is set to 24 hours) and the days. The phone does not have to be awake: Android wakes it at that time (allow **exact alarms** so it is on time, not minutes late), TurboBox opens the page and starts clicking. A notification says it is running.
- **Run now** on the workflow's card, or **Save and run now** in the editor, brings the page to the front and shows the log live (in the editor); use it to try a workflow before you trust it with an alarm. Because the page is on screen, a recording it starts gets the full picture.
- If its page is already open, it uses that one. If all four page slots are busy, it opens the page in a **temporary extra tab** (E, F...) that you can reach from the session screen as usual; the tab closes when the workflow finishes (it stays if a hang-up timer is still running or something went wrong, so you can look). A workflow with several pages starts one run per page, side by side; the log shows `[Page]` in front of each line.
- A run's result is reported once, when the last page is done. A `hangup` timer keeps counting after the steps are done: the log says *Steps finished; the hang-up timer is still counting down*, and when it runs out, *Final action finished: the timer ran out and the page was closed*.
- **Recording size.** A page can have its own size for workflows (page settings > *Size while a workflow records*: page size, zoom and the shape of the picture). While a workflow uses the page, and until its recording ends, the page keeps that size whatever the screen does, so the recording is the same shape from the first frame to the last and nothing is cut off. Off: the page keeps its normal size.
- If a step fails (a button never appears, the page was closed) the workflow stops, a notification says which step and why, and the last run is shown on its card.

## With the screen off
The page, the waiting and the clicking all continue with the screen off. Two things to know:
1. **The microphone.** Android only lets an app use the microphone when it was in front, or has a foreground service that was started from the front. A workflow started by an alarm while the phone is locked is neither unless **Standby** is on (Settings; it keeps one small foreground service up). With Standby on, the call's microphone works; without it, the workflow still clicks and the page still plays, but your microphone may be silent.
2. **The picture of the recording buffer.** With the screen off nothing can be copied from a window, so TurboBox draws the page onto the recording itself: pages and their controls are recorded, but video the browser puts on its own layer may stay black and the picture can be stale. The sound is recorded either way (see [RECORDING_BUFFER](RECORDING_BUFFER.md)).

## Examples
A morning check-in every weekday at 08:55:
```
wait 5s
? click Accept cookies within 10s
click Start within 3m
waitfor Finish
record start
mark Check-in starts
hangup 2h
```
Join a room, muted, and leave after 15 minutes:
```
click Join now within 1m
mute
hangup 15m
```
Type a code, press Enter, then record what plays:
```
type css:#room-code = 4821
key Enter
waitfor Live
record start
```
Anything you type in a workflow is stored in clear text on the phone. Prefer pages that stay signed in (the cookies are kept) over writing a password here.

## Troubleshooting
- *"not found: text:Join now"*: the page says something else, or the button is an image. Read the real label (or a selector) with Inspect in a desktop browser and use that, or use `css:`.
- *"found but not visible"*: the element exists but is hidden; `waitfor` something that appears first, or give it more time with `within`.
- It clicks but nothing happens: some pages need a real touch rather than a script click. Tell the maintainers which page; a native tap step is on the list.
- Two-step sign-ins and CAPTCHAs cannot be automated. Sign in once by hand (the cookies stay).
