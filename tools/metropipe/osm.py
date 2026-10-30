"""OpenStreetMap (Overpass API) import: line geometry, stations, exits and track structure."""
from __future__ import annotations

import heapq
import json
import math
import re
import time
from collections import defaultdict
from pathlib import Path

import requests

from .geo import LatLon, haversine_m, path_length_m, project, simplify
from .model import CityData, Exit, Pattern, Station, Track

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]
USER_AGENT = "MetroPulse-data-pipeline/1.0 (+https://github.com/Arnav-Dugad/metropulse)"
LOCAL_NAME_KEYS = re.compile(r"^name:(hi|kn|te|ta|ml|mr|bn|gu|ur|pa|or|as)$")
STOP_ROLES = {"stop", "stop_entry_only", "stop_exit_only", "forward_stop", "backward_stop"}


class OsmError(RuntimeError):
    pass


QUERY_VERSION = 2  # part of the cache key; bump whenever overpass_query changes


def overpass_query(bbox: list[float], routes: str) -> str:
    # Member positions come from the relation's `geom`; stations and entrances need `body`
    # (plain `tags` output carries no node coordinates).
    s, w, n, e = bbox
    b = f"{s},{w},{n},{e}"
    return f"""
[out:json][timeout:300];
relation["type"="route"]["route"~"^({routes})$"]({b})->.routes;
.routes out body geom;
way(r.routes)->.ways;
.ways out tags;
node(r.routes)->.members;
.members out tags;
(
  node["railway"="station"]({b});
  node["public_transport"="station"]({b});
  way["railway"="station"]({b});
  way["public_transport"="station"]({b});
)->.stations;
.stations out body center;
node["railway"="subway_entrance"]({b});
out body;
"""


FACILITIES_VERSION = 1  # bump with facilities_query changes  # part of the facilities cache key


def facilities_query(bbox: list[float]) -> str:
    """Stations' own accessibility tags, and lifts, toilets, cash machines, cycle and park-and-ride
    parking within a short walk of a station. Kept apart from the main query so adding it leaves the
    network (and its ids) untouched."""
    s, w, n, e = bbox
    b = f"{s},{w},{n},{e}"
    return f"""
[out:json][timeout:300];
(
  node["railway"="station"]({b});
  node["public_transport"="station"]({b});
  node["railway"="stop"]({b});
  node["public_transport"="stop_position"]["subway"="yes"]({b});
  node["public_transport"="stop_position"]["light_rail"="yes"]({b});
  node["public_transport"="platform"]["wheelchair"]({b});
)->.st;
.st out body;
(
  node(around.st:120)["highway"="elevator"];
  node(around.st:150)["amenity"="toilets"];
  node(around.st:120)["amenity"="atm"];
  node(around.st:120)["amenity"="bicycle_parking"];
  node(around.st:300)["amenity"="parking"]["park_ride"~"yes|bus|train|subway"];
  way(around.st:300)["amenity"="parking"]["park_ride"~"yes|bus|train|subway"];
);
out center tags;
"""


def fetch(bbox: list[float], cache: Path, routes: str = "subway|light_rail|monorail", max_age_days: float = 7, query: str | None = None,
          timeout: int = 360, rounds: int = 2) -> dict:
    """Runs the city query against Overpass, caching the response for max_age_days. Each endpoint is
    tried [rounds] times, waiting [timeout] seconds for each answer."""
    if cache.exists() and (time.time() - cache.stat().st_mtime) < max_age_days * 86400:
        return json.loads(cache.read_text(encoding="utf-8"))
    query = query or overpass_query(bbox, routes)
    last_error: Exception | None = None
    for attempt, endpoint in enumerate(OVERPASS_ENDPOINTS * rounds):
        try:
            r = requests.post(endpoint, data={"data": query}, headers={"User-Agent": USER_AGENT}, timeout=timeout)
            if r.status_code == 200:
                data = r.json()
                cache.parent.mkdir(parents=True, exist_ok=True)
                cache.write_text(json.dumps(data), encoding="utf-8")
                return data
            last_error = OsmError(f"{endpoint} answered HTTP {r.status_code}")
        except (requests.RequestException, ValueError) as e:
            last_error = e
        time.sleep(10 * (attempt + 1))  # Overpass asks clients to back off when busy
    raise OsmError(f"Overpass query failed: {last_error}")


