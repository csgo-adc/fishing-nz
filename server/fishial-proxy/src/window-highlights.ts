import type { ConditionRow, SharedWindow } from "./share";

/**
 * What the shared-window page puts first, using the same rules as the apps (Android `WindowHighlights.kt`, iPhone
 * `WindowHighlights` in FishingModels.swift): an outlook colour, three key numbers and anything that needs attention.
 */
export type Tone = "good" | "caution" | "poor" | "unknown";
export type KeyTile = { label: string; value: string; detail: string; concern: boolean };

const GOOD = new Set(["Excellent", "Good", "Comfortable", "Okay"]);
const POOR = new Set(["Uncomfortable", "Challenging", "Concerning", "Check conditions"]);
const CAUTION = new Set(["Demanding", "Fair", "Mixed conditions", "Unfavourable"]);
/** Condition moods that make a key number amber and add a line to the watch-out box. */
const CONCERN = new Set(["Demanding", "Uncomfortable", "Choppy", "More motion", "High waves", "Wet", "Cold", "Hot",
  "After dark", "Limited daylight", "Challenging", "Concerning", "Check conditions", "Unfavourable"]);

export function outlookTone(label: string): Tone {
  if (GOOD.has(label)) return "good";
  if (POOR.has(label)) return "poor";
  if (CAUTION.has(label)) return "caution";
  return "unknown";
}

function segments(value: string): string[] {
  return value.split("\n")[0].split(" · ").map((part) => part.trim());
}

const isConcern = (row: ConditionRow) => CONCERN.has(row.label);
const find = (shared: SharedWindow, title: string) => shared.conditions.find((row) => row.title === title);

function tile(label: string, row: ConditionRow | undefined): KeyTile {
  if (!row || row.value.trim() === "—") return { label, value: "—", detail: "No data", concern: false };
  const parts = segments(row.value);
  const first = parts[0] ?? row.value;
  if (row.title === "Rain") {
    const chance = parts.find((part) => part.includes("%"))?.split(" ")[0];
    return chance ? { label, value: chance, detail: first, concern: isConcern(row) }
      : { label, value: first, detail: parts[1] ?? "", concern: isConcern(row) };
  }
  if (row.title === "Tide") {
    const high = /high/i.test(first);
    const time = first.match(/\d{1,2}:\d{2}\s?[AP]M/i)?.[0] ?? "";
    return { label, value: high ? "High" : "Low", detail: first.startsWith("Next") ? `next ${time}` : time, concern: isConcern(row) };
  }
  return { label, value: first, detail: parts[1] ?? "", concern: isConcern(row) };
}

/** Land: wind, tide and rain. Boat: waves, wind and tide. Empty when the link carries no condition rows. */
export function keyTiles(shared: SharedWindow): KeyTile[] {
  if (shared.conditions.length === 0) return [];
  return shared.boat
    ? [tile("Waves", find(shared, "Offshore waves")), tile("Wind", find(shared, "Wind")), tile("Tide", find(shared, "Tide"))]
    : [tile("Wind", find(shared, "Wind")), tile("Tide", find(shared, "Tide")), tile("Rain", find(shared, "Rain"))];
}

/** Short lines for the amber box: only what needs attention, most important first. */
export function watchOuts(shared: SharedWindow): string[] {
  const lines: string[] = [];
  const wind = find(shared, "Wind");
  if (wind && isConcern(wind)) {
    const gust = wind.value.match(/gust (\d+)/)?.[1];
    lines.push(gust ? `Gusts to ${gust} km/h` : `Wind ${segments(wind.value)[0]}`);
  }
  const waves = find(shared, "Offshore waves");
  if (waves && isConcern(waves)) {
    const height = segments(waves.value)[0];
    lines.push(waves.label === "Choppy" ? `Short, choppy waves (${height})` : `Waves to ${height}`);
  }
  const rain = find(shared, "Rain");
  if (rain && isConcern(rain)) lines.push(`Rain: ${segments(rain.value)[0]} expected`);
  const feels = find(shared, "Feels like");
  if (feels && isConcern(feels)) lines.push(`${feels.label}: feels like ${segments(feels.value)[0]}`);
  const daylight = find(shared, "Daylight");
  if (daylight && isConcern(daylight)) lines.push("Part of the session is after dark");
  // A link has no comfort flag, but the apps put this reason on windows outside the comfort setting.
  if (shared.reason === "Outside your comfort preference") lines.push("Outside your comfort preference");
  return lines;
}
