# Getting started

TurboBox runs web pages the way a call app would: they keep going with the screen off, they can use your phone's microphone, and two can run at once.

## Install
1. Open the [latest release](https://github.com/TurboNZL/turbobox/releases/latest) on the phone and download `turbobox-<version>-android.apk`.
2. Allow "install unknown apps" for your browser when Android asks, then open the file.
3. Open TurboBox and allow the **microphone** and **notifications** when it asks. Notifications matter: the notification is how Android lets
   a page keep running in the background, and it carries Mute and Close.

The app updates itself: Settings > App updates > Check for updates.

## Pin a page
On the home screen tap **Pin a page**, type a name and the address (`meet.example.com/room` is enough, `https://` is added). Tick **Start the recording
buffer when it opens** for a lecture you always want to be able to rewind. A pinned page is one tap from the home screen.

A tile shows the page's own icon (found when you pin it, and updated whenever the page reports one; a letter until then).

Long-press a tile for: Edit, **Add an icon to the home screen** (the launcher puts the page next to your other apps), Remove. The first four pins are
also in the long-press menu of the TurboBox icon.

## Open it
Tap the tile. The page opens in slot **A**. Tap a second tile and it opens in slot **B**; the tabs at the top switch between them, and **Split** shows both at
once (one above the other, or side by side when the phone is on its side). A third page asks which of the two to replace.

The bar acts on the page that is in front:
- **Close** ends the call on your end. **Mute** switches your outgoing audio off in a way the page cannot undo; it turns red while it is on.
- **Timer** hangs up after a delay, or at a clock time (tonight's 03:30).
- **Settings**: privacy switches for the microphone and camera, microphone, echo cancellation, microphone volume with a record-and-play-back test, page volume, brightness, page zoom, page info. The sheet keeps a free strip on its right edge: rest your finger there to scroll.
- **Rec** starts or stops the recording buffer. **CC** turns live captions on (and starts the recording they are saved with). **Step away** / **Back** are for lectures: see [RECORDING_BUFFER](RECORDING_BUFFER.md).
- **Mini** shrinks the page into a small floating window (it also happens by itself when you press Home on Android 12 or newer). The page is laid out to fit the window, including when you rotate the phone. A visible window makes Android much less likely to unload the app.

Leaving the screen never pauses a page. The notification says what is running and has **Mute** and **Close all**; tap it to come back.

## Keep it alive with the screen off
- Settings > **Staying alive with the screen off** > Allow: lets TurboBox ignore battery optimisation, so Android does not stop a page after some hours.
- Some phones (Xiaomi, Huawei, Samsung "sleeping apps", OnePlus) have their own app-killing settings. If a page stops after a while, set TurboBox to "no restrictions" there too (dontkillmyapp.com lists the steps per brand).

## Workflows and Standby
A [workflow](WORKFLOWS.md) can start by itself at a time you set. For one that uses the microphone while the phone is locked, turn **Standby** on in Settings: it
keeps TurboBox ready in the background (one small notification, almost no battery), which is what Android requires before it lets an app use the microphone
from the background. Also allow **exact alarms** (Settings shows a button) so a workflow starts on time and not minutes late.
