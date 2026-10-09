import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { loadWorker } from "./load-worker.mjs";

const worker = await loadWorker();
const env = { AUTH_BASE_URL: "https://fishing.test" };
// Sunday 11 October 2026, 6:00-8:00 PM New Zealand daylight time (UTC+13).
const start = Date.UTC(2026, 9, 11, 5, 0, 0) / 1000;
const window = {
  v: 1, n: "Takapuna Beach", a: "Auckland", b: 0, s: start, e: start + 7200, la: -36.7871, lo: 174.7705,
  o: ["🙂", "Okay"], f: ["🗓️", "Planning forecast"], r: "Lower discomfort", t: start - 86_400,
  c: [["Wind", "12 km/h · gust 20 · SW", "😌", "Comfortable"], ["Tide", "Next high 7:12 PM · 2.4 m CD\nAuckland (Waitematā)", "🕒", "Tide timing"]],
};
const token = (value) => Buffer.from(typeof value === "string" ? value : JSON.stringify(value)).toString("base64url");
const get = (path, overrides = {}, init = {}) => worker.fetch(new Request(`https://fishing.test${path}`, init), { ...env, ...overrides });

test("a shared window opens as a readable page without the app", async () => {
  const response = await get(`/w/${token(window)}`);
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /^text\/html/);
  const html = await response.text();
  for (const text of ["Takapuna Beach", "Auckland", "Land fishing", "Sunday 11 October", "6:00 PM – 8:00 PM", "New Zealand time",
    "Okay", "Planning forecast", "Lower discomfort", "Next high 7:12 PM · 2.4 m CD<br>Auckland (Waitematā)", "Tide timing",
    "Check before you go", "Fishdays - NZ is an independent app"]) assert.ok(html.includes(text), text);
  assert.ok(!html.includes("This window has passed") || start * 1000 < Date.now());
  assert.match(html, /<meta name="robots" content="noindex,nofollow">/);
  assert.match(html, /<meta property="og:title" content="Takapuna Beach · Sun 11 Oct, 6:00 PM–8:00 PM">/);
  assert.match(html, /<meta property="og:image" content="https:\/\/fishing\.test\/share\/icon\.png">/);
  assert.match(html, /<link rel="canonical" href="https:\/\/fishing\.test\/w\/[A-Za-z0-9_-]+">/);
  assert.match(html, /google\.com\/maps\/search\/\?api=1&amp;query=-36\.7871,174\.7705/);
  assert.match(html, /data-open-ios="nz\.fishingnz\.catchcheck:\/\/w\//);
  assert.match(html, /data-open-android="intent:\/\/fishing\.test\/w\/[A-Za-z0-9_-]+#Intent;scheme=https;package=nz\.fishingnz\.app;S\.browser_fallback_url=/);
});

test("the page allows only its own inline script and nothing else", async () => {
  const response = await get(`/w/${token(window)}`);
  const html = await response.text();
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
  assert.equal(scripts.length, 1);
  const hash = createHash("sha256").update(scripts[0][1]).digest("base64");
  const policy = response.headers.get("content-security-policy");
  assert.ok(policy.includes(`script-src 'sha256-${hash}'`), policy);
  for (const directive of ["default-src 'none'", "frame-ancestors 'none'", "base-uri 'none'", "form-action 'none'", "img-src 'self'"]) assert.ok(policy.includes(directive), directive);
  assert.equal(response.headers.get("x-robots-tag"), "noindex, nofollow");
  assert.equal(response.headers.get("referrer-policy"), "no-referrer");
});

test("download buttons stay as # until the store pages are configured", async () => {
  const html = await (await get(`/w/${token(window)}`)).text();
  assert.match(html, /<a id="store-ios" class="btn soon" href="#" aria-disabled="true">/);
  assert.match(html, /<a id="store-android" class="btn soon" href="#" aria-disabled="true">/);
  assert.match(html, /App Store link coming soon/);
  const live = await (await get(`/w/${token(window)}`, {
    IOS_DOWNLOAD_URL: "https://apps.apple.com/app/id123456789", ANDROID_DOWNLOAD_URL: "https://play.google.com/store/apps/details?id=nz.fishingnz.app",
  })).text();
  assert.match(live, /<a id="store-ios" class="btn" href="https:\/\/apps\.apple\.com\/app\/id123456789" rel="noopener">/);
  assert.match(live, /<a id="store-android" class="btn" href="https:\/\/play\.google\.com\/store\/apps\/details\?id=nz\.fishingnz\.app" rel="noopener">/);
  assert.ok(!live.includes("coming soon"));
  const unsafe = await (await get(`/w/${token(window)}`, { IOS_DOWNLOAD_URL: "javascript:alert(1)", ANDROID_DOWNLOAD_URL: "http://insecure.example" })).text();
  assert.ok(!unsafe.includes("javascript:"));
  assert.match(unsafe, /<a id="store-ios" class="btn soon" href="#"/);
  assert.match(unsafe, /<a id="store-android" class="btn soon" href="#"/);
});

test("text from a link is escaped, cleaned and limited", async () => {
  const hostile = {
    ...window, n: "<img src=x onerror=alert(1)>Evil", a: `"><script>alert(1)</script>`, r: "A‮B\u0000C   D",
    c: [["<b>Wind</b>", "x".repeat(500), "<", "&"], ["Rain", "dry", "🌤️", "Mostly dry"]],
  };
  const html = await (await get(`/w/${token(hostile)}`)).text();
  assert.ok(!html.includes("<img src=x"), "name must be escaped");
  assert.ok(!html.includes("<script>alert(1)"), "area must be escaped");
  assert.ok(html.includes("&lt;img src=x onerror=alert(1)&gt;Evil"));
  assert.ok(!html.includes("‮") && !html.includes("\u0000"));
  assert.ok(html.includes("A B C D"));
  assert.ok(!html.includes("x".repeat(200)), "values are capped");
  assert.equal([...html.matchAll(/<script>/g)].length, 1);
  const long = await (await get(`/w/${token({ ...window, n: "N".repeat(300) })}`)).text();
  assert.ok(long.includes(`${"N".repeat(79)}…`));
});

test("a window that has already finished says so, and a boat window says boat", async () => {
  const html = await (await get(`/w/${token({ ...window, s: 1_735_700_000, e: 1_735_707_200, b: 1, la: undefined, lo: undefined })}`)).text();
  assert.ok(html.includes("This window has passed"));
  assert.ok(html.includes("Boat fishing"));
  assert.ok(!html.includes("google.com/maps"), "no map links without coordinates");
});

test("a window that runs past midnight shows both days", async () => {
  const late = Date.UTC(2026, 9, 11, 9, 0, 0) / 1000; // 10:00 PM NZDT
  const html = await (await get(`/w/${token({ ...window, s: late, e: late + 7200 })}`)).text();
  assert.match(html, /10:00 PM – Mon 12 Oct, 12:00 AM/);
});

test("damaged, incomplete or forged links get a friendly 404 page", async () => {
  const bad = [
    "not-valid-base64!", "", "a", token("not json"), token("[]"), token({ ...window, v: 2 }), token({ ...window, n: "" }),
    token({ ...window, e: window.s }), token({ ...window, e: window.s + 90_000 }), token({ ...window, s: 12 }), token({ ...window, s: "soon" }),
    "A".repeat(5000),
  ];
  for (const value of bad) {
    const response = await get(`/w/${value}`);
    assert.equal(response.status, 404, value.slice(0, 30));
    const html = await response.text();
    assert.ok(html.includes("This link can't be opened"));
    assert.ok(html.includes("Download for iPhone"));
    assert.ok(!html.includes("<button class=\"btn top-open\"") && !html.includes("Open in the app"), "nothing to open");
  }
  assert.equal((await get("/w")).status, 404);
});

test("only reading is allowed on share routes", async () => {
  const response = await get(`/w/${token(window)}`, {}, { method: "POST", body: "x" });
  assert.equal(response.status, 405);
  assert.equal((await get(`/w/${token(window)}`, {}, { method: "HEAD" })).status, 200);
});

test("the iPhone association file lists the app only once a team id is set", async () => {
  const empty = await get("/.well-known/apple-app-site-association");
  assert.equal(empty.status, 200);
  assert.match(empty.headers.get("content-type"), /^application\/json/);
  assert.deepEqual(await empty.json(), { applinks: { details: [] } });
  const set = await (await get("/.well-known/apple-app-site-association", { APPLE_TEAM_ID: "abcde12345" })).json();
  assert.deepEqual(set.applinks.details, [{ appIDs: ["ABCDE12345.nz.fishingnz.catchcheck"], components: [{ "/": "/w/*", comment: "Shared fishing windows" }] }]);
  const wrong = await (await get("/.well-known/apple-app-site-association", { APPLE_TEAM_ID: "short" })).json();
  assert.deepEqual(wrong.applinks.details, []);
});

test("the Android asset links file lists valid certificate fingerprints only", async () => {
  const one = "37:B7:79:41:88:76:24:F6:05:43:A0:84:48:40:1E:AF:87:26:74:53:5C:D0:99:C4:81:36:96:52:3F:74:59:F6";
  const two = one.toLowerCase().replace("37", "aa");
  const links = await (await get("/.well-known/assetlinks.json", { ANDROID_CERT_SHA256: `${one}, ${two}, nonsense` })).json();
  assert.deepEqual(links, [{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: "nz.fishingnz.app", sha256_cert_fingerprints: [one, two.toUpperCase()] },
  }]);
  assert.deepEqual(await (await get("/.well-known/assetlinks.json")).json(), []);
});

