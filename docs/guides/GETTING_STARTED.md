# Getting started

TurboBox keeps web pages running in the background: they keep going with the screen off, they can use your phone's microphone, and up to four can run at once (more, temporarily, for a workflow).

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

## Profiles
A profile is a separate identity inside the app: its own pinned pages, its own sign-ins (cookies and storage live in a WebView profile of their own, so signing in to a site in one profile does not sign you in in another) and its own settings (microphone, volumes, recording, screen options; a new profile starts from the defaults and nothing carries over). On the home screen the chips under the title switch profile and show only its pages; **All** shows every page, **+** adds a profile, and a long press on a chip renames or removes it (removing deletes its pages and sign-ins). Choose a page's profile when you pin or edit it. Standby and app updates are shared by all profiles. A profile can have a **password** (long-press its chip > Set a password; any profile, the Default one too). Until it is unlocked its pages are hidden on the home screen, in the Running list and in the launcher shortcuts, and opening one from a shortcut sends you to unlock it first. It locks again when TurboBox is restarted, after you leave the app for two minutes, or with *Lock now*. Only a salted hash is stored; a forgotten password cannot be recovered (removing the profile is the way out). It hides the pages and does not encrypt their saved sign-ins. Workflows still run on a locked profile's pages. The profile you were viewing is remembered between launches.

Pages already pinned belong to the **Default** profile, which keeps everything as it was. Separate sign-ins need a reasonably recent Android System WebView; on an older one profiles still separate pages and settings but share sign-ins (the app says so).

**Open a page** (the tile next to *Pin a page*) opens an address without pinning it. It is **incognito** by default: its own cookies and storage (a separate WebView profile, so your signed-in pages are untouched), no recording, and everything is deleted when you close it. Switch Incognito off for a plain unpinned page. Needs a reasonably recent Android System WebView; otherwise the page opens without the incognito part and says so. Desktop or mobile site is chosen per page, for pinned pages in their page settings.

Long-press a tile for: Edit, **Page settings** (volume, zoom, aspect ratio, desktop or mobile site, microphone and camera, recording: the same settings as on the session screen, without opening the page), **Add an icon to the home screen** (the launcher puts the page next to your other apps), Remove. The first four pins are
also in the long-press menu of the TurboBox icon.

## Open it
Tap the tile. Tap more tiles to open more pages (up to four); the tabs at the top switch between them, and switching tabs does not pause the page you leave. A fifth page asks which to replace. With one page open it is simply its name; the letters appear (as `A | Name`, in the order the pages were opened) once there are two. A workflow that needs a page while four are open gets a temporary extra tab that goes away when the workflow is done.

**Split** puts several pages on screen at once: with it on, tap tabs to choose which pages share the screen (two, three or four; the highlighted tab is the one the bar acts on, tap it again to take it out). Sites show their desktop layout by default; Settings on a page has a **Mobile site** switch if you want the phone layout.

The bar acts on the page that is in front:
- **Close** closes the page straight away. **Mute** switches your outgoing audio off in a way the page cannot undo; it turns red while it is on.
- **Timer** closes the page after a delay, or at a clock time (tonight's 03:30).
- **Settings**: a ‹ › box at the top to go back and forward in the page's history, privacy switches for the microphone and camera, microphone, echo cancellation, microphone volume with a record-and-play-back test, page volume, brightness, page zoom, **aspect ratio** (fit the page into a 16:9, 4:3, 21:9, 1:1, 9:16 or 3:2 box, kept with the pin), page info. The sheet keeps a free strip on its right edge: rest your finger there to scroll.
- **Rec** starts or stops the recording buffer. **CC** turns live captions on (and starts the recording they are saved with). **Step away** / **Back** pause a page for you and catch you up later: see [RECORDING_BUFFER](RECORDING_BUFFER.md).
- **Mini** shrinks the page into a small floating window (it also happens by itself when you press Home on Android 12 or newer). The page is laid out to fit the window, including when you rotate the phone. A visible window makes Android much less likely to unload the app. The window has a microphone button (mute / unmute, the same as the notification's). While **Split** is on, the Mini window shows the pages you split, laid out like the split view, side by side, stacked, or with one page floating over the other (Settings > *Mini window while Split is on*, or a long press on **Mini**); without Split it shows the page in front.
- **Lock** is pocket protection: the screen goes black and ignores every touch (and the Back key). To unlock, touch and hold the two circles at the same time for a moment. It works with Step away, recording and Mini, and the lock never appears in a recording.
- A video's own **full screen** button works (YouTube, Twitch...): the video fills that page's area, not the whole phone; Back leaves it.
- **Hide bar** puts the bar across the top away: the page gets the whole screen and a small round button you can drag stays over it. Tap the button to bring the bar back.

Leaving the screen never pauses a page. The notification says what is running (`A | Name [muted] [recording] [hangs up in 42 min]`) and has **Mute** and **Close all**; tap it to come back. When nothing is running, Standby shows a quiet, minimised notification in its own *Standby* channel: switch that channel off in Android's notification settings to hide it while Standby keeps working.

## Keep it alive with the screen off
- Videos (YouTube, Twitch) keep playing with the screen off: the page is told it is visible and is kept on a private display of the app's own. Settings > *Keep video playing with the screen off* turns that off.
- Settings > **Staying alive with the screen off** > Allow: lets TurboBox ignore battery optimisation, so Android does not stop a page after some hours.
- Some phones (Xiaomi, Huawei, Samsung "sleeping apps", OnePlus) have their own app-killing settings. If a page stops after a while, set TurboBox to "no restrictions" there too (dontkillmyapp.com lists the steps per brand).

## Workflows and Standby
A [workflow](WORKFLOWS.md) can start by itself at a time you set. For one that uses the microphone while the phone is locked, turn **Standby** on in Settings: it
keeps TurboBox ready in the background (one small notification, almost no battery), which is what Android requires before it lets an app use the microphone
from the background. Also allow **exact alarms** (Settings shows a button) so a workflow starts on time and not minutes late.