# ---------------------------------------------------------------------------------------------
# Parsing helpers

def _norm(name: str) -> str:
    n = name.lower()
    n = re.sub(r"\b(metro|station|stn|rapid|interchange|junction)\b", " ", n)
    n = re.sub(r"[^a-z0-9]+", " ", n)
    return " ".join(n.split())


def _names_match(a: str, b: str) -> bool:
    na, nb = _norm(a), _norm(b)
    return bool(na) and bool(nb) and (na == nb or na in nb or nb in na)


def _english_name(tags: dict) -> str:
    return tags.get("name:en") or tags.get("name") or tags.get("official_name") or ""


def _bus_only(tags: dict) -> bool:
    """A bus station: no railway tag and not marked as serving trains, subway or light rail."""
    if tags.get("railway") or tags.get("station") in ("subway", "light_rail", "train", "monorail"):
        return False
    if any(tags.get(k) == "yes" for k in ("train", "subway", "light_rail", "monorail", "tram")):
        return False
    return tags.get("amenity") == "bus_station" or tags.get("bus") == "yes" or "bus station" in tags.get("name", "").lower()


def _is_stop_like(tags: dict) -> bool:
    return (tags.get("railway") in ("station", "halt", "stop", "platform", "tram_stop")
            or tags.get("public_transport") in ("station", "stop_position", "platform", "stop_area"))


def _local_names(tags: dict) -> dict[str, str]:
    return {k.split(":")[1]: v for k, v in tags.items() if LOCAL_NAME_KEYS.match(k)}


def _point(el: dict) -> LatLon | None:
    if "lat" in el:
        return (el["lat"], el["lon"])
    if "center" in el:
        return (el["center"]["lat"], el["center"]["lon"])
    geom = el.get("geometry")
    if geom:
        return (sum(g["lat"] for g in geom) / len(geom), sum(g["lon"] for g in geom) / len(geom))
    return None


class OsmIndex:
    def __init__(self, data: dict):
        self.relations: list[dict] = []
        self.way_tags: dict[int, dict] = {}
        self.node_tags: dict[int, dict] = {}
        self.stations: list[dict] = []
        self.entrances: list[dict] = []
        for el in data.get("elements", []):
            tags = el.get("tags", {})
            if el["type"] == "relation":
                self.relations.append(el)
            elif tags.get("railway") == "subway_entrance":
                self.entrances.append(el)
            elif tags.get("railway") == "station" or tags.get("public_transport") == "station":
                # Bus stations share public_transport=station; a metro stop must never take one's name.
                if ("center" in el or "lat" in el) and not _bus_only(tags):
                    self.stations.append(el)
                if el["type"] == "node":
                    self.node_tags[el["id"]] = tags
            elif el["type"] == "way":
                self.way_tags[el["id"]] = tags
            elif el["type"] == "node":
                self.node_tags[el["id"]] = tags

    def relations_for(self, hints: dict) -> list[dict]:
        name_part = (hints or {}).get("name_contains")
        ref = (hints or {}).get("ref")
        out = []
        # A line whose opening the operator has confirmed may still be tagged as construction in OSM.
        allow_construction = bool((hints or {}).get("allow_construction"))
        for r in self.relations:
            tags = r.get("tags", {})
            if any(k in tags for k in ("disused", "abandoned", "proposed")):
                continue
            under_construction = "construction" in tags or re.search(r"\(u/c\)|under construction", tags.get("name", ""), re.I)
            if under_construction and not allow_construction:
                continue
            names = " | ".join(tags.get(k, "") for k in ("name", "name:en", "official_name"))
            if name_part:
                # Whole-token match, so "Line 1" does not pick up "Line 11".
                part = name_part.lower()
                pattern = ((r"(?<![0-9a-z])" if part[:1].isalnum() else "") + re.escape(part)
                           + (r"(?![0-9a-z])" if part[-1:].isalnum() else ""))
                if re.search(pattern, names.lower()):
                    out.append(r)
            elif ref and tags.get("ref") == str(ref):
                out.append(r)
        return out


