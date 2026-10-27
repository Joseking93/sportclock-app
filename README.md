# SportClock (Android)

The native Android app for SportClock: when your sport is on, in your time.
It reads the same live data as the website ([sportclock.pages.dev](https://sportclock.pages.dev)), so fixtures update without a new APK.

**Private repository.** The app bundles press photos for personal use only; don't make this repo public or share the APK publicly.

## Screenshots

| Home: pinned F1 card | Home: swipe to Man Utd | Calendar |
|---|---|---|
| <img src="docs/screenshots/home-f1-pinned.jpg" width="260"> | <img src="docs/screenshots/home-man-utd.jpg" width="260"> | <img src="docs/screenshots/calendar.jpg" width="260"> |

| Favourites: choose your driver | Favourites: choose your player | Installing: Play Protect check |
|---|---|---|
| <img src="docs/screenshots/favourites-driver.jpg" width="260"> | <img src="docs/screenshots/favourites-player.jpg" width="260"> | <img src="docs/screenshots/play-protect.jpg" width="260"> |

## Install
1. Download the APK from the latest [Release](../../releases) and open it on the phone.
2. If Android asks, allow your browser or Files app to **install unknown apps** (a one-time setting).
3. **Google Play Protect scan:** if Play Protect is on (it is by default), the phone scans the APK before installing, because it doesn't come from the Play Store. Wait for the result. It should say **"This app looks safe"**; then tap **Install** (or **Update**). If it ever offers "Scan app" instead, tap it and wait.
4. Open SportClock.

It is signed with the same key as the earlier web-app version, so it updates in place without uninstalling. Android 8.0 or newer.

## Build
1. Double-click `build-release.cmd` (or run `.\build-release.cmd`).
2. Type the keystore password and the key password (hidden; never stored).
3. The signed APK appears as `SportClock-native.apk`.

Needs a 64-bit JDK 17 in `%USERPROFILE%\.jdks\jdk-17.0.20.1+1` and the Android SDK set in `local.properties`.
The keystore lives outside the repo (`../sportclock-android/android.keystore`) and is never committed.

## Where things are
- `app/src/main/java/app/sportclock/Model.kt`: events, series, time wording, status pills
- `Repo.kt`: data and scores fetching, follows, favourites, pin, and which NEXT UP cards show
- `Ui.kt`: hero card, pager, rows; `MainActivity.kt`: screens; `FollowScreen.kt`: Following and Favourites
- `Feeds.kt`: scores, lineups, goals and tables from ESPN, including FA Cup and Carabao Cup fixtures; F1 standings from Jolpica; news from BBC Sport (all fetched by the phone)
- `Alerts.kt`: "starting soon" alarms, settings, widget redraw timing; `Screens2.kt`: Scores/Tables/F1, News, Settings, lineups with pitch and list views
- `widget/SportClockWidget.kt`: the home-screen widget
- `Nfl.kt`: NFL schedule, scores and standings from ESPN, merged into the events; `MyTab.kt`: the customisable fourth tab
- `F1Weekend.kt`: the F1 weekend page's data (Jolpica calendar and history, OpenF1 circuit keys, Open-Meteo forecast)
- `F1Live.kt` + `F1Screens.kt`: F1 live timing (F1's signalrcore feed), track maps (MultiViewer), pit stops, FIA upgrades
- `Live.kt`: match-day alerts (lineups, goals, cards, HT, FT, live score); `Results.kt`: form, head-to-head, team pages, racing and UFC results; `Screens3.kt`: their screens
- `Badges.kt`: club badges, colours and squads from ESPN for football cards; `Diag.kt`: crash catcher and bug reports; `Updater.kt`: in-app updates; `ServiceScreens.kt`: their screens
- `Photos.kt`: the bundled photo library (generated)

The FA Cup and Carabao Cup tabs include official competition marks, upcoming fixtures and a round-by-round draw. Cup match details show the round, extra-time and penalty information when ESPN provides it. Selecting a cup or following a club adds its matches across competitions to Home; lineups appear in a formation pitch with the full substitutes list once ESPN publishes them.

## Updates and bug reports
The app talks to a small service on the website (`functions/api/app` in the `sportclock` repo) because this repo is private:
- **Updates:** More → Updates (and a note on Home) finds the newest GitHub Release and opens its APK link in the phone's browser, which downloads and installs it.
  The app does not install APKs itself (no REQUEST_INSTALL_PACKAGES): Play Protect blocks sideloaded apps that do.
  Publish a release with the signed APK attached and phones pick it up within 12 hours.
- **Crashes** are saved on the phone and sent as a GitHub issue here the next time the app opens; **More → Report a problem** sends one by hand.
  Release builds are not obfuscated, so the stack traces in those issues are readable.
- The service needs the `GH_TOKEN` secret on Cloudflare (see the website README). Without it, nothing breaks: reports wait on the phone.

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.
