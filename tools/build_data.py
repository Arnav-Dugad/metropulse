"""
MetroPulse data pipeline.

    python -I -X utf8 tools/build_data.py build              # every city in tools/cities/
    python -I -X utf8 tools/build_data.py build delhi pune   # selected cities
    python -I -X utf8 tools/build_data.py build --refresh    # ignore cached downloads
    python -I -X utf8 tools/build_data.py docs               # regenerate DATA_SOURCES.md
    python -I -X utf8 tools/build_data.py delhi-buses        # Delhi bus route names for live buses

Downloads go to tools/cache/ (never committed). Databases are written to
app/src/main/assets/cities/<id>.db and listed in data/manifest.json, which the app checks
for newer data.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import io
import json
import re
import sys
import urllib.parse
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))  # -I drops the script directory from sys.path; add only our own package

import requests  # noqa: E402
import yaml  # noqa: E402

from metropipe import osm  # noqa: E402
from metropipe.compile import SCHEMA_VERSION, write_database  # noqa: E402
from metropipe.gtfs import import_gtfs, slug  # noqa: E402
from metropipe.model import QUALITY_APPROXIMATE, CityData, Line, Source  # noqa: E402
from metropipe.timetable import apply_fare_table, build_line_timetable  # noqa: E402
from metropipe.validate import validate  # noqa: E402

ROOT = TOOLS.parent
CITIES_DIR = TOOLS / "cities"
CACHE = TOOLS / "cache"
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "cities"
MANIFEST = ROOT / "data" / "manifest.json"
RAW_BASE = "https://raw.githubusercontent.com/Arnav-Dugad/metropulse/main/app/src/main/assets/cities"
OSM_SOURCE = Source(
    "Line geometry, stations, entrances and track structure",
    "https://www.openstreetmap.org/copyright",
    "ODbL-1.0",
    "© OpenStreetMap contributors",
)


def load_city_configs(only: list[str] | None) -> list[dict]:
    configs = []
    for f in sorted(CITIES_DIR.glob("*.yaml")):
        cfg = yaml.safe_load(f.read_text(encoding="utf-8"))
        if only and cfg["id"] not in only:
            continue
        configs.append(cfg)
    missing = set(only or []) - {c["id"] for c in configs}
    if missing:
        raise SystemExit(f"No city file for: {', '.join(sorted(missing))}")
    return configs


UNOFFICIAL_LINE_COLOUR = 0x5B6B7F  # neutral slate for lines whose operator publishes no colour


def colour(value: str | None, fallback: int = UNOFFICIAL_LINE_COLOUR) -> int:
    if value and isinstance(value, str) and value.startswith("#") and len(value) == 7:
        return int(value[1:], 16)
    return fallback


def text_colour(rgb: int) -> int:
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    return 0x111111 if (0.299 * r + 0.587 * g + 0.114 * b) > 170 else 0xFFFFFF


def fetch_gtfs(city_id: str, gtfs: dict, refresh: bool) -> Path | None:
    if gtfs.get("local"):
        path = TOOLS / gtfs["local"]
        return path if path.exists() else None
    path = CACHE / city_id / "feed.zip"
    if refresh or not path.exists():
        print(f"  downloading {gtfs['url']}")
        r = requests.get(gtfs["url"], timeout=120, headers={"User-Agent": osm.USER_AGENT})
        r.raise_for_status()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(r.content)
    return path


def match_yaml_line(gtfs_line: Line, cfg_lines: list[dict]) -> dict | None:
    # Explicit GTFS ids win; NYC's routes are single letters that a substring test would confuse, and
    # its three shuttles share the short name "S" (their lines are keyed by route_id).
    for line in cfg_lines:
        if line.get("gtfs_route_id") is not None and slug(str(line["gtfs_route_id"])) == gtfs_line.id:
            return line
    for line in cfg_lines:
        if line.get("gtfs_route") is not None and str(line["gtfs_route"]) in (gtfs_line.name, gtfs_line.id):
            return line
    if any(line.get("gtfs_route") is not None or line.get("gtfs_route_id") is not None for line in cfg_lines):
        return None
    key = gtfs_line.name.lower().replace(" line", "").strip()
    for line in cfg_lines:
        if key and key in line["name"].lower():
            return line
    return None


def build_city(cfg: dict, sources: dict, refresh: bool) -> tuple[CityData, list[str]]:
    city = CityData(cfg["id"], cfg["name"], cfg.get("timezone", "Asia/Kolkata"))
    feed = (sources.get(cfg["id"]) or {}).get("gtfs")
    feed_path = fetch_gtfs(cfg["id"], feed, refresh) if feed else None
    if feed and feed_path is None:
        city.notes.append(f"Official feed not supplied ({feed.get('local')}); built from OpenStreetMap instead.")
        print(f"  feed {feed.get('local')} not found; using OpenStreetMap and published timetables")

    routes = "|".join(sorted({(l.get("osm") or {}).get("route") or "subway|light_rail|monorail" for l in cfg["lines"]}))
    query_key = hashlib.sha1(f"{cfg['bbox']}|{routes}|{osm.QUERY_VERSION}".encode()).hexdigest()[:10]
    data = osm.fetch(cfg["bbox"], CACHE / cfg["id"] / f"overpass-{query_key}.json", routes=routes,
                     max_age_days=0 if refresh else 7)
    index = osm.OsmIndex(data)
    warnings: list[str] = []
    hints: dict[str, dict] = {}

    if feed_path:
        import_gtfs(feed_path, city, feed["quality"])
        for line in city.lines.values():
            match = match_yaml_line(line, cfg["lines"])
            if match is None:
                warnings.append(f"GTFS line {line.name} has no entry in {cfg['id']}.yaml (no colour override, no track)")
                continue
            if match.get("colour"):
                line.color = colour(match["colour"], line.color)
                line.text_color = text_colour(line.color)
            line.name = match["name"]
            line.long_name = match.get("long_name") or line.long_name
            hints[line.id] = match.get("osm") or {}
        city.sources.append(Source(feed["what"], feed.get("page") or feed.get("url", ""), feed["licence"], feed["attribution"]))
        if not any(s.complex_id for s in city.stations.values()):
            group_gtfs_complexes(city)
        ends = [s.end_date for s in city.services.values() if s.end_date]
        city.valid_until = max(ends) if ends else None
        if (cfg.get("fares") or {}).get("override_feed"):
            # The feed's fares are out of date: the city file's published fares replace them.
            city.fare_od.clear()
            city.fare_rules.clear()
            city.fare_discounts.clear()
            city.fare_peak_windows.clear()
        if not city.fare_od and not city.fare_rules:
            apply_fare_table(city, cfg.get("fares"))
    else:
        for order, lc in enumerate(cfg["lines"]):
            rgb = colour(lc.get("colour"))
            city.lines[lc["id"]] = Line(
                id=lc["id"], name=lc["name"], long_name=lc.get("long_name") or "",
                color=rgb, text_color=text_colour(rgb), mode=lc.get("mode") or "metro",
                operator=lc.get("operator") or "", quality=QUALITY_APPROXIMATE, sort_order=order,
            )
            hints[lc["id"]] = {**(lc.get("osm") or {}), "termini": lc.get("termini")}
            warnings += osm.build_patterns(index, city, lc["id"], hints[lc["id"]])
        osm.group_complexes(city)
        start =int(dt.date.today().strftime("%Y%m%d"))
        end = int((dt.date.today() + dt.timedelta(days=400)).strftime("%Y%m%d"))
        for lc in cfg["lines"]:
            build_line_timetable(city, lc["id"], lc, start, end)
        osm.build_walk_transfers(city)
        apply_fare_table(city, cfg.get("fares"))

    if cfg.get("tfl_codes"):
        add_tfl_codes(city)
    add_interchanges(city, cfg.get("interchanges") or [])
    zonal_file = CITIES_DIR / "fares" / f"{cfg['id']}.yaml"
    if zonal_file.exists():
        city.zonal = load_zonal(yaml.safe_load(zonal_file.read_text(encoding="utf-8")), city, warnings)
    osm.build_tracks(index, city, hints)
    if not city.exits:
        osm.build_exits(index, city)
    try:
        fkey = hashlib.sha1(f"{cfg['bbox']}|facilities|{osm.FACILITIES_VERSION}".encode()).hexdigest()[:10]
        fdata = osm.fetch(cfg["bbox"], CACHE / cfg["id"] / f"facilities-{fkey}.json", max_age_days=0 if refresh else 30,
                          query=osm.facilities_query(cfg["bbox"]), timeout=120, rounds=1)
        osm.build_facilities(fdata, city)
    except osm.OsmError as e:
        # Facilities are extras: a busy Overpass leaves them out this time rather than holding the build.
        warnings.append(f"facilities: {e}")
    city.sources.append(OSM_SOURCE)
    for url in cfg.get("sources") or []:
        source = curated_source(url)
        if source is not None:
            city.sources.append(source)
    return city, warnings


# Operators and government bodies; any other site in a city's `sources` is a news report or
# reference work and is credited as one, so the app never calls a newspaper an operator.
OPERATOR_HOSTS = {
    "bmrc.co.in", "chennaimetrorail.org", "cidco.maharashtra.gov.in", "delhimetrorail.com", "gujaratmetrorail.com",
    "hmrl.co.in", "ltmetro.com", "upmetrorail.com", "kochimetro.org", "metrorailnagpur.com", "mmmocl.co.in",
    "mtp.indianrailways.gov.in", "oldmtp.indianrailways.gov.in", "ncrtc.in", "nmrcnoida.com", "mmrcl.com",
    "punemetrorail.org", "reliancemumbaimetro.com", "transport.rajasthan.gov.in",
}
# Operator files hosted elsewhere (Kochi Metro's fare chart lives in its own S3 bucket).
OPERATOR_PATHS = ("s3-ap-south-1.amazonaws.com/kmrldata/",)
FACTS = "Facts (not copyrightable)"


def curated_source(url: str) -> Source | None:
    """How to credit one entry of a city's `sources`; None when another credit already covers it."""
    host = (urllib.parse.urlparse(url).hostname or "").removeprefix("www.")
    if host == "overpass-api.de":
        return None  # how OpenStreetMap data was fetched; OSM_SOURCE credits it
    if any(host == h or host.endswith("." + h) for h in OPERATOR_HOSTS) or any(p in url for p in OPERATOR_PATHS):
        return Source("Operator timetable, fares or line colours", url, FACTS, "Operator publications")
    if host.endswith("wikipedia.org"):
        return Source("Opening dates and line details", url, "Facts (not copyrightable); text CC BY-SA 4.0", "Wikipedia")
    return Source("Fare changes, timings or openings reported in the press", url, FACTS, "News reports")