# ---------------------------------------------------------------------------------------------
# Network building

GAP_BRIDGE_M = 40  # way ends closer than this are treated as connected (mapping slips)
SNAP_LIMIT_M = 450  # a stop further than this from any track node is joined with a straight segment


def _route_shape(ways: list[list[LatLon]], stops: list[LatLon]) -> tuple[list[LatLon], int]:
    """
    Builds the line shape by routing between consecutive stops over the relation's track ways.
    Unlike stitching ways in member order, this tolerates mis-ordered members, small gaps, loops
    and U-shaped lines. Returns (points, number of legs that fell back to a straight segment).
    """
    adj: dict[LatLon, list[tuple[LatLon, float]]] = defaultdict(list)
    for w in ways:
        for a, b in zip(w, w[1:]):
            if a != b:
                d = haversine_m(a, b)
                adj[a].append((b, d))
                adj[b].append((a, d))
    ends = list({p for w in ways if len(w) >= 2 for p in (w[0], w[-1])})
    for i, a in enumerate(ends):
        for b in ends[i + 1:]:
            d = haversine_m(a, b)
            if 0 < d <= GAP_BRIDGE_M:
                adj[a].append((b, d))
                adj[b].append((a, d))
    nodes = list(adj)

    def snap(p: LatLon) -> LatLon | None:
        if not nodes:
            return None
        n = min(nodes, key=lambda q: haversine_m(p, q))
        return n if haversine_m(p, n) <= SNAP_LIMIT_M else None

    def shortest(a: LatLon, b: LatLon) -> list[LatLon] | None:
        dist, prev, heap = {a: 0.0}, {}, [(0.0, a)]
        while heap:
            d, u = heapq.heappop(heap)
            if u == b:
                path = [b]
                while path[-1] != a:
                    path.append(prev[path[-1]])
                return path[::-1]
            if d > dist[u]:
                continue
            for v, w in adj[u]:
                nd = d + w
                if nd < dist.get(v, math.inf):
                    dist[v], prev[v] = nd, u
                    heapq.heappush(heap, (nd, v))
        return None

    snapped = [snap(p) for p in stops]
    shape: list[LatLon] = []
    fallbacks = 0
    for i in range(len(stops) - 1):
        a, b = snapped[i], snapped[i + 1]
        path = shortest(a, b) if a and b else None
        straight = haversine_m(stops[i], stops[i + 1])
        if path is None or path_length_m(path) > 3 * straight + 1500:  # no route, or an absurd detour
            path = [stops[i], stops[i + 1]]
            fallbacks += 1
        shape.extend(path if not shape else path[1:])
    return shape, fallbacks


def _trim_to_termini(stations: list[str], city: CityData, termini: list[str] | None) -> list[str]:
    """Keeps only the open section between the published termini when OSM maps a longer corridor."""
    if not termini or len(termini) != 2:
        return stations
    def find(t: str) -> int | None:
        # An exact name first: "Upminster" must not stop at "Upminster Bridge".
        exact = next((i for i, s in enumerate(stations) if _norm(city.stations[s].name) == _norm(t)), None)
        return exact if exact is not None else next((i for i, s in enumerate(stations) if _names_match(city.stations[s].name, t)), None)

    idx = [find(t) for t in termini]
    if None in idx or idx[0] == idx[1]:
        return stations
    lo, hi = sorted(idx)
    return stations[lo:hi + 1]


