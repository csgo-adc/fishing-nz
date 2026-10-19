#!/usr/bin/env python3
"""Download New Zealand boat ramps from OpenStreetMap and write the list both apps bundle.

Run from the repository root:

    python3 tools/build_boat_ramps.py          # download, filter and write
    python3 tools/build_boat_ramps.py --from overpass.json  # use a saved Overpass reply instead of downloading
    python3 tools/build_boat_ramps.py --check  # confirm the app copies match data/boat_ramps.json

Ramps are OpenStreetMap `leisure=slipway` features. Private, no-access, permit-only and customer-only ramps, ship
slipways on rails and abandoned or disused ones are left out, and points within 30 m are merged. The data is
© OpenStreetMap contributors and available under the Open Database License (ODbL 1.0): the apps credit OpenStreetMap
wherever ramps are shown, and data/boat_ramps.json is this derived database, offered under the same licence.
"""

import argparse
import datetime
import json
import math
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "data/boat_ramps.json"
COPIES = (
    ROOT / "app/src/main/assets/boat_ramps.json",
    ROOT / "iosApp/CatchCheckNZ/Resources/boat_ramps.json",
)
OVERPASS = "https://overpass-api.de/api/interpreter"
QUERY = ('[out:json][timeout:150];area["ISO3166-1"="NZ"][admin_level=2]->.nz;'
         '(node["leisure"="slipway"](area.nz);way["leisure"="slipway"](area.nz););out center tags;')
EXCLUDED_ACCESS = {"private", "no", "permit", "customers"}


def fetch() -> list[dict]:
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    try:
        request = urllib.request.Request(OVERPASS, data=body, headers={"User-Agent": "Fishdays-NZ boat ramp build"})
        with urllib.request.urlopen(request, timeout=240) as response:
            payload = json.load(response)
    except urllib.error.URLError:
        # Some Python builds cannot negotiate TLS with the Overpass servers; curl can.
        result = subprocess.run(["curl", "-sf", "--max-time", "240", "-A", "Fishdays-NZ boat ramp build", OVERPASS, "--data-binary", "@-"],
                                input=body, capture_output=True, check=True)
        payload = json.loads(result.stdout)
    return elements_of(payload)


def elements_of(payload: dict) -> list[dict]:
    if payload.get("remark"):
        raise ValueError(f"Overpass: {payload['remark']}")
    return payload["elements"]


def usable(tags: dict) -> bool:
    if tags.get("access") in EXCLUDED_ACCESS or tags.get("boat") == "no":
        return False
    if "railway" in tags or tags.get("abandoned") == "yes" or tags.get("disused") == "yes":
        return False
    return not any(key.startswith(("abandoned:", "disused:")) for key in tags)


def metres(a: tuple, b: tuple) -> float:
    k = math.cos(math.radians(a[0]))
    return math.hypot((a[0] - b[0]) * 110_570, (a[1] - b[1]) * 111_320 * k)


def build(elements: list[dict]) -> dict:
    ramps: list[dict] = []
    for element in elements:
        tags = element.get("tags", {})
        point = (element.get("lat"), element.get("lon")) if element["type"] == "node" else (element.get("center", {}).get("lat"), element.get("center", {}).get("lon"))
        if point[0] is None or not usable(tags):
            continue
        lat, lon = round(point[0], 5), round(point[1], 5)
        if not (-48.5 <= lat <= -33.5 and 165 <= lon <= 179.5):  # main islands and Rakiura; leaves out outlying islands
            continue
        name = " ".join(str(tags.get("name") or "").split())[:80] or None
        nearby = next((ramp for ramp in ramps if metres((lat, lon), (ramp["lat"], ramp["lon"])) < 30), None)
        if nearby:
            nearby["name"] = nearby["name"] or name
            continue
        ramps.append({"lat": lat, "lon": lon, "name": name, "fee": tags.get("fee") if tags.get("fee") in ("yes", "no") else None,
                      "osm": f"{element['type']}/{element['id']}"})
    ramps.sort(key=lambda ramp: (ramp["lat"], ramp["lon"]))
    return {
        "schema_version": 1,
        "retrieved_on": datetime.date.today().isoformat(),
        "attribution": "© OpenStreetMap contributors",
        "licence": "Open Database License (ODbL) 1.0, https://opendatacommons.org/licenses/odbl/1-0/",
        "note": "OpenStreetMap leisure=slipway features. Public access, condition and tide limits are not confirmed.",
        "ramps": ramps,
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
        print(f"{len(json.loads(expected)['ramps'])} boat ramps — copies match")
        return 0
    elements = elements_of(json.loads(arguments.source.read_text())) if arguments.source else fetch()
    text = json.dumps(build(elements), ensure_ascii=False, separators=(",", ":")) + "\n"
    for path in (OUTPUT, *COPIES):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    catalogue = json.loads(text)
    print(f"Wrote {len(catalogue['ramps'])} boat ramps ({sum(1 for r in catalogue['ramps'] if r['name'])} named), {len(text) // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
