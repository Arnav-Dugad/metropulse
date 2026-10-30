"""Generates the app's bundled map styles ("Pulse Light", "Pulse Dark") from OpenFreeMap's Positron.

OpenFreeMap serves tiles, fonts and sprites for free but asks that custom styles be hosted by the
app, so the styles ship inside the APK. Both themes share Positron's layer structure; this script
recolours it, switches every label to English, removes US road shields and turns buildings into
3D extrusions.

    python -I -X utf8 tools/map_styles.py           # uses the cached Positron if present
    python -I -X utf8 tools/map_styles.py --refresh # fetches Positron again
"""
from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

import requests

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
CACHE = TOOLS / "cache" / "openfreemap-positron.json"
OUT = ROOT / "core" / "map" / "src" / "main" / "assets" / "map"
SOURCE_URL = "https://tiles.openfreemap.org/styles/positron"
USER_AGENT = "MetroPulse-tools/1 (+https://github.com/Arnav-Dugad/metropulse)"

# English only for now: OpenMapTiles' English name, then the Latin transliteration, then the local name.
ENGLISH_NAME = ["coalesce", ["get", "name_en"], ["get", "name:en"], ["get", "name:latin"], ["get", "name"]]

DROP_LAYERS = {"highway-shield-us-interstate", "road_shield_us", "highway-shield-non-us"}

PALETTES = {
    "light": {
        "name": "Pulse Light",
        "background": "#F4F3F8",
        "park": "#E5ECE3",
        "wood": "#E0E6DE",
        "residential": "#EEEDF3",
        "water": "#C9D6E3",
        "waterway": "#BCCBDA",
        "ice": "#FAFAFC",
        "building_flat": "#E6E4EE",
        "building": ["#ECEAF2", "#DEDAE9", "#CFC9E0"],
        "building_opacity": 0.92,
        "road_path": "#E9E8EF",
        "road_minor": "#FFFFFF",
        "road_casing": "#DEDBE7",
        "road_major": "#FFFFFF",
        "road_subtle": "#E3E1EA",
        "rail": "#D3D0DC",
        "rail_dash": "#F4F3F8",
        "aeroway": "#E6E4EE",
        "boundary": "#C6C2D2",
        "label": "#827E93",
        "label_strong": "#4A4659",
        "water_label": "#5C7391",
        "halo": "rgba(244,243,248,0.9)",
    },
    "dark": {
        "name": "Pulse Dark",
        "background": "#0D0C13",
        "park": "#111714",
        "wood": "#121614",
        "residential": "#121119",
        "water": "#0A1322",
        "waterway": "#0F1A2C",
        "ice": "#16151D",
        "building_flat": "#17151F",
        "building": ["#1B1925", "#242031", "#2E2940"],
        "building_opacity": 0.94,
        "road_path": "#17151F",
        "road_minor": "#1B1925",
        "road_casing": "#141219",
        "road_major": "#262234",
        "road_subtle": "#1F1C2A",
        "rail": "#2A2638",
        "rail_dash": "#0D0C13",
        "aeroway": "#1B1925",
        "boundary": "#3A3549",
        "label": "#6C6782",
        "label_strong": "#9C97B2",
        "water_label": "#5E7699",
        "halo": "rgba(13,12,19,0.85)",
    },
}


