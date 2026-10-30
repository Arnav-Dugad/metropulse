"""Imports a GTFS feed (zip) into CityData."""
from __future__ import annotations

import csv
import io
import re
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

from .geo import haversine_m, project
from .model import (
    FRI, MON, SAT, SUN, THU, TUE, WED,
    CityData, Exit, Line, Pattern, Service, Station, Trip,
)

DAY_COLUMNS = [("monday", MON), ("tuesday", TUE), ("wednesday", WED), ("thursday", THU),
               ("friday", FRI), ("saturday", SAT), ("sunday", SUN)]
MODES = {"0": "light_rail", "1": "metro", "2": "metro", "12": "monorail"}


class GtfsError(ValueError):
    pass


def read_table(zf: zipfile.ZipFile, name: str, required: bool = False) -> list[dict[str, str]]:
    if name not in zf.namelist():
        if required:
            raise GtfsError(f"GTFS is missing required file {name}")
        return []
    text = zf.read(name).decode("utf-8-sig")
    return [{k.strip(): (v or "").strip() for k, v in row.items() if k} for row in csv.DictReader(io.StringIO(text))]


def parse_time(value: str) -> int:
    """GTFS HH:MM:SS (hours may exceed 23) to seconds."""
    m = re.fullmatch(r"(\d{1,2}):(\d{2}):(\d{2})", value)
    if not m:
        raise GtfsError(f"Bad GTFS time {value!r}")
    h, mi, s = map(int, m.groups())
    return h * 3600 + mi * 60 + s


def parse_color(value: str, default: int) -> int:
    return int(value, 16) if re.fullmatch(r"[0-9A-Fa-f]{6}", value or "") else default


