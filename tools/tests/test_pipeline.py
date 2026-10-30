"""Unit tests for the data pipeline. Run: python -I -m unittest discover -s tools/tests"""
from __future__ import annotations

import io
import sqlite3
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from metropipe.compile import write_database  # noqa: E402
from metropipe.geo import decode_polyline, encode_polyline, haversine_m, project  # noqa: E402
from metropipe.gtfs import import_gtfs, parse_time  # noqa: E402
from metropipe.model import CityData, Line, Pattern, Station  # noqa: E402
from metropipe.osm import OsmIndex, StationResolver, _route_shape, display_name, group_complexes, track_kind  # noqa: E402
from metropipe.timetable import build_line_timetable, run_profile  # noqa: E402
from metropipe.validate import validate  # noqa: E402
from build_data import curated_source  # noqa: E402

# Three stations ~1.1 km apart along a north-south line.
FEED = {
    "agency.txt": "agency_id,agency_name,agency_url,agency_timezone\nM,Test Metro,https://example.invalid,Asia/Kolkata\n",
    "routes.txt": "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\nR1,M,Blue,North - South,1,0055AA\n",
    "stops.txt": (
        "stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station,zone_id,wheelchair_boarding\n"
        "A,Alpha,12.9000,77.6000,1,,ZA,\nA1,Alpha,12.9000,77.6000,0,A,ZA,\n"
        "B,Bravo,12.9100,77.6000,1,,ZB,\nB1,Bravo,12.9100,77.6000,0,B,ZB,\n"
        "C,Charlie,12.9200,77.6000,1,,ZC,\nC1,Charlie,12.9200,77.6000,0,C,ZC,\n"
        "A_E1,Alpha - Gate 1,12.9001,77.6002,2,A,,1\n"
    ),
    "calendar.txt": "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20260101,20261231\n",
    "calendar_dates.txt": "service_id,date,exception_type\nWK,20260126,2\n",
    "trips.txt": "route_id,service_id,trip_id,direction_id,trip_headsign\nR1,WK,T1,0,Charlie\nR1,WK,T2,0,Charlie\nR1,WK,T3,1,Alpha\n",
    "stop_times.txt": (
        "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
        "T1,06:00:00,06:00:00,A1,1\nT1,06:02:00,06:02:30,B1,2\nT1,06:04:30,06:04:30,C1,3\n"
        "T2,25:10:00,25:10:00,A1,1\nT2,25:12:00,25:12:30,B1,2\nT2,25:14:30,25:14:30,C1,3\n"
        "T3,07:00:00,07:00:00,C1,1\nT3,07:02:00,07:02:30,B1,2\nT3,07:04:30,07:04:30,A1,3\n"
    ),
    "fare_attributes.txt": "fare_id,price,currency_type,payment_method,transfers\nF10,10,INR,1,\nF20,20,INR,1,\n",
    "fare_rules.txt": "fare_id,origin_id,destination_id\nF10,ZA,ZB\nF20,ZA,ZC\n",
}


def make_feed(path: Path, overrides: dict[str, str] | None = None) -> Path:
    with zipfile.ZipFile(path, "w") as zf:
        for name, text in {**FEED, **(overrides or {})}.items():
            zf.writestr(name, text)
    return path


class GtfsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.city = CityData("test", "Test", "Asia/Kolkata")
        import_gtfs(make_feed(self.tmp / "feed.zip"), self.city, "scheduled")

    def test_times_beyond_midnight(self):
        self.assertEqual(parse_time("25:10:00"), 90600)
        with self.assertRaises(ValueError):
            parse_time("6am")

    def test_lines_stations_and_entrances(self):
        line = self.city.lines["blue"]
        self.assertEqual((line.color, line.mode, line.operator), (0x0055AA, "metro", "Test Metro"))
        self.assertEqual(set(self.city.stations), {"A", "B", "C"})  # platforms roll up into stations
        exit_ = self.city.exits[0]
        self.assertEqual((exit_.station_id, exit_.name, exit_.wheelchair), ("A", "Gate 1", True))

    def test_trips_share_patterns_and_profiles(self):
        self.assertEqual(len(self.city.patterns), 2)  # one per direction
        self.assertEqual(len(self.city.profiles), 2)  # T1 and T2 share a run-time profile
        northbound = next(p for p in self.city.profiles if p.pattern_id == 1)
        self.assertEqual(northbound.arrivals, [0, 120, 270])
        self.assertEqual(northbound.departures, [0, 150, 270])
        self.assertEqual(sorted(t.start_secs for t in self.city.trips if t.pattern_id == 1), [21600, 90600])

    def test_stop_distances_follow_the_line(self):
        dists = self.city.patterns[0].dists
        self.assertAlmostEqual(dists[0], 0, delta=1)
        self.assertAlmostEqual(dists[2], haversine_m((12.90, 77.60), (12.92, 77.60)), delta=2)

    def test_calendar_and_fares(self):
        svc = self.city.services["WK"]
        self.assertEqual(svc.days, 0b0011111)
        self.assertEqual(svc.exceptions, {20260126: False})
        self.assertEqual(self.city.fare_od[("A", "C")], 2000)

    def test_frequency_based_trips_are_expanded(self):
        city = CityData("f", "F", "Asia/Kolkata")
        import_gtfs(make_feed(self.tmp / "freq.zip", {
            "frequencies.txt": "trip_id,start_time,end_time,headway_secs\nT1,06:00:00,07:00:00,600\n",
        }), city, "scheduled")
        starts = sorted(t.start_secs for t in city.trips if t.pattern_id == 1 and t.start_secs < 86400)
        self.assertEqual(starts, [21600 + 600 * i for i in range(6)])

    def test_missing_required_file_is_reported(self):
        bad = self.tmp / "bad.zip"
        with zipfile.ZipFile(bad, "w") as zf:
            zf.writestr("agency.txt", FEED["agency.txt"])
        with self.assertRaises(ValueError):
            import_gtfs(bad, CityData("x", "X", "Asia/Kolkata"), "scheduled")

    def test_compiled_database_round_trip(self):
        out = self.tmp / "test.db"
        write_database(self.city, out, {"quality": "scheduled"})
        db = sqlite3.connect(out)
        self.addCleanup(db.close)
        self.assertEqual(db.execute("select count(*) from trip").fetchone()[0], 3)
        # Room opens the file only with its identity hash and matching user_version.
        from metropipe.compile import ROOM_SCHEMA, SCHEMA_VERSION
        self.assertEqual(db.execute("select identity_hash from room_master_table").fetchone()[0], ROOM_SCHEMA["identityHash"])
        self.assertEqual(db.execute("pragma user_version").fetchone()[0], SCHEMA_VERSION)
        self.assertEqual(db.execute("select complex_id from station where id = 'B'").fetchone()[0], "B")
        shape = db.execute("select shape from pattern where id = 1").fetchone()[0]
        self.assertAlmostEqual(decode_polyline(shape)[-1][0], 12.92, places=5)


