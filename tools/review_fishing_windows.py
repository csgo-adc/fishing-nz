#!/usr/bin/env python3
"""Freeze and replay a Raglan planning comparison; no catch or safety score.

Capture once (fails if the directory exists):
  python3 tools/review_fishing_windows.py --capture data/analysis/raglan-2026-09-27
Replay offline, verifying every raw response hash:
  python3 tools/review_fishing_windows.py --snapshot data/analysis/raglan-2026-09-27
Replay prints the comparison without changing the saved files. Forecasts describe
the Raglan tide-station coordinate, not a verified wharf or sheltered fishing site.
Only Python's standard library is required.
"""

import argparse
import bisect
import csv
import hashlib
import io
import json
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo


NZ = ZoneInfo("Pacific/Auckland")
UTC = timezone.utc
METHOD_VERSION = "raglan-window-diagnostic-2"
LATITUDE, LONGITUDE = -37.8, 174.883333
WINDOWS = [
    ("2026-09-30", "07:00", "09:00"),
    ("2026-09-30", "11:00", "13:00"),
    ("2026-09-30", "10:45", "13:45"),
    ("2026-10-01", "08:00", "10:00"),
    ("2026-10-01", "12:00", "14:00"),
    ("2026-10-01", "11:15", "14:15"),
    ("2026-10-02", "05:00", "07:00"),
    ("2026-10-02", "13:00", "15:00"),
    ("2026-10-02", "11:30", "14:45"),
    ("2026-10-03", "06:00", "08:00"),
    ("2026-10-03", "14:00", "16:00"),
    ("2026-10-03", "14:15", "16:30"),
]


def utc_now():
    return datetime.now(UTC).isoformat()


def strict_local(local):
    """Resolve a naive NZ clock time, rejecting nonexistent or ambiguous times."""
    if local.tzinfo is not None:
        raise ValueError("Expected a naive local datetime")
    possibilities = set()
    for fold in (0, 1):
        candidate = local.replace(tzinfo=NZ, fold=fold).astimezone(UTC)
        if candidate.astimezone(NZ).replace(tzinfo=None) == local:
            possibilities.add(candidate)
    if len(possibilities) != 1:
        raise ValueError(f"Ambiguous or nonexistent NZ local time: {local}")
    return possibilities.pop()


def local_time(day, clock):
    return strict_local(datetime.fromisoformat(f"{day}T{clock}"))


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def validate_units(payload, expected, solar=False):
    """Reject incompatible units; absent optional fields remain unknown."""
    units = payload.get("hourly_units", {})
    if units.get("time") != "unixtime":
        raise ValueError("Expected forecast timestamps in unixtime")
    for field, unit in expected.items():
        if payload.get("hourly", {}).get(field) and units.get(field) != unit:
            raise ValueError(f"Unexpected unit for {field}: {units.get(field)}; expected {unit}")
    if solar:
        for field in ("sunrise", "sunset"):
            if payload.get("daily", {}).get(field) and payload.get("daily_units", {}).get(field) != "unixtime":
                raise ValueError(f"Expected {field} timestamps in unixtime")


def series(payload, field):
    """This diagnostic consumes only nonnegative scalar forecast fields."""
    hourly = payload["hourly"]
    times = hourly["time"]
    if any(not finite(t) for t in times) or any(b <= a for a, b in zip(times, times[1:])):
        raise ValueError("Forecast timestamps must be finite and strictly increasing")
    values = hourly.get(field, [])
    if values and len(values) != len(times):
        raise ValueError(f"Misaligned forecast array: {field}")
    upper = 100 if field == "precipitation_probability" else math.inf
    return [(float(t), float(v) if finite(v) and 0 <= v <= upper else None)
            for t, v in zip(times, values or [None] * len(times))]


def interpolate(points, at):
    """Interpolate instant fields only; do not bridge missing hours or values."""
    times = [t for t, _ in points]
    index = bisect.bisect_left(times, at)
    if index < len(points) and points[index][0] == at:
        return points[index][1]
    if index == 0 or index == len(points):
        return None
    before, after = points[index - 1], points[index]
    if before[1] is None or after[1] is None or after[0] - before[0] > 3600:
        return None
    fraction = (at - before[0]) / (after[0] - before[0])
    return before[1] + fraction * (after[1] - before[1])