TFL_API = "https://api.tfl.gov.uk"


def add_tfl_codes(city: CityData) -> None:
    """TfL StopPoint ids for stations whose OSM tags lack them, from TfL's own StopPoint search
    (cached in tools/cache/<city>/tfl_codes.json; requests paced under TfL's anonymous limit)."""
    import time
    cache_path = CACHE / city.id / "tfl_codes.json"
    cache = json.loads(cache_path.read_text(encoding="utf-8")) if cache_path.exists() else {}
    known = set(city.station_codes.values())
    used = {s for p in city.patterns for s in p.station_ids}

    def norm(name: str) -> str:
        name = re.sub(r"\b(underground|dlr|rail)?\s*station\b", " ", name.lower())
        return " ".join(re.sub(r"[^a-z0-9&]+", " ", name).split())

    def get(path: str):
        time.sleep(1.3)  # stay under 50 requests a minute
        r = requests.get(f"{TFL_API}/{path}", timeout=30, headers={"User-Agent": osm.USER_AGENT})
        r.raise_for_status()
        return r.json()

    def tube_child(stop: dict) -> str | None:
        if stop.get("id", "").startswith("940G"):
            return stop["id"]
        for child in stop.get("children") or []:
            found = tube_child(child)
            if found:
                return found
        return None

    for sid in sorted(used - known):
        name = city.stations[sid].name
        if name not in cache:
            code = None
            for match in get(f"StopPoint/Search/{requests.utils.quote(name)}?modes=tube,dlr").get("matches", []):
                if norm(match.get("name", "")) != norm(name):
                    continue
                code = match["id"] if match["id"].startswith("940G") else tube_child(get(f"StopPoint/{match['id']}"))
                break
            cache[name] = code
        if cache[name]:
            city.station_codes.setdefault(cache[name], sid)
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache, indent=1, sort_keys=True), encoding="utf-8")


