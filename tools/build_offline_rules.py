#!/usr/bin/env python3
"""Publish the species pictures for the server and write the rules copy both apps bundle for use with no signal.

Run from the repository root:

    python3 tools/build_offline_rules.py --pictures build/fish-pictures/webp   # copy pictures, write the catalogue
    python3 tools/build_offline_rules.py --rules                               # also download all 8 areas' rules
    python3 tools/build_offline_rules.py --check                               # confirm the copies agree

The pictures are illustrations drawn for the app from written descriptions (data/species_pictures.json lists each
species, the MPI rule names it covers and its pictures). They go to server/fishial-proxy/public/species/, which the
worker serves as static files, with catalog.json giving each picture's SHA-256 so the apps download only changed ones.

The bundled rules are the service's /v1/rules answers for every MPI area without the page text and HTML, plus the
species catalogue. The apps show them only until their first sync with the service, and say how old they are.
"""

import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
SPECIES = ROOT / "data/species_pictures.json"
PUBLIC = ROOT / "server/fishial-proxy/public/species"
CATALOG = PUBLIC / "catalog.json"
OFFLINE = ROOT / "data/offline_rules.json"
COPIES = (
    ROOT / "app/src/main/assets/offline_rules.json",
    ROOT / "iosApp/CatchCheckNZ/Resources/offline_rules.json",
)
SERVICE = "https://fishing.fishnz.space"
AREAS = ("auckland-kermadec", "central", "challenger", "south-east", "southland", "kaikoura", "chatham-rise", "fiordland")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def catalog(source: dict, pictures: Path) -> dict:
    """The published catalogue: each picture's file, size and hash; `version` changes whenever anything does."""
    species = []
    for item in source["species"]:
        files = []
        for picture in item["pictures"]:
            data = (pictures / f"{picture['id']}.webp").read_bytes()
            files.append({**picture, "file": f"{picture['id']}.webp", "bytes": len(data), "sha256": sha256(data)})
        species.append({**item, "pictures": files})
    body = {"schema_version": 1, "note": source["note"], "species": species}
    return {**body, "version": sha256(json.dumps(body, ensure_ascii=False, sort_keys=True).encode())[:16]}


def publish_pictures(pictures: Path) -> dict:
    source = json.loads(SPECIES.read_text())
    PUBLIC.mkdir(parents=True, exist_ok=True)
    wanted = {picture["id"] for item in source["species"] for picture in item["pictures"]}
    for stale in PUBLIC.glob("*.webp"):
        if stale.stem not in wanted:
            stale.unlink()
    for picture in sorted(wanted):
        shutil.copyfile(pictures / f"{picture}.webp", PUBLIC / f"{picture}.webp")
    result = catalog(source, PUBLIC)
    CATALOG.write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n")
    return result


def download_rules() -> list:
    pages = []
    for area in AREAS:
        request = urllib.request.Request(f"{SERVICE}/v1/rules?area={area}", headers={"Accept": "application/json", "User-Agent": "FishdaysNZ-build/1.0"})
        with urllib.request.urlopen(request, timeout=30) as response:
            page = json.load(response)["rules"][0]
        if page.get("needsReview"):
            print(f"warning: {area} is waiting for review at MPI; the bundle keeps that notice", file=sys.stderr)
        pages.append({key: value for key, value in page.items() if key not in ("page_text", "page_html")})
    return pages


def write_offline(rules: list, species: dict) -> None:
    bundle = {"schema_version": 1, "generated_at": datetime.date.today().isoformat(), "rules": rules, "species": species}
    text = json.dumps(bundle, ensure_ascii=False, separators=(",", ":")) + "\n"
    OFFLINE.write_text(text)
    for copy in COPIES:
        copy.write_text(text)


def check() -> int:
    problems = []
    published = json.loads(CATALOG.read_text())
    source = json.loads(SPECIES.read_text())
    if catalog(source, PUBLIC) != published:
        problems.append(f"{CATALOG.relative_to(ROOT)} doesn't match data/species_pictures.json and the pictures; run --pictures")
    offline = OFFLINE.read_text()
    if json.loads(offline)["species"] != published:
        problems.append(f"{OFFLINE.relative_to(ROOT)} has an older species catalogue; run --rules")
    for copy in COPIES:
        if copy.read_text() != offline:
            problems.append(f"{copy.relative_to(ROOT)} differs from {OFFLINE.relative_to(ROOT)}")
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pictures", type=Path, help="folder of <picture id>.webp files to publish")
    parser.add_argument("--rules", action="store_true", help="download every area's rules from the service")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        return check()
    species = publish_pictures(args.pictures) if args.pictures else json.loads(CATALOG.read_text())
    if args.rules:
        write_offline(download_rules(), species)
    elif OFFLINE.exists():
        write_offline(json.loads(OFFLINE.read_text())["rules"], species)
    print(f"{len(species['species'])} species, {sum(len(s['pictures']) for s in species['species'])} pictures, catalogue {species['version']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
