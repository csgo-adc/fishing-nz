import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { Miniflare } from "miniflare";
import { bundleWorker, loadWorker } from "./load-worker.mjs";

const ADMIN = "analytics-test-admin-token-0123456789";
const PASSWORD = "Original123";
const uuid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const DAY = 86_400_000;
const isoAgo = (ms) => new Date(Date.now() - ms).toISOString();
const dayAgo = (days) => isoAgo(days * DAY).slice(0, 10);

async function createWorld(name) {
  const emails = [];
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: name },
    bindings: {
      AUTH_BASE_URL: "https://fishing.test", RESEND_API_KEY: "test-key", ACCOUNT_EMAIL_FROM: "accounts@example.com",
      PUBLIC_DEVELOPER_NAME: "Tristan", PUBLIC_SUPPORT_EMAIL: "support@example.com", ACCOUNT_ADMIN_TOKEN: ADMIN,
    },
    outboundService: async (request) => {
      emails.push(await request.json());
      return Response.json({ id: "test-email" });
    },
  });
  const db = await mf.getD1Database("RULES_DB");
  const migrations = new URL("../migrations/", import.meta.url);
  for (const file of readdirSync(migrations).filter((entry) => entry.endsWith(".sql")).sort()) {
    for (const statement of readFileSync(new URL(file, migrations), "utf8").split(";").filter((sql) => sql.trim())) await db.prepare(statement).run();
  }
  // Each scenario passes its own network address so the abuse limits in one never affect another.
  const api = async (path, method = "GET", body, { token, ip = "198.51.100.1", device, admin, raw } = {}) => {
    const response = await mf.dispatchFetch(`https://fishing.test${path}`, {
      method,
      headers: {
        "cf-connecting-ip": ip,
        ...(body !== undefined || raw ? { "content-type": "application/json" } : {}),
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...(device ? { "x-device-id": device } : {}),
        ...(admin ? { "x-account-admin-token": admin === true ? ADMIN : admin } : {}),
      },
      body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined),
    });
    return { status: response.status, headers: response.headers, data: await response.json().catch(() => null) };
  };
  return { mf, db, emails, api };
}

async function eventually(check, timeout = 4000) {
  const end = Date.now() + timeout;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > end) throw new Error("Timed out waiting for a condition");
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
}

const device = (n, extra = {}) => ({
  id: uuid(n), platform: "android", os_version: "14", device_model: "Pixel 8", app_version: "1.0.4", locale: "en-NZ", time_zone: "Pacific/Auckland", ...extra,
});

