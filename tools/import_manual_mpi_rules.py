#!/usr/bin/env python3
"""Import rule pages captured from MPI's normal browser UI into CatchCheck."""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

from bs4 import BeautifulSoup

from crawl_mpi_rules import AREAS, DEFAULT_DB, SCHEMA, request_rules_api


SNAPSHOT_DIR = Path("/tmp/catchcheck-manual-mpi")
DEFAULT_API = "https://fishing.fishnz.space/v1/rules"
TOKEN_FILE = Path(__file__).resolve().parents[1] / "server" / "fishial-proxy" / ".rules-ingest-token"


def rows_from_snapshots(directory: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    fetched_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    for area in AREAS:
        path = directory / f"{area.id}.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("url") != area.url:
            raise ValueError(f"Snapshot URL does not match the MPI page for {area.id}")
        if not payload.get("reviewed"):
            raise ValueError(f"MPI review date is missing for {area.id}")
        html = payload.get("html", "")
        if len(payload.get("text", "")) < 500 or len(html) < 500:
            raise ValueError(f"MPI content is incomplete for {area.id}")

        soup = BeautifulSoup(html, "html.parser")
        root = soup.select_one("main, article, #content, .main-content") or soup.body or soup
        for tag in root.select("script, style, nav, footer, header, noscript, svg"):
            tag.decompose()

        sections: list[dict[str, str]] = []
        current = {"heading": payload["title"], "text": []}
        for node in root.find_all(["h1", "h2", "h3", "h4", "p", "li"]):
            text = node.get_text(" ", strip=True)
            if not text:
                continue
            if node.name.startswith("h"):
                if current["text"]:
                    sections.append({"heading": current["heading"], "text": "\n".join(current["text"])})
                current = {"heading": text, "text": []}
            else:
                current["text"].append(text)
        if current["text"]:
            sections.append({"heading": current["heading"], "text": "\n".join(current["text"])})

        tables = []
        for table in root.find_all("table"):
            table_rows = []
            for tr in table.find_all("tr"):
                cells = [cell.get_text(" ", strip=True) for cell in tr.find_all(["th", "td"], recursive=False)]
                if cells:
                    table_rows.append(cells)
            if table_rows:
                tables.append(table_rows)

        rows.append({
            "area_id": area.id,
            "area_name": area.name,
            "source_url": area.url,
            "page_title": payload["title"],
            "reviewed_at": payload["reviewed"],
            "fetched_at": fetched_at,
            "content_sha256": __import__("hashlib").sha256(html.encode("utf-8")).hexdigest(),
            "page_text": payload["text"],
            "sections_json": json.dumps(sections, ensure_ascii=False),
            "tables_json": json.dumps(tables, ensure_ascii=False),
            "page_html": html,
        })
    return rows


def save_local(rows: list[dict[str, object]], db_path: Path) -> None:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(db_path) as connection:
        connection.executescript(SCHEMA)
        connection.execute("BEGIN IMMEDIATE")
        connection.executemany(
            """INSERT INTO fishing_rules
               (area_id, area_name, source_url, page_title, reviewed_at, fetched_at,
                content_sha256, page_text, sections_json, tables_json, page_html)
               VALUES (:area_id, :area_name, :source_url, :page_title, :reviewed_at, :fetched_at,
                       :content_sha256, :page_text, :sections_json, :tables_json, :page_html)
               ON CONFLICT(area_id) DO UPDATE SET
                 area_name=excluded.area_name, source_url=excluded.source_url,
                 page_title=excluded.page_title, reviewed_at=excluded.reviewed_at,
                 fetched_at=excluded.fetched_at, content_sha256=excluded.content_sha256,
                 page_text=excluded.page_text, sections_json=excluded.sections_json,
                 tables_json=excluded.tables_json, page_html=excluded.page_html""",
            rows,
        )
        connection.commit()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot-dir", type=Path, default=SNAPSHOT_DIR)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--api-base-url", default=os.environ.get("RULES_API_BASE_URL", DEFAULT_API))
    parser.add_argument("--token-file", type=Path, default=TOKEN_FILE)
    args = parser.parse_args()
    try:
        token = args.token_file.read_text(encoding="utf-8").strip()
        if len(token) < 32:
            raise ValueError("Rules ingest token file is empty or invalid")
        rows = rows_from_snapshots(args.snapshot_dir)
        print(f"Prepared {len(rows)} MPI area pages and {sum(len(json.loads(r['tables_json'])) for r in rows)} tables.", flush=True)
        response = request_rules_api(args.api_base_url.rstrip("/") + "/import", token, rows, "application/json")
        print("Cloudflare response: " + response.decode("utf-8", "replace"), flush=True)
        save_local(rows, args.db)
        print(f"Saved {len(rows)} pages to {args.db}", flush=True)
        return 0
    except (OSError, ValueError, RuntimeError, sqlite3.Error, json.JSONDecodeError) as exc:
        print(f"Manual MPI import stopped: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
