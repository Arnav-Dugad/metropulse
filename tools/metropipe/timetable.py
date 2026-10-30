"""
Builds approximate timetables from published first/last trains and headways.

Run time between stations is modelled from track distance, then scaled so the whole trip
matches the operator's published commercial speed (or a stated default). Every line built
here is marked "approximate" and the app labels its times that way.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .model import FRI, MON_THU, SAT, SUN, CityData, FareRule, Pattern, Service, Trip

DWELL_SECS = 30
INTERCHANGE_DWELL_SECS = 45
ACCEL_PENALTY_SECS = 22  # time lost to accelerating and braking on each run between stations
CRUISE_MPS = 70 / 3.6  # line speed used only for the relative weight of segments
# Indian metro commercial speeds cluster around 32-35 km/h; used only when a line has none published.
DEFAULT_COMMERCIAL_KMH = 33.0


class TimetableError(ValueError):
    pass




@dataclass
class Band:
    start: int
    end: int
    minutes: float


def hhmm(value: str) -> int:
    m = re.fullmatch(r"(\d{1,2}):(\d{2})", str(value).strip())
    if not m:
        raise TimetableError(f"Bad time {value!r}; expected HH:MM")
    h, mi = map(int, m.groups())
    secs = h * 3600 + mi * 60
    return secs + 86400 if h < 3 else secs  # 00:30 belongs to the previous service day


def run_profile(pattern: Pattern, interchange: set[str], avg_kmh: float | None) -> tuple[list[int], list[int]]:
    """Arrival and departure offsets for each stop of the pattern."""
    seg_lengths = [max(1.0, pattern.dists[i + 1] - pattern.dists[i]) for i in range(len(pattern.dists) - 1)]
    weights = [d / CRUISE_MPS + ACCEL_PENALTY_SECS for d in seg_lengths]
    dwell = [INTERCHANGE_DWELL_SECS if s in interchange else DWELL_SECS for s in pattern.station_ids[1:-1]]
    total = sum(seg_lengths) / ((avg_kmh or DEFAULT_COMMERCIAL_KMH) / 3.6)
    running = total - sum(dwell)
    scale = running / sum(weights) if running > sum(weights) * 0.6 else 1.0  # never compress below 60%
    arrivals, departures, t = [0], [0], 0.0
    for i, w in enumerate(weights):
        t += w * scale
        arrivals.append(round(t))
        if i < len(dwell):
            t += dwell[i]
        departures.append(round(t))
    return arrivals, departures


CLOSED = ("none", "closed", "no_service")


def parse_day(block, weekday_block) -> tuple[int | None, int | None, list[Band]] | None:
    """
    None for a day with no trains. Otherwise (first, last, bands); first/last are None and bands
    empty when the operator has not published them (a null day block means "not published").
    """
    if block in CLOSED:
        return None
    if block == "same_as_weekday":
        block = weekday_block
    if not isinstance(block, dict):
        return None, None, []
    first = hhmm(block["first"]) if block.get("first") else None
    last = hhmm(block["last"]) if block.get("last") else None
    bands = []
    for h in block.get("headways") or []:
        if h.get("minutes") is None:
            continue
        start = hhmm(h["from"])
        end = hhmm(h["to"])
        bands.append(Band(start, end if end > start else end + 86400, float(h["minutes"])))
    bands.sort(key=lambda b: b.start)
    return first, last, bands


def headway_at(bands: list[Band], t: int) -> float:
    for b in bands:
        if b.start <= t < b.end:
            return b.minutes
    # Outside every band: use the closest one (published bands rarely cover the edges exactly).
    return min(bands, key=lambda b: min(abs(t - b.start), abs(t - b.end))).minutes


def build_line_timetable(city: CityData, line_id: str, cfg: dict, start_date: int, end_date: int) -> None:
    timetable = cfg.get("timetable") or {}
    weekday = timetable.get("weekday")
    days = {
        "weekday": (MON_THU, weekday),
        # A missing key means the source gave one timetable for every day; an explicit null means unknown.
        # Friday has its own key for Gulf weekends and Friday-night services; it defaults to the weekday one.
        "friday": (FRI, timetable.get("friday", "same_as_weekday")),
        "saturday": (SAT, timetable.get("saturday", "same_as_weekday")),
        "sunday": (SUN, timetable.get("sunday", "same_as_weekday")),
    }
    # Merge days that share the weekday timetable into one service.
    services: dict[str, tuple[int, object]] = {}
    for key, (mask, block) in days.items():
        target = "weekday" if block == "same_as_weekday" else key
        m, b = services.get(target, (0, weekday if target == "weekday" else block))
        services[target] = (m | mask, b)

    interchange = {s for s, lines in _station_lines(city).items() if len(lines) > 1}
    patterns = [p for p in city.patterns if p.line_id == line_id]
    for key, (mask, block) in services.items():
        parsed = parse_day(block, weekday)
        if parsed is None:
            continue  # no trains on these days
        first, last, bands = parsed
        covered = [d for d, (m, _) in days.items() if m & mask]
        if first is not None and last is not None:
            for d in covered:
                city.line_hours[(line_id, d)] = (first, last)
        # A band with no published headway (e.g. only peak frequencies are known) would otherwise be
        # filled from the nearest band, inventing trains; such a day keeps its hours but no departures.
        partial = isinstance(block, dict) and any((h or {}).get("minutes") is None for h in block.get("headways") or [])
        if block == "same_as_weekday" and isinstance(weekday, dict):
            partial = any((h or {}).get("minutes") is None for h in weekday.get("headways") or [])
        if first is None or last is None or not bands or partial:
            city.unknown_timetables.setdefault(line_id, []).extend(covered)
            continue
        service_id = f"{line_id}-{key}"
        city.services[service_id] = Service(service_id, mask, start_date, end_date)
        profiles = {}
        for p in patterns:
            arrivals, departures = run_profile(p, interchange, cfg.get("avg_speed_kmh"))
            profiles[p.id] = city.add_profile(p.id, arrivals, departures)
        if cfg.get("branch_mode") == "weighted":
            _weighted_trips(city, cfg, patterns, profiles, service_id, _departures(first, last, bands))
        elif cfg.get("branch_mode") == "alternate":
            # The published headway is the trunk's: trains take the branches in turn.
            for direction in sorted({p.direction for p in patterns}):
                branches = sorted((p for p in patterns if p.direction == direction), key=lambda p: -len(p.station_ids))
                for i, t in enumerate(_departures(first, last, bands)):
                    p = branches[i % len(branches)]
                    city.trips.append(Trip(p.id, profiles[p.id], service_id, t))
        else:
            for p in patterns:
                for t in _departures(first, last, bands):
                    city.trips.append(Trip(p.id, profiles[p.id], service_id, t))


def _name_key(name: str) -> str:
    return " ".join(re.sub(r"[^a-z0-9]+", " ", name.lower()).split())


def _branch_patterns(city: CityData, branch: dict, patterns: list[Pattern]) -> list[Pattern]:
    """Patterns (both directions) whose ends are the branch's `from`/`to` and that call at every `via`."""
    ends = {_name_key(branch["from"]), _name_key(branch["to"])}
    via = [_name_key(v) for v in branch.get("via") or []]
    out = []
    for p in patterns:
        names = [_name_key(city.stations[s].name) for s in p.station_ids]
        if {names[0], names[-1]} == ends and all(v in names for v in via):
            out.append(p)
    return out