test("anonymous devices, behaviour events and API usage with real D1", { timeout: 120_000 }, async (t) => {
  const { mf, db, emails, api } = await createWorld("analytics-ingest-tests");
  try {
    const count = async (sql, ...values) => (await db.prepare(sql).bind(...values).first()).n;
    const usage = (where, ...values) => db.prepare(`SELECT * FROM api_usage_daily WHERE ${where}`).bind(...values).all().then((result) => result.results);
    async function signUp(email, ip) {
      assert.equal((await api("/v1/auth/register", "POST", { email, password: PASSWORD }, { ip })).status, 202);
      const mail = emails.filter((entry) => entry.to[0] === email && entry.subject.includes("Confirm")).at(-1);
      const token = mail.text.match(/token=([a-f0-9]{64})/)[1];
      assert.equal((await api("/v1/auth/verify-email", "POST", { token }, { ip })).status, 200);
      const login = await api("/v1/auth/login", "POST", { email, password: PASSWORD }, { ip });
      assert.equal(login.status, 200);
      return { token: login.data.token, id: login.data.user.id };
    }

    await t.test("stores an anonymous device and only the events and properties on the allowlist", async () => {
      const response = await api("/v1/analytics/batch", "POST", {
        device: device(1),
        events: [
          { name: "app_open" },
          { name: "screen_view", props: { screen: "map", email: "a@b.c", latitude: -36.8, note: "free text" } },
          { name: "search_run", offset_ms: 5000, props: { mode: "boat", radius_km: 80, result_count: 7, origin: "gps" } },
          { name: "not_a_real_event", props: { screen: "x" } },
        ],
      }, { ip: "198.51.100.2" });
      assert.equal(response.status, 202);
      assert.deepEqual(response.data, { accepted: 3, dropped: 1 });

      const stored = await db.prepare("SELECT * FROM analytics_devices WHERE device_id = ?").bind(uuid(1)).first();
      assert.equal(stored.platform, "android");
      assert.equal(stored.device_model, "Pixel 8");
      assert.equal(stored.user_id, null);
      const events = (await db.prepare("SELECT * FROM analytics_events WHERE device_id = ? ORDER BY occurred_at").bind(uuid(1)).all()).results;
      assert.equal(events.length, 3);
      const screen = events.find((event) => event.event_name === "screen_view");
      assert.deepEqual(JSON.parse(screen.props_json), { screen: "map" }, "unknown properties never reach the database");
      const search = events.find((event) => event.event_name === "search_run");
      assert.deepEqual(JSON.parse(search.props_json), { mode: "boat", radius_km: 80, result_count: 7, origin: "gps" });
      const lag = Date.now() - Date.parse(search.occurred_at);
      assert.ok(lag >= 4500 && lag < 15_000, `offset_ms is applied to the server clock (${lag}ms)`);
      assert.ok(!events.some((event) => /a@b\.c|-36\.8|free text/.test(event.props_json)));
    });

    await t.test("rejects malformed uploads", async () => {
      const post = (body, extra) => api("/v1/analytics/batch", "POST", body, { ip: "198.51.100.3", ...extra });
      assert.equal((await post({ events: [{ name: "app_open" }] })).status, 400, "device required");
      assert.equal((await post({ device: device(2, { id: "not-a-uuid" }), events: [{ name: "app_open" }] })).status, 400);
      assert.equal((await post({ device: device(2, { platform: "windows" }), events: [{ name: "app_open" }] })).status, 400);
      assert.equal((await post({ device: device(2), events: [] })).status, 400);
      assert.equal((await post({ device: device(2), events: Array.from({ length: 51 }, () => ({ name: "app_open" })) })).status, 400);
      assert.equal((await post({ device: device(2), events: "app_open" })).status, 400);
      assert.equal((await post(undefined, { raw: "[1, 2]" })).status, 400);
      assert.equal((await post({ device: device(2), events: [{ name: "app_open", props: { screen: "x".repeat(40_000) } }] })).status, 400, "oversized body");
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(2)), 0);
      const upper = await post({ device: device(2, { id: uuid(2).toUpperCase() }), events: [{ name: "app_open" }] });
      assert.equal(upper.status, 202, "device ids are case-insensitive");
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(2)), 1);
    });

    await t.test("a later upload refreshes the device and keeps when it was first seen", async () => {
      const before = await db.prepare("SELECT * FROM analytics_devices WHERE device_id = ?").bind(uuid(1)).first();
      await new Promise((resolve) => setTimeout(resolve, 15));
      const response = await api("/v1/analytics/batch", "POST", { device: device(1, { app_version: "1.0.5", os_version: "15" }), events: [{ name: "app_open" }] }, { ip: "198.51.100.2" });
      assert.equal(response.status, 202);
      const after = await db.prepare("SELECT * FROM analytics_devices WHERE device_id = ?").bind(uuid(1)).first();
      assert.equal(after.app_version, "1.0.5");
      assert.equal(after.os_version, "15");
      assert.equal(after.first_seen_at, before.first_seen_at);
      assert.ok(after.last_seen_at > before.last_seen_at);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(1)), 1);
    });

    await t.test("counts API calls for consenting devices and signed-in accounts, but not telemetry or admin calls", async () => {
      const ip = "198.51.100.4";
      assert.equal((await api("/v1/rules/status", "GET", undefined, { ip })).status, 200);
      assert.equal((await usage("device_id = ? OR user_id != ''", uuid(3))).length, 0, "no device header and no account: not counted");

      assert.equal((await api("/v1/rules/status", "GET", undefined, { ip, device: uuid(3) })).status, 200);
      assert.equal((await api("/v1/rules/status", "GET", undefined, { ip, device: uuid(3) })).status, 200);
      assert.equal((await api("/v1/me", "GET", undefined, { ip, device: uuid(3) })).status, 401);
      assert.equal((await api("/v1/analytics/batch", "POST", { device: device(3), events: [{ name: "app_open" }] }, { ip, device: uuid(3) })).status, 202);
      assert.equal((await api("/v1/admin/analytics/overview", "GET", undefined, { ip, device: uuid(3), admin: true })).status, 200);
      assert.equal((await api("/__health", "GET", undefined, { ip, device: uuid(3) })).status, 200);
      assert.equal((await api("/v1/rules/status", "OPTIONS", undefined, { ip, device: uuid(3) })).status, 204);

      await eventually(async () => (await usage("device_id = ?", uuid(3))).length === 2);
      const rows = await usage("device_id = ? ORDER BY route", uuid(3));
      assert.deepEqual(rows.map((row) => [row.route, row.calls, row.errors, row.user_id]), [["account", 1, 1, ""], ["rules", 2, 0, ""]]);

      const account = await signUp("usage@example.com", ip);
      assert.equal((await api("/v1/me", "GET", undefined, { ip, token: account.token, device: uuid(4) })).status, 200);
      assert.equal((await api("/v1/me/permissions", "GET", undefined, { ip, token: account.token })).status, 200);
      const accountRows = await eventually(async () => {
        const found = await usage("user_id = ? AND route = 'account'", account.id);
        return found.length === 2 && found;
      });
      const withDevice = accountRows.find((row) => row.device_id === uuid(4));
      assert.equal(withDevice.calls, 1, "one row carries both the account and the device that made the call");
      assert.equal(accountRows.find((row) => row.device_id === "").calls, 1, "without a device header it is still counted for the account");
    });

    await t.test("an upload from a signed-in app links the device, including its earlier anonymous activity", async () => {
      const ip = "198.51.100.5";
      assert.equal((await api("/v1/analytics/batch", "POST", { device: device(5), events: [{ name: "app_open" }, { name: "screen_view", props: { screen: "tide" } }] }, { ip })).status, 202);
      const account = await signUp("linked@example.com", ip);
      assert.equal((await api("/v1/analytics/batch", "POST", { device: device(5), events: [{ name: "sign_in_succeeded", props: { method: "password" } }] }, { ip, token: account.token })).status, 202);
      assert.equal((await db.prepare("SELECT user_id FROM analytics_devices WHERE device_id = ?").bind(uuid(5)).first()).user_id, account.id);
      const events = (await db.prepare("SELECT event_name, user_id FROM analytics_events WHERE device_id = ?").bind(uuid(5)).all()).results;
      assert.deepEqual(events.map((event) => event.event_name).sort(), ["app_open", "screen_view", "sign_in_succeeded"]);
      assert.equal(events.filter((event) => event.user_id === account.id).length, 1, "earlier events stay anonymous rows");

      const detail = await api(`/v1/admin/analytics/users/${account.id}`, "GET", undefined, { admin: true });
      assert.equal(detail.status, 200);
      assert.equal(detail.data.user.email, "linked@example.com");
      assert.equal(detail.data.devices.length, 1);
      assert.equal(detail.data.recent_events.length, 3, "the user's timeline includes what the device did before sign-in");
      assert.ok(detail.data.recent_events.some((event) => event.user_id === null && event.event_name === "app_open"));

      // An invalid token never blocks telemetry; the upload is simply treated as anonymous.
      assert.equal((await api("/v1/analytics/batch", "POST", { device: device(6), events: [{ name: "app_open" }] }, { ip, token: "f".repeat(64) })).status, 202);
      assert.equal((await db.prepare("SELECT user_id FROM analytics_devices WHERE device_id = ?").bind(uuid(6)).first()).user_id, null);
    });

    await t.test("accepts the account-management events and the admin list reports has_password", async () => {
      const response = await api("/v1/analytics/batch", "POST", {
        device: device(30), events: [{ name: "password_changed" }, { name: "signed_out_everywhere" }, { name: "account_deleted" }],
      }, { ip: "198.51.100.31" });
      assert.deepEqual(response.data, { accepted: 3, dropped: 0 });
      const users = await api("/v1/admin/users?search=linked", "GET", undefined, { admin: true });
      assert.equal(users.data.users[0].email, "linked@example.com");
      assert.equal(users.data.users[0].has_password, true);
    });

    await t.test("limits uploads per device", async () => {
      const statuses = [];
      for (let attempt = 0; attempt < 62; attempt++) statuses.push((await api("/v1/analytics/batch", "POST", { device: device(7), events: [{ name: "app_open" }] }, { ip: "198.51.100.7" })).status);
      assert.equal(statuses.filter((status) => status === 202).length, 60);
      assert.deepEqual(statuses.slice(60), [429, 429]);
    });

    await t.test("a device can erase everything stored about it", async () => {
      const ip = "198.51.100.8";
      assert.equal((await api("/v1/analytics/batch", "POST", { device: device(8), events: [{ name: "app_open" }, { name: "screen_view", props: { screen: "map" } }] }, { ip })).status, 202);
      assert.equal((await api("/v1/rules/status", "GET", undefined, { ip, device: uuid(8) })).status, 200);
      await eventually(async () => (await usage("device_id = ?", uuid(8))).length === 1);

      assert.equal((await api("/v1/analytics/device", "DELETE", undefined, { ip })).status, 400, "needs the device header");
      assert.equal((await api("/v1/analytics/device", "DELETE", undefined, { ip, device: "nope" })).status, 400);
      const erased = await api("/v1/analytics/device", "DELETE", undefined, { ip, device: uuid(8) });
      assert.equal(erased.status, 200);
      assert.equal(erased.data.deleted, true);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(8)), 0);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_events WHERE device_id = ?", uuid(8)), 0);
      assert.equal((await usage("device_id = ?", uuid(8))).length, 0);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(1)), 1, "other devices are untouched");
    });

    await t.test("deleting an account erases its analytics and those of the devices it used, and nobody else's", async () => {
      const ip = "198.51.100.9";
      const account = await signUp("erase@example.com", ip);
      assert.equal((await api("/v1/analytics/batch", "POST", { device: device(9), events: [{ name: "app_open" }] }, { ip })).status, 202);
      assert.equal((await api("/v1/analytics/batch", "POST", { device: device(9), events: [{ name: "screen_view", props: { screen: "map" } }] }, { ip, token: account.token })).status, 202);
      assert.equal((await api("/v1/me", "GET", undefined, { ip, token: account.token, device: uuid(9) })).status, 200);
      await eventually(async () => (await usage("user_id = ?", account.id)).length > 0);

      const removed = await api("/v1/me", "DELETE", { confirm: true }, { ip, token: account.token });
      assert.equal(removed.status, 200);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(9)), 0);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_events WHERE device_id = ?", uuid(9)), 0);
      assert.equal((await usage("user_id = ? OR device_id = ?", account.id, uuid(9))).length, 0);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(1)), 1);
      assert.ok(await count("SELECT COUNT(*) AS n FROM analytics_events WHERE device_id = ?", uuid(5)) > 0, "another account's device keeps its history");
    });

    await t.test("daily cleanup removes analytics older than 90 days only", async () => {
      const old = isoAgo(120 * DAY);
      await db.prepare("INSERT INTO analytics_devices (device_id, platform, first_seen_at, last_seen_at) VALUES (?, 'ios', ?, ?)").bind(uuid(20), old, old).run();
      await db.prepare("INSERT INTO analytics_events (id, device_id, event_name, occurred_at) VALUES ('stale-event', ?, 'app_open', ?)").bind(uuid(20), old).run();
      await db.prepare("INSERT INTO analytics_events (id, device_id, event_name, occurred_at) VALUES ('old-event-on-live-device', ?, 'app_open', ?)").bind(uuid(1), old).run();
      await db.prepare("INSERT INTO api_usage_daily (day, user_id, device_id, route, calls) VALUES (?, '', ?, 'rules', 4)").bind(dayAgo(120), uuid(1)).run();
      await db.prepare("INSERT INTO api_usage_daily (day, user_id, device_id, route, calls) VALUES (?, '', ?, 'rules', 4)").bind(dayAgo(2), uuid(21)).run();
      const worker = await loadWorker();
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async () => new Response(`<html>${"Fishing rules. ".repeat(50)}</html>`, { headers: { "content-type": "text/html" } });
      try { await worker.scheduled({ scheduledTime: Date.now() }, { RULES_DB: db }); }
      finally { globalThis.fetch = originalFetch; }
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(20)), 0);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_events WHERE id IN ('stale-event', 'old-event-on-live-device')"), 0);
      assert.equal(await count("SELECT COUNT(*) AS n FROM api_usage_daily WHERE day = ?", dayAgo(120)), 0);
      assert.equal(await count("SELECT COUNT(*) AS n FROM api_usage_daily WHERE device_id = ?", uuid(21)), 1);
      assert.equal(await count("SELECT COUNT(*) AS n FROM analytics_devices WHERE device_id = ?", uuid(1)), 1);
    });
  } finally { await mf.dispose(); }
});

