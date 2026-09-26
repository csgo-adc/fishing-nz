#!/usr/bin/env python3
"""Fetch the official MPI recreational fishing rules pages into SQLite.

MPI's robots.txt asks crawlers to wait 10 seconds between requests. This script
uses that delay and commits a refresh only after every requested page succeeds.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup


BASE = "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/"
USER_AGENT = "CatchCheckNZ-rules-crawler/1.0 (contact: info@mpi.govt.nz)"
DEFAULT_DB = Path(__file__).resolve().parents[1] / "data" / "mpi_fishing_rules.sqlite3"


@dataclass(frozen=True)
class Area:
    id: str
    name: str
    slug: str

    @property
    def url(self) -> str:
        return BASE + self.slug


AREAS = (
    Area("auckland-kermadec", "Auckland / Kermadec", "auckland-kermadec-fishing-rules"),
    Area("central", "Central", "central-fishing-rules"),
    Area("challenger", "Challenger", "challenger-fishing-rules"),
    Area("south-east", "South-East", "south-east-fishing-rules"),
    Area("southland", "Southland", "southland-fishing-rules"),
    Area("kaikoura", "Kaikōura", "kaikoura-fishing-rules"),
    Area("chatham-rise", "Chatham Rise", "chatham-rise-area-recreational-fishing-rules"),
    Area("fiordland", "Fiordland", "fiordland-marine-area-fishing-rules"),
)

SCHEMA = """
CREATE TABLE IF NOT EXISTS fishing_rules (
  area_id TEXT PRIMARY KEY,
  area_name TEXT NOT NULL,
  source_url TEXT NOT NULL,
  page_title TEXT NOT NULL,
  reviewed_at TEXT,
  fetched_at TEXT NOT NULL,
  content_sha256 TEXT NOT NULL,
  page_text TEXT NOT NULL,
  sections_json TEXT NOT NULL,
  tables_json TEXT NOT NULL,
  page_html TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS fishing_rules_fetched_at_idx ON fishing_rules(fetched_at);
"""


def get_robot_delay() -> float:
    request = Request("https://www.mpi.govt.nz/robots.txt", headers={"User-Agent": USER_AGENT})
    try:
        with urlopen(request, timeout=20) as response:
            robots = response.read().decode("utf-8", "replace")
    except (HTTPError, URLError, TimeoutError) as exc:
        raise RuntimeError(f"Could not read MPI robots.txt: {exc}") from exc

    in_star_group = False
    delay = None
    for raw_line in robots.splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line or ":" not in line:
            continue
        key, value = (part.strip() for part in line.split(":", 1))
        if key.lower() == "user-agent":
            in_star_group = value == "*"
        elif key.lower() == "crawl-delay" and in_star_group:
            try:
                delay = float(value)
            except ValueError:
                pass
    return delay if delay is not None else 10.0


def fetch(url: str) -> bytes:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != "www.mpi.govt.nz":
        raise ValueError(f"Refusing to fetch a non-MPI URL: {url}")
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/html"})
    try:
        with urlopen(request, timeout=30) as response:
            body = response.read()
            content_type = response.headers.get_content_type()
    except (HTTPError, URLError, TimeoutError) as exc:
        raise RuntimeError(f"Could not fetch {url}: {exc}") from exc
    if content_type != "text/html":
        raise RuntimeError(f"Expected HTML from {url}, got {content_type}")
    soup = BeautifulSoup(body, "html.parser")
    if soup.select_one('meta[name="ROBOTS"][content*="NOINDEX"]') or "Request unsuccessful" in soup.get_text(" ", strip=True):
        raise RuntimeError(f"MPI returned an anti-bot/interstitial page for {url}; no rules were saved")
    return body


def request_rules_api(url: str, token: str, payload: object, accept: str) -> bytes:
    request = Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "User-Agent": USER_AGENT,
            "X-Rules-Ingest-Token": token,
            "Content-Type": "application/json",
            "Accept": accept,
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=60) as response:
            body = response.read()
            if response.status >= 300:
                raise RuntimeError(f"Rules API returned HTTP {response.status}")
            return body
    except HTTPError as exc:
        detail = exc.read(1000).decode("utf-8", "replace")
        raise RuntimeError(f"Rules API returned HTTP {exc.code}: {detail}") from exc
    except (URLError, TimeoutError) as exc:
        raise RuntimeError(f"Could not reach the Cloudflare rules API: {exc}") from exc


def parse_structured_rules(root, title: str) -> tuple[list[dict[str, object]], list[list[list[str]]]]:
    """Keep prose and table rows separate, including when cells contain paragraphs."""
    sections: list[dict[str, object]] = []
    current: dict[str, object] = {"heading": title, "text": []}
    for node in root.find_all(["h1", "h2", "h3", "h4", "p", "li"]):
        # Tables are stored separately as structured rows. Including text from
        # table cells here creates a second, unreadable stream of cell values.
        if node.find_parent("table") is not None:
            continue
        text = node.get_text(" ", strip=True)
        if not text:
            continue
        if node.name.startswith("h"):
            if current["text"]:
                current["text"] = "\n".join(current["text"])
                sections.append(current)
            current = {"heading": text, "text": []}
        else:
            current["text"].append(text)
    if current["text"]:
        current["text"] = "\n".join(current["text"])
        sections.append(current)

    tables = []
    for table in root.find_all("table"):
        rows = []
        for row in table.find_all("tr"):
            cells = [cell.get_text(" ", strip=True) for cell in row.find_all(["th", "td"], recursive=False)]
            if cells:
                rows.append(cells)
        if rows:
            tables.append(rows)
    return sections, tables


def extract(area: Area, body: bytes, fetched_at: str) -> dict[str, object]:
    soup = BeautifulSoup(body, "html.parser")
    root = soup.select_one("main, article, #content, .main-content") or soup.body or soup
    for tag in root.select("script, style, nav, footer, header, noscript, svg"):
        tag.decompose()

    title = (soup.title.get_text(" ", strip=True) if soup.title else "").strip()
    if not title:
        heading = root.find(["h1"])
        title = heading.get_text(" ", strip=True) if heading else area.name

    reviewed = None
    match = re.search(r"Last reviewed\s*:?\s*(\d{1,2}[./-]\d{1,2}[./-]\d{2,4})", root.get_text(" ", strip=True), re.I)
    if match:
        reviewed = match.group(1)

    sections, tables = parse_structured_rules(root, title)

    page_text = root.get_text("\n", strip=True)
    if len(page_text) < 500 or not root.find(["h1", "h2"]):
        raise RuntimeError(f"Fetched {area.name}, but the page did not contain recognizable rule content")

    return {
        "area_id": area.id,
        "area_name": area.name,
        "source_url": area.url,
        "page_title": title,
        "reviewed_at": reviewed,
        "fetched_at": fetched_at,
        "content_sha256": hashlib.sha256(body).hexdigest(),
        "page_text": page_text,
        "sections_json": json.dumps(sections, ensure_ascii=False),
        "tables_json": json.dumps(tables, ensure_ascii=False),
        "page_html": body.decode("utf-8", "replace"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB, help=f"SQLite database file (default: {DEFAULT_DB})")
    parser.add_argument("--area", choices=[area.id for area in AREAS], action="append", help="Fetch only this area; may be repeated")
    parser.add_argument("--api-base-url", default=os.environ.get("RULES_API_BASE_URL"), help="Cloudflare Worker rules API base URL, ending in /v1/rules")
    parser.add_argument("--source", choices=["worker", "direct"], help="Fetch MPI pages through the Worker (default with --api-base-url) or directly from this computer")
    parser.add_argument("--token-file", type=Path, default=Path(__file__).resolve().parents[1] / "server" / "fishial-proxy" / ".rules-ingest-token", help="Local file holding the Worker import token")
    args = parser.parse_args()
    areas = [area for area in AREAS if not args.area or area.id in args.area]
    source = args.source or ("worker" if args.api_base_url else "direct")

    try:
        delay = get_robot_delay()
        if source == "worker" and not args.api_base_url:
            raise ValueError("--source worker requires --api-base-url")
        token = None
        if args.api_base_url:
            if not args.api_base_url.startswith("https://"):
                raise ValueError("The Cloudflare API base URL must use HTTPS")
            try:
                token = args.token_file.read_text(encoding="utf-8").strip()
            except OSError as exc:
                raise RuntimeError(f"Could not read the API token file at {args.token_file}") from exc
            if len(token) < 32:
                raise RuntimeError("The Cloudflare rules API token file is empty or invalid")
        print(f"MPI crawl delay: {delay:g} seconds; fetching {len(areas)} area page(s).", flush=True)
        fetched = []
        for index, area in enumerate(areas):
            if index:
                time.sleep(delay)
            print(f"Fetching {area.name}…", flush=True)
            if source == "worker":
                body = request_rules_api(args.api_base_url.rstrip("/") + "/source", token, {"area_id": area.id}, "text/html")
            else:
                body = fetch(area.url)
            fetched.append(extract(area, body, datetime.now(timezone.utc).isoformat(timespec="seconds")))

        if args.api_base_url:
            print("Saving refreshed rules to Cloudflare D1…", flush=True)
            result = request_rules_api(args.api_base_url.rstrip("/") + "/import", token, fetched, "application/json")
            print("Cloudflare response: " + result.decode("utf-8", "replace"), flush=True)

        args.db.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(args.db) as connection:
            connection.executescript(SCHEMA)
            connection.execute("BEGIN IMMEDIATE")
            connection.executemany(
                """INSERT INTO fishing_rules
                   (area_id, area_name, source_url, page_title, reviewed_at, fetched_at,
                    content_sha256, page_text, sections_json, tables_json, page_html)
                   VALUES (:area_id, :area_name, :source_url, :page_title, :reviewed_at,
                           :fetched_at, :content_sha256, :page_text, :sections_json, :tables_json, :page_html)
                   ON CONFLICT(area_id) DO UPDATE SET
                     area_name=excluded.area_name, source_url=excluded.source_url,
                     page_title=excluded.page_title, reviewed_at=excluded.reviewed_at,
                     fetched_at=excluded.fetched_at, content_sha256=excluded.content_sha256,
                     page_text=excluded.page_text, sections_json=excluded.sections_json,
                     tables_json=excluded.tables_json, page_html=excluded.page_html""",
                fetched,
            )
            connection.commit()
        print(f"Saved {len(fetched)} area page(s) to {args.db}")
        return 0
    except (OSError, RuntimeError, ValueError, sqlite3.Error) as exc:
        print(f"Crawler stopped: {exc}", file=sys.stderr)
        print("No partial refresh was committed.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
