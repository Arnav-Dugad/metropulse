# Contributing

Thanks for helping. MetroPulse has two rules that shape every change:

1. **Never show invented data.** Every time is labelled *Live*, *Scheduled* or *Approx.*; when an
   operator publishes nothing, the app says "times unavailable" rather than guessing.
2. **No paid services and no backend.** Everything runs on the phone, from bundled or freely
   published data. No analytics, ads or tracking.

## Building

- Android Studio (or its bundled JDK 21) and the Android SDK with platform 37.
- `./gradlew :app:assembleDebug` builds a debug APK; `./gradlew test` runs the unit tests.
- Debug builds never offer app updates. To test the updater locally, see the comment in
  `app/build.gradle.kts` (`-Pmetropulse.debugUpdateFeedUrl`).

The code is split into `:core:domain` (pure Kotlin: routing, fares, trip tracking), `:core:data`
(Room, DataStore, updaters), `:core:designsystem`, `:core:map` (MapLibre) and `:app`.

## City data

City databases are built by the Python pipeline in `tools/` from GTFS feeds, OpenStreetMap and the
curated files in `tools/cities/<city>.yaml`:

```sh
python -m pip install -r tools/requirements.txt
python -I -X utf8 tools/build_data.py build bengaluru   # one city; omit for all
python -I -X utf8 tools/build_data.py docs              # regenerate DATA_SOURCES.md
cd tools && python -m unittest discover -s tests        # pipeline tests
```

Every value in a city file needs a source in `source_urls`, and anything not published goes in
`unknowns`, never a guess. Corrections to OpenStreetMap are best made in OpenStreetMap itself.

## Pull requests

- Keep changes focused and describe what you checked (tests, a screenshot for UI changes).
- Match the surrounding code style; the project uses the official Kotlin style.
- By contributing you agree that your code is released under the MIT licence.

## Reporting problems

Use the issue templates. For wrong times or stations, say the city, line, station and what you
saw, with a link to the operator's source if you have one.