class GeoTest(unittest.TestCase):
    def test_polyline_round_trip(self):
        pts = [(12.971599, 77.594566), (12.972, 77.6), (13.0, 77.5)]
        self.assertEqual(decode_polyline(encode_polyline(pts)), pts)

    def test_projection(self):
        line = [(0.0, 0.0), (0.0, 0.01)]
        along, offset, _ = project(line, (0.0005, 0.005))
        self.assertAlmostEqual(along, haversine_m((0, 0), (0, 0.005)), delta=1)
        self.assertAlmostEqual(offset, haversine_m((0, 0), (0.0005, 0)), delta=1)

    def test_route_shape_ignores_member_order_and_small_gaps(self):
        # Ways listed out of order, one reversed, with a 20 m mapping gap between the last two.
        ways = [[(0.0, 0.002), (0.0, 0.003)], [(0.0, 0.0), (0.0, 0.001)], [(0.0, 0.002), (0.0, 0.001)],
                [(0.0, 0.00318), (0.0, 0.004)]]
        stops = [(0.0, 0.0), (0.0, 0.002), (0.0, 0.004)]
        shape, fallbacks = _route_shape(ways, stops)
        self.assertEqual(fallbacks, 0)
        self.assertEqual(shape[0], (0.0, 0.0))
        self.assertEqual(shape[-1], (0.0, 0.004))
        self.assertTrue(all(a[1] < b[1] for a, b in zip(shape, shape[1:])))  # strictly eastwards

    def test_route_shape_falls_back_to_straight_legs(self):
        shape, fallbacks = _route_shape([], [(0.0, 0.0), (0.0, 0.01)])
        self.assertEqual((shape, fallbacks), ([(0.0, 0.0), (0.0, 0.01)], 1))

    def test_track_kind(self):
        self.assertEqual(track_kind({"bridge": "viaduct", "layer": "1"}), "elevated")
        self.assertEqual(track_kind({"tunnel": "yes", "layer": "-2"}), "underground")
        self.assertEqual(track_kind({"location": "underground"}), "underground")
        self.assertEqual(track_kind({}), "at_grade")

    def test_relation_name_match_is_whole_token(self):
        index = OsmIndex({"elements": [
            {"type": "relation", "id": 1, "tags": {"name": "Mumbai Metro Line 1"}},
            {"type": "relation", "id": 11, "tags": {"name": "Mumbai Metro Line 11"}},
        ]})
        self.assertEqual([r["id"] for r in index.relations_for({"name_contains": "Line 1"})], [1])

    def test_non_stop_member_does_not_become_a_station(self):
        city = CityData("t", "T", "Asia/Kolkata")
        index = OsmIndex({"elements": [
            {"type": "node", "id": 5, "lat": 19.0, "lon": 72.8, "tags": {"amenity": "hospital", "name": "Clinic"}},
        ]})
        member = {"type": "node", "ref": 5, "role": "platform", "lat": 19.0, "lon": 72.8}
        self.assertIsNone(StationResolver(index, city).resolve(member))
        self.assertEqual(city.stations, {})

    def test_branch_found_first_does_not_flip_the_main_line(self):
        from metropipe.osm import _assign_directions
        city = CityData("t", "T", "Asia/Kolkata")
        # Main line M0..M4 running north; a branch B0 -> M1 joins it from the west.
        for i in range(5):
            city.stations[f"M{i}"] = Station(f"M{i}", f"M{i}", 28.0 + i * 0.01, 77.0)
        city.stations["B0"] = Station("B0", "B0", 28.0, 76.99)
        main = [f"M{i}" for i in range(5)]
        shape = [city.stations[s].point for s in main]
        branch = Pattern(0, "G", 0, "", ["B0", "M1"], [city.stations["B0"].point, city.stations["M1"].point])
        patterns = [branch, Pattern(0, "G", 0, "", main, shape), Pattern(0, "G", 0, "", main[::-1], shape[::-1]),
                    Pattern(0, "G", 0, "", ["M1", "B0"], branch.shape[::-1])]
        _assign_directions(patterns, city)
        self.assertEqual([p.direction for p in patterns], [0, 0, 1, 1])

    def test_display_name_strips_line_qualifiers(self):
        self.assertEqual(display_name("Kirti Nagar (Blue Line)"), "Kirti Nagar")
        self.assertEqual(display_name("Dahisar (East) [Line 7]"), "Dahisar (East)")
        self.assertEqual(display_name("District Court Metro Station"), "District Court")
        self.assertEqual(display_name("Andheri L1"), "Andheri")
        self.assertEqual(display_name("Paddington (Circle and Hammersmith & City lines)"), "Paddington")
        self.assertEqual(display_name("Woolwich Arsenal DLR"), "Woolwich Arsenal")
        self.assertEqual(display_name("Mahatma Gandhi Bus Station"), "Mahatma Gandhi Bus Station")
        self.assertEqual(display_name("Sector 18"), "Sector 18")

    def test_duplicates_merge_and_interchanges_group(self):
        city = CityData("t", "T", "Asia/Kolkata")
        index = OsmIndex({"elements": [
            {"type": "node", "id": 1, "lat": 28.0, "lon": 77.0, "tags": {"railway": "stop", "name": "Rajiv Chowk"}},
            {"type": "node", "id": 2, "lat": 28.0003, "lon": 77.0, "tags": {"railway": "stop", "name": "Rajiv Chowk Metro"}},
            {"type": "node", "id": 3, "lat": 28.002, "lon": 77.0, "tags": {"railway": "stop", "name": "Rajiv Chowk (Blue Line)"}},
        ]})
        resolver = StationResolver(index, city)
        ids = [resolver.resolve({"type": "node", "ref": i, "role": "stop", "lat": 28.0 + d, "lon": 77.0})
               for i, d in ((1, 0.0), (2, 0.0003), (3, 0.002))]
        self.assertEqual(ids[0], ids[1])  # 33 m apart: one station
        self.assertNotEqual(ids[0], ids[2])  # 220 m apart: separate platforms of one interchange
        city.patterns = [Pattern(1, "a", 0, "", [ids[0], "x"], []), Pattern(2, "b", 0, "", [ids[2], "y"], [])]
        city.stations["x"] = Station("x", "Elsewhere", 28.1, 77.0)
        city.stations["y"] = Station("y", "Faraway", 28.2, 77.0)
        group_complexes(city)
        self.assertEqual(city.stations[ids[0]].complex_id, city.stations[ids[2]].complex_id)
        self.assertEqual(city.stations[ids[2]].name, "Rajiv Chowk")
        self.assertIsNone(city.stations["x"].complex_id)


