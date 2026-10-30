# Hyderabad Metro official GTFS

HMRL emails the download link for its GTFS feed after you fill in a request form
(see the open-data section of the HMRL / Telangana Open Data portals).

1. Download the ZIP from the emailed link.
2. Save it here, exactly as: `tools/input/hyderabad/hmrl_gtfs.zip`
3. Rebuild Hyderabad:

   ```
   python -I -X utf8 tools/build_data.py build hyderabad
   python -I -X utf8 tools/build_data.py docs
   ```

With the file present, Hyderabad switches from the approximate OpenStreetMap timetable to HMRL's
official schedule ("Scheduled" in the app). The ZIP itself is git-ignored; only the compiled
database is committed. HMRL publishes the data under the Open Government Licence – India,
which allows redistribution with attribution.
