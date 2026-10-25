#!/usr/bin/env python3
"""Download New Zealand wharves, breakwaters and groynes from OpenStreetMap and write the list both apps bundle.

Run from the repository root:

    python3 tools/build_shore_structures.py          # download (about 10 minutes), filter and write
    python3 tools/build_shore_structures.py --from overpass.json  # use a saved Overpass reply instead of downloading
    python3 tools/build_shore_structures.py --check  # confirm the app copies match data/shore_structures.json

Structures are OpenStreetMap `man_made=pier`, `breakwater` and `groyne` ways within 100 m of the sea coastline. Floating
pontoons, private, no-access and `fishing=no` structures, abandoned or disused ones, unnamed jetties under 80 m (mostly
private bach jetties), marina arms, industrial berths, anything inside a port or marina area or a no-fishing marine
area, and a few mislabelled features are left out. Pieces of one structure within 40 m are merged. The data is
© OpenStreetMap contributors and available under the Open Database License (ODbL 1.0): the apps credit OpenStreetMap
wherever structures are shown, and data/shore_structures.json is this derived database, offered under the same licence.
"""

import argparse
import datetime
import json
import math
from pathlib import Path
import re
import subprocess
import sys
from typing import Optional
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "data/shore_structures.json"
COPIES = (
    ROOT / "app/src/main/assets/shore_structures.json",
    ROOT / "iosApp/CatchCheckNZ/Resources/shore_structures.json",
)
PROTECTION = ROOT / "data/marine_protection.json"
OVERPASS = "https://overpass-api.de/api/interpreter"
# The coastline filter is slow on the Overpass servers (8 to 10 minutes) but leaves out thousands of lake and river jetties.
QUERY = ('[out:json][timeout:900];area["ISO3166-1"="NZ"][admin_level=2]->.nz;way["natural"="coastline"](area.nz)->.coast;'
         'way["man_made"~"^(pier|breakwater|groyne)$"](area.nz)(around.coast:100);out geom tags;'
         '(nwr["leisure"="marina"](area.nz);nwr["landuse"="port"](area.nz);nwr["industrial"="port"](area.nz););out geom tags;')
KINDS = {"pier": "wharf", "breakwater": "breakwater", "groyne": "groyne"}
EXCLUDED_ACCESS = {"private", "no", "permit", "customers"}
INDUSTRIAL = re.compile(r"\b(tanker|oil|fuel|cement|container|log|berths?|interisland|ferry terminal)\b", re.I)
MARINA_ARM = re.compile(r"^([A-Z]|[A-Z] Pier|Pier [A-Z])$")
NO_FISHING = {"reserve", "high_protection"}
# Features tagged as piers that are a fuel terminal, fishing company wharves, marina or tour-only landings, a building, a
# boardwalk or a ramp, checked on 25 October 2026.
EXCLUDED_WAYS = {
    91965558,    # Seaview Wharf, Wellington fuel terminal
    779009129,   # Lighter Basin, Viaduct Harbour marina
    767482483,   # Viaduct Port
    886811485,   # Waitemata Plaza West Marina
    874568293,   # The Lookout, Viaduct Harbour
    91967099,    # Sanford Wharf, Tauranga fishing company
    659861135,   # Ice Wharf, Tauranga fishing company
    770781776,   # Hataitai Beach toilets and changing rooms
    91965205,    # Mahaua Lodge, private landing
    91964833,    # Seaplane Ramp
    40795417,    # Deep Cove, Doubtful Sound tour landing
    1367309453,  # Boardwalk, Tauranga
}
MIN_LENGTH = 20
MIN_UNNAMED_WHARF = 80
MERGE_METRES = 40


def fetch() -> list[dict]:
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    try:
        request = urllib.request.Request(OVERPASS, data=body, headers={"User-Agent": "Fishdays-NZ shore structure build"})
        with urllib.request.urlopen(request, timeout=960) as response:
            payload = json.load(response)
    except urllib.error.URLError:
        # Some Python builds cannot negotiate TLS with the Overpass servers; curl can.
        result = subprocess.run(["curl", "-sf", "--max-time", "960", "-A", "Fishdays-NZ shore structure build", OVERPASS, "--data-binary", "@-"],
                                input=body, capture_output=True, check=True)
        payload = json.loads(result.stdout)
    return elements_of(payload)


def elements_of(payload: dict) -> list[dict]:
    if payload.get("remark"):
        raise ValueError(f"Overpass: {payload['remark']}")
    return payload["elements"]


def metres(a: tuple, b: tuple) -> float:
    k = math.cos(math.radians(a[0]))
    return math.hypot((a[0] - b[0]) * 110_570, (a[1] - b[1]) * 111_320 * k)


def inside(lat: float, lon: float, edges: list[tuple]) -> bool:
    """Even-odd test over (lon, lat) edges; holes and unordered multipolygon members work without joining rings."""
    crossings = 0
    for (x1, y1), (x2, y2) in edges:
        if (y1 > lat) != (y2 > lat) and lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1:
            crossings += 1
    return crossings % 2 == 1


def edges_of(rings: list[list]) -> list[tuple]:
    return [(ring[i], ring[i + 1]) for ring in rings for i in range(len(ring) - 1)]


def area_edges(element: dict) -> list[tuple]:
    """The outline edges of a port or marina way or multipolygon from `out geom`."""
    if element["type"] == "way":
        rings = [[(g["lon"], g["lat"]) for g in element.get("geometry", []) if g]]
    else:
        rings = [[(g["lon"], g["lat"]) for g in member.get("geometry") or [] if g] for member in element.get("members", [])]
    return edges_of([ring for ring in rings if len(ring) > 1])