def slug(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")


def import_gtfs(path: Path, city: CityData, quality: str) -> None:
    with zipfile.ZipFile(path) as zf:
        _import(zf, city, quality)


def _import(zf: zipfile.ZipFile, city: CityData, quality: str) -> None:
    agencies = {a.get("agency_id", ""): a.get("agency_name", "") for a in read_table(zf, "agency.txt", required=True)}
    default_agency = next(iter(agencies.values()), "")

    # Lines
    route_line: dict[str, str] = {}
    routes = read_table(zf, "routes.txt", required=True)
    # Short names can repeat (NYC's three shuttles are all "S"); those routes are told apart by route_id.
    short_counts = Counter(r.get("route_short_name") for r in routes if r.get("route_short_name"))
    for order, r in enumerate(routes):
        short = r.get("route_short_name")
        line_id = slug(short if short and short_counts[short] == 1 else r["route_id"])
        route_line[r["route_id"]] = line_id
        city.route_codes[r["route_id"]] = line_id
        city.lines[line_id] = Line(
            id=line_id,
            name=r.get("route_short_name") or r.get("route_long_name") or r["route_id"],
            long_name=r.get("route_long_name", ""),
            color=parse_color(r.get("route_color", ""), 0x6C3BFF),
            text_color=parse_color(r.get("route_text_color", ""), 0xFFFFFF),
            mode=MODES.get(r.get("route_type", "1"), "metro"),
            operator=agencies.get(r.get("agency_id", ""), default_agency),
            quality=quality,
            sort_order=order,
        )

    # Stations: platforms (location_type 0) roll up to their parent station.
    stops = read_table(zf, "stops.txt", required=True)
    translations = defaultdict(dict)
    for t in read_table(zf, "translations.txt"):
        if t.get("table_name") == "stops" and t.get("field_name") == "stop_name":
            translations[t.get("record_id") or t.get("field_value")][t["language"]] = t["translation"]
    stop_station: dict[str, str] = {}
    for s in stops:
        lt = s.get("location_type", "") or "0"
        if lt == "1" or (lt == "0" and not s.get("parent_station")):
            city.stations[s["stop_id"]] = Station(
                id=s["stop_id"], name=s["stop_name"], lat=float(s["stop_lat"]), lon=float(s["stop_lon"]),
                local_names=translations.get(s["stop_id"]) or translations.get(s["stop_name"]) or {},
            )
    for s in stops:
        lt = s.get("location_type", "") or "0"
        parent = s.get("parent_station") or s["stop_id"]
        if lt == "0":
            stop_station[s["stop_id"]] = parent
        elif lt == "2" and parent in city.stations:
            name = s["stop_name"]
            station_name = city.stations[parent].name
            # "Attiguppe – Entrance A" -> "Entrance A"
            if name.startswith(station_name):
                name = name[len(station_name):].lstrip(" -–—:").strip() or name
            wb = s.get("wheelchair_boarding")
            city.exits.append(Exit(parent, name, float(s["stop_lat"]), float(s["stop_lon"]),
                                   wheelchair=True if wb == "1" else False if wb == "2" else None))

    # Shapes
    shapes: dict[str, list[tuple[int, float, float]]] = defaultdict(list)
    for p in read_table(zf, "shapes.txt"):
        shapes[p["shape_id"]].append((int(p["shape_pt_sequence"]), float(p["shape_pt_lat"]), float(p["shape_pt_lon"])))
    shape_points = {k: [(lat, lon) for _, lat, lon in sorted(v)] for k, v in shapes.items()}

    # Services
    for c in read_table(zf, "calendar.txt"):
        days = sum(bit for col, bit in DAY_COLUMNS if c.get(col) == "1")
        city.services[c["service_id"]] = Service(c["service_id"], days, int(c["start_date"]), int(c["end_date"]))
    for d in read_table(zf, "calendar_dates.txt"):
        svc = city.services.setdefault(d["service_id"], Service(d["service_id"], 0, int(d["date"]), int(d["date"])))
        svc.exceptions[int(d["date"])] = d["exception_type"] == "1"
        svc.start_date, svc.end_date = min(svc.start_date, int(d["date"])), max(svc.end_date, int(d["date"]))

    # Trips -> patterns + profiles
    trips = {t["trip_id"]: t for t in read_table(zf, "trips.txt", required=True)}
    stop_times: dict[str, list[dict[str, str]]] = defaultdict(list)
    for st in read_table(zf, "stop_times.txt", required=True):
        stop_times[st["trip_id"]].append(st)
    frequencies = defaultdict(list)
    for f in read_table(zf, "frequencies.txt"):
        frequencies[f["trip_id"]].append(f)

    pattern_ids: dict[tuple, int] = {}
    for trip_id, rows in stop_times.items():
        trip = trips.get(trip_id)
        if trip is None or trip["route_id"] not in route_line:
            continue
        rows.sort(key=lambda r: int(r["stop_sequence"]))
        stations, arrivals, departures = [], [], []
        for r in rows:
            station = stop_station.get(r["stop_id"])
            if station is None:
                raise GtfsError(f"stop_times references unknown stop {r['stop_id']}")
            arr = parse_time(r["arrival_time"] or r["departure_time"])
            dep = parse_time(r["departure_time"] or r["arrival_time"])
            if stations and stations[-1] == station:  # same station twice in a row (platform change)
                departures[-1] = dep
                continue
            stations.append(station)
            arrivals.append(arr)
            departures.append(dep)
        if len(stations) < 2:
            continue
        line_id = route_line[trip["route_id"]]
        direction = int(trip.get("direction_id") or 0)
        key = (line_id, direction, tuple(stations), trip.get("shape_id", ""))
        pattern_id = pattern_ids.get(key)
        if pattern_id is None:
            pattern_id = len(city.patterns) + 1
            pattern_ids[key] = pattern_id
            shape = shape_points.get(trip.get("shape_id", "")) or [city.stations[s].point for s in stations]
            pattern = Pattern(pattern_id, line_id, direction,
                              trip.get("trip_headsign") or city.stations[stations[-1]].name, list(stations), shape)
            pattern.dists = stop_distances(pattern, city)
            city.patterns.append(pattern)
        start = departures[0]
        profile_id = city.add_profile(pattern_id, [a - start for a in arrivals], [d - start for d in departures])
        if frequencies.get(trip_id):
            for f in frequencies[trip_id]:
                t, end, step = parse_time(f["start_time"]), parse_time(f["end_time"]), int(f["headway_secs"])
                while t < end:
                    city.trips.append(Trip(pattern_id, profile_id, trip["service_id"], t))
                    t += step
        else:
            city.trips.append(Trip(pattern_id, profile_id, trip["service_id"], start))

    # Transfers between stations (or within one, for platform changes)
    for t in read_table(zf, "transfers.txt"):
        a, b = stop_station.get(t["from_stop_id"], t["from_stop_id"]), stop_station.get(t["to_stop_id"], t["to_stop_id"])
        if a in city.stations and b in city.stations and t.get("transfer_type") != "3":
            secs = int(t.get("min_transfer_time") or 0) or 120
            city.transfers[(a, b)] = max(city.transfers.get((a, b), 0), secs)

    _import_fares(zf, city, stop_station)


def stop_distances(pattern: Pattern, city: CityData) -> list[float]:
    """Distance of each stop along the shape, searching forward so loops and U-turns resolve correctly."""
    dists, index = [], 0
    for sid in pattern.station_ids:
        along, _, index = project(pattern.shape, city.stations[sid].point, index)
        dists.append(along)
    return dists


def _import_fares(zf: zipfile.ZipFile, city: CityData, stop_station: dict[str, str]) -> None:
    prices = {f["fare_id"]: round(float(f["price"]) * 100) for f in read_table(zf, "fare_attributes.txt")}
    # zone_id -> station (BMRCL uses one zone per station)
    zone_station: dict[str, str] = {}
    for s in read_table(zf, "stops.txt"):
        if s.get("zone_id"):
            zone_station.setdefault(s["zone_id"], stop_station.get(s["stop_id"], s.get("parent_station") or s["stop_id"]))
    for r in read_table(zf, "fare_rules.txt"):
        a, b = zone_station.get(r.get("origin_id", "")), zone_station.get(r.get("destination_id", ""))
        if a and b and r["fare_id"] in prices:
            city.fare_od[(a, b)] = prices[r["fare_id"]]

    # Fares v2: derive media discounts relative to the token price of the same fare band.
    products = read_table(zf, "fare_products.txt")
    media_names = {m["fare_media_id"]: m.get("fare_media_name", "") for m in read_table(zf, "fare_media.txt")}
    by_band: dict[str, dict[str, float]] = defaultdict(dict)
    for p in products:
        m = re.fullmatch(r"fare_(\d+)_(\w+?)(?:_(peak|offpeak))?", p["fare_product_id"])
        if m:
            band, media, timeframe = m.groups()
            by_band[band][(media, timeframe or "all")] = float(p["amount"])
    for band in by_band.values():
        base = band.get(("token", "all"))
        if not base:
            continue
        for (media, timeframe), amount in band.items():
            if media == "token":
                continue
            percent = round((1 - amount / base) * 100, 2)
            if percent <= 0:
                continue
            key = ("smart_card" if media == "smartcard" else "qr_ticket" if media == "qr" else media, timeframe, "all", "")
            if key not in city.fare_discounts:
                city.fare_discounts[key] = (percent, media_names.get(media, media))
    windows = defaultdict(list)
    for t in read_table(zf, "timeframes.txt"):
        if t.get("timeframe_group_id") == "peak":
            svc = city.services.get(t.get("service_id", ""))
            windows[(parse_time(t["start_time"]), parse_time(t["end_time"]))].append(svc.days if svc else 0)
    for (start, end), masks in windows.items():
        days = 0
        for m in masks:
            days |= m
        city.fare_peak_windows.append((days, start, end))
