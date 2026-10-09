import { escapeHtml } from "./privacy";
import type { SharedWindow } from "./share";

export type ShareConfig = {
  /** Public origin of the API, e.g. https://fishing.fishnz.space. Links into the apps always use it. */
  origin: string;
  /** App Store and Google Play pages, or "#" until the apps are published. */
  iosUrl: string;
  androidUrl: string;
};

const IOS_BUNDLE_SCHEME = "nz.fishingnz.catchcheck";
const ANDROID_PACKAGE = "nz.fishingnz.app";
const ZONE = "Pacific/Auckland";
const longDayParts = new Intl.DateTimeFormat("en-NZ", { timeZone: ZONE, weekday: "long", day: "numeric", month: "long" });
const shortDayParts = new Intl.DateTimeFormat("en-NZ", { timeZone: ZONE, weekday: "short", day: "numeric", month: "short" });
const clock = new Intl.DateTimeFormat("en-US", { timeZone: ZONE, hour: "numeric", minute: "2-digit", hour12: true });
const calendarDay = new Intl.DateTimeFormat("en-CA", { timeZone: ZONE });

// Runs in the visitor's browser. Static on purpose: the page's CSP allows exactly this text by hash, and the
// per-link values it needs are read from data attributes on <body>.
const PAGE_SCRIPT = `(function () {
  var root = document.documentElement, body = document.body, ua = navigator.userAgent || "";
  var ios = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
  var android = /Android/.test(ua);
  root.setAttribute("data-platform", ios ? "ios" : android ? "android" : "desktop");
  var wechat = /MicroMessenger/i.test(ua);
  if (wechat || /FBAN|FBAV|Instagram|Line\\/|Snapchat|TikTok|musical_ly|Twitter/i.test(ua)) {
    var hint = document.getElementById("hint");
    if (hint) hint.hidden = false;
    var zh = document.getElementById("hint-zh");
    if (zh && wechat) zh.hidden = false;
  }
  var missing = document.getElementById("not-installed");
  Array.prototype.forEach.call(document.querySelectorAll("[data-open-app]"), function (button) {
    button.addEventListener("click", function (event) {
      event.preventDefault();
      var target = body.getAttribute(ios ? "data-open-ios" : "data-open-android");
      if (!target) return;
      window.location.href = target;
      if (ios && missing) setTimeout(function () { if (!document.hidden) missing.hidden = false; }, 1500);
    });
  });
  Array.prototype.forEach.call(document.querySelectorAll("[aria-disabled=true]"), function (link) {
    link.addEventListener("click", function (event) { event.preventDefault(); });
  });
  Array.prototype.forEach.call(document.querySelectorAll("[data-copy-link]"), function (button) {
    var label = button.textContent;
    button.addEventListener("click", function () {
      var done = function () { button.textContent = "Link copied"; setTimeout(function () { button.textContent = label; }, 2000); };
      if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(window.location.href).then(done, function () {}); return; }
      var field = document.createElement("textarea");
      field.value = window.location.href; field.setAttribute("readonly", ""); field.style.position = "fixed"; field.style.opacity = "0";
      document.body.appendChild(field); field.select();
      try { if (document.execCommand("copy")) done(); } catch (error) {}
      document.body.removeChild(field);
    });
  });
})();`;

/** "Sunday 11 October": built from parts so punctuation does not vary between runtimes. */
function dayLabel(format: Intl.DateTimeFormat, date: Date): string {
  const parts = Object.fromEntries(format.formatToParts(date).map((part) => [part.type, part.value]));
  return `${parts.weekday} ${parts.day} ${parts.month}`;
}
const longDay = { format: (date: Date) => dayLabel(longDayParts, date) };
const shortDay = { format: (date: Date) => dayLabel(shortDayParts, date) };

let scriptHash: Promise<string> | undefined;
function pageScriptHash(): Promise<string> {
  scriptHash ??= crypto.subtle.digest("SHA-256", new TextEncoder().encode(PAGE_SCRIPT))
    .then((digest) => btoa(String.fromCharCode(...new Uint8Array(digest))));
  return scriptHash;
}