class StationResolver:
    """Maps route stop members to OSM station objects (or creates stations from the stops)."""

    def __init__(self, index: OsmIndex, city: CityData):
        self.index = index
        self.city = city
        self.candidates = [(s, _point(s), _english_name(s.get("tags", {}))) for s in index.stations]

    def resolve(self, member: dict) -> str | None:
        tags = self.index.node_tags.get(member["ref"], {}) if member["type"] == "node" else self.index.way_tags.get(member["ref"], {})
        point = _point(member)
        if point is None:
            return None
        name = _english_name(tags)
        best, best_score = None, math.inf
        for el, p, sname in self.candidates:
            if p is None:
                continue
            d = haversine_m(point, p)
            if name and sname and _names_match(name, sname) and d < 700:
                score = d
            elif d < 220:
                score = d + 400  # nearby but unnamed or differently named: weaker
            else:
                continue
            if score < best_score:
                best, best_score = (el, p, sname), score
        if best is None:
            # Only a stop-like object may become a station; mis-added members (a hospital, a
            # building) are dropped instead of turning into a fake stop.
            if not _is_stop_like(tags):
                return None
            sid = f"osm-{member['type'][0]}{member['ref']}"
            if sid not in self.city.stations and name:
                return self._add(sid, name, point, tags)
            return sid if sid in self.city.stations else None
        el, p, sname = best
        sid = f"osm-{el['type'][0]}{el['id']}"
        if sid not in self.city.stations:
            sid = self._add(sid, sname or name, p, el.get("tags", {}))
        # Platforms carry the operator's own code where the station node has the mainline one
        # (Harrow & Wealdstone: station 9100HROW, Underground platform 9400ZZLUHAW1).
        self._remember_code(tags, sid)
        return sid

    def _add(self, sid: str, name: str, point: LatLon, tags: dict) -> str:
        """Adds a station, unless OSM maps the same station twice (node and area, one per line or
        direction); then the existing one is reused."""
        name = display_name(name)
        key = _norm(name)
        for other in self.city.stations.values():
            if _norm(other.name) == key and haversine_m(point, other.point) <= DUPLICATE_STATION_M:
                self._remember_code(tags, other.id)
                return other.id
        self.city.stations[sid] = Station(sid, name, point[0], point[1], _local_names(tags), sid)
        self._remember_code(tags, sid)
        return sid

    def _remember_code(self, tags: dict, sid: str) -> None:
        """NaPTAN station codes (9400ZZLUOXC) become TfL StopPoint ids (940GZZLUOXC) for live arrivals."""
        # Shared stations list several codes: "9100HROW;9400ZZLUHAW;9100HROWDC".
        for code in tags.get("naptan:AtcoCode", "").split(";"):
            code = code.strip()
            if code.startswith("940") and len(code) >= 11:
                # Platform codes add a digit (9400ZZLUHAW1); newer stations have 12-letter codes (9400ZZBPSUST).
                base = code[:-1] if code[-1].isdigit() else code
                self.city.station_codes.setdefault("940G" + base[4:], sid)


DUPLICATE_STATION_M = 60  # same name and this close: one station mapped twice
COMPLEX_M = 400  # same name and this close: platforms of one interchange

_LINE_QUALIFIER = re.compile(r"\s*[(\[][^)\]]*\b(lines?|metro|corridor)\b[^)\]]*[)\]]\s*$", re.I)
_METRO_SUFFIX = re.compile(r"\s+(metro(\s+station)?|[LM]\d|DLR)$", re.I)


def _latin(text: str) -> bool:
    """True for text in Latin script (the app is English-only for now)."""
    return bool(text) and all(ord(c) < 0x250 or not c.isalpha() for c in text)


