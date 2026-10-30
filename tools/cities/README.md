# City definition files

Each `<city-id>.yaml` holds the hand-curated facts the pipeline cannot get from GTFS or
OpenStreetMap: which lines are operating, their official colours, operating hours,
headways and fares. **Every value must come from a cited source.** Anything not found is
`null` and listed under `unknowns`. Never guess silently.

Timetables built from these files are always labelled "approximate" in the app, because
they model trains from published first/last times and headways rather than real trip
schedules.

```yaml
id: mumbai                      # lower-case slug, also the database file name
name: Mumbai
state: Maharashtra
timezone: Asia/Kolkata
bbox: [18.89, 72.77, 19.30, 73.10]   # south, west, north, east; covers all operating lines
checked: 2026-10-29             # date the sources were read

lines:
  - id: mum-l1                  # city-unique slug
    name: Line 1                # short display name ("Purple Line", "Line 1", "Aqua Line")
    long_name: Versova – Ghatkopar
    operator: Mumbai Metro One Pvt Ltd (MMOPL)
    colour: "#00A0E3"           # official colour if the operator publishes one
    colour_source: https://...   # URL, or "operator network map" with its URL
    mode: metro                 # metro | light_rail | monorail
    termini: [Versova, Ghatkopar]
    opened: 2014-06-08          # first section opened (only operating lines belong here)
    osm:                        # hints to find the OSM route relation(s)
      ref: "1"                  # value of the relation's ref tag if known, else null
      name_contains: "Line 1"   # substring of the relation name
    timetable:
      weekday:
        first: "05:30"          # first departure from a terminus, local time
        last: "23:50"           # last departure from a terminus
        headways:               # minutes between trains; cover first..last without gaps
          - {from: "05:30", to: "08:00", minutes: 8}
          - {from: "08:00", to: "11:00", minutes: 4}
      saturday: same_as_weekday  # or a block like weekday
      sunday: {first: "06:30", last: "23:00", headways: [{from: "06:30", to: "23:00", minutes: 10}]}
    avg_speed_kmh: 33           # commercial speed incl. dwell, if published, else null
    source_urls: [https://...]
    notes: >-
      Peak hours are not published; peak windows 08:00–11:00 and 17:00–20:00 are an assumption.

fares:
  type: distance_slab           # distance_slab | station_count_slab | per_line | null
  currency: INR
  slabs:                        # upper bound inclusive, fare in rupees
    - {max_km: 3, fare: 10}
    - {max_km: 8, fare: 20}
  discounts:
    - {media: smart_card, percent: 10, note: "Off all journeys"}
    - {media: qr_ticket, percent: 5, note: ""}
  notes: ""
  source_urls: [https://...]

sources: [https://...]          # every URL used anywhere in this file
unknowns: [fares.discounts]     # fields left null because no source was found
```

Additional forms the pipeline accepts:
- `minutes` may be decimal (`6.75` = 6 min 45 s).
- A day may be `no_service` (no trains that day) or `same_as_weekday`.
- A day block with first/last times but no `headways` means *not published*. The app shows
  "times unavailable" for that line and day; no trains are invented.
- A fare slab with `max_km: null` is open-ended.
- `fares.applies_to_lines: [line-id, ...]` limits slabs to journeys within those lines.
- Discounts are modelled only when any rider gets them by paying with `smart_card`, `qr_ticket` or
  `ncmc`. Optional fields: `timeframe: peak | offpeak` (needs `fares.peak_windows`),
  `days: mon_sat | sunday` (Sunday stands in for holidays), `lines: [line-id, ...]`.
  Mark group concessions and passes with `eligibility: students | concession | pass`, and offers
  you could not confirm with `verified: false`; both are kept for the record but not used.
- `fares.peak_windows: [{days: mon_sat | mon_fri | daily | sunday, from: "08:00", to: "12:00"}]`.
- `fares.type: per_line` with `per_line: [{lines: [...], type: distance_slab, slabs: [...]}, ...]`
  covers cities where each operator runs its own fare system (e.g. Mumbai).

Rules for timetable data:
- Use the operator's own pages, press releases, or official social posts first. Reputable
  news is acceptable when it quotes the operator. Note the date of any news source.
- If only "peak X min / off-peak Y min" is published, use 08:00–11:00 and 17:00–20:00 as
  peak windows and say so in `notes`.
- A line or extension that is not open to passengers on the `checked` date is left out.