def add_interchanges(city: CityData, pairs: list) -> None:
    """Interchanges a feed misses: [station name, station name, walk seconds] joins the two stations."""
    by_name: dict[str, list[str]] = {}
    for s in city.stations.values():
        by_name.setdefault(s.name, []).append(s.id)
    for a, b, secs in pairs:
        for x in by_name.get(a, []):
            for y in by_name.get(b, []):
                city.transfers[(x, y)] = int(secs)
                city.transfers[(y, x)] = int(secs)
                root = city.stations[x].complex_id or x
                city.stations[x].complex_id = root
                city.stations[y].complex_id = root


DAY_MASKS = {"mon_fri": 31, "mon_sat": 63, "daily": 127, "sat_sun": 96}


def load_zonal(cfg: dict, city: CityData, warnings: list[str]) -> dict:
    """tools/cities/fares/<city>.yaml -> the app's zonal fare data (station ids, minor units, seconds)."""
    def clock(t: str) -> int:
        h, m = t.split(":")
        return int(h) * 3600 + int(m) * 60

    by_name: dict[str, list[str]] = {}
    for s in city.stations.values():
        by_name.setdefault(s.name, []).append(s.id)
    zones: dict[str, list[int]] = {}
    for name, z in (cfg.get("station_zones") or {}).items():
        ids = by_name.get(name)
        if not ids:
            warnings.append(f"fares: no station named {name!r}")
            continue
        for sid in ids:
            zones[sid] = sorted(int(v) for v in z)
    used = {s for p in city.patterns for s in p.station_ids}
    missing = sorted(city.stations[s].name for s in used if s not in zones)
    if missing:
        warnings.append(f"fares: no zone for {len(missing)} stations, e.g. {missing[:3]}")
    rows = []
    for f in cfg.get("fares") or []:
        z = f["zones"]
        lo, hi = (z[0], z[1]) if isinstance(z, list) else (z, z)
        peak = f.get("peak", f.get("fare"))
        off = f.get("offpeak", f.get("fare"))
        rows.append({"lo": int(lo), "hi": int(hi), "peak": round(float(peak) * 100), "offpeak": round(float(off) * 100)})
    peak_windows = [{"days": DAY_MASKS[w["days"]], "from": clock(w["from"]), "to": clock(w["to"])} for w in cfg.get("peak") or []]
    return {"rule": cfg["rule"], "zones": zones, "fares": rows, "peak": peak_windows}


