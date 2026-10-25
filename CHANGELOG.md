# Changelog

Newest first. Each version's APK is attached to its GitHub Release.

## 2.8 (25 Oct 2026)
- **F1 tab** in the bottom bar:
  - **Weekend:** the current or next Grand Prix, with what's next and a countdown, the track graphic first, then the schedule, results so far, last year and the forecast.
  - **Live:** F1 Live timing.
  - **Standings:** drivers and teams.
  - **Season:** every 2026 race with its winner; tap one for its weekend page.
- **News** moved into More to make room.
- **Fixed:** the match sheet now scrolls. Before, a long F1 page squeezed the results to nothing and drew the track over the forecast.
- **Faster weekend page:** all its sources load at once, and grey pulsing placeholders show where the results and track are still coming.

## 2.7 (25 Oct 2026)
- **F1 weekend page:** tap any F1 card or session (the NEXT UP card, a list row, the calendar) to see the whole Grand Prix:
  - **Schedule:** every session in your time, marked done or LIVE, with the forecast for each.
  - **Results so far:** fastest in each practice, pole, sprint and race winners. Tap a session for its top 10; spoiler-free hides them.
  - **The track:** the circuit map, number of corners, and last year's winner, laps, race time and fastest lap.
  - **Race-day forecast** at the circuit: temperature, sky, chance of rain, wind.
  - Buttons for **Live timing** and the FIA **Upgrades** document, and a link to the circuit on Wikipedia.
- **New track graphics**, here and in F1 Live: a lime racing line on a dark track, corner numbers in badges, a chequered start/finish line and an arrow for the lap direction.

## 2.6 (25 Oct 2026)
- **F1 Live** (Scores → Racing → F1 → Live), straight from Formula 1's own live timing:
  - **Status banner:** green, yellow, safety car, VSC or red flag, plus lap count and weather.
  - **Timing tower:** positions, gaps and intervals, tyres, pit stops, and IN PIT / OUT / STOPPED.
  - **Map:** the circuit with numbered corners. Marshal sectors under yellow light up; the whole track goes orange for a safety car or red for a red flag.
  - **Race control:** every message, including flags, safety car, investigations, penalties and track limits.
  - **Pits:** live pit-lane times, and the full pit-stop list after the race.
  - **Upgrades:** the FIA's Car Presentation Submissions for the weekend, every team's new parts and why, shown in the app.
- **F1 alerts** for sessions you follow (More → Settings → F1 live): safety car, VSC, red flag, green flag, retirements, lead changes, and the chequered flag with the top three or pole.
- While on screen, F1 Live refreshes every 15 seconds during a session. In the background it checks every 2 minutes; Android may stretch that while the phone sleeps.

## 2.5 (25 Oct 2026)
- **Live match alerts** for your team and any match you belled: goals (with scorer and minute), red cards, half-time and full-time. More → Settings → Match day.
- **Lineups out:** both starting elevens in a notification, about an hour before kick-off.
- **Live score** in a quiet notification that updates during the match, and on the widget.
- How often it checks: every 10 minutes from 70 minutes before kick-off, every 2.5 minutes during the match, and nothing at full time. Android can stretch this to about 9 minutes while the phone sleeps.
- **Team pages:** badge, league position, league form, results and fixtures, and team news. Open one from a match sheet, Scores → Your teams, or a full-time card.
- **Full-time cards on Home** for your teams' matches from the last 36 hours, with the scorers.
- **Racing results:** F1, IndyCar, NASCAR and MotoGP, every session of the latest weekend. F1 race times come from Jolpica once it has the race.
- **UFC:** the latest card, main event first, with winners, method, round and time.
- **Form and head-to-head** in every football match sheet.
- **Add to calendar** and **Highlights** (a YouTube search) in match sheets.
- **Spoiler-free mode** (More → Settings): scores and results stay hidden until you tap, and no score alerts are sent.

## 2.4.1 (25 Oct 2026)
- **Widget badges:** the widget showed the club colours but no badges, because it tried to download them on the main thread, which Android refuses. It now fetches and draws them in the background, and a cut-off download can no longer leave a broken badge behind.

## 2.4 (25 Oct 2026)
- **Club badges on NEXT UP:** a football card shows both clubs half and half, each badge on its own club colour. Badges and colours come from ESPN.
- **Your favourite player only when he plays:** his photo leads a match only if he's in one of the two squads (checked against ESPN's current squads). Ronaldo no longer fronts Man Utd v Bournemouth.
- **No favourite:** choose None for player, driver or fighter. With no player, football cards always show the badges.
- The widget shows the same badges.
- **Updates no longer trip Play Protect:** More → Updates now hands the download to your browser, and SportClock no longer asks for permission to install apps. Play Protect blocks sideloaded apps that install APKs themselves.

## 2.3 (25 Oct 2026)
- **Pick which matches alert you.** Every upcoming match and session has a bell: tap it to get an alert for just that one, or to silence one you'd otherwise get. The same switch is in the match sheet.
- **Alert for: All or Picked**, per follow, in More → Settings. Football competitions start as Picked, so a Premier League weekend no longer means ten alerts; your team, F1 and UFC start as All.
- A picked match keeps its alert if TV moves it to another day.

## 2.2 (25 Oct 2026)
- **Tables no longer crash** when you switch league; only the first table you opened worked before. Switching F1 drivers/teams had the same bug.
- **Updates inside the app:** More → Updates shows the newest version and installs it in two taps. Home shows a note when one is ready.
- **Crash reports:** if the app crashes, it sends a report by itself the next time it opens.
- **Report a problem** in More sends a note straight to the app's GitHub, with the version, phone model and the screens you just used.

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
