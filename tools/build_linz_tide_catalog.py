#!/usr/bin/env python3
"""Build the daily-prediction tide-site catalog from LINZ's published links.

Only sites with a direct CSV for the requested year are included. LINZ offset
sites need reference-port calculations and cannot use this catalog as-is.
The script checks each linked CSV's published coordinates before replacing the
output file. Run with --check to verify an existing catalog without changing it.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit, urlunsplit
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

from bs4 import BeautifulSoup


SOURCE_URL = "https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions/tide-predictions-list-view"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "data" / "linz_daily_tide_stations.json"
USER_AGENT = "CatchCheckNZ-tide-catalog/1.0"
CSV_HOST = "static.charts.linz.govt.nz"
CSV_PREFIX = "/tide-tables/maj-ports/csv/"
COORDINATE = re.compile(r"^\s*(\d{1,3})°(\d{1,2}(?:\.\d+)?)'?\s*([NSEW])\s*$")


def fetch(url: str):
    request = Request(url, headers={"User-Agent": USER_AGENT})
    return urlopen(request, timeout=25)


def fetch_url(raw_url: str) -> str:
    """Encode spaces and non-ASCII characters for urllib, keeping LINZ's URL in output."""
    parsed = urlsplit(raw_url)
    return urlunsplit((parsed.scheme, parsed.netloc, quote(unquote(parsed.path), safe="/"), parsed.query, parsed.fragment))


def parse_coordinate(value: str, latitude: bool) -> float:
    match = COORDINATE.fullmatch(value)
    if not match:
        raise ValueError(f"Unrecognised LINZ coordinate {value!r}")
    degrees, minutes, hemisphere = int(match[1]), float(match[2]), match[3]
    if minutes >= 60 or (latitude and (degrees > 90 or hemisphere not in "NS")) or (
        not latitude and (degrees > 180 or hemisphere not in "EW")
    ):
        raise ValueError(f"Invalid LINZ coordinate {value!r}")
    result = degrees + minutes / 60
    return round(-result if hemisphere in "SW" else result, 6)


def csv_site(csv_url: str, year: int) -> tuple[str, float, float]:
    parsed = urlsplit(csv_url)
    filename = unquote(parsed.path.removeprefix(CSV_PREFIX)).strip()
    suffix = f" {year}.csv"
    if parsed.scheme != "https" or parsed.hostname != CSV_HOST or not parsed.path.startswith(CSV_PREFIX) or (
        not filename.endswith(suffix) or "/" in filename
    ):
        raise ValueError(f"Unexpected LINZ daily-prediction CSV link: {csv_url}")
    csv_name = filename.removesuffix(suffix)
    if not csv_name:
        raise ValueError(f"Missing station name in CSV link: {csv_url}")
    with fetch(fetch_url(csv_url)) as response:
        first_line = response.readline(1024).decode("utf-8-sig").strip()
    row = next(csv.reader(io.StringIO(first_line)))
    if len(row) != 4 or not row[0].strip().isdigit() or not row[1].strip():
        raise ValueError(f"Unexpected LINZ CSV header for {csv_name}: {row!r}")
    return csv_name, parse_coordinate(row[2], latitude=True), parse_coordinate(row[3], latitude=False)


def build_catalog(year: int) -> dict:
    with fetch(SOURCE_URL) as response:
        soup = BeautifulSoup(response.read(), "html.parser")
    table = next((table for table in soup.find_all("table") if [
        th.get_text(" ", strip=True) for th in table.select("thead th")
    ] == ["Location", "Type", "Reference port", "Prediction/Offset files"]), None)
    if table is None:
        raise ValueError("LINZ tide-predictions table was not found")

    stations = []
    skipped_daily = []
    seen_names = set()
    seen_urls = set()
    for tr in table.select("tbody tr"):
        cells = tr.find_all("td", recursive=False)
        if len(cells) != 4 or cells[1].get_text(" ", strip=True) != "Daily Predictions":
            continue
        name = cells[0].get_text(" ", strip=True)
        links = [link.get("href", "") for link in cells[3].find_all("a") if link.get_text(" ", strip=True) == f"{year}.csv"]
        if not links:
            skipped_daily.append(name)
            continue
        if not name or name in seen_names or len(links) != 1 or links[0] in seen_urls:
            raise ValueError(f"Ambiguous or duplicate LINZ tide site: {name!r}")
        url = links[0]
        csv_name, latitude, longitude = csv_site(url, year)
        stations.append({
            "name": name,
            "csv_name": csv_name,
            "latitude": latitude,
            "longitude": longitude,
            f"linz_{year}_csv_url": url,
        })
        seen_names.add(name)
        seen_urls.add(url)

    if len(stations) < 50:
        raise ValueError(f"Only {len(stations)} LINZ daily CSV sites found; expected at least 50")
    if skipped_daily:
        print(f"Skipped Daily Predictions sites without a {year} CSV: {', '.join(skipped_daily)}", file=sys.stderr)
    stations.sort(key=lambda station: station["name"].casefold())
    return {
        "source": SOURCE_URL,
        "coordinate_source": f"first row of each linked LINZ {year} daily-prediction CSV; coordinate precision is as published by LINZ",
        "count": len(stations),
        "stations": stations,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--year", type=int, default=datetime.now(ZoneInfo("Pacific/Auckland")).year,
                        help="LINZ prediction year (default: current year in New Zealand)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Catalog JSON path")
    parser.add_argument("--check", action="store_true", help="Compare with the saved catalog; do not write")
    args = parser.parse_args()
    if args.year < 2020 or args.year > 2100:
        parser.error("--year must be between 2020 and 2100")

    try:
        catalog = build_catalog(args.year)
        content = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
        if args.check:
            if args.output.read_text(encoding="utf-8") != content:
                print(f"Catalog differs from LINZ {args.year} links: {args.output}", file=sys.stderr)
                return 1
            print(f"Verified {catalog['count']} LINZ daily CSV sites in {args.output}")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.output.parent, prefix=".linz-tides-", delete=False) as temporary:
            temporary.write(content)
            temporary_path = Path(temporary.name)
        temporary_path.replace(args.output)
        print(f"Saved {catalog['count']} LINZ daily CSV sites to {args.output}")
        return 0
    except (OSError, UnicodeError, ValueError, csv.Error) as exc:
        print(f"Could not build LINZ tide catalog: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