def no_fishing_areas() -> list[tuple]:
    areas = json.loads(PROTECTION.read_text())["areas"]
    return [(area["bbox"], [edges_of(polygon) for polygon in area["polygons"]]) for area in areas if area["kind"] in NO_FISHING]


def usable(tags: dict) -> bool:
    if tags.get("access") in EXCLUDED_ACCESS or tags.get("fishing") == "no" or tags.get("floating") == "yes":
        return False
    if tags.get("mooring") in {"commercial", "private", "cruise"}:
        return False
    if tags.get("abandoned") == "yes" or tags.get("disused") == "yes" or any(key.startswith(("abandoned:", "disused:")) for key in tags):
        return False
    name = " ".join(str(tags.get("name") or "").split())
    return not INDUSTRIAL.search(name) and not MARINA_ARM.match(name)


def position(geometry: list[tuple]) -> tuple[tuple, float]:
    """A point on the structure and its length: the middle of a line, or the outline vertex nearest the centre."""
    segments = [metres(geometry[i], geometry[i + 1]) for i in range(len(geometry) - 1)]
    if geometry[0] == geometry[-1]:
        centre = (sum(p[0] for p in geometry) / len(geometry), sum(p[1] for p in geometry) / len(geometry))
        return min(geometry, key=lambda p: metres(p, centre)), sum(segments) / 2
    half, run = sum(segments) / 2, 0.0
    for i, length in enumerate(segments):
        if run + length >= half:
            t = (half - run) / length if length else 0
            a, b = geometry[i], geometry[i + 1]
            return (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])), sum(segments)
        run += length
    return geometry[-1], sum(segments)


def build(elements: list[dict], protected: Optional[list] = None) -> dict:
    protected = no_fishing_areas() if protected is None else protected
    excluded_areas = [area_edges(e) for e in elements if e.get("tags", {}).get("man_made") not in KINDS]
    candidates: list[dict] = []
    for element in elements:
        tags = element.get("tags", {})
        kind = KINDS.get(tags.get("man_made"))
        geometry = [(g["lat"], g["lon"]) for g in element.get("geometry", []) if g]
        if element["type"] != "way" or kind is None or element["id"] in EXCLUDED_WAYS or len(geometry) < 2 or not usable(tags):
            continue
        (lat, lon), length = position(geometry)
        name = " ".join(str(tags.get("name") or "").split())[:80] or None
        if length < MIN_LENGTH or (kind == "wharf" and name is None and length < MIN_UNNAMED_WHARF):
            continue
        if not (-48.5 <= lat <= -33.5 and 165 <= lon <= 179.5):  # main islands and Rakiura; leaves out outlying islands
            continue
        if any(inside(lat, lon, edges) for edges in excluded_areas):
            continue
        if any(w <= lon <= e and s <= lat <= n and any(inside(lat, lon, edges) for edges in polygons) for (w, s, e, n), polygons in protected):
            continue
        candidates.append({"lat": round(lat, 5), "lon": round(lon, 5), "name": name, "kind": kind, "osm": f"way/{element['id']}", "length": length})
    # Keep the named and longest piece where an outline and a centreline, or several segments, describe one structure.
    candidates.sort(key=lambda item: (item["name"] is None, -item["length"]))
    structures: list[dict] = []
    for item in candidates:
        if any(kept["kind"] == item["kind"] and metres((kept["lat"], kept["lon"]), (item["lat"], item["lon"])) < MERGE_METRES for kept in structures):
            continue
        structures.append({key: value for key, value in item.items() if key != "length"})
    structures.sort(key=lambda item: (item["lat"], item["lon"]))
    return {
        "schema_version": 1,
        "retrieved_on": datetime.date.today().isoformat(),
        "attribution": "© OpenStreetMap contributors",
        "licence": "Open Database License (ODbL) 1.0, https://opendatacommons.org/licenses/odbl/1-0/",
        "note": "OpenStreetMap man_made=pier, breakwater and groyne ways on the sea coast. Public access and fishing rules are not confirmed.",
        "structures": structures,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="only confirm the app copies match the list")
    parser.add_argument("--from", dest="source", type=Path, help="a saved Overpass JSON reply to use instead of downloading")
    arguments = parser.parse_args()
    if arguments.check:
        expected = OUTPUT.read_text()
        stale = [str(path.relative_to(ROOT)) for path in COPIES if not path.exists() or path.read_text() != expected]
        if stale:
            print("Out of date: " + ", ".join(stale), file=sys.stderr)
            return 1
        print(f"{len(json.loads(expected)['structures'])} wharves, breakwaters and groynes — copies match")
        return 0
    elements = elements_of(json.loads(arguments.source.read_text())) if arguments.source else fetch()
    text = json.dumps(build(elements), ensure_ascii=False, separators=(",", ":")) + "\n"
    for path in (OUTPUT, *COPIES):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    structures = json.loads(text)["structures"]
    counts = {kind: sum(1 for item in structures if item["kind"] == kind) for kind in KINDS.values()}
    print(f"Wrote {len(structures)} structures ({counts['wharf']} wharves, {counts['breakwater']} breakwaters, {counts['groyne']} groynes; "
          f"{sum(1 for item in structures if item['name'])} named), {len(text) // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
