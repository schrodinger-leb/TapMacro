# Tap Macro

A TinyTask-style tap recorder/player for Android: record a sequence of taps,
then replay them at 1x / 2x / 5x / a custom speed multiplier — no root required.

## How it works

- **Recording:** a transparent full-screen overlay captures your touches
  (position + hold duration + delay since the previous tap) while instantly
  replaying each one to the app underneath via Android's Accessibility
  gesture API, so you see it register live as you tap.
- **Playback:** the same Accessibility Service replays the recorded taps with
  their delays divided by your chosen speed multiplier.
- **Controller:** a small draggable floating bubble (Record / Stop / Play /
  Speed / Save) that stays on top of whatever app you're automating.

No root is used or required. This relies entirely on the public
`AccessibilityService` + `dispatchGesture` API.

## Building the APK

### Option A — On a PC
1. Install **Android Studio** (free, from developer.android.com).
2. `File → Open`, select this `TapMacro` folder.
3. Let Android Studio sync Gradle.
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)`.
5. Grab the APK from `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — Phone only, via GitHub Actions
This repo includes `.github/workflows/build.yml`, which builds the APK on
GitHub's servers — no SDK install on the phone needed. Push this project to
a GitHub repo (e.g. from Termux with `git`), then check the **Actions** tab
in your repo on github.com for the built APK under the workflow run's
**Artifacts**.

## Using the app

1. Open **Tap Macro**, tap **"Enable Accessibility Service"** → find "Tap
   Macro" in the list → turn it on.
2. Tap **"Allow Draw Over Other Apps"** → grant the permission.
3. Tap **"Start Floating Controller"** — a small bubble appears on screen.
4. Open the app/game you want to automate. Drag the bubble wherever's
   convenient (drag the `⠿ Tap Macro` handle).
5. Tap **Rec**, perform your taps, tap **Stop**.
6. Pick a speed (**1x / 2x / 5x / Custom**) from the dropdown.
7. Tap **Play** to replay the sequence at that speed. Tap **Save** if you
   want to keep the macro (stored as JSON under the app's private storage —
   wire up a "load" screen in `MainActivity` if you want to browse/replay
   saved macros later; `MacroStorage.list()/load()` are already there).

## Notes & limits

- Works system-wide (any app), since gestures are dispatched at the OS level
  via Accessibility — but some apps with strong anti-automation/anti-cheat
  protections may detect or block synthetic gestures.
- Coordinates are recorded in absolute screen pixels, so a macro recorded on
  one device/resolution won't line up correctly on a different device.
- The custom speed dialog accepts any positive multiplier (e.g. `0.5` for
  half speed, `10` for 10x).
