/**
 * Condition rows in plain words: "22 km/h · gust 28 · S" reads "Breezy: 22 km/h, gusts to 28 km/h, from the south".
 * Shared links keep the short form the apps write, so the page puts it into words when it is shown. The same rules are
 * in the apps: Android `PlainConditions.kt`, iPhone `PlainConditions` (FishingModels.swift).
 */
const COMPASS: Record<string, string> = {
  N: "north", NE: "north-east", E: "east", SE: "south-east", S: "south", SW: "south-west", W: "west", NW: "north-west",
};

/** The word for a steady wind, in km/h. The steps follow the land comfort bands (12, 20 and 30 km/h). */
export function windStrength(kmh: number): string {
  if (kmh < 12) return "Light wind";
  if (kmh < 20) return "Gentle breeze";
  if (kmh < 30) return "Breezy";
  if (kmh < 40) return "Windy";
  return "Very windy";
}

/** "from the south" for one direction, "from SW, then W" when it changes; null when unknown. */
function from(directions: string[]): string | null {
  const known = directions.map((value) => value.trim()).filter((value) => value in COMPASS);
  if (known.length === 0) return null;
  return known.length === 1 ? `from the ${COMPASS[known[0]]}` : `from ${known.join(", then ")}`;
}

/** Whole seconds from a period range: "6.5–7.0" is "7", "5.6–8.2" is "6–8"; null without a number. */
function seconds(range: string): string | null {
  const values = range.split("–").map((part) => part.trim()).filter(Boolean).map(Number).filter(Number.isFinite);
  if (values.length === 0) return null;
  const low = Math.round(Math.min(...values));
  const high = Math.round(Math.max(...values));
  return low === high ? `${low}` : `${low}–${high}`;
}

const WIND = /^(\d+) km\/h · gust (\d+)(?: · ([A-Z/]+))?$/;
const WAVES = /^(\d+(?:\.\d+)?) m · (.+) s$/;
const RAIN = /^(\d+(?:\.\d+)?) mm · peak \S+ mm\/h · (?:(\d+)% hourly|chance —)$/;
const TIDE = /^(Next )?(high|low) (.+) · (\d+(?:\.\d+)?) m CD$/;
const DAYLIGHT = /^(\d+)% (session|visit|known time)$/;
const SHORE = /^(.+) · before (.+) · return (.+)$/;

function tide(value: string): string | null {
  const lines = value.split("\n").map((line) => line.trim()).filter(Boolean);
  const event = lines[0]?.match(TIDE);
  if (!event) return null;
  const [, next, kind, time, height] = event;
  const start = next ? `Next ${kind}` : kind[0].toUpperCase() + kind.slice(1);
  const stations = lines.slice(1).map((line) => `Tide station: ${line.replace(/^LINZ /, "").split(" · ").join(", ")}`);
  return [`${start} tide ${time}, ${height} m`, ...stations].join("\n");
}

function daylight(percent: string, of: string): string {
  if (percent === "100") return of === "visit" ? "Whole visit in daylight" : "All in daylight";
  if (percent === "0") return "All after dark";
  if (of === "visit") return `${percent}% of your visit in daylight`;
  if (of === "known time") return `${percent}% of the known times in daylight`;
  return `${percent}% in daylight`;
}

/** "30 min walk each way", "short walk", or the two ways separately when they differ. */
function walk(before: string, back: string): string[] {
  if (before === back && before === "—") return ["walk time not set"];
  if (before === back && before === "0 min") return ["short walk"];
  if (before === back) return [`${before} walk each way`];
  return [before === "—" ? "walk-in time not set" : `${before} walk in`, back === "—" ? "return time not set" : `${back} back`];
}

/** A shared condition row in plain words. Text it does not recognise, such as a cut-off row, stays as it is. */
export function plainValue(title: string, raw: string): string {
  const value = raw.trim();
  switch (title) {
    case "Wind": {
      const match = value.match(WIND);
      if (!match) return value;
      const [, speed, gust, directions = ""] = match;
      return [`${windStrength(Number(speed))}: ${speed} km/h`, `gusts to ${gust} km/h`, from(directions.split("/"))]
        .filter(Boolean).join(", ");
    }
    case "Offshore waves": {
      const match = value.match(WAVES);
      if (!match) return value;
      const apart = seconds(match[2]);
      return `Up to ${Number(match[1]).toFixed(1)} m${apart ? `, ${apart} ${apart === "1" ? "second" : "seconds"} apart` : ""}`;
    }
    case "Rain": {
      const match = value.match(RAIN);
      if (!match) return value;
      const total = Number(match[1]).toFixed(1);
      return [total === "0.0" ? "No rain expected" : `${total} mm in total`,
        match[2] && Number(match[2]) > 0 ? `up to ${match[2]}% chance in any hour` : null].filter(Boolean).join(", ");
    }
    case "Tide":
      return tide(value) ?? value;
    case "Daylight": {
      const match = value.match(DAYLIGHT);
      return match ? daylight(match[1], match[2]) : value;
    }
    case "Shore plan": {
      const match = value.match(SHORE);
      if (!match) return value;
      const [, setting, before, back] = match;
      return [setting === "Not set" || setting === "Not sure" ? "Shore type not set" : setting, ...walk(before, back)].join(" · ");
    }
    default:
      return value;
  }
}

/** "22 km/h" over "Breezy · gusts 28 · from S" ("from SW to E" when it changes). Null when the row is not recognised. */
export function windTile(value: string): { value: string; detail: string } | null {
  const match = value.trim().match(WIND);
  if (!match) return null;
  const [, speed, gust, directions = ""] = match;
  const known = directions.split("/").filter((direction) => direction in COMPASS);
  const from = known.length === 0 ? null : known.length === 1 ? `from ${known[0]}` : `from ${known[0]} to ${known[known.length - 1]}`;
  return { value: `${speed} km/h`, detail: [windStrength(Number(speed)), `gusts ${gust}`, from].filter(Boolean).join(" · ") };
}

/** "1.2 m" over "6–7 s apart". Null when the row is not recognised. */
export function waveTile(value: string): { value: string; detail: string } | null {
  const match = value.trim().match(WAVES);
  if (!match) return null;
  const apart = seconds(match[2]);
  return { value: `${match[1]} m`, detail: apart ? `${apart} s apart` : "" };
}