def _weighted_trips(city: CityData, cfg: dict, patterns: list[Pattern], profiles: dict[int, int], service_id: str,
                    departures: list[int]) -> None:
    """
    The published headway is the trunk's; each departure goes to a branch in proportion to its
    measured share (smooth weighted round robin), and after `night_after` only to branches with a
    night service. Patterns no branch claims get no trips.
    """
    night_after = hhmm(cfg.get("night_after") or "25:00")
    branches = cfg.get("branches") or []
    for direction in sorted({p.direction for p in patterns}):
        own = [p for p in patterns if p.direction == direction]
        options = []  # (pattern, share, night)
        for b in branches:
            matched = [p for p in _branch_patterns(city, b, own)]
            if not matched:
                continue
            best = max(matched, key=lambda p: len(p.station_ids))
            options.append((best, float(b["share"]), bool(b.get("night"))))
        if not options:
            raise TimetableError(f"{cfg.get('id')}: no branch matches a pattern in direction {direction}")
        credit = [0.0] * len(options)
        for t in departures:
            eligible = [i for i, o in enumerate(options) if t < night_after or o[2]]
            if not eligible:
                continue
            total = sum(options[i][1] for i in eligible)
            for i in eligible:
                credit[i] += options[i][1] / total
            pick = max(eligible, key=lambda i: credit[i])
            credit[pick] -= 1
            p = options[pick][0]
            city.trips.append(Trip(p.id, profiles[p.id], service_id, t))