def colour_for(layer_id: str, pal: dict) -> dict[str, object]:
    """Paint colour overrides for one Positron layer."""
    if layer_id == "background":
        return {"background-color": pal["background"]}
    if layer_id == "park":
        return {"fill-color": pal["park"]}
    if layer_id == "water":
        return {"fill-color": pal["water"]}
    if layer_id.startswith("landcover_ice") or layer_id.startswith("landcover_glacier"):
        return {"fill-color": pal["ice"]}
    if layer_id == "landuse_residential":
        return {"fill-color": pal["residential"]}
    if layer_id == "landcover_wood":
        return {"fill-color": pal["wood"]}
    if layer_id == "waterway":
        return {"line-color": pal["waterway"]}
    if layer_id == "building":
        return {"fill-color": pal["building_flat"], "fill-outline-color": pal["building_flat"]}
    if layer_id.startswith("aeroway"):
        key = "fill-color" if layer_id == "aeroway-area" else "line-color"
        return {key: pal["aeroway"]}
    if layer_id in ("road_area_pier",):
        return {"fill-color": pal["background"]}
    if layer_id == "road_pier":
        return {"line-color": pal["background"]}
    if layer_id == "highway_path":
        return {"line-color": pal["road_path"]}
    if layer_id == "highway_minor":
        return {"line-color": pal["road_minor"]}
    if layer_id.endswith("_casing"):
        return {"line-color": pal["road_casing"]}
    if layer_id.endswith("_subtle"):
        return {"line-color": pal["road_subtle"]}
    if layer_id.endswith("_inner"):
        return {"line-color": pal["road_major"]}
    if layer_id.startswith("railway"):
        return {"line-color": pal["rail_dash"] if layer_id.endswith("dashline") else pal["rail"]}
    if layer_id.startswith("boundary"):
        return {"line-color": pal["boundary"]}
    if layer_id.startswith(("waterway_line_label", "water_name")):
        return {"text-color": pal["water_label"], "text-halo-color": pal["halo"]}
    if layer_id.startswith("label_") or layer_id == "airport":
        strong = layer_id in ("label_city", "label_city_capital", "label_town") or layer_id.startswith("label_country")
        return {"text-color": pal["label_strong"] if strong else pal["label"], "text-halo-color": pal["halo"]}
    if layer_id.startswith("highway-name"):
        return {"text-color": pal["label"], "text-halo-color": pal["halo"]}
    return {}


def building_3d(pal: dict) -> dict:
    low, mid, high = pal["building"]
    return {
        "id": "building-3d",
        "type": "fill-extrusion",
        "source": "openmaptiles",
        "source-layer": "building",
        "minzoom": 13,
        "filter": ["!=", ["get", "hide_3d"], True],
        "paint": {
            "fill-extrusion-color": ["interpolate", ["linear"], ["coalesce", ["get", "render_height"], 0], 0, low, 30, mid, 120, high],
            # Buildings rise as the map zooms in rather than popping up.
            "fill-extrusion-height": ["interpolate", ["linear"], ["zoom"], 13, 0, 14, ["coalesce", ["get", "render_height"], 0]],
            "fill-extrusion-base": ["interpolate", ["linear"], ["zoom"], 13, 0, 14, ["coalesce", ["get", "render_min_height"], 0]],
            "fill-extrusion-opacity": pal["building_opacity"],
            "fill-extrusion-vertical-gradient": True,
        },
    }


def make_style(base: dict, theme: str) -> dict:
    pal = PALETTES[theme]
    style = copy.deepcopy(base)
    style["name"] = pal["name"]
    style["sources"] = {"openmaptiles": style["sources"]["openmaptiles"]}  # the shaded-relief raster is unused
    style["metadata"] = {
        "metropulse:generated_by": "tools/map_styles.py",
        "metropulse:based_on": SOURCE_URL,
        "metropulse:attribution": "OpenFreeMap © OpenMapTiles Data from OpenStreetMap",
    }
    layers = []
    for layer in style["layers"]:
        if layer["id"] in DROP_LAYERS or layer.get("source") == "ne2_shaded":
            continue
        layer.setdefault("paint", {}).update(colour_for(layer["id"], pal))
        layout = layer.get("layout", {})
        if "text-field" in layout and layer["id"] not in DROP_LAYERS:
            layout["text-field"] = ENGLISH_NAME
        if layer["id"] == "building":
            layer["maxzoom"] = 14  # flat footprints until the 3D layer has fully risen
        layers.append(layer)
        if layer["id"] == "building":
            layers.append(building_3d(pal))
    style["layers"] = layers
    return style


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--refresh", action="store_true", help="download Positron again")
    args = parser.parse_args()
    if args.refresh or not CACHE.exists():
        r = requests.get(SOURCE_URL, headers={"User-Agent": USER_AGENT}, timeout=60)
        r.raise_for_status()
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        CACHE.write_text(r.text, encoding="utf-8")
    base = json.loads(CACHE.read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    for theme in PALETTES:
        out = OUT / f"pulse-{theme}.json"
        out.write_text(json.dumps(make_style(base, theme), ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print(f"wrote {out.relative_to(ROOT)} ({out.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
