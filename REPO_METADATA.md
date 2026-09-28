# Repo metadata: canonical copy

Source of truth for the **github.com/Marshall-Wagner/basic-apps** repo's own metadata (the bits
that live in GitHub's UI, not in a file) plus the README framing rules. Same idea as the
`LinkedIn_Assets/*.txt` trackers: edit here, then paste into GitHub.

Last updated: 2026-09-26 ("dialer" -> "phone" and "system monitor" -> "hardware monitor"
everywhere BasicMonitor is described (this file, both READMEs, the website card, both LinkedIn
trackers), so the app list matches what the apps are actually called. "dialer" was inherited from
the original description and was the only surface not saying "phone". "system monitor"
over-promises: on desktop that term implies process management, which BasicMonitor does not do; it
reports hardware state only, and "hardware monitor" is the established category name for that.
Prior 2026-09-25: de-capsed "INTERNET" in the About description; it reads as shouting in plain
prose. Kept the caps in README/app-README bodies, where it is the literal Android permission name
in backticks and therefore correct.)

---

## Repo name

```
basic-apps
```

## Display title (README H1)

```
Basic Apps: a minimal, offline, privacy-first Android suite
```

## About description (GitHub UI: repo page -> About -> gear icon)

GitHub caps this field at 350 characters. Plain text only, no markdown/backticks, so permission
names cannot be code-formatted here. Use plain hyphens, no em/en dashes.

CANONICAL (paste this):

```
Eight minimal, fully-offline Android apps (phone, SMS, contacts, keyboard, clock, calendar, camera, hardware monitor) - Kotlin/Compose, and none of them can reach the internet.
```

Previous LIVE value (superseded 2026-09-25):

```
Eight minimal, fully-offline Android apps (SMS, dialer, contacts, keyboard, clock, calendar, camera, system monitor) - Kotlin/Compose, no INTERNET permission.
```

## Homepage field

Currently empty. Options if you ever want it filled: the latest release
(`https://github.com/Marshall-Wagner/basic-apps/releases/latest`) or the portfolio site
(`https://marshall-wagner.github.io`). Leaving it empty is fine.

## Topics (20 set)

```
alarm-clock, android, android-app, calendar, camera, contacts, dialer, foss,
jetpack-compose, keyboard, kotlin, material-design, material3, no-internet, offline,
open-source, privacy, privacy-first, sms, system-monitor
```

`dialer` and `system-monitor` stay as topics even though the prose says "phone" and "hardware
monitor": topics exist to be searched, and those are the terms people actually search GitHub for.
Prose describes, topics get found; they do not have to match word for word.

---

## Copy rules

- **"internet" casing.** In *prose* surfaces (this About description, LinkedIn, the website card)
  write lowercase "internet". In the **README** bodies, `INTERNET` in backticks is the literal
  `android.permission.INTERNET` identifier, so the caps are correct there - do not lowercase those.
- **No em/en dashes** anywhere (hyphens and commas only), matching the rest of the suite's docs.
- **No bolding random mid-sentence words** in the READMEs. Bold only a leading term or label.
- Screenshots in READMEs use fake demo data captured on an emulator; real-device shots stay in
  the gitignored `_private-shots/`.

## Where each surface lives

| Surface | Where to edit |
|---|---|
| Repo About description, topics, homepage | GitHub UI (repo page -> About -> gear), copy from this file |
| Root README | `README.md` in this repo |
| Per-app READMEs | `<App>/README.md` |
| Build instructions | `BUILD.md` |
| Release notes + APK archive | `published-apks/v<ver>/` (gitignored) |
| LinkedIn project + featured copy | `Resume Website Project/Programming/LinkedIn_Assets/` |
| Portfolio website card | `Resume Website Project/Programming/resume_website_for_github/index.html` |