def group_gtfs_complexes(city: CityData) -> None:
    """A feed's transfers between different stations are its official interchanges (NYC: Times Sq-42 St
    is four stations); those form complexes. Feeds without such transfers fall back to same name and
    a short walk. Two stations that merely share a name (NYC has six "86 St") stay apart."""
    links = [(a, b) for (a, b) in city.transfers if a != b]
    if not links:
        osm.group_complexes(city)
        return
    parent = {s: s for s in city.stations}

    def find(s: str) -> str:
        while parent[s] != s:
            parent[s] = parent[parent[s]]
            s = parent[s]
        return s

    for a, b in links:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)
    groups: dict[str, list[str]] = {}
    for s in city.stations:
        groups.setdefault(find(s), []).append(s)
    for root, members in groups.items():
        if len(members) > 1:
            for m in members:
                city.stations[m].complex_id = root


def city_currency(cfg: dict) -> str:
    """ISO 4217 code of the city's fares (Indian cities predate the key and use rupees)."""
    return cfg.get("currency") or (cfg.get("fares") or {}).get("currency") or "INR"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summarise_quality(city: CityData) -> str:
    qualities = {l.quality for l in city.lines.values()}
    for q in ("approximate", "estimated", "scheduled"):
        if q in qualities:
            return q
    return "approximate"


