# Changelog

Newest first. Each version's APK is attached to its GitHub Release.

## 2.1 (25 Oct 2026)
- **Scores that work:** live scores, the minute, half-time and full-time results now come from ESPN, fetched by the phone. They are checked every 2 minutes while a match you can see is on, otherwise every 15. The old free feed kept flipping live matches back to "not started".
- **Lineups and goals:** a match's detail sheet shows both starting elevens with formation and subs once they are out (about an hour before kick-off), plus who scored.
- **League tables** for the Premier League, Championship, La Liga, Serie A, Bundesliga, Ligue 1, Eredivisie, Primeira Liga and the Champions League, with your team highlighted.
- **F1 standings**, drivers and teams (Jolpica).
- **News tab:** BBC Sport headlines for what you follow (F1, UFC, boxing, motorsport, your club), with photos; tap to read.
- **Starting-soon alerts are back:** turn them on in More → Settings and pick 10, 15, 30 or 60 minutes before. Exact alarms, so they arrive with the app closed, and they are re-armed after a restart. TBC events never alert.
- **Competition logos** (Champions League, Premier League, F1, UFC and the rest) on rows, cards and the detail sheet.
- **Text size** setting: Small, Medium, Large or the phone's own.
- **Widget timing:** redraws every 5 minutes in the last hour and every 15 within three hours, so it says "in about 10 min" or "in about 1h 15m" instead of "within the hour".
- The detail sheet's date no longer repeats itself ("Tue 3 · Tue 3 Nov" is now "Tuesday 3 Nov", or "Today · Sat 24 Oct").

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
