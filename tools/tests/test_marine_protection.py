"""The marine protection layer: ring handling, and app copies that match the catalogue."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_marine_protection import COPIES, OUTPUT, contains, polygons, signed_area


class MarineProtectionTest(unittest.TestCase):
    def test_esri_rings_become_outer_rings_with_their_holes(self):
        outer = [[0, 0], [0, 10], [10, 10], [10, 0], [0, 0]]  # clockwise: an outer ring in Esri JSON
        island = [[2, 2], [4, 2], [4, 4], [2, 4], [2, 2]]  # counterclockwise: a hole
        stray = [[20, 20], [22, 20], [22, 22], [20, 22], [20, 20]]  # a hole outside every outer ring
        result = polygons([outer, island, stray])
        self.assertEqual(len(result), 1)
        self.assertEqual(len(result[0]), 2)
        self.assertGreater(signed_area(result[0][0]), 0)  # RFC 7946: counterclockwise outside
        self.assertLess(signed_area(result[0][1]), 0)
        self.assertTrue(contains(result[0][0], [5, 5]))
        self.assertTrue(contains(result[0][1], [3, 3]))

    def test_app_copies_match_the_catalogue(self):
        expected = OUTPUT.read_text()
        for path in COPIES:
            self.assertEqual(path.read_text(), expected, path)
        catalogue = json.loads(expected)
        self.assertEqual(catalogue["schema_version"], 1)
        kinds = {area["kind"] for area in catalogue["areas"]}
        self.assertEqual(kinds, {"reserve", "high_protection", "seafloor_protection"})


if __name__ == "__main__":
    unittest.main()