def timetable_coverage(city: CityData) -> str:
    """full: departures on every line and day type; partial: some show "times unavailable";
    none: the operator publishes only first/last trains, so no departures exist."""
    if not city.trips:
        # "none": first and last trains only; "unknown": not even those are published (Doha).
        return "none" if city.line_hours else "unknown"
    return "partial" if city.unknown_timetables else "full"


def cmd_build(args) -> int:
    sources = yaml.safe_load((TOOLS / "sources.yaml").read_text(encoding="utf-8")) or {}
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {"cities": []}
    entries = {c["id"]: c for c in manifest.get("cities", [])}
    failed = []
    for cfg in load_city_configs(args.cities):
        print(f"== {cfg['name']}")
        try:
            city, warnings = build_city(cfg, sources, args.refresh)
        except Exception as e:  # report and continue with the next city
            print(f"  FAILED: {e}")
            failed.append(cfg["id"])
            continue
        report = validate(city, cfg.get("bbox"))
        for w in warnings + report.warnings:
            print(f"  warning: {w}")
        for e in report.errors:
            print(f"  ERROR: {e}")
        if not report.ok:
            failed.append(cfg["id"])
            continue

        out = ASSETS / f"{cfg['id']}.db"
        previous = entries.get(cfg["id"], {})
        meta = {"data_version": str(previous.get("version", 0)), "quality": summarise_quality(city), "currency": city_currency(cfg)}
        write_database(city, out, meta)
        digest = sha256(out)
        version = previous.get("version", 0)
        if digest != previous.get("sha256"):
            version = int(dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M"))
            write_database(city, out, {**meta, "data_version": str(version)})
            digest = sha256(out)
        lats = [s.lat for s in city.stations.values()]
        lons = [s.lon for s in city.stations.values()]
        entries[cfg["id"]] = {
            "id": cfg["id"],
            "name": cfg["name"],
            "state": cfg.get("state", ""),
            "country": cfg.get("country", "India"),
            "version": version,
            "schema_version": SCHEMA_VERSION,
            "quality": summarise_quality(city),
            "timetable": timetable_coverage(city),
            "lines": len(city.lines),
            "stations": len({s for p in city.patterns for s in p.station_ids}),
            "center": [round(sum(lats) / len(lats), 5), round(sum(lons) / len(lons), 5)],
            "bbox": cfg["bbox"],
            "size": out.stat().st_size,
            "sha256": digest,
            "url": f"{RAW_BASE}/{cfg['id']}.db",
        }
        if city.valid_until:
            entries[cfg["id"]]["valid_until"] = city.valid_until
        print(f"  ok: {len(city.lines)} lines, {entries[cfg['id']]['stations']} stations, {len(city.trips)} trips, "
              f"{len(city.tracks)} track pieces, {len(city.exits)} exits, {out.stat().st_size / 1024:.0f} KB")

    text = json.dumps({
        "schema_version": SCHEMA_VERSION,
        "generated_at": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cities": sorted(entries.values(), key=lambda c: c["name"]),
    }, indent=2, ensure_ascii=False) + "\n"
    # data/manifest.json is what installed apps poll; the bundled copy lets the app work offline.
    for path in (MANIFEST, ASSETS / "manifest.json"):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    if failed:
        print(f"\nFailed: {', '.join(failed)}")
        return 1
    return 0


QUALITY_TEXT = {
    "scheduled": "Scheduled: official timetable with per-station times",
    "estimated": "Scheduled (estimated): official departures, per-station times modelled",
    "approximate": "Approximate: modelled from published first/last trains and headways",
}


OTD_ROUTES = TOOLS / "input" / "delhi" / "otd" / "routes.txt"
BUS_ROUTES_ASSET = ROOT / "app" / "src" / "main" / "assets" / "live" / "delhi-bus-routes.json"


def bus_routes(text: str) -> dict[str, list[str]]:
    """routes.txt -> {route_id: [public name, description]}; the description is left empty when it
    only repeats the name."""
    import csv

    out: dict[str, list[str]] = {}
    for row in csv.DictReader(io.StringIO(text.lstrip("\ufeff"))):
        rid = (row.get("route_id") or "").strip()
        short = (row.get("route_short_name") or "").strip()
        long = re.sub(r"\s+", " ", (row.get("route_long_name") or "").strip())
        name = short or long
        if not rid or not name:
            continue
        out[rid] = [name, "" if long == name else long]
    return out


def cmd_delhi_buses(_args) -> int:
    """Route names for Delhi's live bus positions, from the Open Transit Data static routes.txt,
    downloaded by hand from https://otd.delhi.gov.in/data/static/ (the portal asks for a form)."""
    if not OTD_ROUTES.exists():
        print(f"Missing {OTD_ROUTES.relative_to(ROOT)}: download 'routes' from https://otd.delhi.gov.in/data/static/")
        return 1
    routes = bus_routes(OTD_ROUTES.read_text(encoding="utf-8", errors="replace"))
    BUS_ROUTES_ASSET.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "source": "Delhi Open Transit Data, Transport Department, GNCTD (otd.delhi.gov.in)",
        "updated": dt.date.today().isoformat(),
        "routes": dict(sorted(routes.items())),
    }
    BUS_ROUTES_ASSET.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"wrote {len(routes)} routes to {BUS_ROUTES_ASSET.relative_to(ROOT)}")
    return 0


