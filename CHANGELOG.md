# Changelog

All notable changes to MetroPulse are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [SemVer](https://semver.org/).

## [1.0.0] - 2026-10-30

First public release.

### Added
- **Live arrivals in London and New York** from TfL's and the MTA's feeds: live departures at stations and live trains on line maps, labelled *Live*. Turn them off in Settings.
- **International metros:** London (Underground and DLR, Night Tube included), New York (the MTA's official timetable), Dubai, Doha and Riyadh. Cities are grouped by country, fares show in each city's currency, and Gulf cities' Friday timetables are used on Fridays.
- **Offline maps:** download a city's map in Settings to see streets, labels and 3D buildings without a connection. The map says when you're offline.
- **Welcome tour.** The first launch introduces the app, asks for your city, and explains why on-board mode would like location and notifications before Android asks. Every permission can be skipped.
- **Home-screen widgets.**
  - *Next trains* shows timetabled departures from your favourite stations. Tap the station name to switch between favourites. Trains that end before the terminus are faded.
  - *My trip* follows a trip in progress, or starts one with a tap.
- **"Start my commute" Quick Settings tile.** Set home and work stations in Settings. The tile heads to work before noon and home after.
- **Launcher shortcuts** for search, on-board mode and your favourite or recent journeys.
- **More settings:** theme (system, light or dark), high contrast, reduce motion, default wake alert, weekly city data updates with a manual check.
- **Licences and sources** screen crediting the current city's data sources, the map and every library.
- **Journey planner.** The Plan tab finds the fastest route and the one with fewest changes between any two stations, offline.
  - Pick stations by search or from nearby ones, swap them, and leave now, in 15–60 minutes or at a chosen time.
  - Each option shows its times, changes, lines, the token fare and the smart-card price, labelled *Scheduled* or *Approx.*
  - The journey screen shows a timeline in line colours with every change and walk, the stops in between, and the fare per ticket. *Start trip* hands the journey to on-board mode.
  - Favourite journeys and recent ones start a new plan with one tap.
- **Station details:** the next three trains per line and direction, with short trips marked "Ends at …", today's first and last train, exits with step-free tags, and buttons to plan from or to the station or show it on the map.
- **Line details:** a strip map of every station with the timetabled trains moving along it, in both directions.
- **Search** from the map's *Where to?* bar, with nearby and favourite stations listed before you type.
- Smooth transitions between lists and details. The tab bar steps aside on detail screens. Every screen has a designed empty or error state.
- **On-board mode.** Tap *On board*, pick your stop, and MetroPulse follows your train.
  - It starts from the nearest station, shows the route and departure, and notices when the train moves off. It warns you if the train is going the wrong way or if you miss your stop.
  - In tunnels and underground stations it carries on by the timetable, labelled *Scheduled*, and switches back to *Live* when GPS returns.
  - A wake alert rings one or two stops before your destination, with a full-screen card that works with the phone locked and the screen off.
  - The trip shows in a progress notification with your line's colours, your changes and the minutes left, as a Live Update on Android 16. The map follows your train in violet.
  - Location is used only during a trip, never leaves the phone, and works without Google Play services. Without location permission, the trip follows the timetable.
- **3D network map.** The Map tab shows the chosen city in 3D, with buildings rising as you zoom in.
  - Metro lines appear in their official colours. Elevated sections stand on viaducts with pillars, and underground sections are dashed.
  - Trains move along the lines by the timetable, with a label saying they are scheduled (or approximate) positions, not live ones. Cities without a published timetable say so instead of showing trains.
  - Tap a station to see which lines serve it.
  - The map uses free OpenFreeMap tiles with bundled light and dark styles, English labels, and the OpenStreetMap attribution always visible.
- **Choose your city.** A city picker shows all 21 systems with an honest label for each: timetable, approximate times, or first & last trains only. The choice is remembered, and the city's data is installed from the APK on first use.
- **Lines tab with real data:** every line with its official colour, termini, station count and today's first and last trains. Lines without a published timetable say "Times unavailable" instead of showing invented departures.
- Routing engine (RAPTOR) that finds the fastest journey and the one with fewest changes, leaves as late as the connection allows, and handles after-midnight trains. For lines without a timetable it finds the route and estimates the ride time, clearly marked as untimed.
- Fare engine covering official station-to-station tables, distance and station-count slabs, separate tickets per operator, and smart-card/QR discounts by peak hours, day of week and line.
- Typo-tolerant station search ("rajiv chok", "MG Rd"), plus GPS map-matching and dead reckoning groundwork for on-board mode.
- On-device storage for favourite stations, favourite journeys and recent journeys.
- **Offline network data for 21 Indian metro systems**, bundled in the app: Agra, Ahmedabad–Gandhinagar, Bengaluru, Bhopal, Chennai, Delhi, Gurugram, Hyderabad, Indore, Jaipur, Kanpur, Kochi, Kolkata, Lucknow, Meerut, Mumbai, Nagpur, Navi Mumbai, Noida, Patna and Pune.
  - Lines carry official colours. Stations come with entrances, track geometry tagged elevated, underground or at grade, interchange walking times, published first and last trains, and fares where operators publish them.
  - Every city is labelled with how its times were made. Bengaluru: official departures with modelled per-station times. Most cities: modelled from the operator's published first/last trains and headways. Delhi, Agra, Bhopal, Indore and Meerut: first and last trains only, because no headways are published. Days without published headways show "times unavailable"; times are never invented.
  - City data updates arrive weekly from GitHub without an app update. Each download is verified with SHA-256 and an SQLite integrity check, then swapped in atomically.
- `tools/build_data.py`: the Python pipeline that builds the city databases from GTFS, OpenStreetMap (Overpass) and curated per-city sources, with validation and a generated [DATA_SOURCES.md](DATA_SOURCES.md).
- **In-app auto-update from GitHub Releases.**
  - On launch and once a day in the background, the app checks `releases/latest`, honouring the user's setting and using ETags for conditional requests.
  - The update sheet shows the version change, size, date and rendered release notes. GitHub's auto-generated notes are tidied, so PR links become `#12`.
  - Downloads run in a foreground WorkManager job with a progress notification. They resume over HTTP Range and continue in the background.
  - Every APK is checked against GitHub's SHA-256 asset digest (or a `.sha256` companion asset) and must be signed with the installed app's certificate. Corrupted or impostor APKs are refused and deleted.
  - Installation goes through a `PackageInstaller` session, with a guided flow for the "install unknown apps" permission that resumes on its own once permission is granted.
  - Users can skip a version, choose "Later", or turn off automatic checks. Downloaded APKs are cleaned up after a successful update.
  - Debug builds never offer release APKs. Local end-to-end testing uses `-Pmetropulse.debugUpdateFeedUrl`.
- Design system:
  - Inter variable font with tabular numerals, light, dark and high-contrast colour schemes, and spring motion tokens that respect reduced motion.
  - Frosted-glass surfaces (Haze; RenderEffect on API 31+), gradient hero surfaces, a shimmer progress bar, rolling digit counters, Live/Scheduled/Approx. time chips and semantic haptics.
  - A custom stroke icon set.
- An original adaptive app icon (pulse wave into a station ring) with a themed monochrome layer, and an animated splash screen.
- App shell: floating glass tab bar (Map, Plan, Lines, Settings), placeholder screens and a Settings screen with update controls.
- Gradle multi-module build (`:app`, `:core:domain`, `:core:data`, `:core:designsystem`), Hilt, R8 and resource shrinking for release builds, and versionCode derived from the version name.

### Changed
- Night Tube hours read "From 5:50 am, through the night".
- **Smaller download:** the release APK is about 14 MB, down from about 28 MB, and starts faster thanks to a baseline profile.
- On-board mode says when it is following the timetable because location is off or not allowed, with a way to fix it.
- If a city's data can't be opened, the app offers to try again or pick another city instead of showing a blank map.
- Where an operator publishes only peak frequencies, the app no longer fills the rest of the day from them; those days show first and last trains only.
- Black line colours (London's Northern line) show in grey on dark backgrounds.
- Buttons grow with large text, and tab labels no longer clip at the largest font sizes. Every control has a TalkBack label.
- City data sources are credited by who published them: operators, news reports or Wikipedia.
- **More realistic 3D tracks and trains.**
  - Lines rise and fall smoothly between viaduct, ground and tunnel. Viaducts have parapets and T-shaped piers, ramps run on embankments, and tunnel entrances sit in walled cuttings with a portal.
  - Trains have shaped noses, bogies, a livery band and a roof, and follow the track up and down ramps. Trains underground show as faint ghosts.
- Planned changes allow at least two minutes, even where a feed lists less, so plans don't depend on a sprint between platforms.
- City databases are now generated from the app's own Room schema, so the app opens them without conversion.
- Fare discounts that only some riders get (student concessions, multi-trip passes) or that could not be verified are no longer applied.
- City data versions only change when the data itself changes, so data updates are not re-downloaded needlessly.

### Fixed
- Some London stations were named after a neighbouring bus station.
- Line directions are correct when OpenStreetMap lists a branch first (Delhi Green Line showed the branch as the main route).
