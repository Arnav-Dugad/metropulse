"""In-memory model of one city's network, shared by the GTFS and OSM builders."""
from __future__ import annotations

from dataclasses import dataclass, field

from .geo import LatLon

# How trustworthy a line's times are. Mirrored in the app's TimeSource chip.
QUALITY_SCHEDULED = "scheduled"  # an operator timetable with per-stop times
QUALITY_ESTIMATED = "estimated"  # operator departure times, modelled intermediate stops
QUALITY_APPROXIMATE = "approximate"  # modelled from first/last train and headways

MON, TUE, WED, THU, FRI, SAT, SUN = (1 << i for i in range(7))
WEEKDAYS = MON | TUE | WED | THU | FRI
MON_THU = MON | TUE | WED | THU


@dataclass
class Line:
    id: str
    name: str
    long_name: str
    color: int  # 0xRRGGBB
    text_color: int
    mode: str
    operator: str
    quality: str
    sort_order: int = 0


@dataclass
class Station:
    id: str
    name: str
    lat: float
    lon: float
    local_names: dict[str, str] = field(default_factory=dict)
    osm_id: str | None = None
    complex_id: str | None = None  # None = a station of its own

    @property
    def point(self) -> LatLon:
        return (self.lat, self.lon)


@dataclass
class Pattern:
    """One ordered stopping sequence of a line in one direction."""
    id: int
    line_id: str
    direction: int
    headsign: str
    station_ids: list[str]
    shape: list[LatLon]
    dists: list[float] = field(default_factory=list)  # metres along shape for each stop


@dataclass
class Profile:
    """Arrival/departure offsets (seconds from the trip's first departure) for each stop of a pattern."""
    id: int
    pattern_id: int
    arrivals: list[int]
    departures: list[int]


@dataclass
class Trip:
    pattern_id: int
    profile_id: int
    service_id: str
    start_secs: int  # first departure, seconds after service-day midnight (may exceed 86400)


@dataclass
class Service:
    id: str
    days: int  # bitmask MON..SUN
    start_date: int  # yyyymmdd
    end_date: int
    exceptions: dict[int, bool] = field(default_factory=dict)  # date -> added (True) / removed (False)


@dataclass
class Track:
    kind: str  # elevated | underground | at_grade
    line_ids: set[str]
    coords: list[LatLon]


@dataclass
class Exit:
    station_id: str
    name: str
    lat: float
    lon: float
    wheelchair: bool | None = None


@dataclass
class FareRule:
    kind: str  # km | stations
    upper: float
    fare_paise: int
    day_type: str = "all"  # all | weekday | holiday
    line_id: str = ""  # "" = whole network


@dataclass
class Source:
    what: str
    url: str
    licence: str
    attribution: str


@dataclass
class CityData:
    id: str
    name: str
    timezone: str
    lines: dict[str, Line] = field(default_factory=dict)
    stations: dict[str, Station] = field(default_factory=dict)
    patterns: list[Pattern] = field(default_factory=list)
    profiles: list[Profile] = field(default_factory=list)
    trips: list[Trip] = field(default_factory=list)
    services: dict[str, Service] = field(default_factory=dict)
    transfers: dict[tuple[str, str], int] = field(default_factory=dict)
    tracks: list[Track] = field(default_factory=list)
    exits: list[Exit] = field(default_factory=list)
    fare_rules: list[FareRule] = field(default_factory=list)
    fare_od: dict[tuple[str, str], int] = field(default_factory=dict)
    # (media, timeframe all|peak|offpeak, day type all|weekday|holiday, line id or "") -> (percent, note)
    fare_discounts: dict[tuple[str, str, str, str], tuple[float, str]] = field(default_factory=dict)
    fare_peak_windows: list[tuple[int, int, int]] = field(default_factory=list)  # (days mask, start, end)
    sources: list[Source] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    # Identifiers that live data feeds use: TfL StopPoint id -> station id (from OSM NaPTAN codes),
    # GTFS route_id -> line id. The app matches realtime predictions with them.
    station_codes: dict[str, str] = field(default_factory=dict)
    # From OpenStreetMap: station id -> step-free (wheelchair tags), and station id -> facilities nearby.
    station_access: dict[str, bool] = field(default_factory=dict)
    # Last day (yyyymmdd) an official feed's calendar covers; None for timetables built here.
    valid_until: int | None = None
    station_facilities: dict[str, list[str]] = field(default_factory=dict)
    # Zonal fares (London, Dubai): rule, peak windows, table and station id -> zones; see tools/cities/fares/.
    zonal: dict | None = None
    route_codes: dict[str, str] = field(default_factory=dict)
    # line id -> days ("weekday", "saturday", "sunday") whose timetable the operator has not published
    unknown_timetables: dict[str, list[str]] = field(default_factory=dict)
    # (line id, day type) -> (first departure, last departure) as published, even without headways
    line_hours: dict[tuple[str, str], tuple[int, int]] = field(default_factory=dict)

    _profile_index: dict[tuple, int] = field(default_factory=dict, repr=False)

    def add_profile(self, pattern_id: int, arrivals: list[int], departures: list[int]) -> int:
        """Stores a profile, reusing an identical one for the same pattern."""
        key = (pattern_id, tuple(arrivals), tuple(departures))
        if key in self._profile_index:
            return self._profile_index[key]
        profile = Profile(len(self.profiles) + 1, pattern_id, arrivals, departures)
        self.profiles.append(profile)
        self._profile_index[key] = profile.id
        return profile.id