def display_name(name: str) -> str:
    """Strips the line qualifiers OSM adds to tell interchange platforms apart:
    "Kirti Nagar (Blue Line)", "Dahisar (East) [Line 7]", "Kukatpally Metro", "Andheri L1"."""
    previous = None
    while previous != name:
        previous = name
        name = _METRO_SUFFIX.sub("", _LINE_QUALIFIER.sub("", name)).strip()
    return name or previous


def group_complexes(city: CityData) -> None:
    """Gives the platforms of one interchange (same name, a short walk apart) a shared complex_id."""
    ids = sorted({s for p in city.patterns for s in p.station_ids})
    for i, a in enumerate(ids):
        sa = city.stations[a]
        for b in ids[i + 1:]:
            sb = city.stations[b]
            if sb.complex_id is None and _norm(sa.name) == _norm(sb.name) \
                    and haversine_m(sa.point, sb.point) <= COMPLEX_M:
                sa.complex_id = sa.complex_id or a
                sb.complex_id = sa.complex_id


def build_patterns(index: OsmIndex, city: CityData, line_id: str, hints: dict) -> list[str]:
    """Creates patterns for one line from its OSM route relations. Returns warnings."""
    warnings: list[str] = []
    relations = index.relations_for(hints)
    if not relations:
        raise OsmError(f"{city.id}/{line_id}: no OSM route relation matches {hints}")
    resolver = StationResolver(index, city)
    candidates: list[Pattern] = []
    for rel in relations:
        members = rel.get("members", [])
        stop_members = [m for m in members if m["role"] in STOP_ROLES]
        if len(stop_members) < 2:
            stop_members = [m for m in members if m["role"].startswith("platform")]
        stations: list[str] = []
        for m in stop_members:
            sid = resolver.resolve(m)
            if sid and (not stations or stations[-1] != sid):
                stations.append(sid)
        full_length = len(stations)
        stations = _trim_to_termini(stations, city, hints.get("termini"))
        if len(stations) < 2:
            warnings.append(f"{line_id}: relation {rel['id']} has too few resolvable stops; skipped")
            continue
        if len(stations) < full_length:
            warnings.append(f"{line_id}: relation {rel['id']} trimmed to the open section "
                            f"({full_length} -> {len(stations)} stations)")
        ways = [[(g["lat"], g["lon"]) for g in m.get("geometry", [])] for m in members
                if m["type"] == "way" and not m["role"].startswith("platform") and m.get("geometry")]
        shape, fallbacks = _route_shape(ways, [city.stations[s].point for s in stations])
        if fallbacks:
            warnings.append(f"{line_id}: relation {rel['id']}: {fallbacks} leg(s) not connected by track; drawn straight")
        shape = simplify(shape, 1.5)
        trimmed = len(stations) < full_length
        tags = rel.get("tags", {})
        # The relation's destination, in English; otherwise the terminus station's (English) name.
        to = tags.get("to:en") or (tags.get("to") if _latin(tags.get("to", "")) else None)
        headsign = (None if trimmed else to) or city.stations[stations[-1]].name
        candidates.append(Pattern(0, line_id, 0, headsign, stations, shape))

    # Drop patterns that are a contiguous part of a longer one (short-turn or duplicate relations).
    def contained(small: list[str], big: list[str]) -> bool:
        n = len(small)
        return n < len(big) and any(big[i:i + n] == small for i in range(len(big) - n + 1))

    kept = [p for p in candidates if not any(contained(p.station_ids, q.station_ids) for q in candidates if q is not p)]
    unique: list[Pattern] = []
    for p in kept:
        if all(p.station_ids != q.station_ids for q in unique):
            unique.append(p)

    # One-directional mapping: synthesise the opposite direction.
    if len(unique) == 1:
        p = unique[0]
        unique.append(Pattern(0, line_id, 1, city.stations[p.station_ids[0]].name,
                              list(reversed(p.station_ids)), list(reversed(p.shape))))
        warnings.append(f"{line_id}: only one direction mapped in OSM; reverse direction synthesised")

    _assign_directions(unique, city)
    for p in unique:
        p.id = len(city.patterns) + 1
        p.dists = _stop_distances(p, city)
        city.patterns.append(p)
    return warnings