test("admin analysis by account and by device", { timeout: 120_000 }, async (t) => {
  const { mf, db, api } = await createWorld("analytics-admin-tests");
  try {
    const U1 = uuid(101);
    const U2 = uuid(102);
    const [Da, Db, Dc, Dd] = [uuid(201), uuid(202), uuid(203), uuid(204)];
    const now = new Date().toISOString();
    for (const [id, email, name] of [[U1, "alice@example.com", "Alice"], [U2, "bob@example.com", "Bob"]]) {
      await db.prepare("INSERT INTO account_users (id, email, password_hash, password_salt, display_name, created_at, updated_at, email_verified) VALUES (?, ?, 'h', 's', ?, ?, ?, 1)")
        .bind(id, email, name, now, now).run();
    }
    const addDevice = (id, user, platform, model, version) =>
      db.prepare("INSERT INTO analytics_devices (device_id, user_id, platform, os_version, device_model, app_version, locale, time_zone, first_seen_at, last_seen_at) VALUES (?, ?, ?, '17', ?, ?, 'en-NZ', 'Pacific/Auckland', ?, ?)")
        .bind(id, user, platform, model, version, isoAgo(5 * DAY), isoAgo(DAY)).run();
    await addDevice(Da, U1, "android", "Pixel 8", "1.0.4");
    await addDevice(Db, U1, "ios", "iPhone16,2", "1.0.4");
    await addDevice(Dc, null, "ios", "iPhone15,4", "1.0.3");
    await addDevice(Dd, null, "android", "Galaxy S23", "1.0.3");
    const addEvent = (device, user, name, props, ago) =>
      db.prepare("INSERT INTO analytics_events (id, device_id, user_id, event_name, props_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?)")
        .bind(crypto.randomUUID(), device, user, name, JSON.stringify(props), isoAgo(ago)).run();
    await addEvent(Da, null, "app_open", {}, 4 * DAY);            // before the account signed in on this device
    await addEvent(Da, U1, "app_open", {}, 3 * DAY);
    await addEvent(Da, U1, "screen_view", { screen: "map" }, 3 * DAY - 1000);
    await addEvent(Da, U1, "screen_view", { screen: "map" }, 3 * DAY - 2000);
    await addEvent(Db, U1, "app_open", {}, 2 * DAY);
    await addEvent(Dc, null, "app_open", {}, 2 * DAY);
    await addEvent(Dc, null, "screen_view", { screen: "tide" }, 2 * DAY - 1000);
    await addEvent(Dc, null, "fish_identify_started", {}, 2 * DAY - 2000);
    await addEvent(Dd, null, "app_open", {}, 60 * DAY);           // outside the 30 day window
    const addUsage = (day, user, device, route, calls, errors) =>
      db.prepare("INSERT INTO api_usage_daily (day, user_id, device_id, route, calls, errors) VALUES (?, ?, ?, ?, ?, ?)").bind(day, user, device, route, calls, errors).run();
    await addUsage(dayAgo(0), U1, Da, "rules", 10, 1);
    await addUsage(dayAgo(0), U1, Da, "fish_identify", 3, 0);
    await addUsage(dayAgo(0), "", Dc, "rules", 5, 0);
    await addUsage(dayAgo(1), U2, "", "account", 2, 0);
    await addUsage(dayAgo(60), U1, Da, "rules", 99, 0);           // outside the window
    const nzToday = new Date().toLocaleDateString("en-CA", { timeZone: "Pacific/Auckland" });
    await db.prepare("INSERT INTO account_identification_usage (user_id, day, used) VALUES (?, ?, 2)").bind(U1, nzToday).run();
    await db.prepare("INSERT INTO account_feedback (id, user_id, category, message, rating, created_at) VALUES ('f1', ?, 'idea', 'More tides', 5, ?)").bind(U1, now).run();

    const admin = (path) => api(path, "GET", undefined, { admin: true });

    await t.test("every analysis route needs the admin token", async () => {
      for (const path of ["/v1/admin/analytics/overview", "/v1/admin/analytics/subjects", `/v1/admin/analytics/users/${U1}`, `/v1/admin/analytics/devices/${Da}`]) {
        assert.equal((await api(path)).status, 401, path);
        assert.equal((await api(path, "GET", undefined, { admin: "wrong-token" })).status, 401, path);
      }
      assert.equal((await api("/v1/admin/analytics/overview", "POST", {}, { admin: true })).status, 404);
      assert.equal((await admin("/v1/admin/analytics/nothing")).status, 404);
    });

    await t.test("overview totals the window and ignores older data", async () => {
      const { status, data } = await admin("/v1/admin/analytics/overview?days=30");
      assert.equal(status, 200);
      assert.equal(data.range_days, 30);
      assert.deepEqual(data.totals, { active_devices: 3, anonymous_devices: 1, active_users: 1, events: 8, api_calls: 20, api_errors: 1 });
      assert.deepEqual(Object.fromEntries(data.events_by_name.map((row) => [row.event_name, [row.events, row.devices]])),
        { app_open: [4, 3], screen_view: [3, 2], fish_identify_started: [1, 1] });
      assert.deepEqual(data.top_screens.map((row) => [row.screen, row.views, row.devices]), [["map", 2, 1], ["tide", 1, 1]]);
      assert.deepEqual(data.api_by_route.map((row) => [row.route, row.calls, row.errors, row.users, row.devices]),
        [["rules", 15, 1, 1, 2], ["fish_identify", 3, 0, 1, 1], ["account", 2, 0, 1, 0]]);
      assert.equal(data.daily.reduce((sum, row) => sum + row.api_calls, 0), 20);
      assert.equal(data.daily.reduce((sum, row) => sum + row.events, 0), 8);
      assert.ok(data.devices_by_version.some((row) => row.platform === "ios" && row.app_version === "1.0.4" && row.devices === 1));

      const wide = await admin("/v1/admin/analytics/overview?days=90");
      assert.equal(wide.data.totals.active_devices, 4, "a wider window includes the older device");
      assert.equal(wide.data.totals.api_calls, 119);
      assert.equal((await admin("/v1/admin/analytics/overview?days=9999")).data.range_days, 90);
    });

    await t.test("lists accounts by API use with their activity", async () => {
      const { data } = await admin("/v1/admin/analytics/subjects?type=user");
      assert.equal(data.total, 2);
      assert.deepEqual(data.subjects.map((row) => row.email), ["alice@example.com", "bob@example.com"]);
      const alice = data.subjects[0];
      assert.deepEqual([alice.api_calls, alice.api_errors, alice.events, alice.devices], [13, 1, 4, 2]);
      assert.ok(alice.last_event_at);
      const bob = data.subjects[1];
      assert.deepEqual([bob.api_calls, bob.events, bob.devices, bob.last_event_at], [2, 0, 0, null]);
      assert.deepEqual((await admin("/v1/admin/analytics/subjects?type=user&search=bob")).data.subjects.map((row) => row.email), ["bob@example.com"]);
      assert.equal((await admin("/v1/admin/analytics/subjects?type=user&search=%25")).data.total, 0, "wildcards are escaped");
      assert.deepEqual((await admin("/v1/admin/analytics/subjects?type=user&limit=1&offset=1")).data.subjects.map((row) => row.email), ["bob@example.com"]);
      assert.equal((await admin(`/v1/admin/analytics/subjects?search=${"x".repeat(121)}`)).status, 400);
      assert.equal((await admin("/v1/admin/analytics/subjects?type=user&sort=events;DROP TABLE account_users")).status, 200, "unknown sorts fall back to the default");
    });

    await t.test("lists devices, separating anonymous ones from those linked to an account", async () => {
      const all = await admin("/v1/admin/analytics/subjects?type=device&days=90");
      assert.equal(all.data.total, 4);
      assert.deepEqual(all.data.subjects.slice(0, 2).map((row) => row.device_id), [Da, Dc], "most API calls first");
      const alice = all.data.subjects.find((row) => row.device_id === Da);
      assert.deepEqual([alice.user_email, alice.api_calls, alice.events, alice.device_model, alice.platform], ["alice@example.com", 112, 4, "Pixel 8", "android"]);
      const anonymous = (await admin("/v1/admin/analytics/subjects?type=device&scope=anonymous")).data.subjects;
      assert.deepEqual(anonymous.map((row) => row.device_id).sort(), [Dc, Dd].sort());
      assert.ok(anonymous.every((row) => row.user_id === null && row.user_email === null));
      assert.deepEqual((await admin("/v1/admin/analytics/subjects?type=device&scope=linked")).data.subjects.map((row) => row.device_id).sort(), [Da, Db].sort());
      assert.deepEqual((await admin("/v1/admin/analytics/subjects?type=device&search=iphone15")).data.subjects.map((row) => row.device_id), [Dc]);
      assert.deepEqual((await admin("/v1/admin/analytics/subjects?type=device&search=alice")).data.subjects.map((row) => row.device_id).sort(), [Da, Db].sort());
      const recent = await admin("/v1/admin/analytics/subjects?type=device&sort=newest");
      assert.equal(recent.status, 200);
    });

    await t.test("shows one account in depth", async () => {
      const { status, data } = await admin(`/v1/admin/analytics/users/${U1}`);
      assert.equal(status, 200);
      assert.equal(data.user.email, "alice@example.com");
      assert.equal(data.user.email_verified, true);
      assert.equal(data.devices.length, 2);
      assert.deepEqual(data.api_by_route.map((row) => [row.route, row.calls, row.errors]), [["rules", 10, 1], ["fish_identify", 3, 0]]);
      assert.deepEqual(data.api_by_day.map((row) => row.calls), [13]);
      assert.deepEqual(Object.fromEntries(data.events_by_name.map((row) => [row.event_name, row.events])), { app_open: 3, screen_view: 2 });
      assert.equal(data.recent_events.length, 5, "includes the event from before this account signed in");
      assert.ok(data.recent_events[0].occurred_at >= data.recent_events.at(-1).occurred_at, "newest first");
      assert.deepEqual(data.recent_events.find((event) => event.event_name === "screen_view").props, { screen: "map" });
      assert.equal(data.fish_identification.today.limit, 5);
      assert.equal(data.fish_identification.today.used, 2);
      assert.equal(data.fish_identification.history.length, 1);
      assert.equal(data.feedback_count, 1);
      assert.ok(data.first_event_at < data.last_event_at);
    });

    await t.test("shows one device in depth", async () => {
      const { data } = await admin(`/v1/admin/analytics/devices/${Da.toUpperCase()}`);
      assert.equal(data.device.user_email, "alice@example.com");
      assert.equal(data.device.device_model, "Pixel 8");
      assert.equal(data.recent_events.length, 4);
      assert.deepEqual(data.api_by_route.map((row) => [row.route, row.calls]), [["rules", 10], ["fish_identify", 3]]);
      const anonymous = await admin(`/v1/admin/analytics/devices/${Dc}`);
      assert.equal(anonymous.data.device.user_email, null);
      assert.deepEqual(anonymous.data.api_by_route.map((row) => [row.route, row.calls]), [["rules", 5]]);
      assert.deepEqual(anonymous.data.recent_events.map((event) => event.event_name), ["fish_identify_started", "screen_view", "app_open"]);
    });

    await t.test("reports missing or malformed ids as not found", async () => {
      assert.equal((await admin(`/v1/admin/analytics/users/${uuid(999)}`)).status, 404);
      assert.equal((await admin(`/v1/admin/analytics/devices/${uuid(999)}`)).status, 404);
      assert.equal((await admin("/v1/admin/analytics/users/not-an-id")).status, 404);
      assert.equal((await admin("/v1/admin/analytics/devices/1%27%20OR%201=1")).status, 404);
    });
  } finally { await mf.dispose(); }
});
