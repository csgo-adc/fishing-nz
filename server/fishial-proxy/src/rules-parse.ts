import { parse, HTMLElement, TextNode, type Node } from "node-html-parser";

/**
 * Turns an MPI area rules page into the stored rule data, the same way tools/crawl_mpi_rules.py does (sections of prose,
 * tables expanded cell by cell), so pages uploaded in the CMS and pages imported with the Python tools match.
 */
export type ParsedRulesPage = {
  title: string;
  reviewedAt: string | null;
  sections: Array<{ heading: string; text: string }>;
  tables: string[][][];
  pageText: string;
  /** The MPI address the page says it is (canonical link, og:url or a browser's "saved from" note), if any. */
  sourceUrl: string | null;
};

export class RulesPageError extends Error {}

const SECTION_TAGS = new Set(["h1", "h2", "h3", "h4", "p", "li"]);
const DROPPED_TAGS = "script, style, nav, footer, header, noscript, svg";

/** BeautifulSoup's get_text(separator, strip=True): every text piece trimmed, empty ones dropped, joined. */
function textOf(node: Node, separator: string): string {
  const parts: string[] = [];
  const walk = (current: Node) => {
    if (current instanceof TextNode) {
      const text = current.text.trim();
      if (text) parts.push(text);
    } else if (current instanceof HTMLElement) {
      for (const child of current.childNodes) walk(child);
    }
  };
  walk(node);
  return parts.join(separator);
}

const tag = (element: HTMLElement) => (element.rawTagName || "").toLowerCase();

/** The nearest table around an element, or null. */
function parentTable(element: HTMLElement): HTMLElement | null {
  for (let parent = element.parentNode; parent; parent = parent.parentNode) if (tag(parent) === "table") return parent;
  return null;
}

function descendants(root: HTMLElement, names: Set<string>): HTMLElement[] {
  const found: HTMLElement[] = [];
  const walk = (element: HTMLElement) => {
    for (const child of element.childNodes) {
      if (!(child instanceof HTMLElement)) continue;
      if (names.has(tag(child))) found.push(child);
      walk(child);
    }
  };
  walk(root);
  return found;
}

/** Python's int() on an attribute: digits with optional sign and surrounding spaces, otherwise invalid. */
function spanValue(value: string | undefined): number {
  if (value === undefined) return 1;
  if (!/^\s*[+-]?\d+\s*$/.test(value)) throw new RulesPageError("MPI rule table has an invalid cell span");
  return Number(value.trim());
}

/** Expands row and column spans so every value stays under its own heading; a full-width note stays one cell. */
export function expandTableGrid(table: HTMLElement): string[][] {
  const expanded: Array<{ values: string[]; fullWidth: boolean }> = [];
  let active = new Map<number, { value: string; remaining: number }>();
  let expectedWidth = 0;
  for (const row of descendants(table, new Set(["tr"]))) {
    if (parentTable(row) !== table) continue;
    const cells = row.childNodes.filter((child): child is HTMLElement => child instanceof HTMLElement && ["th", "td"].includes(tag(child)));
    if (cells.length === 0) continue;
    const occupied = new Map<number, string>();
    for (const [column, { value }] of active) occupied.set(column, value);
    const nextActive = new Map<number, { value: string; remaining: number }>();
    for (const [column, { value, remaining }] of active) if (remaining > 1) nextActive.set(column, { value, remaining: remaining - 1 });
    let cursor = 0;
    for (const cell of cells) {
      const columns = spanValue(cell.getAttribute("colspan"));
      const rows = spanValue(cell.getAttribute("rowspan"));
      if (columns < 1 || columns > 50 || rows < 1 || rows > 100) throw new RulesPageError("MPI rule table has an invalid cell span");
      const blocked = (start: number) => { for (let column = start; column < start + columns; column++) if (occupied.has(column)) return true; return false; };
      while (blocked(cursor)) cursor += 1;
      if (cursor + columns > 50) throw new RulesPageError("MPI rule table has excessive columns");
      const value = textOf(cell, " ");
      for (let column = cursor; column < cursor + columns; column++) {
        occupied.set(column, value);
        if (rows > 1) nextActive.set(column, { value, remaining: rows - 1 });
      }
      cursor += columns;
    }
    const width = Math.max(...occupied.keys()) + 1;
    for (let column = 0; column < width; column++) if (!occupied.has(column)) throw new RulesPageError("MPI rule table has a gap between cells");
    expectedWidth = Math.max(expectedWidth, width);
    const fullWidth = cells.length === 1 && active.size === 0 && spanValue(cells[0].getAttribute("colspan")) === width;
    expanded.push({ values: Array.from({ length: width }, (_, column) => occupied.get(column)!), fullWidth });
    active = nextActive;
  }
  if (active.size > 0 || expanded.some(({ values }) => values.length !== expectedWidth)) {
    throw new RulesPageError("MPI rule table has inconsistent row widths or an unfinished row span");
  }
  return expanded.map(({ values, fullWidth }) => fullWidth ? [values[0]] : values);
}