def _assign_directions(patterns: list[Pattern], city: CityData) -> None:
    """Direction 0 runs the same way as the line's longest pattern; branches and short turns are
    oriented by the order of shared stations or, failing that, by where their ends fall along it."""
    ordered = sorted(patterns, key=lambda p: -len(p.station_ids))
    reference = ordered[0]
    reference.direction = 0
    done = [reference]
    for p in ordered[1:]:
        twin = next((q for q in done if q.station_ids == p.station_ids[::-1]), None)
        shared = [s for s in p.station_ids if s in reference.station_ids]
        if twin is not None:
            p.direction = 1 - twin.direction
        elif len(shared) >= 2:
            p.direction = 0 if reference.station_ids.index(shared[0]) < reference.station_ids.index(shared[-1]) else 1
        else:
            start = project(reference.shape, city.stations[p.station_ids[0]].point)[0]
            end = project(reference.shape, city.stations[p.station_ids[-1]].point)[0]
            p.direction = 0 if end >= start else 1
        done.append(p)


def _stop_distances(pattern: Pattern, city: CityData) -> list[float]:
    dists, index = [], 0
    for sid in pattern.station_ids:
        along, _, index = project(pattern.shape, city.stations[sid].point, index)
        dists.append(along)
    return dists


TRACK_MATCH_M = 60  # a track way belongs to a line only if it runs along one of the line's patterns