def cmd_docs(_args) -> int:
    sources = yaml.safe_load((TOOLS / "sources.yaml").read_text(encoding="utf-8")) or {}
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {"cities": []}
    built = {c["id"]: c for c in manifest["cities"]}
    out = [
        "# Data sources",
        "",
        "Generated by `python -I -X utf8 tools/build_data.py docs` from `tools/cities/*.yaml` and `tools/sources.yaml`.",
        "",
        "Every time shown in MetroPulse is labelled with where it came from. Indian metros do not publish",
        "live train positions, so the only *Live* times come from your own phone's GPS while on board.",
        "",
        "## Licences",
        "",
        "- **OpenStreetMap** data (all cities: track geometry, stations, entrances) © OpenStreetMap contributors,",
        "  available under the [Open Database License](https://opendatacommons.org/licenses/odbl/1-0/).",
        "  The city databases in `app/src/main/assets/cities/` are derived databases and are likewise",
        "  offered under the ODbL. The app's source code is MIT.",
        "- Facts such as first/last train times, headways and fares come from operators' public pages and are",
        "  cited per city below.",
        "",
        "## Cities",
        "",
        "| City | Lines | Stations | Timetable quality | Feed |",
        "|---|---|---|---|---|",
    ]
    configs = load_city_configs(None)
    for cfg in configs:
        b = built.get(cfg["id"], {})
        feed = (sources.get(cfg["id"]) or {}).get("gtfs")
        feed_used = feed and b.get("quality", "approximate") != "approximate"
        if feed_used:
            feed_text = f"[{feed['licence']}]({feed.get('page') or feed.get('url')})"
        elif feed:
            feed_text = "OpenStreetMap + operator timetables (official feed not supplied yet)"
        else:
            feed_text = "OpenStreetMap + operator timetables"
        coverage = b.get("timetable")
        if coverage == "none":
            quality = "First and last trains only: no headways published, so no departure times are shown"
        else:
            quality = QUALITY_TEXT.get(b.get("quality", "approximate"), "")
            if coverage == "partial":
                quality += "; some days show times unavailable"
        out.append(f"| {cfg['name']} | {b.get('lines', len(cfg['lines']))} | {b.get('stations', '–')} | {quality} | {feed_text} |")
    out.append("")
    for cfg in configs:
        out += [f"### {cfg['name']}", ""]
        feed = (sources.get(cfg["id"]) or {}).get("gtfs")
        if feed:
            out.append(f"- **Timetable feed:** {feed['what']}. Licence: {feed['licence']}. Attribution: {feed['attribution']}.")
            if feed.get("local"):
                out.append(f"  Supplied manually at `tools/{feed['local']}`; when absent, the city is built from OpenStreetMap.")
        out.append(f"- **Checked:** {cfg.get('checked', 'unknown')}")
        for line in cfg["lines"]:
            urls = ", ".join(f"<{u}>" for u in (line.get("source_urls") or []))
            out.append(f"- **{line['name']}** ({line.get('long_name') or ''}): {urls}")
        fares = cfg.get("fares") or {}
        if fares.get("source_urls"):
            out.append("- **Fares:** " + ", ".join(f"<{u}>" for u in fares["source_urls"]))
        if cfg.get("unknowns"):
            out.append("- **Not found (left empty):** " + ", ".join(map(str, cfg["unknowns"])))
        for omitted in cfg.get("omitted") or []:
            out.append(f"- **Omitted:** {omitted}")
        out.append("")
    out += [
        "## Live data",
        "",
        "- **London:** arrivals, line status and station closures from TfL's Unified API (Powered by TfL Open Data).",
        "- **Delhi buses:** live positions of DTC and cluster buses from Delhi Open Transit Data",
        "  (Transport Department, GNCTD; <https://otd.delhi.gov.in/data/realtime/>), shown near metro stations.",
        "  Release builds carry a key for the feed; route names come from the portal's static `routes.txt`",
        "  (`tools/build_data.py delhi-buses`). The feed has no metro trains and no arrival predictions.",
        "",
        "### Not used",
        "",
        "- **DMRC GTFS (otd.delhi.gov.in).** The maintainer has DMRC's permission to convert it, but the portal's",
        "  download currently fails after the request form, and the feed was last updated in August 2023 (stations",
        "  opened since are missing). Until it can be merged with the newer sections, Delhi is built from",
        "  OpenStreetMap and DMRC's published timetables and fares. See tools/input/delhi/README.md.",
        "",
    ]
    (ROOT / "DATA_SOURCES.md").write_text("\n".join(out), encoding="utf-8")
    print("wrote DATA_SOURCES.md")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build", help="fetch, validate and compile city databases")
    b.add_argument("cities", nargs="*", help="city ids (default: all)")
    b.add_argument("--refresh", action="store_true", help="ignore cached downloads")
    sub.add_parser("docs", help="regenerate DATA_SOURCES.md")
    sub.add_parser("delhi-buses", help="Delhi bus route names from tools/input/delhi/otd/routes.txt")
    args = parser.parse_args()
    return {"build": cmd_build, "docs": cmd_docs, "delhi-buses": cmd_delhi_buses}[args.command](args)


if __name__ == "__main__":
    raise SystemExit(main())
