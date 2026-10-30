# Delhi: DMRC GTFS

MetroPulse has DMRC's permission to convert its GTFS feed. The files are published on the Delhi
Open Transit Data portal, <https://otd.delhi.gov.in/data/staticDMRC/>, behind a request form
(name, email, usage). In October 2026 the download failed after submitting the form.

When it works, put the files here (they are not committed):

- `stops.txt`, `routes.txt`, `trips.txt`, `stop_times.txt` (the portal offers no `calendar.txt`)

The feed was last updated on 10 August 2023 and the portal describes its times as approximate, so:

- lines and stations opened since then (for example the Magenta Line extension and the Pink Line
  Majlis Park – Maujpur section) are not in it and must still come from OpenStreetMap;
- its trips need a calendar: without `calendar.txt`, the service days have to be inferred from
  `service_id` values;
- the times stay labelled *Approx.* in the app.

Merging the feed with the newer OpenStreetMap sections is listed in TODO.md.