def instant_summary(points, start, end):
    """Retain known extremes even when some of the window cannot be assessed."""
    boundaries = sorted({start, end, *(t for t, _ in points if start < t < end)})
    sampled = [(t, interpolate(points, t)) for t in boundaries]
    known = [value for _, value in sampled if value is not None]
    covered = 0.0
    integral = 0.0
    for (left, a), (right, b) in zip(sampled, sampled[1:]):
        if a is not None and b is not None and right - left <= 3600:
            covered += right - left
            integral += (a + b) / 2 * (right - left)
    complete = math.isclose(covered, end - start)
    return {
        "known_max": max(known) if known else None,
        "known_min": min(known) if known else None,
        "mean": integral / covered if complete and covered else None,
        "coverage_fraction": covered / (end - start),
        "complete": complete,
    }


def preceding_summary(points, start, end):
    """A sample at 09:00 covers 08:00–09:00; partial-bin totals are estimates."""
    if any(b[0] - a[0] < 3600 for a, b in zip(points, points[1:])):
        raise ValueError("Preceding-hour forecast intervals overlap or are unordered")
    covered = 0.0
    weighted = 0.0
    known = []
    contributing = []
    for timestamp, value in points:
        overlap = max(0.0, min(end, timestamp) - max(start, timestamp - 3600))
        if overlap <= 0:
            continue
        contributing.append(timestamp)
        if value is not None:
            covered += overlap
            weighted += value * overlap
            known.append(value)
    complete = math.isclose(covered, end - start)
    return {
        "known_max": max(known) if known else None,
        "known_total_hour_equivalents": weighted / 3600 if known else None,
        "total_hour_equivalents": weighted / 3600 if complete else None,
        "coverage_fraction": covered / (end - start),
        "complete": complete,
        "contributing_timestamps": contributing,
    }


def parse_linz(raw):
    """LINZ's local clock labels already include NZ daylight saving."""
    rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"))))
    if not rows or len(rows[0]) < 2 or [value.strip() for value in rows[0][:2]] != ["180", "Raglan"]:
        raise ValueError("Expected LINZ station 180, Raglan")
    if not any(len(row) >= 2 and row[0].strip() == "Local Std or Daylight Time"
               and row[1].strip() == "Tidal heights in metres." for row in rows[:4]):
        raise ValueError("Unexpected LINZ time or height unit declaration")
    events = []
    for row in rows:
        if len(row) < 6 or not row[0].strip().isdigit():
            continue
        day, month, year = int(row[0]), int(row[2]), int(row[3])
        if year != 2026:
            raise ValueError("Unexpected year in Raglan 2026 tide source")
        for index in range(4, len(row) - 1, 2):
            clock, height = row[index].strip(), row[index + 1].strip()
            if not clock and not height:
                continue
            parsed = datetime.strptime(f"{year}-{month}-{day} {clock}", "%Y-%m-%d %H:%M")
            level = float(height)
            if not math.isfinite(level):
                raise ValueError("Non-finite LINZ height")
            events.append({"at": strict_local(parsed), "height_m_chart_datum": level})
    events.sort(key=lambda event: event["at"])
    if len(events) < 2 or any(b["at"] <= a["at"] for a, b in zip(events, events[1:])):
        raise ValueError("Missing or duplicate LINZ tide events")
    for index, event in enumerate(events):
        level = event["height_m_chart_datum"]
        neighbours = [other["height_m_chart_datum"] for other in events[max(0, index - 1):index + 2]
                      if other is not event]
        if not (all(level > other for other in neighbours) or all(level < other for other in neighbours)):
            raise ValueError("LINZ events do not alternate between high and low water")
        if index + 1 < len(events):
            high = event["height_m_chart_datum"] > events[index + 1]["height_m_chart_datum"]
        else:
            high = event["height_m_chart_datum"] > events[index - 1]["height_m_chart_datum"]
        event["type"] = "High" if high else "Low"
    return events


