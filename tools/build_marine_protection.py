#!/usr/bin/env python3
"""Download DOC marine protection boundaries and write the map layer both apps bundle.

Run from the repository root:

    python3 tools/build_marine_protection.py          # download, simplify and write
    python3 tools/build_marine_protection.py --check  # confirm the app copies match data/marine_protection.json

Writes data/marine_protection.json and identical copies in the Android assets and iPhone resources. Boundaries are
simplified to about 30 m (0.0003 degrees), which suits a map overlay and a "you may be inside" warning but is not a
legal boundary. Each area is a GeoJSON-style multipolygon: a list of polygons, each an outer ring followed by its holes
(islands outside the area), counterclockwise outside and clockwise holes as RFC 7946 asks. Apps test points with the
even-odd rule over every ring.
"""

import argparse
import datetime
import json
import math
from pathlib import Path
import sys
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "data/marine_protection.json"
COPIES = (
    ROOT / "app/src/main/assets/marine_protection.json",
    ROOT / "iosApp/CatchCheckNZ/Resources/marine_protection.json",
)
SERVICE = "https://services1.arcgis.com/3JjYDyG3oajxU6HO/arcgis/rest/services"
LAYERS = (
    # (service, name field, how to classify a feature)
    ("DOC_Marine_Reserves", "Name", lambda attributes: "reserve"),
    ("SE_marine_reserves_type1_CURRENT", "NAME", lambda attributes: "reserve"),
    ("Hauraki_Gulf_Tikapa_Moana_Marine_Protection_Act", "Name", lambda attributes: {
        "S13_MARINE_RESERVES_DECLARED": "reserve",
        "S20_HIGH_PROTECTION_AREAS_DECLARED": "high_protection",
        "S16_SEAFLOOR_PROTECTION_AREAS_DECLARED": "seafloor_protection",
    }.get(attributes.get("Section"))),
)
OFFSET_DEGREES = 0.0003
ATTRIBUTION = ("Marine protected area boundaries: Crown Copyright, Department of Conservation Te Papa Atawhai. "
               "Marine reserves layer licensed under CC BY 4.0.")


def fetch(service: str) -> dict:
    query = urllib.parse.urlencode({
        "where": "1=1", "outFields": "*", "outSR": 4326, "maxAllowableOffset": OFFSET_DEGREES,
        "geometryPrecision": 5, "f": "json",
    })
    with urllib.request.urlopen(f"{SERVICE}/{service}/FeatureServer/0/query?{query}", timeout=120) as response:
        payload = json.load(response)
    if "error" in payload or payload.get("exceededTransferLimit"):
        raise ValueError(f"{service}: incomplete response")
    return payload


def signed_area(ring: list) -> float:
    """Positive for counterclockwise rings in longitude/latitude."""
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:])) / 2


def contains(ring: list, point: list) -> bool:
    inside = False
    x, y = point
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def polygons(rings: list) -> list:
    """Esri rings (clockwise outside, counterclockwise holes) as outer-then-holes polygons in RFC 7946 order."""
    outers = [ring for ring in rings if signed_area(ring) < 0]
    holes = [ring for ring in rings if signed_area(ring) > 0]
    result = [[list(reversed(outer))] for outer in outers]
    for hole in holes:
        # The smallest outer ring around the hole; a hole that simplification left outside every ring is dropped.
        owners = [index for index, outer in enumerate(outers) if contains(outer, hole[0])]
        if owners:
            owner = min(owners, key=lambda index: abs(signed_area(outers[index])))
            result[owner].append(list(reversed(hole)))
    return result


def build() -> dict:
    areas: dict[str, dict] = {}
    for service, name_field, kind_of in LAYERS:
        for feature in fetch(service)["features"]:
            attributes = feature["attributes"]
            kind = kind_of(attributes)
            name = " ".join(str(attributes.get(name_field) or "").split())
            if kind is None or not name:
                continue
            if attributes.get("End_Date"):
                continue
            rings = [
                [[round(x, 5), round(y, 5)] for x, y in ring]
                for ring in feature["geometry"]["rings"]
                if len(ring) >= 4 and all(math.isfinite(x) and math.isfinite(y) for x, y in ring)
            ]
            if not rings:
                continue
            # The south-east layer repeats five reserves already in DOC's national layer; keep the first copy.
            key = name.casefold()
            if key in areas:
                continue
            shapes = polygons(rings)
            if not shapes:
                continue
            xs = [point[0] for ring in rings for point in ring]
            ys = [point[1] for ring in rings for point in ring]
            areas[key] = {"name": name, "kind": kind, "bbox": [min(xs), min(ys), max(xs), max(ys)], "polygons": shapes}
    result = sorted(areas.values(), key=lambda area: (area["kind"], area["name"]))
    return {
        "schema_version": 1,
        "retrieved_on": datetime.date.today().isoformat(),
        "attribution": ATTRIBUTION,
        "note": "Simplified to about 30 m for a map overlay. Not for navigation or legal interpretation; "
                "check DOC and MPI for official boundaries, rules and temporary closures.",
        "sources": [f"{SERVICE}/{service}/FeatureServer/0" for service, _, _ in LAYERS],
        "areas": result,
    }


def serialise(catalogue: dict) -> str:
    return json.dumps(catalogue, ensure_ascii=False, separators=(",", ":")) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="only confirm the app copies match the catalogue")
    arguments = parser.parse_args()
    if arguments.check:
        expected = OUTPUT.read_text()
        stale = [str(path.relative_to(ROOT)) for path in COPIES if not path.exists() or path.read_text() != expected]
        if stale:
            print("Out of date: " + ", ".join(stale), file=sys.stderr)
            return 1
        print(f"{len(json.loads(expected)['areas'])} marine protection areas — copies match")
        return 0
    text = serialise(build())
    for path in (OUTPUT, *COPIES):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    catalogue = json.loads(text)
    counts = {kind: sum(area["kind"] == kind for area in catalogue["areas"]) for kind in ("reserve", "high_protection", "seafloor_protection")}
    print(f"Wrote {len(catalogue['areas'])} areas {counts}, {len(text) // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
