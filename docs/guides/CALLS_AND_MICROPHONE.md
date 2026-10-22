# Calls and the microphone

This is Tether's web session, kept as it was. A page cannot see your phone's individual microphones, so TurboBox captures the one **you** choose and plays it into the page as its microphone.

## Choosing the microphone
Settings (on a page) > **Microphone (all pages)**. The list is the phone's real inputs: the built-in mic, a wired headset, a USB device, Bluetooth. Until you pick one the phone's own microphone is used, and the page is shown that name.

Pick the **phone mic** while a Bluetooth headset plays the sound: the headset then stays in its high-quality music mode (A2DP) instead of switching to the low-quality call mode, because TurboBox never puts Android into call mode. The choice is shared by every page, and changing it in the middle of a call does not restart the call.

The **Microphone level** bar at the top of the sheet is a live test of the chosen input.

## Volume
- **Microphone volume** (0-300%): how loudly the other person hears you. **Record 4 s and play it back** records with exactly the settings the call uses, plays it through the phone's media volume, and says whether it was too loud, good, faint or silent; one tap sets the volume that would have been right. It works while you are muted.
- **Page volume** (0-200%): the page's own sound. Above 100% boosts a quiet speaker. Kept with the pinned page.

## Mute
**Mute** is a lock, not a switch. A page flips its own track's on/off switch whenever its own mute button is pressed, which would undo a plain mute. TurboBox locks every outgoing audio track so that the real state is "what the page set **and not** our mute", also for new calls, replaced tracks and clones, sends digital silence from the native microphone path while muted, and asks the page every two seconds whether the mute is real. If something leaked, it fixes it at once and shows "MUTE LEAK FIXED". If the page stops answering, it says "MUTE NOT CONFIRMED" (close the page if you must be sure).

## Echo cancellation
**Echo cancellation & noise suppression** uses the phone's own effects on the chosen input. Turn it off for music or a quiet room.

## The page sees a desktop browser
TurboBox asks sites for their desktop layout: a desktop user agent and client hints, a 1280 px wide layout, desktop `navigator` properties. **Page zoom** lays the page out again at `1280 / zoom` pixels, like browser zoom. **Show page info** in Settings prints what the page sees.

## Hang-up timer
Timer ends the call on your end by closing the page: after 15 minutes to 8 hours, or at the next occurrence of a clock time. The notification shows a live countdown.