const STYLE = `
:root{color-scheme:light dark;--bg:#f6f8fc;--surface:#fff;--text:#172235;--muted:#536179;--line:#dde4f0;--primary:#315de0;--on-primary:#fff;--soft:#e5ecff;--on-soft:#17367f;--note:#fff6e8;--on-note:#6b3f00;--shadow:0 1px 2px rgba(23,34,53,.06),0 8px 24px rgba(23,34,53,.06)}
@media (prefers-color-scheme:dark){:root{--bg:#0d1523;--surface:#162235;--text:#eaf1fc;--muted:#a9b6cc;--line:#2a3a55;--primary:#afc5ff;--on-primary:#122d73;--soft:#263c69;--on-soft:#dce6ff;--note:#3a2c12;--on-note:#ffd9a0;--shadow:none}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--text);font:16px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
a{color:var(--primary)}
.top{display:flex;align-items:center;justify-content:space-between;gap:12px;max-width:1040px;margin:0 auto;padding:14px 16px}
.brand{display:flex;align-items:center;gap:10px;font-weight:700;color:var(--text);text-decoration:none}
.brand img{width:36px;height:36px;border-radius:9px;display:block}
.layout{display:grid;gap:20px;max-width:1040px;margin:0 auto;padding:4px 16px 32px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:20px;padding:22px;box-shadow:var(--shadow)}
.kicker{margin:0 0 6px;font-size:12px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--primary)}
h1{margin:0;font-size:clamp(28px,6vw,40px);line-height:1.15;overflow-wrap:anywhere}
h2{margin:0 0 10px;font-size:18px;line-height:1.3}
.meta{margin:6px 0 0;color:var(--muted)}
.chip{display:inline-block;margin-left:6px;padding:2px 10px;border-radius:999px;background:var(--soft);color:var(--on-soft);font-size:13px;font-weight:600;vertical-align:1px}
.when{margin:20px 0 0;padding:16px 18px;border-radius:16px;background:var(--soft);color:var(--on-soft)}
.when .day{font-size:15px;font-weight:600}
.when .hours{font-size:clamp(26px,5.5vw,34px);font-weight:800;line-height:1.2;margin:2px 0}
.when .zone{font-size:13px;opacity:.85}
.passed{display:inline-block;margin-top:10px;padding:2px 10px;border-radius:999px;background:var(--note);color:var(--on-note);font-size:13px;font-weight:600}
.outlook{display:flex;align-items:center;gap:14px;margin:18px 0 0}
.outlook .emoji{font-size:38px;line-height:1}
.outlook .label{font-size:20px;font-weight:700;line-height:1.2}
.outlook .sub,.small{color:var(--muted);font-size:14px}
section{margin-top:24px}
.why{margin:0;padding:14px 16px;border-radius:14px;background:var(--soft);color:var(--on-soft)}
.grid{display:grid;gap:10px;grid-template-columns:repeat(auto-fill,minmax(210px,1fr))}
.cond{padding:12px 14px;border:1px solid var(--line);border-radius:14px}
.cond .t{font-size:12px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}
.cond .v{margin:2px 0 4px;font-weight:600;overflow-wrap:anywhere}
.cond .m{font-size:14px;color:var(--muted)}
.maps{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}
.maps a{padding:7px 14px;border:1px solid var(--line);border-radius:999px;text-decoration:none;font-weight:600;font-size:14px}
.notice{margin-top:24px;padding:14px 16px;border-radius:14px;background:var(--note);color:var(--on-note);font-size:14px}
.get h2{font-size:20px}
.get p{margin:0 0 16px;color:var(--muted)}
.btn{display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:52px;padding:10px 16px;border-radius:14px;border:1px solid var(--primary);background:var(--primary);color:var(--on-primary);font:inherit;font-weight:700;text-decoration:none;text-align:center;cursor:pointer;width:100%}
.btn small{font-size:12px;font-weight:500;opacity:.85}
.btn.ghost{background:transparent;color:var(--primary)}
.btn.soon{background:transparent;color:var(--muted);border-color:var(--line);cursor:default}
.stack{display:grid;gap:10px}
.soon-note{margin:10px 0 0;font-size:13px;color:var(--muted)}
.mobile-only,.top-open{display:none}
html[data-platform=ios] .mobile-only,html[data-platform=android] .mobile-only{display:flex}
html[data-platform=ios] .btn.top-open,html[data-platform=android] .btn.top-open{display:inline-flex;flex-direction:row;width:auto;min-height:38px;padding:6px 16px}
html[data-platform=ios] #store-android,html[data-platform=android] #store-ios{order:2}
.stack>[data-copy-link]{order:3}
#hint{max-width:1040px;margin:0 auto 12px;padding:12px 16px;border-radius:14px;background:var(--note);color:var(--on-note);font-size:14px}
#hint p{margin:0}#hint p+p{margin-top:6px}
#not-installed{margin:10px 0 0;font-size:14px;color:var(--muted)}
footer{max-width:1040px;margin:0 auto;padding:8px 16px 40px;color:var(--muted);font-size:13px}
footer p{margin:0 0 8px}
@media (min-width:880px){
  .top{padding:20px 16px}
  .layout{grid-template-columns:minmax(0,1fr) 340px;align-items:start;gap:24px}
  .card{padding:32px}
  .get{position:sticky;top:24px}
}
@media print{.get,.top-open,#hint{display:none!important}body{background:#fff}.card{box-shadow:none}}
`;

