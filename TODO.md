# MetroPulse TODO

The full plan is in phases 1–9. English UI only for now; localization is deferred.

## Phase 1: Foundation, design system, auto-update ✅
- [x] Multi-module Gradle project, Hilt, R8
- [x] Design system (colours, Inter, motion, glass, buttons, progress, counters, chips, icons, haptics)
- [x] Adaptive icon and animated splash
- [x] Navigation shell and Settings
- [x] GitHub Releases auto-update, verified end to end on the API 36 emulator:
  - [x] launch check, sheet, download, SHA-256 and signature verification, permission flow, install, relaunch on the new version
  - [x] tampered digest rejected; APK signed with a different key rejected
  - [x] background download, "ready" notification, sheet, install
- [ ] Re-check the updater on the API 28 emulator (`un28`): legacy signature APIs and the confirmation flow
- [x] After v1.0.0 ships, verify the real GitHub path (done: a 0.9.0 build updated itself to v1.0.0)

## Phase 2: Data pipeline (`/tools`) ✅
- [x] `build_data.py build [cities] [--refresh]` and `docs`: fetch, validate, compile, manifest (23 pipeline tests)
- [x] All 21 cities build and validate (`app/src/main/assets/cities/*.db`, about 4.8 MB on disk, about 1 MB compressed in the APK)
- [x] Bengaluru from Vonter/bmrcl-gtfs (ODbL), labelled Scheduled (estimated)
- [x] Hyderabad importer for the HMRL GTFS, falling back to OSM until the ZIP is supplied
- [x] Delhi from OSM plus DMRC published timings. The OTD GTFS is **not used**: its terms forbid modification, and it dates from Aug 2023
- [x] Interchange platforms grouped by `station.complex_id`; duplicate OSM stations merged
- [x] DATA_SOURCES.md generated, `data/manifest.json` with `timetable` coverage (full / partial / none)
- [x] `BundledCitiesTest` checks every bundled database against the manifest (size, SHA-256, schema)
- [x] Hyderabad: official HMRL GTFS via Open Data Telangana (OpenCity copy); fares from the city file (feed fares predate the 2025 cut)
- [ ] Bengaluru: check whether the official BMRCL feed on the DULT portal is downloadable without login; prefer it if so
- [ ] Delhi, Agra, Bhopal, Indore and Meerut have no published headways, so they show first and last trains only. Add headways if the operators publish them (never guess)
- [ ] Saturday/Sunday headways are missing for several lines (shown as times unavailable)
- [ ] Delhi Pink Line extension (Majlis Park – Maujpur-Babarpur, opened 2026-03-08) is parked in `tools/cities/pending/` until OSM maps a route relation
- [ ] Fares missing for Kochi, Pune, Nagpur, Navi Mumbai, Agra, Bhopal, Indore and Meerut. Kochi, Pune and Nagpur publish only station-to-station matrices, which would need transcribing
- [ ] Nagpur has 0 mapped entrances in OSM; Sitabuldi (interchange) is about 270 m from each line's track in OSM

## Phase 3: Domain and data layer ✅
- [x] City databases written from Room's exported schema (`core/data/schemas/…CityDatabase/1.json`): exact DDL, identity hash, `user_version`. Every bundled city opens in Room (Robolectric test)
- [x] Bundled city installed on city pick (`CityRepository.select` → `CityFiles.ensureInstalled`); network loaded into memory, reloaded after a data update
- [x] User database (favourite stations, favourite journeys, recent journeys) and the selected city in DataStore
- [x] RAPTOR (Pareto by arrival and changes, latest-departure tightening, after-midnight and next-day trains), estimated routing for lines without timetables, fare engine (OD table, distance/station slabs, per-operator tickets, media discounts by peak/day/line), fuzzy station search, map-matcher (Kalman, outlier and direction gating) and dead reckoning, all with tests
- [x] City picker, real Lines tab, city in Settings; verified on the API 36 emulator (Bengaluru, Delhi; light and dark)
- [x] Discounts curated: only general smart-card/QR/NCMC discounts are modelled; student concessions, passes and unverified offers are kept in YAML but skipped
- [x] Pattern directions fixed when a branch is mapped first (Delhi Green Line); city data versions now stable across rebuilds
- [ ] Untimed journeys estimate ride time at 33 km/h on every line, so Delhi's Airport Express looks no faster than other lines. Use line-specific speeds where operators publish them
- [ ] Delhi Airport Express has its own fares in reality; the network-wide slabs are applied to it
- [ ] Holidays other than Sundays are not modelled for holiday fares and discounts
- [x] Wire the planner, search, fares and favourites into the Plan tab and station screens (Phase 6)
- [ ] Re-check on API 28 (`un28`)