def _departures(first: int, last: int, bands: list[Band]) -> list[int]:
    out, t = [], first
    while t <= last:
        out.append(t)
        t += round(headway_at(bands, t) * 60)
    return out


def _station_lines(city: CityData) -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for p in city.patterns:
        for s in p.station_ids:
            out.setdefault(s, set()).add(p.line_id)
    return out


GENERAL_MEDIA = ("smart_card", "qr_ticket", "ncmc")
DISCOUNT_DAYS = {"all": "all", "weekday": "weekday", "mon_sat": "weekday", "holiday": "holiday", "sunday": "holiday"}
PEAK_DAYS = {"daily": 127, "mon_sat": 63, "mon_fri": 31, "sunday": 64}


def _general_discount(d: dict) -> bool:
    """Only discounts any rider gets by paying with a common medium are modelled. Concessions for a
    group (students, seniors), multi-trip passes, unconfirmed offers and zero discounts are left out."""
    return (d.get("media") in GENERAL_MEDIA and (d.get("percent") or 0) > 0
            and not d.get("eligibility") and d.get("verified", True) is not False)


def _clock(text: str) -> int:
    h, m = text.split(":")
    return int(h) * 3600 + int(m) * 60


def apply_fare_table(city: CityData, fares: dict | None) -> None:
    """Slabs and media discounts from a city file (used when no GTFS fares exist)."""
    if not fares:
        return
    for d in fares.get("discounts") or []:
        if _general_discount(d):
            for line_id in d.get("lines") or [""]:
                key = (d["media"], d.get("timeframe") or "all", DISCOUNT_DAYS[d.get("days") or "all"], line_id)
                city.fare_discounts[key] = (float(d["percent"]), d.get("note") or "")
    for w in fares.get("peak_windows") or []:
        city.fare_peak_windows.append((PEAK_DAYS[w["days"]], _clock(w["from"]), _clock(w["to"])))
    if fares.get("type") == "per_line":
        # Separate fare systems per operator (e.g. Mumbai): each group's slabs apply within its lines.
        for group in fares.get("per_line") or []:
            apply_fare_table(city, {**group, "applies_to_lines": group.get("lines"), "discounts": []})
        return
    if not fares.get("type") or not any(
        fares.get(k) for k in ("slabs", "holiday_slabs", "sunday_slabs", "sunday_holiday_slabs", "weekday_slabs")
    ):
        return
    kind = {"distance_slab": "km", "station_count_slab": "stations"}.get(fares["type"])
    scopes = fares.get("applies_to_lines") or [""]
    if kind is None:
        raise TimetableError(f"Unsupported fare type {fares['type']}")
    tables = {"all": fares.get("slabs")}
    for key, day_type in (("holiday_slabs", "holiday"), ("sunday_slabs", "holiday"), ("sunday_holiday_slabs", "holiday"),
                          ("weekday_slabs", "weekday")):
        if fares.get(key):
            tables[day_type] = fares[key]
            if day_type == "holiday" and "weekday" not in tables and tables["all"]:
                tables["weekday"] = tables.pop("all")
    for line_id in scopes:
        for day_type, slabs in tables.items():
            for slab in slabs or []:
                upper = slab.get("max_km", slab.get("max_stations"))
                if upper is None:
                    upper = 1e9  # open-ended top slab
                unit = "stations" if "max_stations" in slab else "km" if "max_km" in slab else kind
                city.fare_rules.append(FareRule(unit, float(upper), round(float(slab["fare"]) * 100), day_type, line_id))