def overlap_seconds(start, end, intervals):
    clipped = sorted((max(start, a), min(end, b)) for a, b in intervals if a < end and b > start)
    total, previous_end = 0.0, start
    for left, right in clipped:
        left = max(left, previous_end)
        if right > left:
            total += right - left
            previous_end = right
    return total


def preference_overlap(start, end, events):
    intervals = [(event["at"].timestamp() - 150 * 60, event["at"].timestamp() + 30 * 60)
                 for event in events if event["type"] == "High"]
    seconds = overlap_seconds(start, end, intervals)
    return {"minutes": seconds / 60, "fraction": seconds / (end - start)}


def daylight_summary(start, end, daily):
    intervals, known_days = [], set()
    rises, sets = daily.get("sunrise", []), daily.get("sunset", [])
    for rise, setting in zip(rises, sets):
        if finite(rise) and finite(setting) and setting > rise:
            intervals.append((rise, setting))
            known_days.add(datetime.fromtimestamp(rise, NZ).date())
    first_day = datetime.fromtimestamp(start, NZ).date()
    last_day = datetime.fromtimestamp(end - 0.001, NZ).date()
    required = {first_day + timedelta(days=i) for i in range((last_day - first_day).days + 1)}
    complete = required <= known_days
    seconds = overlap_seconds(start, end, intervals)
    return {"minutes": seconds / 60 if complete else None,
            "fraction": seconds / (end - start) if complete else None,
            "complete": complete}


def analyze_window(weather, marine, tides, day, begin, finish):
    start, end = local_time(day, begin), local_time(day, finish)
    if end <= start:
        raise ValueError("This diagnostic requires a same-day positive window")
    a, b = start.timestamp(), end.timestamp()
    wind = instant_summary(series(weather, "wind_speed_10m"), a, b)
    gust = preceding_summary(series(weather, "wind_gusts_10m"), a, b)
    rain = preceding_summary(series(weather, "precipitation"), a, b)
    probability = preceding_summary(series(weather, "precipitation_probability"), a, b)
    wave = instant_summary(series(marine, "wave_height"), a, b)
    period = instant_summary(series(marine, "wave_period"), a, b)
    daylight = daylight_summary(a, b, weather.get("daily", {}))
    fields = {"wind_kmh": wind, "gust_kmh": gust, "rain_mm": rain,
              "hourly_rain_probability_percent": probability, "wave_height_m": wave,
              "wave_period_s": period, "daylight": daylight}
    day_events = [{"local_time": event["at"].astimezone(NZ).isoformat(),
                   "type": event["type"], "height_m_chart_datum": event["height_m_chart_datum"]}
                  for event in tides if event["at"].astimezone(NZ).date().isoformat() == day]
    return {
        "start_local": start.astimezone(NZ).isoformat(), "end_local": end.astimezone(NZ).isoformat(),
        "duration_minutes": (b - a) / 60, "facts": fields,
        "incomplete_fields": [name for name, value in fields.items() if not value["complete"]],
        "official_tide_events": day_events,
        "declared_preference_overlap": preference_overlap(a, b, tides),
        "known_wave_meets_legacy_3m_exclusion": wave["known_max"] is not None and wave["known_max"] >= 3,
        "site_suitability": "unassessed: tide-station coordinate; exact site/exposure/access unknown",
        "official_warnings": "unassessed: no warnings service queried",
        "catch_potential": "unassessed: no validated species/site catch model",
    }


def source_urls():
    common = {"latitude": LATITUDE, "longitude": LONGITUDE, "forecast_days": 8,
              "timezone": "Pacific/Auckland", "timeformat": "unixtime"}
    weather = common | {
        "hourly": "wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,precipitation_probability,weather_code",
        "daily": "sunrise,sunset", "cell_selection": "land",
        "wind_speed_unit": "kmh", "precipitation_unit": "mm",
    }
    marine = common | {
        "hourly": "wave_height,wave_period,wave_direction,swell_wave_height,swell_wave_period,swell_wave_direction,sea_level_height_msl",
        "cell_selection": "sea", "length_unit": "metric",
    }
    return {
        "weather": ("weather.json", "https://api.open-meteo.com/v1/forecast?" + urlencode(weather)),
        "marine": ("marine.json", "https://marine-api.open-meteo.com/v1/marine?" + urlencode(marine)),
        "tides": ("raglan-2026.csv", "https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Raglan%202026.csv"),
    }