test("the preview icon is a PNG", async () => {
  const response = await get("/share/icon.png");
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "image/png");
  const bytes = new Uint8Array(await response.arrayBuffer());
  assert.deepEqual([...bytes.slice(0, 8)], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
});

test("the link format the Android and iPhone apps write is read the same way", async () => {
  // The same token is decoded by the Android unit tests and the iPhone regression checks.
  const golden = "eyJ2IjoxLCJuIjoiVGFrYXB1bmEgQmVhY2giLCJhIjoiQXVja2xhbmQiLCJiIjowLCJzIjoxNzkxNjk0ODAwLCJlIjoxNzkxNzAyMDAwLCJsYSI6LTM2Ljc4NzEsImxvIjoxNzQuNzcwNSwibyI6WyLwn5mCIiwiT2theSJdLCJmIjpbIvCfl5PvuI8iLCJQbGFubmluZyBmb3JlY2FzdCJdLCJyIjoiTG93ZXIgZGlzY29tZm9ydCIsInQiOjE3OTE2MDg0MDAsImMiOltbIldpbmQiLCIxMiBrbS9oIMK3IGd1c3QgMjAgwrcgU1ciLCLwn5iMIiwiQ29tZm9ydGFibGUiXSxbIlRpZGUiLCJOZXh0IGhpZ2ggNzoxMiBQTSDCtyAyLjQgbSBDRFxuQXVja2xhbmQgKFdhaXRlbWF0xIEpIiwi8J-VkiIsIlRpZGUgdGltaW5nIl1dfQ";
  const response = await get(`/w/${golden}`);
  assert.equal(response.status, 200);
  const html = await response.text();
  for (const text of ["Takapuna Beach", "Auckland", "Land fishing", "Sunday 11 October", "6:00 PM – 8:00 PM", "Okay", "Planning forecast",
    "Lower discomfort", "12 km/h · gust 20 · SW", "Comfortable", "Next high 7:12 PM · 2.4 m CD<br>Auckland (Waitematā)", "Tide timing",
    "Forecast snapshot from Saturday 10 October"]) assert.ok(html.includes(text), text);
});