/** Prose under each heading, leaving table cells to the tables. */
export function parseStructuredRules(root: HTMLElement, title: string): { sections: Array<{ heading: string; text: string }>; tables: string[][][] } {
  const sections: Array<{ heading: string; text: string }> = [];
  let current = { heading: title, text: [] as string[] };
  for (const node of descendants(root, SECTION_TAGS)) {
    if (parentTable(node)) continue;
    const text = textOf(node, " ");
    if (!text) continue;
    if (tag(node).startsWith("h")) {
      if (current.text.length) sections.push({ heading: current.heading, text: current.text.join("\n") });
      current = { heading: text, text: [] };
    } else current.text.push(text);
  }
  if (current.text.length) sections.push({ heading: current.heading, text: current.text.join("\n") });
  const tables = descendants(root, new Set(["table"])).map(expandTableGrid).filter((rows) => rows.length > 0);
  return { sections, tables };
}

const MPI_RULES_URL = /https:\/\/www\.mpi\.govt\.nz\/fishing-aquaculture\/recreational-fishing\/fishing-rules\/[a-z0-9-]+/i;

export function parseRulesPage(html: string, fallbackTitle: string): ParsedRulesPage {
  const document = parse(html, { comment: false, blockTextElements: { script: false, noscript: false, style: false } });
  const savedFrom = html.slice(0, 2000).match(/<!--\s*saved from url=\(\d+\)(\S+)\s*-->/i)?.[1];
  const declared = document.querySelector('link[rel="canonical"]')?.getAttribute("href") ||
    document.querySelector('meta[property="og:url"]')?.getAttribute("content") || savedFrom || "";
  const sourceUrl = declared.match(MPI_RULES_URL)?.[0]?.toLowerCase() ?? null;
  // MPI shows "Last reviewed" near the foot of the page, which can sit outside the main content.
  const reviewedPattern = /Last reviewed\s*:?\s*(\d{1,2}[./-]\d{1,2}[./-]\d{2,4})/i;
  const reviewedAnywhere = textOf(document, " ").match(reviewedPattern)?.[1] ?? null;
  const root = document.querySelector("main, article, #content, .main-content") || document.querySelector("body") || document;
  for (const dropped of root.querySelectorAll(DROPPED_TAGS)) dropped.remove();
  let title = textOf(document.querySelector("title") ?? new HTMLElement("title", {}), " ");
  if (!title) {
    const heading = root.querySelector("h1");
    title = heading ? textOf(heading, " ") : fallbackTitle;
  }
  const reviewedAt = textOf(root, " ").match(reviewedPattern)?.[1] ?? reviewedAnywhere;
  const { sections, tables } = parseStructuredRules(root, title);
  const pageText = textOf(root, "\n");
  if (pageText.length < 500 || !root.querySelector("h1, h2")) {
    throw new RulesPageError("This doesn't look like an MPI rules page: it has too little text or no headings. Save the whole page and try again.");
  }
  return { title, reviewedAt, sections, tables, pageText, sourceUrl };
}

/** An HTML fragment as an element tree from the same parser, for tests and callers outside this file. */
export function parseFragment(html: string): HTMLElement {
  return parse(html, { comment: false, blockTextElements: { script: false, noscript: false, style: false } });
}