def capture(directory):
    directory.mkdir(parents=True, exist_ok=False)
    manifest = {"method_version": METHOD_VERSION, "capture_started_utc": utc_now(),
                "requested_coordinate": {"latitude": LATITUDE, "longitude": LONGITUDE},
                "coordinate_role": "Raglan LINZ tide station; not a verified fishing access point",
                "sources": {}}
    for name, (filename, url) in source_urls().items():
        request = Request(url, headers={"User-Agent": "CatchCheckNZ-window-diagnostic/1.0"})
        with urlopen(request, timeout=60) as response:
            raw = response.read()
            metadata = {"file": filename, "url": url, "retrieved_at_utc": utc_now(),
                        "sha256": hashlib.sha256(raw).hexdigest(), "http_status": response.status,
                        "content_type": response.headers.get("Content-Type"),
                        "http_last_modified": response.headers.get("Last-Modified"),
                        "http_date": response.headers.get("Date")}
        with (directory / filename).open("xb") as destination:
            destination.write(raw)
        if name in ("weather", "marine"):
            payload = json.loads(raw)
            metadata["returned_grid_coordinate"] = {key: payload.get(key) for key in ("latitude", "longitude", "elevation")}
            metadata["units"] = {"hourly": payload.get("hourly_units"), "daily": payload.get("daily_units")}
            metadata["timezone"] = payload.get("timezone")
            metadata["forecast_issue_time"] = "not provided by this response; retrieval time is not model issue time"
            metadata["model_selection"] = "provider default/best match; not identified per value in response"
        else:
            metadata["units"] = {"height": "metres above Chart Datum", "time": "NZ local time including published daylight saving"}
            metadata["station"] = "Raglan"
        manifest["sources"][name] = metadata
    manifest["capture_completed_utc"] = utc_now()
    with (directory / "manifest.json").open("x") as destination:
        json.dump(manifest, destination, indent=2)
        destination.write("\n")
    report = replay(directory)
    with (directory / "comparison.json").open("x") as destination:
        json.dump(report, destination, indent=2)
        destination.write("\n")
    with (directory / "comparison.md").open("x") as destination:
        destination.write(markdown(report))
    return report


def replay(directory):
    manifest = json.loads((directory / "manifest.json").read_text())
    raw = {}
    for name, source in manifest["sources"].items():
        filename = source["file"]
        if Path(filename).name != filename:
            raise ValueError("Snapshot source must be a local filename")
        raw[name] = (directory / filename).read_bytes()
        if hashlib.sha256(raw[name]).hexdigest() != source["sha256"]:
            raise ValueError(f"Snapshot integrity failure: {name}")
    weather, marine = json.loads(raw["weather"]), json.loads(raw["marine"])
    validate_units(weather, {"wind_speed_10m": "km/h", "wind_gusts_10m": "km/h",
                             "precipitation": "mm", "precipitation_probability": "%"}, solar=True)
    validate_units(marine, {"wave_height": "m", "wave_period": "s"})
    tides = parse_linz(raw["tides"])
    return {"method_version": METHOD_VERSION, "snapshot": manifest,
            "preference": "Illustrative planning preference, inferred from the user's interest in late incoming tide: high tide minus 150 minutes through high tide plus 30 minutes. Not a confirmed trip setting or a catch, current or safety model.",
            "windows": [analyze_window(weather, marine, tides, *window) for window in WINDOWS]}


def display(value, digits=1):
    return "unknown" if value is None else f"{value:.{digits}f}"


