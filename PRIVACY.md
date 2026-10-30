# Privacy

MetroPulse has no accounts, no ads, no analytics and no crash reporting. It never sends your
location, journeys or favourites anywhere. This page lists everything the app stores and every
server it talks to.

_Last updated: 30 October 2026 (live arrivals added)._

## What stays on your phone

| Data | Why | Where |
|---|---|---|
| Your location | To follow your train during a trip, and to list stations near you when you search or pick a station | Used in memory only. It is not saved and never sent anywhere. |
| Favourite stations and journeys, recent journeys | Shown in Plan, Search and the widgets | The app's private database |
| Settings (city, theme, commute stations, wake alert) | To remember your choices | The app's private storage |
| City network data and downloaded maps | So the app works offline | The app's private storage |

Android's own backup may copy the app's private storage to your Google account if you have
device backup turned on. MetroPulse does not run that backup and cannot read it.

To delete everything, clear the app's storage in Android settings or uninstall it.

## Servers the app contacts

These requests carry only what is needed to download the file. Like any download, the server
sees your IP address and the app's user agent (`MetroPulse/<version>`).

| Server | What for | When |
|---|---|---|
| `tiles.openfreemap.org` ([OpenFreeMap](https://openfreemap.org)) | Map tiles, map icons and label fonts. The server can tell which map areas are being viewed. | While you look at the map, and when you download a city's map for offline use |
| `api.github.com` | Checks whether a new version of the app is out | When the app opens and about once a day, unless you turn off automatic checks in Settings |
| `github.com` and its download servers | Downloads a new version of the app | Only when you choose to update |
| `raw.githubusercontent.com` | Checks for and downloads newer city data (stations, timetables, fares) | About once a week, unless you turn off data updates in Settings |
| `api.tfl.gov.uk` ([TfL open data](https://tfl.gov.uk/info-for/open-data-users/)) | Live arrival times in London. TfL can tell which stations or lines are being looked at. | Only while a London station or line is on screen, every 30 seconds, unless you turn off live arrivals in Settings |
| `api-endpoint.mta.info` (the MTA) | Live arrival times in New York. The MTA can tell which lines are being looked at. | Only while a New York station or line is on screen, every 30 seconds, unless you turn off live arrivals in Settings |

Nothing else is contacted. Timetables, routes and fares are worked out on your phone.

## Permissions

| Permission | Why | If you say no |
|---|---|---|
| Location (precise or approximate) | Following your train; nearby stations | Trips follow the timetable, labelled *Scheduled*; you pick stations by name |
| Notifications | Trip progress on the lock screen; the wake alert; update notices | No trip notification or wake alarm notification |
| Full-screen alerts | The wake alert over the lock screen | The wake alert shows as a normal notification |
| Alarms and reminders (exact alarms) | A backup alarm so the wake alert still rings if Android stops the app | The backup alarm may ring a little late |
| Install apps | Installing updates you have downloaded | Updates have to be installed by hand from GitHub |
| Foreground service, network, vibration | Running a trip in the background, downloads, the wake alert's vibration | Granted automatically by Android |

## Third-party data

Station, line and timetable data comes from open sources, credited in the app under
*Settings › Licences and data sources* and in [DATA_SOURCES.md](DATA_SOURCES.md). These are
files bundled with the app or downloaded from GitHub; no third party learns how you use them.

## Questions

Open an issue at <https://github.com/Arnav-Dugad/metropulse/issues>.
