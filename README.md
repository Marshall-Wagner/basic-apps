# Basic Apps: a minimal, offline, privacy-first Android suite

[![Unit tests](https://github.com/Marshall-Wagner/basic-apps/actions/workflows/unit-tests.yml/badge.svg)](https://github.com/Marshall-Wagner/basic-apps/actions/workflows/unit-tests.yml)

A set of hand-built replacements for the phone's stock apps, designed around one rule:

> **None of these apps hold the `INTERNET` permission.**
> Nothing you type, dial, photograph, or receive can leave the device, not to me, not to anyone. Privacy isn't a setting here; it's enforced by the manifest.

Each app is a small, dependency-light, fully-offline tool that does one job well, built with a consistent Kotlin + Jetpack Compose stack.

---

## Why I built these

Mostly to fix real problems on my own phones:

- On the ROG Phone 6 (Chinese ROM), the stock phone, SMS, and camera apps were dated, heavier than I wanted, and buggy. The camera in particular would often refuse to open with an "another app is using the camera" error, leaving me with no working camera at all. Focused, offline replacements I fully control fixed the daily friction and removed background behavior I didn't need.
- Keeping every app fully offline was the point: once each one does its job without the network, "nothing leaves the device" stops being a promise and becomes something the manifest enforces.
- BasicSms sticks to plain SMS/MMS rather than RCS. My ROG Phone 5 (global ROM) shipped near-stock, AOSP-based apps, and at some point its messaging was quietly migrated to Google Messages, and RCS with it, without my noticing at first - exactly the kind of silent change to a core app I wanted to be rid of. RCS also can't be done offline: it routes through carrier or Google (Jibe) servers, so it can't live in a suite whose rule is no INTERNET permission. (On encryption: Google Messages does end-to-end encrypt its own RCS chats with the Signal Protocol; the still-maturing part is cross-platform, standardized RCS E2EE. Either way, RCS needs the network, which these apps don't use.)

---

## The apps

| App | Role | What it does |
|-----|------|--------------|
| **[BasicPhone](BasicPhone/)** | Default dialer / phone | Call log, full in-call UI (`InCallService`/Telecom), STIR/SHAKEN spam screening, voicemail with saved PIN + DTMF, Bluetooth call-audio routing, VoLTE indicator, multi-SIM, offline number lookup, call blocking |
| **[BasicSms](BasicSms/)** | Default SMS app | Reliable background receipt (`SMS_DELIVER`), conversation threads, multi-SIM send, MMS images, backup import, notification handling |
| **[BasicKeyboard](BasicKeyboard/)** | Keyboard | English + Russian layouts, symbols & emoji, auto number/dial pad for numeric & phone fields, multi-touch key rollover, held-backspace word delete, sensitive-clip-aware clipboard strip, adjustable size/style |
| **[BasicCamera](BasicCamera/)** | Camera | Photo & video (CameraX), HEIC capture, aspect-ratio & resolution control, zoom presets, volume-button shutter, EXIF stripping |
| **[BasicContacts](BasicContacts/)** | Contacts | List / detail / edit over `ContactsContract`, vCard import & export |
| **[BasicClock](BasicClock/)** | Clock, alarms & timers | Time-zone-anchored alarms (ring at the correct local moment, right across DST & travel), ringtone picker, full-screen ring with snooze, world clock with live times + country names, plus a stopwatch and a background countdown timer |
| **[BasicCalendar](BasicCalendar/)** | Calendar alarms | Set an alarm for a specific future date and time in any city's time zone; month grid + event list, none / weekly / monthly / yearly repeats, reminder lead time (30 / 60 min before, or none for a silent entry), full-screen ring with snooze, survives reboot & zone changes |
| **[BasicMonitor](BasicMonitor/)** | System monitor | Live CPU model & clock, GPU model, RAM, swap, storage, battery, all with zero permissions from public APIs / world-readable sysfs |

---

## Why this is more than a to-do app

Most of these live in parts of the Android platform that ordinary apps never touch, which is the interesting part:

- **Privileged system roles**: a real default dialer (`InCallService` + `TelecomManager`), a real default SMS app (`SMS_DELIVER` broadcast, the role/permission system), and a custom keyboard (`InputMethodService`). These are areas with strict contracts and little margin for error.
- **Reactive data**: call logs and message threads stream via Paging 3 backed by `ContentObserver` invalidation, so the UI stays live without polling.
- **Careful version-guarding**: one codebase spanning Android 8 (API 26) through Android 15 (API 35), guarding APIs like `POST_NOTIFICATIONS`, `CallStyle`, Bluetooth routing, and scoped SMS sending per version.
- **Real-world hardening**: pragmatic workarounds for aggressive OEM power management (battery-exemption prompts, background-receipt reliability) learned from testing on actual hardware.

## Tech stack

Kotlin · Jetpack Compose · Coroutines & Flow · Paging 3 · Room · CameraX · Material 3
`minSdk 26` (BasicCamera `29` / Android 10) · `compileSdk / targetSdk 35` · arm64 · R8 full-mode release builds

**Theming:** Material 3, following your system light/dark theme (switch it in Android's display settings, since there's no in-app toggle). The SMS, dialer, and contacts apps also adopt Material You dynamic color on Android 12+; the camera uses a dark viewfinder by design.

**Footprint:** each app uses roughly 40-45 MB of RAM while running (idle, on its main screen) and effectively 0% CPU at rest (no background work, no network). Most of that is the shared Jetpack Compose + Android runtime baseline every Compose app carries, so these are about as lean as a Compose UI app gets. (RAM measured as PSS, so it is representative rather than exact for any given phone.)

## Download & install

Prebuilt, signed APKs are on the [Releases page](https://github.com/Marshall-Wagner/basic-apps/releases/latest). Grab the apps you want and sideload them: allow "install unknown apps" for your file manager, then open each APK.

**Requirements:** an `arm64-v8a` (64-bit ARM) phone, which is essentially every phone from ~2017 on. The APKs are arm64 only and will not install on 32-bit ARM (`armeabi-v7a`) or `x86` devices. Android 8+ (API 26) for every app except BasicCamera, which needs Android 10+ (API 29). Each release also ships a `SHA256SUMS.txt` so you can verify downloads (`sha256sum -c SHA256SUMS.txt`).

## Building

Each app is an independent Gradle project; open any one in Android Studio and run it. See [BUILD.md](BUILD.md) for details. Requires JDK 17 or 21 (not 22+).

## Status & scope

A personal project, built and tested primarily on an ASUS ROG Phone 6 (Android 14, Chinese ROM: no Google services, aggressive background killing) and a ROG Phone 5 (Android 11, global ROM, closer to stock). Covering both is deliberate: the CN ROM stresses the background-receipt and battery-exemption hardening far harder than the global build. All eight apps also launch and run without crashes or issues on a Samsung Galaxy S22+ (One UI, Android 12+), a second OEM, though SMS/call telephony was not exercised there. Several were additionally checked on an HTC 10 (Android 8, the `minSdk 26` floor), where BasicKeyboard, BasicClock, BasicMonitor, and BasicContacts run fully and BasicSms/BasicPhone install and run but can't be exercised (that phone has no cell service). So SMS and call telephony (real send/receive and calls) was validated only on the ROG phones, and BasicCamera requires Android 10 (API 29), so it won't install on the HTC 10. Many decisions (and a few workarounds) are tuned to these devices, so behavior may differ on other phones or ROMs. Provided as-is, with limited support.

## License

Released under the [GNU General Public License v3.0](LICENSE). You're free to use, study, modify, and share these apps; derivative works must remain open under the same license, fitting for tools whose whole point is that you can audit exactly what they do.
