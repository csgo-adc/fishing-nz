"""The species pictures the server publishes and the rules copy the apps bundle agree with each other."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_offline_rules import AREAS, CATALOG, OFFLINE, PUBLIC, SPECIES, check


class OfflineRulesTest(unittest.TestCase):
    def test_copies_match(self):
        self.assertEqual(check(), 0)

    def test_every_picture_is_published_once(self):
        catalog = json.loads(CATALOG.read_text())
        files = {picture["file"] for item in catalog["species"] for picture in item["pictures"]}
        self.assertEqual(files, {path.name for path in PUBLIC.glob("*.webp")})
        ids = [item["id"] for item in json.loads(SPECIES.read_text())["species"]]
        self.assertEqual(len(ids), len(set(ids)))
        for item in catalog["species"]:
            self.assertIn(item["group"], {"finfish", "shark", "shellfish", "crayfish"})
            self.assertTrue(item["match"] and item["pictures"], item["id"])

    def test_bundle_has_every_area_without_page_html(self):
        bundle = json.loads(OFFLINE.read_text())
        self.assertEqual([rule["area_id"] for rule in bundle["rules"]], list(AREAS))
        for rule in bundle["rules"]:
            self.assertNotIn("page_html", rule)
            self.assertTrue(rule["tables"] or rule["needsReview"], rule["area_id"])


if __name__ == "__main__":
    unittest.main()