## Phase 4: 3D map ✅
- [x] `:core:map` module on MapLibre Android 13.7.1 (OpenGL, texture mode so the glass overlays blur the map)
- [x] Bundled "Pulse Light" / "Pulse Dark" styles generated from OpenFreeMap Positron by `tools/map_styles.py`: English labels, app palettes, 3D buildings (`render_height`, rising with zoom); licences in `licenses/MAP-STYLES.md`
- [x] Metro layers: official line colours; elevated stretches as 3D viaducts (deck, coloured stripe, pillars); underground dashed; stretches classified per pattern from OSM track kinds (`TrackProfile`)
- [x] Station pins (interchanges ringed) with zoom-faded labels; tap a station for a glass card with its lines
- [x] Scheduled train positions as 3D cars that bend through curves and ride on viaducts; dots at city zoom; ~15 updates/s, 1/s with reduced motion; a pill says "Scheduled / Approx. · Trains shown by timetable", or that no timetable is published
- [x] Camera: pitched overview that frames the city, flies on city change, keeps its place across tab switches; attribution always visible
- [x] Verified on the API 36 emulator (Delhi, Bengaluru; light and dark)
- [x] APK size: MapLibre's native libraries for four ABIs push the debug APK to ~68 MB. Use `abiFilters` or ABI splits in Phase 8 (release is ARM-only with compressed libraries: 14.4 MB)
- [x] Follow mode (camera tracks the rider's train with look-ahead) comes with on-board mode (Phase 5)
- [x] Credit OpenFreeMap and the Positron style designers on the About screen (Phase 7)
- [ ] The overview leaves empty space above and below wide networks (Delhi); consider a tighter fit or a lower tilt
## Phase 5: On-board mode ✅
- [x] `TripTracker` (pure Kotlin, 8 tests): waiting, boarding detected from movement, riding, changes, arrival; live GPS vs schedule (tunnels, underground platforms); wrong-way and missed-stop warnings; wake alert N stops (or 90 s) before the destination
- [x] Location foreground service on the platform `LocationManager` only (fused provider where Android has one, else GPS). No Google Play services, so it works on de-Googled phones and suits F-Droid. Adaptive sampling (1 s near stops, 3 s cruising)
- [x] Wake alert: alarm-sound notification with a full-screen card over the lock screen, plus an exact-alarm backup (inexact if exact alarms are not allowed)
- [x] Trip notification via `NotificationCompat.ProgressStyle`: line-coloured segments, change points, train tracker, "N min" status chip, promoted on Android 16 builds that support Live Updates; plain progress bar elsewhere
- [x] On board button → trip sheet (nearest station, fuzzy destination search, route preview, wake 1/2 stops before) → violet trip card with countdown, stops, Live/Scheduled/Approx. chip; map follows the rider's train
- [x] Verified on the API 36 emulator with GPS traces along the Purple Line: boarding, live tracking, a tunnel (location off) carried by the schedule, recovery to Live, alert one stop before (heads-up when unlocked; full-screen card waking a sleeping screen), arrival and automatic end
- [ ] The API 36.0 emulator image predates promoted Live Updates (`setRequestPromotedOngoing` arrived in a later 16.x); check the status bar chip on an Android 16 QPR image or a real phone
- [ ] Fully automatic trips without choosing a destination (detect the line and direction from movement alone)
- [ ] Android groups the wake alert with other notifications after it has alerted (shown as "silent" afterwards); harmless, but keep an eye on it in future Android versions
- [ ] Re-check on API 28 (`un28`): no `ProgressStyle`, exact alarms always allowed, no notification permission
## Phase 6: Screens and 3D structures ✅
- [x] **3D track:** an elevation profile per pattern ramps smoothly between viaduct, ground and tunnel (330 m ramps, 5 m steps). Viaducts get a deck, parapets and T-piers every 34 m. Ramps down to ground run on an embankment. Descents into tunnels run through a walled cutting to a portal box. Tunnels are a translucent dashed ghost
- [x] **3D trains:** cars with tapered noses, bogies, a livery band, a pale body and a roof. Each car follows the track height and tilts through ramps. Trains in tunnels show as translucent ghosts. Full detail only in the viewport at zoom 13+, dots elsewhere
- [x] **Plan tab:** from/to pickers (search plus nearby stations), swap, leave now/in 15–60 min/at a time, fastest and fewest-change options with times, changes, fare and card price, *Scheduled/Approx.* labels. Favourite journeys and recents start a plan with one tap
- [x] **Journey screen:** a summary card, a timeline with line-coloured rails, changes (including at stops both lines share), walks and expandable intermediate stops, a fare breakdown by ticket and card, *Start trip* into on-board mode, favourite toggle
- [x] **Station screen:** lines, next three departures per line and direction (short trips labelled "Ends at …"), first and last train, exits with step-free tags, plan from/to, show on map, favourite
- [x] **Line screen:** strip map with scheduled trains moving along it, both directions, operator, hours
- [x] **Search:** typo-tolerant, with nearby (by GPS) and favourite stations before typing
- [x] Map: the *Where to?* bar opens search; the station card links to details. Lines open their strip map
- [x] Shared-element transitions (line card → line screen, journey card → journey screen, station card → station screen), and the tab bar slides away on detail screens
- [x] Designed empty and error states: nothing planned, no route, no matches, station/line/journey gone, service ended
- [x] Planning never allows less than a 2 min change at a stop, even when a feed publishes less (Bengaluru's modelled feed says 60 s at Majestic)
- [x] Verified on the API 36 emulator (Bengaluru; light and dark)
- [ ] Departures per platform: feeds give one stop per station, so platforms are not known yet
- [x] Facilities beyond exits (lifts, toilets, parking) from OSM
- [ ] Shared-element transitions from map pins (the pin is drawn by MapLibre, not Compose)
## Phase 7: Widgets, shortcuts, QS tile, onboarding, settings, accessibility ✅
- [x] Onboarding (4 pages): welcome, city, location (explained, optional), notifications and lock-screen alerts; shown once, skippable permissions, animated emblems that hold still with reduced motion
- [x] Settings: commute (home/work stations), wake alert default, theme (system/light/dark), high contrast, reduce motion (also follows Android's "remove animations"), weekly city data updates with a manual check, licences
- [x] About screen: the app's licence, the current city's data sources (grouped by publisher), map credits (OSM, OpenFreeMap, OpenMapTiles, Positron, MapLibre, Noto) and fonts/libraries
- [x] "Start my commute" Quick Settings tile: home → work before noon, work → home after; shows the destination or the running trip; Android 13+ offers it from Settings with the system prompt
- [x] Widgets (Glance): *Next trains* from favourite stations (tap the name for the next favourite; clock times that stay right between refreshes; short trips faded; refreshes when its first train leaves, without waking the phone) and *My trip* (destination, stops, arrival, Live/Scheduled, progress; or one tap to start)
- [x] Launcher shortcuts: Search, On board, plus up to two favourite or recent journeys (updated with the city)
- [x] Accessibility: every control named for TalkBack (checked with a uiautomator audit of all tabs and detail screens); checked at 2× font size; buttons grow with text; tab labels and the app title cap their growth so they never break mid-word
- [x] Data credits: city sources are now credited by publisher (operator, news report, Wikipedia); the Overpass endpoint is no longer listed as an operator publication
- [x] Verified on the API 36 emulator
- [ ] Translations and a language switcher: English only for now, by choice
- [ ] Units setting: not needed while every city is metric
- [ ] Widget previews in the picker (Android 15 generated previews) show the app icon for now
- [ ] The map itself is not readable by TalkBack (MapLibre draws to a texture); stations are reachable through search, lines and plans
## International cities (added 2026-10-30) ✅
- [x] London: 11 Underground lines + 5 DLR services; first/last trains and headways from TfL's Unified API timetables and working timetables; Night Tube on Friday/Saturday nights; trains shared among branches by measured shares (Northern via Bank/Charing Cross, Mill Hill East…), night trains only on night branches
- [x] New York: the MTA's official static GTFS (29 routes, 496 stations, scheduled times), flat $3.00 fare, interchanges from the feed's transfers (Times Sq-42 St is one station; the six 86 St stations stay apart)
- [x] Dubai (Red with Expo branch, Green): RTA first/last trains by day; Riyadh (6 lines): published hours with Friday's late start, flat SAR 4; Doha (Red, Gold, Green): flat QR 2, no published times
- [x] Pipeline: `friday` day type (Gulf weekends, Night Tube), `branch_mode: weighted` with per-branch shares and night service, exact `gtfs_route`/`gtfs_route_id` matching, duplicate GTFS short names, currency and country per city, English headsigns for Arabic-only OSM tags, exact terminus matching, 32-hour service days
- [x] A day with only some headways published (peak only) now counts as "times unavailable" instead of filling the gaps from the nearest band (this also corrected Ahmedabad's Violet line)
- [x] App: city picker grouped by country, fares in each city's currency, Friday timetables, black lines (Northern) drawn grey on dark surfaces, "times unavailable" label for cities with no published times
- [x] The MTA and DMRC have given the maintainer permission to convert their feeds (2026-10-30)
- [x] Delhi live bus positions (OTD VehiclePositions, key from the `OTD_DELHI_KEY` secret) near metro stations; verified on the API 36 emulator at Rajiv Chowk
- [ ] Delhi bus route names: needs the **current** OTD static `routes.txt` in `tools/input/delhi/otd/`, then `tools/build_data.py delhi-buses`. Until then buses show their registration
  - 2026-10-30: the portal's static download fails; its file server (traffickarma.iiitd.edu.in:9010) times out
  - Older copies must not be used: MobilityDatabase mdb-1262/mdb-3139 (2022–24 data) and the NIUA CKAN copy (2020) number routes differently. Checked against live positions, buses were a median 10 km from their supposed route's stops, no better than a random route
- [ ] Delhi buses: each refresh downloads the whole feed (~600 KB); consider a Wi-Fi-only option or a slower refresh on mobile data
- [ ] Delhi: the OTD portal's DMRC download fails after its request form; once the files are in `tools/input/delhi/`, merge the 2023 feed with the newer OpenStreetMap sections (see that folder's README)
- [ ] The MTA feed starts on 1 Nov 2026 and runs to 15 May 2027: rebuild New York (`build --refresh new-york`) before then
- [x] London, Dubai: zonal fares (peak/off-peak) from `tools/cities/fares/`
- [ ] Dubai: no published off-peak/weekend headways; Doha and Riyadh publish none. Dubai's RTA GTFS (Dubai Pulse) needs a data request
- [ ] Dubai: OSM's Expo-branch relations skip Jumeirah Golf Estates
- [ ] London: the Woodford–Hainault shuttle takes a share of the Central line's Bank headway (central frequency ~11% low); weekend District Olympia shuttle under-represented; Kennington terminators counted as Battersea; "London Waterloo" for the Waterloo & City platform
- [ ] Elizabeth line, Overground, Dubai Tram, Lusail Tram, and other Middle East systems (Istanbul, Tehran, Cairo…) could be added the same way

## Live data (added 2026-10-30) ✅
- [x] Researched free realtime sources: only TfL (London) is free, keyless and allowed to be called directly by apps; the MTA's terms forbid apps fetching its feeds from MTA servers; no Indian or Gulf metro publishes a public realtime feed (Delhi OTD realtime is buses only, with private keys; Dubai's API needs a secret)
- [x] London: live arrivals on station screens and live trains on line strip maps from TfL's Unified API, "Powered by TfL Open Data" shown, OS/Geomni notices in About; Settings › Live arrivals (on by default); stations matched by TfL StopPoint ids from OSM NaPTAN codes (multi-valued tags, platform codes) and TfL's StopPoint search at build time, with a name fallback in the app
- [x] New York: live arrivals and live trains on line maps from the MTA's GTFS-realtime feeds (own decoder, no protobuf runtime); enabled with the MTA's permission (`metropulse.mtaRealtime=true`)
- [x] Fixed: stations named after a neighbouring bus station ("Canada Water Bus Station"); Night Tube hours now read "From 5:50 am, through the night"
- [ ] Live trains on the 3D map (TfL has no position feed; positions would be estimated from predictions for every line at once, about 1 MB per refresh)
- [ ] TfL: 50 requests a minute per phone without a key; a shared app key would pool all users into one 500/min quota, so none is used

## Phase 8: Quality pass ✅
- [x] Edge cases: city data that fails to load shows "Try again" / "Choose another city" (it was blank on the map tab); on-board without location permission or with location off says the trip follows the timetable, with a link to fix it; the map shows an offline banner; cities with a timetable but no trains running now no longer claim to have no timetable
- [x] Offline maps: Settings › Offline map downloads the city through MapLibre's offline database, at most 3,000 tiles per city (full zoom-14 detail for every current city), 4 parallel requests to OpenFreeMap; progress, cancel and delete; verified in airplane mode
- [x] New York's 261 patterns crashed the map (out of memory building 3D structures): structures are now built once per stretch of track, however many lines share it
- [x] Baseline profile (`:baselineprofile`, generated on the emulator: welcome tour, map, search, a station, Plan, Lines); release cold start 2.0 s on the emulator under ARM translation
- [x] Jank (release build, API 36 emulator, ARM translation): map pan 50th/95th percentile 16/18 ms, 0% legacy-janky; NYC Lines list 18/23 ms, 1.3% janky
- [x] APK size: release 14.4 MB (ARM only, compressed native libraries, all 26 cities); debug 40 MB
- [x] PRIVACY.md: every stored item, every server contacted, every permission and what happens without it
- [ ] The city picker sheet scrolls less smoothly over the live 3D map (map keeps animating trains behind it): pause train animation while a sheet covers the map
- [x] Release-test the updater end to end once the GitHub repo exists (Phase 9)
## Phase 9: GitHub release ✅
- [x] README (banner, screenshots, features, cities table, live data, install with certificate fingerprint, build, privacy, credits), MIT LICENSE (city data under ODbL and sources' terms), CONTRIBUTING, issue templates
- [x] Release keystore outside the repo (`C:\Users\rduga\.metropulse-keys\`), passwords next to it; GitHub secrets SIGNING_KEYSTORE_B64 / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD
- [x] CI workflow (tests + debug build on every push) and release workflow (tests, signed APK named by the tag, `.sha256` companion, generated notes)
- [x] Published: https://github.com/Arnav-Dugad/metropulse, release v1.0.0 (signed APK 13.7 MB + .sha256; GitHub digest matches; certificate CB:B3:18:B2…F1:59)
- [x] Real updater path verified on the emulator: a release-signed 0.9.0 found v1.0.0 on GitHub, downloaded it, verified SHA-256 and signature, walked through the "install unknown apps" permission, installed, and kept the rider's city and settings
- [ ] **You:** back up `C:\Users\rduga\.metropulse-keys\` (the keystore and its passwords). Without it, installed apps cannot be updated

## Known notes
- Android asks for confirmation on every self-update of a sideloaded app on the API 36 emulator, even with `USER_ACTION_NOT_REQUIRED`. This is expected because the app is not the installer of record.
- The debug APK is about 15.7 MB, mostly unshrunk dex (Compose); the city databases add about 1 MB. Review in Phase 8.
- During Phase 2 research, one research agent read DMRC/NMRC/UPMRC pages through the Chrome browser extension. Nothing was submitted or signed into.

## Logos, live alerts and polish (2026-10-30) ✅
- [x] Operator logos and line symbols from Wikimedia Commons (licenses/LOGOS.md), credited in the app with a not-affiliated notice
- [x] Live trains on the 3D map (London, New York) with glide smoothing; alerts from TfL and the MTA on map, lines, stations, journeys
- [x] Live check in the planner, platform numbers, sharing, leave-by reminders, weekday commute reminder, commute suggestion
- [x] Step-free routing (post-filter on OSM wheelchair tags), facilities from OSM, options comparison, trip history, usage-ranked search
- [x] Widgets: responsive sizes, lock screen category, line symbols, per-direction favourites, mini line strip
- [x] Weekly data rebuild Action (PR) with calendar-expiry check; freshness line in the city picker
- [ ] Noida has no operator logo on Commons that is free to use; Dubai's RTA logo is a PNG (no SVG on Commons)
- [ ] Best-carriage advice: no open data exists (TfL's station data covers ~55 platforms only)
- [ ] Step-free data is OSM's: "limited" is treated as unknown, and many Indian stations are untagged
- [ ] Platform numbers only where the operator's live feed has them (London); timetables have one stop per station
- [ ] Map pin → station shared-element transition: the pin is drawn by MapLibre, so the sheet's expanding card stands in
- [ ] Turn on "Allow GitHub Actions to create and approve pull requests" for the weekly data workflow

