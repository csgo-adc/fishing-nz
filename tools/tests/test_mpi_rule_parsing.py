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

    def test_row_and_column_spans_keep_limits_under_the_right_headings(self):
        html = """
        <main><table>
          <tr><th>Species</th><th>Daily limit</th><th>Minimum length</th></tr>
          <tr><td>Groper (Kermadecs)</td><td rowspan="2">5 combined; no more than 3 kingfish</td><td>—</td></tr>
          <tr><td>Kingfish (Kermadecs)</td><td>75</td></tr>
          <tr><td colspan="3">** Read the subarea note.</td></tr>
        </table></main>
        """
        _, tables = parse_structured_rules(BeautifulSoup(html, "html.parser").main, "Auckland")
        self.assertEqual(tables[0][2], ["Kingfish (Kermadecs)", "5 combined; no more than 3 kingfish", "75"])
        self.assertEqual(tables[0][3], ["** Read the subarea note."])

    def test_inconsistent_table_stops_the_import(self):
        html = """
        <main><table>
          <tr><th>Species</th><th>Daily limit</th><th>Minimum length</th></tr>
          <tr><td>Kingfish</td><td>75</td></tr>
        </table></main>
        """
        with self.assertRaisesRegex(RuntimeError, "inconsistent row widths"):
            parse_structured_rules(BeautifulSoup(html, "html.parser").main, "Area")


if __name__ == "__main__":
    unittest.main()
