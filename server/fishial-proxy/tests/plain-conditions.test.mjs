import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import test from "node:test";

// The same cases as Android's PlainConditionsTest and the iPhone plainWordChecks.
const bundled = await build({
  entryPoints: [fileURLToPath(new URL("../src/plain-conditions.ts", import.meta.url))],
  bundle: true, write: false, format: "esm", target: "es2022", platform: "browser",
});
const { plainValue, waveTile, windStrength, windTile } =
  await import(`data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString("base64")}`);

test("shared condition rows read in plain words", () => {
  const cases = [
    ["Wind", "22 km/h · gust 28 · S", "Breezy: 22 km/h, gusts to 28 km/h, from the south"],
    ["Wind", "8 km/h · gust 15 · SW/W", "Light wind: 8 km/h, gusts to 15 km/h, from SW, then W"],
    ["Wind", "12 km/h · gust 20", "Gentle breeze: 12 km/h, gusts to 20 km/h"],
    ["Offshore waves", "1.2 m · 6.5–7.0 s", "Up to 1.2 m, 7 seconds apart"],
    ["Offshore waves", "1.4 m · 5.0–5.5 s", "Up to 1.4 m, 5–6 seconds apart"],
    ["Offshore waves", "—", "—"],
    ["Rain", "0.4 mm · peak 0.3 mm/h · 40% hourly", "0.4 mm in total, up to 40% chance in any hour"],
    ["Rain", "0.0 mm · peak 0.0 mm/h · 10% hourly", "No rain expected, up to 10% chance in any hour"],
    ["Rain", "1.2 mm · peak 0.8 mm/h · chance —", "1.2 mm in total"],
    ["Rain", "0.0 mm · peak 0.0 mm/h · 0% hourly", "No rain expected"],
    ["Tide", "Next high 7:12 PM · 2.4 m CD\nAuckland (Waitematā)", "Next high tide 7:12 PM, 2.4 m\nTide station: Auckland (Waitematā)"],
    ["Tide", "low Thu 15 Oct, 1:20 AM · 0.4 m CD\nLINZ Auckland · 3.0 km away", "Low tide Thu 15 Oct, 1:20 AM, 0.4 m\nTide station: Auckland, 3.0 km away"],
    ["Daylight", "100% session", "All in daylight"], ["Daylight", "75% session", "75% in daylight"],
    ["Daylight", "100% visit", "Whole visit in daylight"], ["Daylight", "0% known time", "All after dark"],
    ["Shore plan", "Beach · before 20 min · return 15 min", "Beach · 20 min walk in · 15 min back"],
    ["Shore plan", "Not set · before — · return —", "Shore type not set · walk-in time not set · return time not set"],
    ["Wind", "14 km/h · gust 22 · SW and a few more…", "14 km/h · gust 22 · SW and a few more…"],
    ["Feels like", "14–16°C", "14–16°C"],
  ];
  for (const [title, value, expected] of cases) assert.equal(plainValue(title, value), expected, `${title}: ${value}`);
  assert.deepEqual([11, 12, 20, 30, 40].map(windStrength), ["Light wind", "Gentle breeze", "Breezy", "Windy", "Very windy"]);
});

test("key numbers keep the number and explain it underneath", () => {
  assert.deepEqual(windTile("20 km/h · gust 45 · NW"), { value: "20 km/h", detail: "Breezy · gusts 45 · from NW" });
  assert.deepEqual(windTile("12 km/h · gust 20"), { value: "12 km/h", detail: "Gentle breeze · gusts 20" });
  assert.deepEqual(windTile("5 km/h · gust 17 · SW/SE/E"), { value: "5 km/h", detail: "Light wind · gusts 17 · from SW to E" });
  assert.deepEqual(waveTile("1.4 m · 5.0–5.5 s"), { value: "1.4 m", detail: "5–6 s apart" });
  assert.equal(windTile("—"), null);
});
