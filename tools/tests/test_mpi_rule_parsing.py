"""Regression coverage for MPI table cells containing nested paragraphs."""

import sys
import unittest
from pathlib import Path

from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from crawl_mpi_rules import parse_structured_rules


class RuleParsingTest(unittest.TestCase):
    def test_species_table_stays_structured_and_footnote_stays_as_prose(self):
        html = """
        <main>
          <h2>Finfish – size limits and catch/bag limits</h2>
          <p>There is a combined daily bag limit of 20 finfish.</p>
          <h4>Table 1: Individual species daily limits</h4>
          <table>
            <tr><th><p>Finfish species</p></th><th><p>Maximum daily limit</p></th></tr>
            <tr><td><p>Snapper</p></td><td><p>10</p></td></tr>
          </table>
          <p>*** Check the precise boundary on the official map.</p>
        </main>
        """
        root = BeautifulSoup(html, "html.parser").main

        sections, tables = parse_structured_rules(root, "Central fishing rules")

        self.assertEqual(tables[0][1], ["Snapper", "10"])
        self.assertFalse(any("Snapper" in section["text"] for section in sections))
        self.assertTrue(any("precise boundary" in section["text"] for section in sections))


if __name__ == "__main__":
    unittest.main()