class TimetableTest(unittest.TestCase):
    def city(self) -> CityData:
        c = CityData("t", "T", "Asia/Kolkata")
        for i in range(5):
            c.stations[f"S{i}"] = Station(f"S{i}", f"Stop {i}", 12.9 + i * 0.01, 77.6)
        shape = [c.stations[f"S{i}"].point for i in range(5)]
        p = Pattern(1, "L", 0, "Stop 4", [f"S{i}" for i in range(5)], shape)
        p.dists = [project(shape, c.stations[s].point)[0] for s in p.station_ids]
        c.patterns.append(p)
        return c

    def test_profile_matches_commercial_speed(self):
        c = self.city()
        arrivals, departures = run_profile(c.patterns[0], set(), avg_kmh=36)
        total_m = c.patterns[0].dists[-1]
        self.assertAlmostEqual(arrivals[-1], total_m / 10, delta=3)  # 36 km/h = 10 m/s including dwell
        self.assertTrue(all(d - a == 30 for a, d in zip(arrivals[1:-1], departures[1:-1])))

    def test_headway_bands_generate_trips(self):
        c = self.city()
        cfg = {"timetable": {
            "weekday": {"first": "06:00", "last": "07:00", "headways": [
                {"from": "06:00", "to": "06:30", "minutes": 10}, {"from": "06:30", "to": "07:00", "minutes": 5}]},
            "saturday": "same_as_weekday",
            "sunday": {"first": "08:00", "last": "08:30", "headways": [{"from": "08:00", "to": "08:30", "minutes": 15}]},
        }}
        build_line_timetable(c, "L", cfg, 20260101, 20261231)
        weekday = sorted(t.start_secs // 60 for t in c.trips if t.service_id == "L-weekday")
        self.assertEqual(weekday, [360, 370, 380, 390, 395, 400, 405, 410, 415, 420])
        self.assertEqual(c.services["L-weekday"].days, 0b0111111)  # Mon-Sat share the weekday timetable
        self.assertEqual(len([t for t in c.trips if t.service_id == "L-sunday"]), 3)

    def test_partly_published_headways_invent_nothing(self):
        c = self.city()
        cfg = {"timetable": {"weekday": {"first": "05:00", "last": "23:00", "headways": [
            {"from": "05:00", "to": "07:00", "minutes": None},
            {"from": "07:00", "to": "09:00", "minutes": 4},
            {"from": "09:00", "to": "23:00", "minutes": None}]}}}
        build_line_timetable(c, "L", cfg, 20260101, 20261231)
        self.assertEqual(c.trips, [])
        self.assertIn("weekday", c.unknown_timetables["L"])
        self.assertEqual(c.line_hours[("L", "weekday")], (5 * 3600, 23 * 3600))

    def test_weighted_branches_follow_shares_and_night_service(self):
        c = self.city()
        trunk = c.patterns[0]
        c.stations["D"] = Station("D", "Spur", 0.0, 0.02)
        spur = Pattern(len(c.patterns) + 1, "L", trunk.direction, "Spur", trunk.station_ids[:2] + ["D"], trunk.shape)
        spur.dists = [0.0, 1000.0, 2000.0]
        c.patterns.append(spur)
        cfg = {"branch_mode": "weighted", "night_after": "25:00", "branches": [
            {"from": "Stop 0", "to": "Stop 4", "share": 0.75, "night": True},
            {"from": "Stop 0", "to": "Spur", "share": 0.25, "night": False}],
            "timetable": {"weekday": {"first": "23:00", "last": "26:50", "headways": [{"from": "23:00", "to": "26:50", "minutes": 10}]}}}
        build_line_timetable(c, "L", cfg, 20260101, 20261231)
        day = [t for t in c.trips if t.service_id == "L-weekday" and t.start_secs < 25 * 3600]
        night = [t for t in c.trips if t.service_id == "L-weekday" and t.start_secs >= 25 * 3600]
        self.assertEqual(sum(t.pattern_id == spur.id for t in day), 3)  # a quarter of 12 day departures
        self.assertTrue(all(t.pattern_id == trunk.id for t in night))  # no night trains to the spur

    def test_friday_can_differ_from_the_weekday(self):
        c = self.city()
        cfg = {"timetable": {
            "weekday": {"first": "06:00", "last": "06:20", "headways": [{"from": "06:00", "to": "06:20", "minutes": 10}]},
            "friday": {"first": "14:00", "last": "14:10", "headways": [{"from": "14:00", "to": "14:10", "minutes": 10}]},
            "saturday": "same_as_weekday",
            "sunday": "same_as_weekday",
        }}
        build_line_timetable(c, "L", cfg, 20260101, 20261231)
        self.assertEqual(c.services["L-weekday"].days, 0b1101111)  # every day but Friday
        self.assertEqual(c.services["L-friday"].days, 0b0010000)
        self.assertEqual(c.line_hours[("L", "friday")], (14 * 3600, 14 * 3600 + 600))

    def test_alternating_branches_share_the_trunk_headway(self):
        c = self.city()
        trunk = c.patterns[0]
        c.stations["D"] = Station("D", "D", 0.0, 0.02)
        branch = Pattern(len(c.patterns) + 1, "L", trunk.direction, "D", trunk.station_ids[:2] + ["D"], trunk.shape)
        branch.dists = [0.0, 1000.0, 2000.0]
        c.patterns.append(branch)
        cfg = {"branch_mode": "alternate", "timetable": {
            "weekday": {"first": "06:00", "last": "06:30", "headways": [{"from": "06:00", "to": "06:30", "minutes": 10}]}}}
        build_line_timetable(c, "L", cfg, 20260101, 20261231)
        same_way = [t for t in c.trips if c.patterns[t.pattern_id - 1].direction == trunk.direction]
        self.assertEqual(sorted(t.start_secs for t in same_way), [21600, 22200, 22800, 23400])  # one train per headway
        self.assertEqual({t.pattern_id for t in same_way}, {trunk.id, branch.id})  # shared by both branches

    def test_unpublished_days_are_recorded_not_invented(self):
        c = self.city()
        c.lines["L"] = Line("L", "L", "", 0, 0, "metro", "", "approximate")
        cfg = {"timetable": {
            "weekday": {"first": "06:00", "last": "06:20", "headways": [{"from": "06:00", "to": "06:20", "minutes": 10}]},
            "saturday": "no_service",
            "sunday": {"first": "08:00", "last": "22:00", "headways": []},
        }}
        build_line_timetable(c, "L", cfg, 20260101, 20261231)
        self.assertEqual({t.service_id for t in c.trips}, {"L-weekday"})
        self.assertEqual(c.unknown_timetables, {"L": ["sunday"]})
        self.assertNotIn("L-saturday", c.services)
        self.assertEqual(c.line_hours[("L", "sunday")], (8 * 3600, 22 * 3600))  # hours still kept

    def test_null_day_means_unknown_not_closed(self):
        c = self.city()
        cfg = {"timetable": {"weekday": {"first": "06:00", "last": "23:00", "headways": None},
                             "saturday": None, "sunday": None}}
        build_line_timetable(c, "L", cfg, 20260101, 20261231)
        self.assertEqual(c.trips, [])
        self.assertEqual(sorted(c.unknown_timetables["L"]), ["friday", "saturday", "sunday", "weekday"])
        self.assertEqual(set(c.line_hours), {("L", "weekday"), ("L", "friday")})

    def test_per_line_fares(self):
        from metropipe.timetable import apply_fare_table
        c = self.city()
        apply_fare_table(c, {"type": "per_line", "discounts": [{"media": "smart_card", "percent": 10}], "per_line": [
            {"lines": ["A", "B"], "type": "distance_slab", "slabs": [{"max_km": 3, "fare": 10}, {"max_km": None, "fare": 20}]},
            {"lines": ["C"], "type": "distance_slab", "slabs": [{"max_km": 2, "fare": 15}]},
        ]})
        self.assertEqual(sorted((f.line_id, f.upper, f.fare_paise) for f in c.fare_rules),
                         [("A", 3.0, 1000), ("A", 1e9, 2000), ("B", 3.0, 1000), ("B", 1e9, 2000), ("C", 2.0, 1500)])
        self.assertEqual(c.fare_discounts[("smart_card", "all", "all", "")][0], 10.0)

    def test_only_general_discounts_are_kept(self):
        from metropipe.timetable import apply_fare_table
        c = self.city()
        apply_fare_table(c, {"discounts": [
            {"media": "smart_card", "percent": 10, "timeframe": "peak", "days": "mon_sat"},
            {"media": "smart_card", "percent": 5, "days": "sunday", "lines": ["A", "B"]},
            {"media": "smart_card", "percent": 30, "eligibility": "students"},
            {"media": "qr_ticket", "percent": 20, "verified": False},
            {"media": "smart_card", "percent": 0},
            {"media": "group_ticket", "percent": 10},
        ], "peak_windows": [{"days": "mon_sat", "from": "08:00", "to": "12:00"}]})
        self.assertEqual(sorted(c.fare_discounts), [
            ("smart_card", "all", "holiday", "A"), ("smart_card", "all", "holiday", "B"), ("smart_card", "peak", "weekday", ""),
        ])
        self.assertEqual(c.fare_peak_windows, [(63, 8 * 3600, 12 * 3600)])

    def test_validation_catches_misplaced_stop(self):
        c = self.city()
        c.lines["L"] = Line("L", "L", "", 0, 0, "metro", "", "approximate")
        c.stations["S2"].lon += 0.01  # ~1 km off the track
        report = validate(c)
        self.assertTrue(any("from the line's track" in e for e in report.errors))


class SourceCreditTest(unittest.TestCase):
    def test_sources_are_credited_by_who_published_them(self):
        self.assertEqual("Operator publications", curated_source("https://english.bmrc.co.in/fare-rules").attribution)
        self.assertEqual("Operator publications", curated_source("https://lucknow.upmetrorail.com/en/timings").attribution)
        self.assertEqual("News reports", curated_source("https://www.siasat.com/namma-metro-may-get-costlier-again-3512363/").attribution)
        self.assertEqual("Wikipedia", curated_source("https://en.wikipedia.org/wiki/Patna_Metro").attribution)
        self.assertIsNone(curated_source("https://overpass-api.de/api/interpreter"))


if __name__ == "__main__":
    unittest.main()
