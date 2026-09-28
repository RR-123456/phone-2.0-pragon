# PragonMobile - Android app for Pragon

The app links your phone to Pragon on the PC (scan a QR once). After that,
"open YouTube on my phone" opens it - no cable, no USB debugging.

## 1. Get the APK (pick ONE way)

### A) Android Studio (free, ~1 GB)
1. Install Android Studio (developer.android.com/studio).
2. File > Open > choose this `PragonMobile` folder. Let Gradle sync finish (first time downloads a lot).
3. Build > Build Bundle(s) / APK(s) > Build APK(s). Click "locate" in the popup.
   The file is `app/build/outputs/apk/debug/app-debug.apk`.

### B) No install: build it on GitHub (free account)
1. Create a new repository on github.com.
2. Upload the CONTENTS of this folder (including the `.github` folder) to it.
3. Repository > Actions > "Build PragonMobile APK" > Run workflow. Wait ~5 min.
4. Open the finished run > Artifacts > download `PragonMobile-apk` > unzip to get `app-debug.apk`.

## 2. Install on the phone
Copy `app-debug.apk` to the phone, open it, allow "Install unknown apps" when asked.

## 3. One-time permissions (inside the app)
1. **Enable accessibility service** > PragonMobile > On. (Needed for Home/Back, taps, swipes, typing.)
2. **Allow display over other apps** (lets it open apps in the background).
3. **Battery: don't restrict** (on OnePlus also: Settings > Battery > App battery management > PragonMobile > Allow background activity / auto-launch),
   otherwise OxygenOS kills the connection.

## 4. Pair
1. Start Pragon on the PC. Open Remote - PhoneView > Connect Phone (QR appears).
2. In PragonMobile tap "Scan QR from Pragon". Status turns to "Connected to Pragon on your PC".
3. Say: "open YouTube on my phone".

Phone and PC must be on the same Wi-Fi. The pairing is remembered; after a PC restart the app
reconnects by itself (open PragonMobile once if the phone was rebooted).

## What works
open/launch any app by name, YouTube/Google search, open links, Home/Back/Recents,
notifications, quick settings, lock, volume, play/pause/next/previous, scroll/swipe, tap,
type into the focused box, open dialer, phone status.
Screenshots and force-closing apps need ADB (Android doesn't let normal apps do those);
Pragon automatically uses ADB for those if it's set up.

## Two-way Remote screen (new)

Once paired, PragonMobile shows a **Remote** section (below the permission buttons):
- **Chat** with Pragon from the phone - same as typing into PhoneView's web chat box.
- **Model pills** (Pragon / Jarvis / Friday / Ghost) - switch engines from the phone; switching
  on the desktop updates the pills here too.
- **Mic button** - streams the phone's mic to Pragon (needs the Microphone permission, asked for
  the first time you tap it).
- **PC-control buttons** - Wake, Lock PC, Play/Pause, Volume, Next, and "Open on PC" (type an app
  or site name and it opens on the computer). Media/volume/lock buttons are Windows-only for now.

This needs the updated `pragon_jarvis` zip (the one with `pragon_phoneview/server.py` and
`features/feature/pc_control.py` changes) - an older PC install will just ignore these messages.