def build_tracks(index: OsmIndex, city: CityData, line_hints: dict[str, dict]) -> None:
    """Track ways along each line's open section, classified (elevated/underground) for the 3D map."""
    shapes: dict[str, list[list[LatLon]]] = defaultdict(list)
    for p in city.patterns:
        shapes[p.line_id].append(p.shape)

    def along_line(line_id: str, geom: list[LatLon]) -> bool:
        # Sample the way; most samples must lie on the line (excludes unopened extensions and depot spurs).
        samples = geom[:: max(1, len(geom) // 5)] + [geom[-1]]
        near = sum(1 for pt in samples if any(project(s, pt)[1] <= TRACK_MATCH_M for s in shapes[line_id]))
        return near >= 0.6 * len(samples)

    way_lines: dict[int, set[str]] = defaultdict(set)
    way_geom: dict[int, list[LatLon]] = {}
    for line_id, hints in line_hints.items():
        for rel in index.relations_for(hints):
            for m in rel.get("members", []):
                if m["type"] == "way" and not m["role"].startswith("platform") and m.get("geometry"):
                    geom = [(g["lat"], g["lon"]) for g in m["geometry"]]
                    if shapes[line_id] and along_line(line_id, geom):
                        way_lines[m["ref"]].add(line_id)
                        way_geom[m["ref"]] = geom
    for way_id, lines in way_lines.items():
        tags = index.way_tags.get(way_id, {})
        if tags.get("railway") not in (None, "subway", "light_rail", "monorail", "rail", "narrow_gauge"):
            continue  # platforms or other non-track members
        city.tracks.append(Track(track_kind(tags), lines, simplify(way_geom[way_id], 1.0)))


def track_kind(tags: dict) -> str:
    layer = int(tags["layer"]) if re.fullmatch(r"-?\d+", tags.get("layer", "")) else 0
    if tags.get("tunnel") not in (None, "no") or tags.get("location") == "underground" or layer < 0:
        return "underground"
    if tags.get("bridge") not in (None, "no") or tags.get("location") == "overground" or layer > 0:
        return "elevated"
    return "at_grade"


def build_exits(index: OsmIndex, city: CityData, max_dist_m: float = 300.0) -> None:
    stations = list(city.stations.values())
    for el in index.entrances:
        p = _point(el)
        if p is None:
            continue
        nearest = min(stations, key=lambda s: haversine_m(p, s.point), default=None)
        if nearest is None or haversine_m(p, nearest.point) > max_dist_m:
            continue
        tags = el.get("tags", {})
        label = tags.get("name:en") or tags.get("name") or (f"Gate {tags['ref']}" if tags.get("ref") else "Entrance")
        wheelchair = {"yes": True, "no": False}.get(tags.get("wheelchair", ""))
        city.exits.append(Exit(nearest.id, label, p[0], p[1], wheelchair))


FACILITY_KINDS = (
    ("highway", "elevator", "lift"),
    ("amenity", "toilets", "toilets"),
    ("amenity", "atm", "atm"),
    ("amenity", "bicycle_parking", "bicycle_parking"),
    ("amenity", "parking", "parking"),
)


def build_facilities(data: dict, city: CityData) -> None:
    """Step-free access (station and platform `wheelchair` tags) and nearby facilities per station.

    A tagged OSM station counts for the network station of the same name within 400 m, or failing a
    name match the nearest one within 120 m. Facilities go to the nearest station within reach.
    """
    stations = list(city.stations.values())
    if not stations:
        return
    tagged: dict[str, list[bool]] = {}
    for el in data.get("elements", []):
        tags = el.get("tags", {})
        p = _point(el)
        if p is None:
            continue
        if "wheelchair" in tags:
            # "limited" often means step-free to the ticket hall only, so it says nothing either way.
            value = {"yes": True, "no": False}.get(tags["wheelchair"])
            rail = tags.get("railway") or tags.get("station") or any(tags.get(k) == "yes" for k in ("subway", "light_rail", "train", "monorail"))
            if value is None or not rail or _bus_only(tags) or tags.get("highway") == "bus_stop":
                continue
            name = _english_name(tags)
            near = [s for s in stations if haversine_m(p, s.point) < 400]
            named = [s for s in near if name and _names_match(name, s.name)]
            target = min(named or [s for s in near if haversine_m(p, s.point) < 120], key=lambda s: haversine_m(p, s.point), default=None)
            if target is not None:
                tagged.setdefault(target.id, []).append(value)
            continue
        kind = next((k for key, val, k in FACILITY_KINDS if tags.get(key) == val), None)
        if kind is None:
            continue
        reach = 300 if kind == "parking" else 150
        nearest = min(stations, key=lambda s: haversine_m(p, s.point))
        if haversine_m(p, nearest.point) <= reach:
            have = city.station_facilities.setdefault(nearest.id, [])
            if kind not in have:
                have.append(kind)
    # Any step-free platform or station tag makes the station step-free; only all-"no" makes it not.
    for sid, values in tagged.items():
        city.station_access[sid] = any(values)
    for have in city.station_facilities.values():
        have.sort()


def build_walk_transfers(city: CityData, max_dist_m: float = 300.0) -> None:
    """Links stations of different lines that are close enough to walk between (out-of-system or in-station)."""
    station_lines: dict[str, set[str]] = defaultdict(set)
    for p in city.patterns:
        for s in p.station_ids:
            station_lines[s].add(p.line_id)
    ids = list(station_lines)
    for i, a in enumerate(ids):
        if len(station_lines[a]) > 1:
            city.transfers.setdefault((a, a), 180)  # change of line within one station
        for b in ids[i + 1:]:
            if station_lines[a] == station_lines[b]:
                continue
            sa, sb = city.stations[a], city.stations[b]
            d = haversine_m(sa.point, sb.point)
            if d <= max_dist_m or (sa.complex_id is not None and sa.complex_id == sb.complex_id):
                secs = int(120 + d / 1.1)  # buffer for stairs and gates, plus walking at 1.1 m/s
                city.transfers.setdefault((a, b), secs)
                city.transfers.setdefault((b, a), secs)