function formatClock(seconds: number): string {
  return clock.format(new Date(seconds * 1000)).replace(/\s/g, " ");
}

function when(shared: SharedWindow) {
  const startDate = new Date(shared.start * 1000);
  const endDate = new Date(shared.end * 1000);
  const sameDay = calendarDay.format(startDate) === calendarDay.format(endDate);
  const range = sameDay
    ? `${formatClock(shared.start)} – ${formatClock(shared.end)}`
    : `${formatClock(shared.start)} – ${shortDay.format(endDate)}, ${formatClock(shared.end)}`;
  const brief = sameDay
    ? `${shortDay.format(startDate)}, ${formatClock(shared.start)}–${formatClock(shared.end)}`
    : `${shortDay.format(startDate)}, ${formatClock(shared.start)} – ${shortDay.format(endDate)}, ${formatClock(shared.end)}`;
  return { day: longDay.format(startDate), range, brief };
}

function storeButton(id: string, label: string, store: string, href: string): string {
  if (href === "#") return `<a id="${id}" class="btn soon" href="#" aria-disabled="true"><span>${label}</span><small>${store} link coming soon</small></a>`;
  return `<a id="${id}" class="btn" href="${escapeHtml(href)}" rel="noopener"><span>${label}</span><small>${store}</small></a>`;
}

function getTheApp(config: ShareConfig, lead: string, canOpen: boolean): string {
  const soon = config.iosUrl === "#" && config.androidUrl === "#";
  return `<aside class="card get" aria-labelledby="get-title">
  <h2 id="get-title">Plan it with Fishdays - NZ</h2>
  <p>${lead}</p>
  <div class="stack">
    ${canOpen ? `<button class="btn mobile-only" type="button" data-open-app><span>Open in the app</span><small>Already installed</small></button>` : ""}
    ${storeButton("store-ios", "Download for iPhone", "App Store", config.iosUrl)}
    ${storeButton("store-android", "Download for Android", "Google Play", config.androidUrl)}
    <button class="btn ghost" type="button" data-copy-link>Copy link</button>
  </div>
  <p id="not-installed" hidden>Nothing opened? The app may not be installed yet.${soon ? "" : " Use a download button above."}</p>
</aside>`;
}

