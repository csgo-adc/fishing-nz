"""The wharf and breakwater list: which OpenStreetMap structures are kept, and app copies that match the list."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_shore_structures import COPIES, OUTPUT, build, usable


def way(way_id, tags, points):
    return {"type": "way", "id": way_id, "tags": tags, "geometry": [{"lat": lat, "lon": lon} for lat, lon in points]}


class ShoreStructureTest(unittest.TestCase):
    def test_leaves_out_private_floating_and_industrial_structures(self):
        self.assertTrue(usable({"man_made": "pier", "name": "Paihia Wharf"}))
        for tags in ({"access": "private"}, {"fishing": "no"}, {"floating": "yes"}, {"mooring": "commercial"},
                     {"disused:man_made": "pier"}, {"name": "Tanker Wharf"}, {"name": "Fuel Berth"}, {"name": "B Pier"}, {"name": "C"}):
            self.assertFalse(usable({"man_made": "pier", **tags}), tags)

    def test_keeps_long_or_named_structures_and_merges_pieces(self):
        long_line = [(-36.9, 175.1), (-36.9, 175.1012)]  # about 107 m
        short_line = [(-36.95, 175.1), (-36.95, 175.1006)]  # about 53 m
        marina = {"type": "way", "id": 9, "tags": {"leisure": "marina"},
                  "geometry": [{"lat": -37.01, "lon": 175.09}, {"lat": -37.01, "lon": 175.11}, {"lat": -36.99, "lon": 175.11},
                               {"lat": -36.99, "lon": 175.09}, {"lat": -37.01, "lon": 175.09}]}
        reserve = ([175.19, -37.11, 175.21, -37.09], [[[((175.19, -37.11), (175.21, -37.11)), ((175.21, -37.11), (175.21, -37.09)),
                                                        ((175.21, -37.09), (175.19, -37.09)), ((175.19, -37.09), (175.19, -37.11))]]])
        built = build([
            way(1, {"man_made": "pier"}, long_line),
            way(2, {"man_made": "pier", "name": "Kawakawa Bay Wharf"}, [(-36.90005, 175.1), (-36.90005, 175.1012)]),
            way(3, {"man_made": "pier"}, short_line),
            way(4, {"man_made": "pier", "name": "Orere Wharf"}, [(-36.96, 175.1), (-36.96, 175.1006)]),
            way(5, {"man_made": "breakwater"}, [(-36.97, 175.1), (-36.97, 175.1001)]),
            way(6, {"man_made": "groyne"}, [(-37.0, 175.1), (-37.0, 175.1006)]),
            way(7, {"man_made": "breakwater"}, [(-37.1, 175.2), (-37.1, 175.2006)]),
            marina,
        ], [(reserve[0], reserve[1][0])])
        names = [(item["kind"], item["name"]) for item in built["structures"]]
        self.assertIn(("wharf", "Kawakawa Bay Wharf"), names)  # the named piece of the merged pair
        self.assertIn(("wharf", "Orere Wharf"), names)  # named, so kept although short
        self.assertNotIn(("wharf", None), names)  # unnamed and under 80 m, or merged into the named wharf
        self.assertEqual([kind for kind, _ in names].count("breakwater"), 0)  # one under 20 m, one inside a no-fishing area
        self.assertNotIn(("groyne", None), names)  # inside a marina area
        self.assertEqual(len(names), 2)

    def test_app_copies_match_the_list(self):
        expected = OUTPUT.read_text()
        for path in COPIES:
            self.assertEqual(path.read_text(), expected, path)
        catalogue = json.loads(expected)
        self.assertIn("OpenStreetMap", catalogue["attribution"])
        self.assertGreater(len(catalogue["structures"]), 400)
        self.assertEqual({item["kind"] for item in catalogue["structures"]}, {"wharf", "breakwater", "groyne"})


if __name__ == "__main__":
    unittest.main()
