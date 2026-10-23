# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with rotating nature wallpapers. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>`.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen, including the wallpapers; plain Android, no background services. Its only network use is the weather |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, grid columns, weather location, verse list |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Clock, greeting, date and the weather (Open-Meteo, refreshed at most every 30 minutes while the
  home screen is in front; a failed fetch retries after 5 minutes, up to three times) top-left.
  The weather line shows the current temperature plus an icon and words for the day's forecast
  (rain, storms, snow, fog, cloud, clear, or windy from the gust level in `config.xml`).
  Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Pills top-right for **Wi-Fi** (network name and a live icon for
  working, no internet, connecting or offline, or an Ethernet icon when wired; opens Wi-Fi
  settings) and **Settings**; Up from the dock reaches them. The remote's Menu key opens an
  **Inputs** dropdown (HDMI/AV/Live TV, or Settings when there are none).
- Today's schedule top-right from up to three Google Calendar private iCal links in
  `launcher/calendar.env` (git-ignored) as `ICAL_URL_1=` to `ICAL_URL_3=`; the build compiles them
  in and prints only how many it found. Up to four events still to come today (all-day ones on one
  line at the top), or tomorrow's first three when nothing is left, in Melbourne time. Fetched
  only while the home screen is in front, at most every 30 minutes (5 after a failure, three
  times), cached for offline use and marked stale after 3 hours. Recurring events, exceptions,
  moved or cancelled occurrences, time zones and daylight saving are handled by a small parser
  (`launcher/src/.../IcsParser.java`, tested against made-up events in `launcher/test/`). Set
  `show_event_titles` to `false` in `config.xml` to show only times and "Busy".
- A verse of the day lower-left, one of 30 references chosen by date, in the ESV from the
  [ESV API](https://api.esv.org/), labelled ESV. Crossway's copyright notice can be shown as one
  dim line along the bottom edge by setting `show_esv_notice` to `true` in `config.xml` (off by
  default). Put your key in `launcher/esv.env` (git-ignored) as `ESV_API_KEY=<key>` and rebuild; the
  build compiles it in and never prints it. The app asks for the day's passage only while the home
  screen is in front, retries a failure after an hour (at most three requests a day), and caches it. Without a key, offline, or on any error it shows the bundled public-domain
  Berean Standard Bible text, labelled BSB. Verses that would need more than four lines are skipped,
  never shortened.
- Daily verse references from YouVersion Platform. With a key in `launcher/youversion.env`
  (git-ignored) as `YOUVERSION_APP_KEY=<key>` (never commit or share it) and the ESV key, the day's
  reference is YouVersion's verse of the day, shown in the ESV. A range too long for the card shows
  its first verse. The year's list is fetched while the home screen is in front, kept for 30 days,
  and refetched in a new year. Without the key, on any failure, or with `use_youversion_votd` set
  to `false` in `config.xml`, the bundled 30-verse list is used as before. `scripts/yv-check.sh`
  tries the same API by hand.
- A dock of your favourite apps. The focused app's name appears above it over a glow coloured from
  its banner. Press Down for every other launchable app, plus Settings, in a grid. The clock,
  weather and verse are display only.
- Long-press OK on a dock app for **Move** or **Remove from dock**, or on a grid app for **Add to
  dock** (up to 6), or on a grid app for **Move** or **Add to dock**. Move works like stock Android
  TV: Left/Right (and Up/Down in the grid) slide the app, OK saves, Back cancels. Both orders are
  saved on the TV; `config.xml` is only the first-run default. New apps join the end of the grid,
  and the Settings tile stays last.
- Long-press a grid app for **Hide**. The Settings tile's menu then offers **Show hidden apps (N)**,
  which shows them dimmed in their places with **Unhide** in their menu, until **Hide hidden
  apps** or a restart. Apps in `hidden_apps` in `config.xml` stay hidden and aren't listed.
- `icon_aliases` in `config.xml` shows another installed app's own banner on a tile, such as
  YouTube's on SmartTube (`org.smarttube.stable=com.google.android.youtube.tv|SmartTube`; the
  `|Label` part is optional). Nothing is bundled; if that app isn't installed the tile keeps its own icon.
- 12 nature photos crossfade every 10 minutes, with a scrim so text stays readable. Swap them in
  `launcher/assets/wallpapers/` (1920×1080 JPEGs, credits in `launcher/WALLPAPER-CREDITS.md`).
- Deterministic D-pad focus, remembers the last tile, picks up newly installed apps on return.

Change the dock: edit `config.xml`, then run `launcher/build-and-install.sh`.

Icons are [Material Symbols](https://github.com/google/material-design-icons) (outlined, Apache
License 2.0), converted to vector drawables in `launcher/res/drawable/`: weight 400 for every icon on
the Wi-Fi and Settings buttons, in every network state (white on glass needs the heavier stroke),
and weight 300 for the rest. The "!" badge for
"no internet" is hand-drawn, since Material has no such icon.

The fallback verses are the Berean Standard Bible (BSB), public domain
([berean.bible/licensing.htm](https://berean.bible/licensing.htm)), copied verbatim from
bereanbible.com/bsb.txt, downloaded 23 October 2026.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