function htmlResponse(title: string, description: string, head: string, body: string, bodyAttributes: string, hash: string, status: number): Response {
  const html = `<!doctype html>
<html lang="en-NZ"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${escapeHtml(title)}</title>
<meta name="description" content="${escapeHtml(description)}">
<meta name="robots" content="noindex,nofollow"><meta name="color-scheme" content="light dark">
<link rel="icon" href="/share/icon.png"><link rel="apple-touch-icon" href="/share/icon.png">
${head}
<style>${STYLE}</style></head>
<body${bodyAttributes}>
${body}
<script>${PAGE_SCRIPT}</script>
</body></html>`;
  return new Response(html, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8", "cache-control": "no-store",
      "referrer-policy": "no-referrer", "x-content-type-options": "nosniff", "x-robots-tag": "noindex, nofollow",
      "content-security-policy": `default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-${hash}'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
    },
  });
}

const HINT = `<div id="hint" role="note" hidden><p>Opened from inside another app? Tap the <strong>⋯</strong> or share menu and choose <strong>Open in browser</strong> (Safari or Chrome). From there you can open this window in Fishdays - NZ or download the app.</p>
<p id="hint-zh" lang="zh-Hans" hidden>请点击右上角的「⋯」，选择「在浏览器中打开」，即可在 Fishdays - NZ 中查看或下载应用。</p></div>`;

function header(canOpen: boolean): string {
  return `<header class="top"><div class="brand"><img src="/share/icon.png" alt="" width="36" height="36"><span>Fishdays - NZ</span></div>
${canOpen ? `<button class="btn top-open" type="button" data-open-app>Open in app</button>` : ""}</header>
${HINT}`;
}

const FOOTER = `<footer>
<p>Fishdays - NZ is an independent app and is not affiliated with or endorsed by the New Zealand Government, MPI or LINZ.</p>
<p>Weather and offshore waves: Open-Meteo. Tide predictions, where verified: LINZ. <a href="/privacy">Privacy policy</a></p>
</footer>`;

export async function renderSharePage(shared: SharedWindow, token: string, config: ShareConfig): Promise<Response> {
  const hash = await pageScriptHash();
  const time = when(shared);
  const mode = shared.boat ? "Boat" : "Land";
  const link = `${config.origin}/w/${token}`;
  const outlookText = shared.outlook ? `${shared.outlook.emoji} ${shared.outlook.label}` : "";
  const title = `${shared.name} · ${time.brief}`;
  const description = [outlookText, `${mode} fishing`, shared.area].filter(Boolean).join(" · ") + ". A fishing window shared from Fishdays - NZ.";
  const passed = shared.end * 1000 < Date.now();
  const image = `${config.origin}/share/icon.png`;
  const head = [
    `<link rel="canonical" href="${escapeHtml(link)}">`,
    `<meta property="og:type" content="website"><meta property="og:site_name" content="Fishdays - NZ">`,
    `<meta property="og:title" content="${escapeHtml(title)}"><meta property="og:description" content="${escapeHtml(description)}">`,
    `<meta property="og:url" content="${escapeHtml(link)}"><meta property="og:image" content="${escapeHtml(image)}">`,
    `<meta property="og:image:width" content="512"><meta property="og:image:height" content="512">`,
    `<meta name="twitter:card" content="summary"><meta name="twitter:title" content="${escapeHtml(title)}"><meta name="twitter:description" content="${escapeHtml(description)}">`,
  ].join("\n");
  const conditions = shared.conditions.length ? `<section aria-labelledby="conditions"><h2 id="conditions">Conditions</h2><div class="grid">${
    shared.conditions.map((row) => `<div class="cond"><div class="t">${escapeHtml(row.title)}</div><div class="v">${escapeHtml(row.value).replace(/\n/g, "<br>")}</div><div class="m">${escapeHtml(row.emoji)} ${escapeHtml(row.label)}</div></div>`).join("")
  }</div></section>` : "";
  const place = shared.latitude !== null && shared.longitude !== null ? (() => {
    const at = `${shared.latitude.toFixed(4)},${shared.longitude.toFixed(4)}`;
    const links: [string, string][] = [
      ["Google Maps", `https://www.google.com/maps/search/?api=1&query=${at}`],
      ["Apple Maps", `https://maps.apple.com/?ll=${at}&q=${encodeURIComponent(shared.name)}`],
      ["OpenStreetMap", `https://www.openstreetmap.org/?mlat=${shared.latitude.toFixed(4)}&mlon=${shared.longitude.toFixed(4)}#map=14/${shared.latitude.toFixed(4)}/${shared.longitude.toFixed(4)}`],
    ];
    return `<section aria-labelledby="where"><h2 id="where">Where</h2><div class="maps">${
      links.map(([label, href]) => `<a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${label}</a>`).join("")
    }</div><p class="small">An area marker, not a surveyed fishing spot. Access, closures and fishing restrictions are unchecked.</p></section>`;
  })() : "";
  const snapshot = shared.sharedAt
    ? `Forecast snapshot from ${longDay.format(new Date(shared.sharedAt * 1000))}. Forecasts change, so open the app for the latest.`
    : "Forecast snapshot. Forecasts change, so open the app for the latest.";
  const body = `${header(true)}
<main class="layout">
<article class="card">
  <p class="kicker">Shared fishing window</p>
  <h1>${escapeHtml(shared.name)}</h1>
  <p class="meta">${escapeHtml(shared.area)}<span class="chip">${mode} fishing</span></p>
  <div class="when"><div class="day">${escapeHtml(time.day)}</div><div class="hours">${escapeHtml(time.range)}</div><div class="zone">New Zealand time</div>${passed ? `<span class="passed">This window has passed</span>` : ""}</div>
  ${shared.outlook ? `<div class="outlook"><span class="emoji" aria-hidden="true">${escapeHtml(shared.outlook.emoji)}</span><div><div class="label">${escapeHtml(shared.outlook.label)}</div><div class="sub">Window outlook${shared.confidence ? ` · ${escapeHtml(shared.confidence.emoji)} ${escapeHtml(shared.confidence.label)}` : ""}</div></div></div>` : ""}
  ${shared.reason ? `<section aria-labelledby="why"><h2 id="why">Why this window</h2><p class="why">${escapeHtml(shared.reason)}</p></section>` : ""}
  ${conditions}
  ${place}
  <div class="notice"><strong>Check before you go.</strong> This compares forecast conditions only. It does not predict catches or confirm it is safe. Check local access, marine warnings and the current MPI fishing rules for your exact spot.</div>
  <p class="small" style="margin:14px 0 0">${escapeHtml(snapshot)}</p>
</article>
${getTheApp(config, "Compare fishing windows, tides, weather and MPI fishing rules around New Zealand. Open this window in the app, or download it.", true)}
</main>
${FOOTER}`;
  const bodyAttributes = ` data-open-ios="${IOS_BUNDLE_SCHEME}://w/${token}" data-open-android="${escapeHtml(androidIntent(config, token, link))}"`;
  return htmlResponse(`${title} · Fishdays - NZ`, description, head, body, bodyAttributes, hash, 200);
}

