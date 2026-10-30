"""Writes CityData to the compact SQLite file the app reads.

The table layout comes from Room's exported schema of the app's CityDatabase
(core/data/schemas/...CityDatabase/<version>.json), so the files open in Room unchanged: the
exact CREATE statements, Room's identity hash in room_master_table, and PRAGMA user_version.
"""
from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from .geo import encode_polyline, path_length_m
from .model import CityData

ROOM_SCHEMA_DIR = (Path(__file__).resolve().parents[2] / "core" / "data" / "schemas"
                   / "io.github.arnavdugad.metropulse.core.data.city.db.CityDatabase")


def _room_schema() -> dict:
    files = sorted(ROOM_SCHEMA_DIR.glob("*.json"), key=lambda p: int(p.stem))
    if not files:
        raise FileNotFoundError(f"No Room schema in {ROOM_SCHEMA_DIR}; build the app once (gradlew :core:data:kspDebugKotlin)")
    return json.loads(files[-1].read_text(encoding="utf-8"))["database"]


ROOM_SCHEMA = _room_schema()
SCHEMA_VERSION: int = ROOM_SCHEMA["version"]


def schema_statements() -> list[str]:
    out = []
    for entity in ROOM_SCHEMA["entities"]:
        table = entity["tableName"]
        out.append(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices") or []:
            out.append(index["createSql"].replace("${TABLE_NAME}", table))
    return out + ROOM_SCHEMA["setupQueries"]


def _insert(db: sqlite3.Connection, table: str, columns: tuple[str, ...], rows) -> None:
    sql = f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({', '.join('?' * len(columns))})"
    db.executemany(sql, rows)


def write_database(city: CityData, out: Path, meta: dict[str, str]) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    db = sqlite3.connect(tmp)
    try:
        for statement in schema_statements():
            db.execute(statement)
        base_meta = {
            "schema_version": str(SCHEMA_VERSION),
            "city_id": city.id,
            "city_name": city.name,
            "timezone": city.timezone,
            "notes": "\n".join(city.notes),
            "unknown_timetables": json.dumps(city.unknown_timetables, sort_keys=True),
            "station_codes": json.dumps(city.station_codes, sort_keys=True),
            "route_codes": json.dumps(city.route_codes, sort_keys=True),
        }
        _insert(db, "meta", ("key", "value"), sorted({**base_meta, **meta}.items()))
        _insert(db, "line", ("id", "name", "long_name", "color", "text_color", "mode", "operator", "quality", "sort_order"),
                [(l.id, l.name, l.long_name, l.color, l.text_color, l.mode, l.operator, l.quality, l.sort_order)
                 for l in city.lines.values()])
        _insert(db, "line_hours", ("line_id", "day_type", "first_secs", "last_secs"),
                [(line, day, first, last) for (line, day), (first, last) in sorted(city.line_hours.items())])
        used = {s for p in city.patterns for s in p.station_ids}
        _insert(db, "station", ("id", "name", "lat", "lon", "local_names", "osm_id", "complex_id"),
                [(s.id, s.name, round(s.lat, 6), round(s.lon, 6), json.dumps(s.local_names, ensure_ascii=False, sort_keys=True),
                  s.osm_id, s.complex_id or s.id)
                 for s in city.stations.values() if s.id in used])
        _insert(db, "pattern", ("id", "line_id", "direction", "headsign", "shape", "length_m"),
                [(p.id, p.line_id, p.direction, p.headsign, encode_polyline(p.shape), round(path_length_m(p.shape), 1))
                 for p in city.patterns])
        _insert(db, "pattern_stop", ("pattern_id", "seq", "station_id", "dist_m"),
                [(p.id, i, s, round(d, 1)) for p in city.patterns for i, (s, d) in enumerate(zip(p.station_ids, p.dists))])
        _insert(db, "profile", ("id", "pattern_id", "arrivals", "departures"),
                [(pr.id, pr.pattern_id, ",".join(map(str, pr.arrivals)), ",".join(map(str, pr.departures))) for pr in city.profiles])
        _insert(db, "service", ("id", "days", "start_date", "end_date"),
                [(s.id, s.days, s.start_date, s.end_date) for s in city.services.values()])
        _insert(db, "service_exception", ("service_id", "date", "added"),
                [(s.id, d, int(added)) for s in city.services.values() for d, added in sorted(s.exceptions.items())])
        trips = sorted(city.trips, key=lambda t: (t.pattern_id, t.service_id, t.start_secs))
        _insert(db, "trip", ("id", "pattern_id", "profile_id", "service_id", "start_secs"),
                [(i + 1, t.pattern_id, t.profile_id, t.service_id, t.start_secs) for i, t in enumerate(trips)])
        _insert(db, "transfer", ("from_station", "to_station", "walk_secs"),
                [(a, b, secs) for (a, b), secs in sorted(city.transfers.items()) if a in used and b in used])
        _insert(db, "track", ("id", "kind", "line_ids", "geometry"),
                [(i + 1, t.kind, ",".join(sorted(t.line_ids)), encode_polyline(t.coords)) for i, t in enumerate(city.tracks)])
        exits = [e for e in city.exits if e.station_id in used]
        _insert(db, "station_exit", ("id", "station_id", "name", "lat", "lon", "wheelchair"),
                [(i + 1, e.station_id, e.name, round(e.lat, 6), round(e.lon, 6), None if e.wheelchair is None else int(e.wheelchair))
                 for i, e in enumerate(exits)])
        _insert(db, "fare_od", ("from_station", "to_station", "fare_paise"),
                [(a, b, paise) for (a, b), paise in sorted(city.fare_od.items()) if a in used and b in used])
        rules = sorted(city.fare_rules, key=lambda f: (f.line_id, f.day_type, f.upper))
        _insert(db, "fare_rule", ("id", "kind", "upper", "fare_paise", "day_type", "line_id"),
                [(i + 1, f.kind, f.upper, f.fare_paise, f.day_type, f.line_id) for i, f in enumerate(rules)])
        _insert(db, "fare_discount", ("id", "media", "timeframe", "day_type", "line_id", "percent", "note"),
                [(i + 1, media, tf, day, line, pct, note)
                 for i, ((media, tf, day, line), (pct, note)) in enumerate(sorted(city.fare_discounts.items()))])
        _insert(db, "fare_peak_window", ("id", "days", "start_secs", "end_secs"),
                [(i + 1, days, start, end) for i, (days, start, end) in enumerate(sorted(city.fare_peak_windows))])
        _insert(db, "source", ("id", "what", "url", "licence", "attribution"),
                [(i + 1, s.what, s.url, s.licence, s.attribution) for i, s in enumerate(city.sources)])
        db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
        db.commit()
        db.execute("VACUUM")
    finally:
        db.close()
    out.unlink(missing_ok=True)
    tmp.rename(out)
