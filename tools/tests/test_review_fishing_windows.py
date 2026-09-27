"""Regression tests for the diagnostic's source and interval semantics."""

import sys
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from review_fishing_windows import (
    daylight_summary, instant_summary, local_time, parse_linz,
    preceding_summary, preference_overlap, replay, series, strict_local, validate_units,
)

LINZ_HEADER = (b"180,Raglan,37 degrees south,174 degrees east\n"
               b"Local Std or Daylight Time,Tidal heights in metres.\n")


class WindowDiagnosticTest(unittest.TestCase):
    def test_preceding_hour_uses_end_labels_and_excludes_adjacent_bins(self):
        result = preceding_summary([(6 * 3600, 99), (7 * 3600, 99),
                                    (8 * 3600, 2), (9 * 3600, 4), (10 * 3600, 99)],
                                   7 * 3600, 9 * 3600)
        self.assertEqual(result["known_max"], 4)
        self.assertEqual(result["total_hour_equivalents"], 6)
        self.assertEqual(result["contributing_timestamps"], [8 * 3600, 9 * 3600])
        self.assertTrue(result["complete"])

    def test_fractional_bin_rain_allocated_by_overlap(self):
        result = preceding_summary([(11 * 3600, 4), (12 * 3600, 2)],
                                   10.75 * 3600, 11.25 * 3600)
        self.assertEqual(result["total_hour_equivalents"], 1.5)

    def test_overlapping_hourly_intervals_rejected(self):
        with self.assertRaisesRegex(ValueError, "intervals overlap"):
            preceding_summary([(3600, 1), (5400, 1)], 0, 5400)

    def test_instant_includes_end_and_fractional_interpolation(self):
        result = instant_summary([(7 * 3600, 0), (8 * 3600, 2), (9 * 3600, 4)],
                                 7.5 * 3600, 9 * 3600)
        self.assertEqual(result["known_max"], 4)
        self.assertEqual(result["known_min"], 1)
        self.assertEqual(result["mean"], 2.5)
        self.assertTrue(result["complete"])

    def test_known_high_wave_survives_missing_sample(self):
        result = instant_summary([(7 * 3600, 3.5), (8 * 3600, None), (9 * 3600, 2)],
                                 7 * 3600, 9 * 3600)
        self.assertEqual(result["known_max"], 3.5)
        self.assertFalse(result["complete"])
        self.assertIsNone(result["mean"])

    def test_no_interpolation_across_absent_hour(self):
        result = instant_summary([(7 * 3600, 1), (9 * 3600, 2)], 7 * 3600, 9 * 3600)
        self.assertEqual(result["coverage_fraction"], 0)

    def test_partial_rain_does_not_become_complete_total(self):
        result = preceding_summary([(8 * 3600, 1), (9 * 3600, None)], 7 * 3600, 9 * 3600)
        self.assertEqual(result["known_total_hour_equivalents"], 1)
        self.assertIsNone(result["total_hour_equivalents"])
        self.assertEqual(result["coverage_fraction"], 0.5)

    def test_full_window_daylight_not_midpoint(self):
        start = local_time("2026-09-30", "06:00").timestamp()
        end = local_time("2026-09-30", "08:00").timestamp()
        sunrise = local_time("2026-09-30", "07:00").timestamp()
        sunset = local_time("2026-09-30", "19:00").timestamp()
        result = daylight_summary(start, end, {"sunrise": [sunrise], "sunset": [sunset]})
        self.assertEqual(result["fraction"], 0.5)
        self.assertEqual(result["minutes"], 60)

    def test_missing_solar_is_unknown_not_dark(self):
        result = daylight_summary(0, 3600, {})
        self.assertFalse(result["complete"])
        self.assertIsNone(result["fraction"])

    def test_published_nzdt_is_applied_once(self):
        events = parse_linz(LINZ_HEADER + b"27,Su,9,2026,06:00,0.5,12:00,3.0\n")
        self.assertEqual(events[0]["at"], datetime(2026, 9, 26, 17, tzinfo=timezone.utc))
        self.assertEqual(events[1]["at"], datetime(2026, 9, 26, 23, tzinfo=timezone.utc))

    def test_reject_nonexistent_and_ambiguous_nz_clock(self):
        for local in (datetime(2026, 9, 27, 2, 30), datetime(2026, 4, 5, 2, 30)):
            with self.assertRaisesRegex(ValueError, "Ambiguous or nonexistent"):
                strict_local(local)

    def test_tide_csv_rejects_nonexistent_event_time(self):
        with self.assertRaisesRegex(ValueError, "Ambiguous or nonexistent"):
            parse_linz(LINZ_HEADER + b"27,Su,9,2026,02:30,0.5,12:00,3.0\n")

    def test_wrong_tide_station_rejected(self):
        with self.assertRaisesRegex(ValueError, "station 180, Raglan"):
            parse_linz(LINZ_HEADER.replace(b"Raglan", b"Thames")
                       + b"27,Su,9,2026,06:00,0.5,12:00,3.0\n")

    def test_non_alternating_tide_events_rejected(self):
        with self.assertRaisesRegex(ValueError, "do not alternate"):
            parse_linz(LINZ_HEADER + b"27,Su,9,2026,06:00,0.5,12:00,1.5,18:00,3.0\n")

    def test_wrong_forecast_units_rejected(self):
        payload = {"hourly": {"wind_speed_10m": [10]},
                   "hourly_units": {"time": "unixtime", "wind_speed_10m": "kn"}}
        with self.assertRaisesRegex(ValueError, "Unexpected unit"):
            validate_units(payload, {"wind_speed_10m": "km/h"})

    def test_out_of_range_rain_probability_is_unknown(self):
        points = series({"hourly": {"time": [0, 3600, 7200],
                                   "precipitation_probability": [101, -1, 50]}},
                        "precipitation_probability")
        self.assertEqual(points, [(0, None), (3600, None), (7200, 50)])

    def test_replay_rejects_modified_raw_response(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "weather.json").write_text("{}")
            (directory / "manifest.json").write_text(json.dumps({"sources": {
                "weather": {"file": "weather.json", "sha256": "0" * 64}}}))
            with self.assertRaisesRegex(ValueError, "Snapshot integrity failure: weather"):
                replay(directory)

    def test_late_incoming_preference_fit_not_catch_score(self):
        events = [{"at": local_time("2026-09-30", "13:23"), "type": "High"}]
        early = preference_overlap(local_time("2026-09-30", "07:00").timestamp(),
                                   local_time("2026-09-30", "09:00").timestamp(), events)
        later = preference_overlap(local_time("2026-09-30", "11:00").timestamp(),
                                   local_time("2026-09-30", "13:00").timestamp(), events)
        self.assertEqual(early["minutes"], 0)
        self.assertEqual(later["minutes"], 120)
        self.assertGreater(later["fraction"], early["fraction"])


if __name__ == "__main__":
    unittest.main()
