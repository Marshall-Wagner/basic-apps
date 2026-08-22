# Basic Apps: a minimal, offline, privacy-first Android suite

A set of hand-built replacements for the phone's stock apps, designed around one rule:

> **None of these apps hold the `INTERNET` permission.**
> Nothing you type, dial, photograph, or receive can leave the device, not to me, not to anyone. Privacy isn't a setting here; it's enforced by the manifest.

Each app is a small, dependency-light, fully-offline tool that does one job well, built with a consistent Kotlin + Jetpack Compose stack.

---

## The apps

| App | Role | What it does |
|-----|------|--------------|
| **[BasicPhone](BasicPhone/)** | Default dialer / phone | Call log, full in-call UI (`InCallService`/Telecom), STIR/SHAKEN spam screening, voicemail with saved PIN + DTMF, Bluetooth call-audio routing, VoLTE indicator, multi-SIM, offline number lookup |
| **[BasicSms](BasicSms/)** | Default SMS app | Reliable background receipt (`SMS_DELIVER`), conversation threads, multi-SIM send, MMS images, backup import, notification handling |
| **[BasicKeyboard](BasicKeyboard/)** | Keyboard | English + Russian layouts, symbols & emoji, multi-touch key rollover, held-backspace word delete, sensitive-clip-aware clipboard strip, adjustable size/style |
| **[BasicCamera](BasicCamera/)** | Camera | Photo & video (CameraX), HEIC capture, aspect-ratio & resolution control, zoom presets, volume-button shutter, EXIF stripping |
| **[BasicContacts](BasicContacts/)** | Contacts | List / detail / edit over `ContactsContract`, vCard import & export |
| **[BasicClock](BasicClock/)** | Clock, alarms & timers | Time-zone-anchored alarms (ring at the correct local moment, right across DST & travel), ringtone picker, full-screen ring with snooze, world clock with live times + country names, plus a stopwatch and a background countdown timer |
| **[BasicCalendar](BasicCalendar/)** | Calendar alarms | Set an alarm for a specific future date and time in any city's time zone; month grid + event list, none / weekly / monthly / yearly repeats, full-screen ring with snooze, survives reboot & zone changes |
| **[BasicMonitor](BasicMonitor/)** | System monitor | Live CPU model & clock, GPU model, RAM, swap, storage, battery, all with **zero permissions** from public APIs / world-readable sysfs |

---

## Why this is more than a to-do app

Most of these live in parts of the Android platform that ordinary apps never touch, which is the interesting part:

- **Privileged system roles**: a real default **dialer** (`InCallService` + `TelecomManager`), a real default **SMS app** (`SMS_DELIVER` broadcast, the role/permission system), and a custom **keyboard** (`InputMethodService`). These are areas with strict contracts and little margin for error.
- **Reactive data**: call logs and message threads stream via **Paging 3** backed by **`ContentObserver`** invalidation, so the UI stays live without polling.
- **Careful version-guarding**: one codebase spanning **Android 8 (API 26) through Android 15 (API 35)**, guarding APIs like `POST_NOTIFICATIONS`, `CallStyle`, Bluetooth routing, and scoped SMS sending per version.
- **Real-world hardening**: pragmatic workarounds for aggressive OEM power management (battery-exemption prompts, background-receipt reliability) learned from testing on actual hardware.

## Tech stack

Kotlin · Jetpack Compose · Coroutines & Flow · Paging 3 · Room · CameraX · Material 3
`minSdk 26` · `compileSdk / targetSdk 35` · arm64 · R8 full-mode release builds

**Theming:** Material 3, following your system light/dark theme (switch it in Android's display settings, since there's no in-app toggle). The SMS, dialer, and contacts apps also adopt Material You dynamic color on Android 12+; the camera uses a dark viewfinder by design.

## Building

Each app is an independent Gradle project; open any one in **Android Studio** and run it. See **[BUILD.md](BUILD.md)** for details. Requires **JDK 17 or 21** (not 22+).

## Status & scope

A personal project, built and tested primarily on an **ASUS ROG Phone 6 (Android 14)** and **ROG Phone 5 (Android 11)**. Many decisions (and a few workarounds) are tuned to those devices, so behavior may differ on other phones or ROMs. Provided **as-is**, with limited support.

## License

Released under the **[GNU General Public License v3.0](LICENSE)**. You're free to use, study, modify, and share these apps; derivative works must remain open under the same license, fitting for tools whose whole point is that you can audit exactly what they do.
