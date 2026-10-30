"""Sanity checks on a built city. Errors stop the build; warnings are reported."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

from .geo import haversine_m, project
from .model import CityData

MAX_STOP_OFFSET_M = 300  # a stop further than this from its line's track means bad matching
WARN_STOP_OFFSET_M = 120
MIN_HEADWAY_SECS = 90  # anything tighter on one pattern suggests duplicated trips


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def validate(city: CityData, bbox: list[float] | None = None) -> Report:
    r = Report()
    if not city.lines:
        r.errors.append("no lines")
    if not city.stations:
        r.errors.append("no stations")

    for s in city.stations.values():
        if not s.name.strip():
            r.errors.append(f"station {s.id} has no name")
        if bbox and not (bbox[0] - 0.05 <= s.lat <= bbox[2] + 0.05 and bbox[1] - 0.05 <= s.lon <= bbox[3] + 0.05):
            r.errors.append(f"station {s.name} ({s.lat:.5f},{s.lon:.5f}) is outside the city bounds")

    lines_with_trips = {p.line_id for p in city.patterns if any(t.pattern_id == p.id for t in city.trips)}
    for line in city.lines.values():
        if line.id not in lines_with_trips and line.id not in city.unknown_timetables:
            r.errors.append(f"line {line.id} has no trips and no 'times unavailable' record")
    lines_with_patterns = {p.line_id for p in city.patterns}
    for line in city.lines.values():
        if line.id not in lines_with_patterns:
            r.errors.append(f"line {line.id} has no patterns")

    for p in city.patterns:
        if len(p.station_ids) < 2:
            r.errors.append(f"pattern {p.id} has fewer than two stops")
            continue
        missing = [s for s in p.station_ids if s not in city.stations]
        if missing:
            r.errors.append(f"pattern {p.id} references unknown stations {missing}")
            continue
        if any(b < a - 1 for a, b in zip(p.dists, p.dists[1:])):
            r.errors.append(f"pattern {p.id} ({p.line_id}) stops are out of order along the track")
        for sid in p.station_ids:
            _, offset, _ = project(p.shape, city.stations[sid].point)
            name = city.stations[sid].name
            if offset > MAX_STOP_OFFSET_M:
                r.errors.append(f"{p.line_id}: {name} is {offset:.0f} m from the line's track")
            elif offset > WARN_STOP_OFFSET_M:
                r.warnings.append(f"{p.line_id}: {name} is {offset:.0f} m from the line's track")
        for a, b in zip(p.station_ids, p.station_ids[1:]):
            gap = haversine_m(city.stations[a].point, city.stations[b].point)
            if gap > 8000:
                r.warnings.append(f"{p.line_id}: {city.stations[a].name} → {city.stations[b].name} is {gap / 1000:.1f} km apart")

    for prof in city.profiles:
        if prof.arrivals[0] != 0 and prof.departures[0] != 0:
            r.errors.append(f"profile {prof.id} does not start at 0")
        seq = [t for pair in zip(prof.arrivals, prof.departures) for t in pair]
        if any(b < a for a, b in zip(seq, seq[1:])):
            r.errors.append(f"profile {prof.id} (pattern {prof.pattern_id}) goes back in time")

    starts = defaultdict(list)
    for t in city.trips:
        if t.service_id not in city.services:
            r.errors.append(f"trip on pattern {t.pattern_id} uses unknown service {t.service_id}")
            break
        # A service day runs from midnight into the next morning: 24-hour systems (New York) start trips
        # at 00:xx, and London's Saturday Night Tube runs until about 07:00 on Sunday (31:00).
        if not 0 <= t.start_secs <= 32 * 3600:
            r.errors.append(f"trip on pattern {t.pattern_id} starts at an implausible time {t.start_secs}")
        starts[(t.pattern_id, t.service_id)].append(t.start_secs)
    for (pattern_id, service_id), times in starts.items():
        times.sort()
        tight = sum(1 for a, b in zip(times, times[1:]) if b - a < MIN_HEADWAY_SECS)
        if tight:
            r.warnings.append(f"pattern {pattern_id}/{service_id}: {tight} departures less than {MIN_HEADWAY_SECS}s apart")

    for (a, b), paise in city.fare_od.items():
        if paise <= 0 or a not in city.stations or b not in city.stations:
            r.errors.append(f"bad fare {a}->{b}: {paise}")
            break
    groups = defaultdict(list)
    for f in city.fare_rules:
        groups[(f.kind, f.day_type, f.line_id)].append(f)
        if f.line_id and f.line_id not in city.lines:
            r.errors.append(f"fare rule for unknown line {f.line_id}")
    for key, slab in groups.items():
        slab.sort(key=lambda f: f.upper)
        if any(b.fare_paise < a.fare_paise for a, b in zip(slab, slab[1:])):
            r.errors.append(f"fare slabs {key} are not increasing")
    for line_id, days in city.unknown_timetables.items():
        r.warnings.append(f"{line_id}: no published headways for {', '.join(sorted(set(days)))}; shown as times unavailable")
    if not city.fare_od and not city.fare_rules:
        r.warnings.append("no fare data")
    if not city.tracks:
        r.warnings.append("no track geometry for the 3D map")
    return r
