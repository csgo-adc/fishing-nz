"""The boat ramp list: which OpenStreetMap slipways are kept, and app copies that match the list."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_boat_ramps import COPIES, OUTPUT, build, usable


class BoatRampTest(unittest.TestCase):
    def test_keeps_public_ramps_and_merges_duplicates(self):
        self.assertTrue(usable({"leisure": "slipway"}))
        for tags in ({"access": "private"}, {"access": "customers"}, {"railway": "rail"}, {"disused:leisure": "slipway"}, {"abandoned": "yes"}):
            self.assertFalse(usable({"leisure": "slipway", **tags}), tags)
        built = build([
            {"type": "node", "id": 1, "lat": -36.9, "lon": 175.1, "tags": {"leisure": "slipway"}},
            {"type": "way", "id": 2, "center": {"lat": -36.90005, "lon": 175.10005}, "tags": {"leisure": "slipway", "name": "Kawakawa Bay ramp"}},
            {"type": "node", "id": 3, "lat": -29.2, "lon": -177.9, "tags": {"leisure": "slipway"}},
        ])
        self.assertEqual(len(built["ramps"]), 1)
        self.assertEqual(built["ramps"][0]["name"], "Kawakawa Bay ramp")

    def test_app_copies_match_the_list(self):
        expected = OUTPUT.read_text()
        for path in COPIES:
            self.assertEqual(path.read_text(), expected, path)
        catalogue = json.loads(expected)
        self.assertIn("OpenStreetMap", catalogue["attribution"])
        self.assertGreater(len(catalogue["ramps"]), 900)


if __name__ == "__main__":
    unittest.main()
