"""Small geodesy helpers: distances, polyline encoding and linear referencing."""
from __future__ import annotations

import math
from typing import Iterable, Sequence

LatLon = tuple[float, float]

EARTH_RADIUS_M = 6_371_008.8


def haversine_m(a: LatLon, b: LatLon) -> float:
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def path_length_m(points: Sequence[LatLon]) -> float:
    return sum(haversine_m(points[i], points[i + 1]) for i in range(len(points) - 1))


def _to_xy(origin: LatLon, p: LatLon) -> tuple[float, float]:
    """Equirectangular projection in metres around origin; accurate to well under 1 m at city scale."""
    k = math.cos(math.radians(origin[0]))
    return (
        math.radians(p[1] - origin[1]) * EARTH_RADIUS_M * k,
        math.radians(p[0] - origin[0]) * EARTH_RADIUS_M,
    )


def project(points: Sequence[LatLon], target: LatLon, start_index: int = 0) -> tuple[float, float, int]:
    """
    Projects target onto the polyline (searching from segment start_index onwards).
    Returns (distance along the line in metres, perpendicular offset in metres, segment index).
    """
    best = (math.inf, 0.0, start_index)  # (offset, along, index)
    along = path_length_m(points[: start_index + 1]) if start_index > 0 else 0.0
    for i in range(start_index, len(points) - 1):
        a, b = points[i], points[i + 1]
        seg_len = haversine_m(a, b)
        ax, ay = 0.0, 0.0
        bx, by = _to_xy(a, b)
        px, py = _to_xy(a, target)
        denom = bx * bx + by * by
        t = 0.0 if denom == 0 else max(0.0, min(1.0, (px * bx + py * by) / denom))
        cx, cy = ax + t * bx, ay + t * by
        offset = math.hypot(px - cx, py - cy)
        if offset < best[0]:
            best = (offset, along + t * seg_len, i)
        along += seg_len
    return best[1], best[0], best[2]


def encode_polyline(points: Iterable[LatLon], precision: int = 6) -> str:
    """Google encoded polyline, precision 6 (matches the app's decoder)."""
    factor = 10**precision
    out: list[str] = []
    prev_lat = prev_lon = 0
    for lat, lon in points:
        ilat, ilon = round(lat * factor), round(lon * factor)
        for delta in (ilat - prev_lat, ilon - prev_lon):
            v = ~(delta << 1) if delta < 0 else delta << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        prev_lat, prev_lon = ilat, ilon
    return "".join(out)


def decode_polyline(encoded: str, precision: int = 6) -> list[LatLon]:
    factor = 10**precision
    coords: list[LatLon] = []
    index = lat = lon = 0
    while index < len(encoded):
        for which in (0, 1):
            shift = result = 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if which == 0:
                lat += delta
            else:
                lon += delta
        coords.append((lat / factor, lon / factor))
    return coords


def simplify(points: Sequence[LatLon], tolerance_m: float = 2.0) -> list[LatLon]:
    """Douglas-Peucker in metres; keeps endpoints. Trims shapes without visible change."""
    if len(points) < 3:
        return list(points)
    origin = points[0]
    xy = [_to_xy(origin, p) for p in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        s, e = stack.pop()
        (x1, y1), (x2, y2) = xy[s], xy[e]
        dx, dy = x2 - x1, y2 - y1
        norm = math.hypot(dx, dy)
        worst, idx = 0.0, -1
        for i in range(s + 1, e):
            x0, y0 = xy[i]
            d = abs(dy * x0 - dx * y0 + x2 * y1 - y2 * x1) / norm if norm else math.hypot(x0 - x1, y0 - y1)
            if d > worst:
                worst, idx = d, i
        if worst > tolerance_m and idx > 0:
            keep[idx] = True
            stack += [(s, idx), (idx, e)]
    return [p for p, k in zip(points, keep) if k]
