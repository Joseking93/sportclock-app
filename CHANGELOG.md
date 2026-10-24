# Changelog

Newest first. Each version's APK is attached to its GitHub Release.

## 2.0.3 (25 Oct 2026)
- **Live countdown with seconds** on the NEXT UP card: the ring ticks every second (2:41:37, then 41:37 in the last hour) and still fills over the last two hours.
- **Sharper widget photo:** photos are bundled at up to 1280 px and the widget draws them at about its own pixel size instead of stretching a half-size copy.
- **The widget says how long to go**, rounded so it stays true between redraws: "in ~3h", "in about 1½h", "within the hour", "on now".

## 2.0.2 (24 Oct 2026)
- The widget's photo now melts into the background with no visible edge: the fade is built into the image and it fills the whole widget, with a soft darkening at the bottom so the next two rows stay readable.
- The widget's next-two rows no longer run the fixture into the day.

## 2.0.1 (24 Oct 2026)
- The NEXT UP card now grows with its text, so the big time is never clipped on phones with a larger font setting.

## 2.0 (24 Oct 2026)
First native version (Kotlin + Jetpack Compose); replaces the web-app wrapper as an in-place update.
- **NEXT UP cards you swipe**: one per thing you follow (F1, Man Utd, UFC), soonest first, with dots underneath.
- **Pin** a card (pin button or long-press) to keep it first.
- **Hero photo per sport**, led by your favourites: your driver's car for F1, your fighter for UFC (black and white), your player for your club.
- **Following** with Favourites (driver, fighter, player) picked from the bundled photo library, series switches, competitions and team search.
- **Calendar** (week strip and agenda), **Scores** (free feed; labelled as lagging behind the match, with the football-data.org credit).
- **Home-screen widget**, 2x2 and 4x2: next event with photo, start time and day; the wide version adds the next two.
- Same honesty rules as the website: "~" for approximate times, TBC never shows a guessed time, LIVE only for confirmed starts.