def markdown(report):
    manifest = report["snapshot"]
    lines = ["# Raglan window comparison", "",
             f"Frozen retrieval: {manifest['capture_started_utc']} through {manifest['capture_completed_utc']}.", "",
             f"Analysis: {report['method_version']}. Original capture: {manifest['method_version']}; raw responses unchanged.", "",
             "These are forecasts for the Raglan tide-station coordinate (-37.8, 174.883333). Exact fishing site, exposure, access and official warnings are **unassessed**. No window is certified safe or predicted to catch more fish.", "",
             report["preference"], "",
             "Two-hour rows compare the app's early session with a later core. Longer rows reproduce the pasted itinerary for inspection; different durations are not ranked against each other.", "",
             "| NZ date / window | Minutes | Wind max km/h | Gust max km/h | Rain mm estimate | Max hourly rain chance | Offshore Hs max m | Mean wave period max s | Daylight | Preferred tide overlap | Compared-field coverage |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    for item in report["windows"]:
        start, end = datetime.fromisoformat(item["start_local"]), datetime.fromisoformat(item["end_local"])
        facts, preference = item["facts"], item["declared_preference_overlap"]
        sunshine = facts["daylight"]["fraction"]
        cells = [f"{start:%a %d %b %H:%M}–{end:%H:%M}", f"{item['duration_minutes']:.0f}",
                 display(facts["wind_kmh"]["known_max"]), display(facts["gust_kmh"]["known_max"]),
                 display(facts["rain_mm"]["total_hour_equivalents"], 2),
                 display(facts["hourly_rain_probability_percent"]["known_max"], 0) + "%",
                 display(facts["wave_height_m"]["known_max"], 2), display(facts["wave_period_s"]["known_max"]),
                 display(None if sunshine is None else sunshine * 100, 0) + "%",
                 f"{preference['minutes']:.0f} min ({preference['fraction'] * 100:.0f}%)",
                 "Complete" if not item["incomplete_fields"] else "Missing: " + ", ".join(item["incomplete_fields"])]
        lines.append("| " + " | ".join(cells) + " |")
    lines += ["", "Hs is offshore significant wave height. The table gives its highest forecast value during the session, not the largest individual wave or wave height at a wharf. Mean wave period is not peak swell period. Complete coverage applies only to the compared fields, not to warnings, access or site suitability.", "",
              "Maximum values retain known samples even when coverage is incomplete. Missing values never erase a known elevated wave. Rain chance is the maximum hourly probability, not the chance of rain at any point across the whole trip. Rain totals for fractional hours allocate hourly totals by time overlap; sub-hour rain timing is unknown.", "",
              "Wind and waves include the session endpoints, interpolated between available hourly values. Gusts, rainfall and rain probabilities refer to the preceding hour: a 07:00–09:00 session uses entries labelled 08:00 and 09:00. Daylight covers the full fishing session; access/setup/return times are not supplied and remain unassessed. Interpolation does not add forecast precision.", "", "## Official Raglan tide events", "",
              "| Date | Events, NZ local time |", "|---|---|"]
    seen = set()
    for item in report["windows"]:
        day = item["start_local"][:10]
        if day in seen:
            continue
        seen.add(day)
        events = "; ".join(f"{event['type']} {datetime.fromisoformat(event['local_time']):%H:%M} ({event['height_m_chart_datum']:.1f} m)"
                           for event in item["official_tide_events"])
        lines.append(f"| {day} | {events} |")
    lines += ["", "LINZ event times already include daylight saving. Heights use Chart Datum. Interpolated offshore sea level is retained in the raw marine snapshot for inspection but is not used for tide preferences or local current estimates.", "", "## Sources and reproducibility", ""]
    for name, source in manifest["sources"].items():
        lines.append(f"- [{name}]({source['url']}), retrieved {source['retrieved_at_utc']}; raw file `{source['file']}`; SHA-256 `{source['sha256']}`.")
        if "returned_grid_coordinate" in source:
            lines.append(f"  Returned grid coordinate: {json.dumps(source['returned_grid_coordinate'])}. Model issue time is not provided; retrieval time is recorded separately.")
    lines += ["", "All units, raw responses and response metadata are frozen in this directory. Offline replay validates hashes before analysis. The method produces an evidence comparison rather than a combined score or recommended winner.", "",
              "API interval definitions: [weather](https://open-meteo.com/en/docs), [marine](https://open-meteo.com/en/docs/marine-weather-api).", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--capture", type=Path)
    mode.add_argument("--snapshot", type=Path)
    args = parser.parse_args()
    report = capture(args.capture) if args.capture else replay(args.snapshot)
    print(markdown(report))


if __name__ == "__main__":
    main()
