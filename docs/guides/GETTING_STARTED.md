# Getting started

TurboBox runs web pages the way a call app would: they keep going with the screen off, they can use your phone's microphone, and two (or more, for a workflow) can run at once.

## Install
1. Open the [latest release](https://github.com/TurboNZL/turbobox/releases/latest) on the phone and download `turbobox-<version>-android.apk`.
2. Allow "install unknown apps" for your browser when Android asks, then open the file.
3. Open TurboBox and allow the **microphone** and **notifications** when it asks. Notifications matter: the notification is how Android lets
   a page keep running in the background, and it carries Mute and Close.

The app updates itself: Settings > App updates > Check for updates.

## Pin a page
On the home screen tap **Pin a page**, type a name and the address (`example.com/page` is enough, `https://` is added). Choose how it records (follow
the default, always, never) for a page you always want to be able to rewind. A pinned page is one tap from the home screen. There is no limit on how many
you pin; **Reorder** (above the tiles) shows ‹ › on each tile to move it earlier or later.

A tile shows the page's own icon (found when you pin it, and updated whenever the page reports one; a letter until then).

Long-press a tile for: Edit, **Add an icon to the home screen** (the launcher puts the page next to your other apps), Remove. The first four pins are
also in the long-press menu of the TurboBox icon.

## Open it
Tap the tile. The page opens as **A**. Tap a second tile and it opens as **B**; the tabs at the top switch between them, and **Split** shows both at
once (one above the other, or side by side when the phone is on its side). A third page asks which of the two to replace. With one page open it is
simply its name; the letters appear (as `A | Name`) once there are two. A workflow that needs a page while A and B are busy gets a temporary extra tab
that goes away when the workflow is done.

The bar acts on the page that is in front:
- **Close** ends the session on your end. **Mute** switches your outgoing audio off in a way the page cannot undo; it turns red while it is on.
- **Timer** hangs up after a delay, or at a clock time (tonight's 03:30).
- **Settings**: a ‹ › box at the top to go back and forward in the page's history, privacy switches for the microphone and camera, microphone, echo cancellation, microphone volume with a record-and-play-back test, page volume, brightness, page zoom, **aspect ratio** (fit the page into a 16:9, 4:3, 21:9, 1:1, 9:16 or 3:2 box, kept with the pin), page info. The sheet keeps a free strip on its right edge: rest your finger there to scroll.
- **Rec** starts or stops the recording buffer. **CC** turns live captions on (and starts the recording they are saved with). **Step away** / **Back** pause a page for you and catch you up later: see [RECORDING_BUFFER](RECORDING_BUFFER.md).
- **Mini** shrinks the page into a small floating window (it also happens by itself when you press Home on Android 12 or newer). The page is laid out to fit the window, including when you rotate the phone. A visible window makes Android much less likely to unload the app. The window has a microphone button (mute / unmute, the same as the notification's). With two pages open, Settings > *Mini window with two pages* (or a long press on **Mini**) can show them side by side or stacked.
- **Lock** is pocket protection: the screen goes black and ignores every touch (and the Back key). To unlock, touch and hold the two circles at the same time for a moment. It works with Step away, recording and Mini, and the lock never appears in a recording.
- A video's own **full screen** button works (YouTube, Twitch...): the page fills the screen and turns sideways with the phone; Back leaves it.
- Settings > *Options bar as a floating button* replaces the bar across the top with a small round button you can drag: tap it to open the bar over the page.

Leaving the screen never pauses a page. The notification says what is running (`A | Name [muted] [recording] [hangs up in 42 min]`) and has **Mute** and **Close all**; tap it to come back. When nothing is running, Standby shows a quiet, minimised notification in its own *Standby* channel: switch that channel off in Android's notification settings to hide it while Standby keeps working.

## Keep it alive with the screen off
- Videos (YouTube, Twitch) keep playing with the screen off: the page is told it is visible and is kept on a private display of the app's own. Settings > *Keep video playing with the screen off* turns that off.
- Settings > **Staying alive with the screen off** > Allow: lets TurboBox ignore battery optimisation, so Android does not stop a page after some hours.
- Some phones (Xiaomi, Huawei, Samsung "sleeping apps", OnePlus) have their own app-killing settings. If a page stops after a while, set TurboBox to "no restrictions" there too (dontkillmyapp.com lists the steps per brand).

## Workflows and Standby
A [workflow](WORKFLOWS.md) can start by itself at a time you set. For one that uses the microphone while the phone is locked, turn **Standby** on in Settings: it
keeps TurboBox ready in the background (one small notification, almost no battery), which is what Android requires before it lets an app use the microphone
from the background. Also allow **exact alarms** (Settings shows a button) so a workflow starts on time and not minutes late.