const UNAVAILABLE = {
  damaged: { status: 404, title: "This link can't be opened", text: "It looks incomplete or damaged, which can happen when a long link is split across messages. Ask the person who sent it to share the window again." },
  gone: { status: 404, title: "This shared window is no longer available", text: "Shared windows are kept for 60 days, or the link was typed wrongly. Ask the person who sent it to share the window again." },
  unavailable: { status: 503, title: "Please try again in a moment", text: "We couldn't load this shared window just now. Refresh the page in a minute." },
} as const;

export async function renderInvalidSharePage(config: ShareConfig, kind: keyof typeof UNAVAILABLE = "damaged"): Promise<Response> {
  const hash = await pageScriptHash();
  const { status, title, text } = UNAVAILABLE[kind];
  const body = `${header(false)}
<main class="layout">
<article class="card">
  <p class="kicker">Shared fishing window</p>
  <h1>${title}</h1>
  <p class="meta">${text}</p>
</article>
${getTheApp(config, "Find your own fishing windows with tides, weather and MPI fishing rules around New Zealand.", false)}
</main>
${FOOTER}`;
  return htmlResponse(`${title} · Fishdays - NZ`, "This shared fishing window could not be opened.", "", body, "", hash, status);
}

/** Chrome on Android opens the app named in the intent even when the link is not a verified App Link, or stays here. */
function androidIntent(config: ShareConfig, token: string, link: string): string {
  const host = new URL(config.origin).host;
  return `intent://${host}/w/${token}#Intent;scheme=https;package=${ANDROID_PACKAGE};S.browser_fallback_url=${encodeURIComponent(link)};end`;
}
