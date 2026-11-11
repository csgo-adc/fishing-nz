# Updating the fishing rules

Both apps show MPI's recreational fishing rules for the 8 Fisheries New Zealand areas. They keep every area on the
phone and download an area again when its version in `/v1/rules/manifest` changes, so a rules update in the API
reaches the apps on their next sync, with no app release.

## Why updates need a browser

MPI's website sits behind Imperva bot protection. Since late September 2026 every automated request (the Worker's
daily check from Cloudflare, a script on a Mac, GitHub Actions) gets an "anti-bot" interstitial instead of the page,
so no server can download the rules by itself. Fishdays - NZ does not try to get around that protection. Instead:

1. **The API notices when an area may be out of date** and emails a reminder (below).
2. **You save the area's page in your normal browser and upload it in the CMS.** The API reads it, shows exactly
   which limits changed, and publishes it when you agree.

If MPI ever allows the Worker's user agent (`CatchCheckNZ-rules-crawler/1.0`) or offers an official data feed, the
daily check in `src/index.ts` (`refreshRuleArea`) will start reporting changes again. Asking MPI for that is the only
way to make updates fully automatic.

## Updating an area in the CMS (Rules page)

1. Open cms.fishnz.space → **Rules** → **Update** on the area.
2. **Open the MPI page** with the button (opens mpi.govt.nz in your browser).
3. **Save it**: Chrome or Edge, Ctrl+S (⌘S on a Mac) → *Webpage, HTML only*. Safari: File → Save As → *Page Source*.
   Or open the page source (Ctrl+U / ⌥⌘U), select all, copy, and paste it into the box.
4. **Upload** the saved file and press **Preview changes**. The API parses it exactly like
   `tools/crawl_mpi_rules.py` (`src/rules-parse.ts`, checked against all 8 pages captured in September) and lists every
   rule row that changed, was added or was removed, with before and after. It refuses another area's page or a page
   without rule tables, and warns when the page is older than the saved rules, has no "Last reviewed" date, or would
   remove most of the saved rows (usually a partly saved page).
5. **Publish**. The area's rules, review date and version change at once; the apps download them on their next sync.
   The Rules page keeps the last 30 publishes under **Recent updates**.

The bundled copy inside the apps (`data/offline_rules.json`, used only before the first sync) is refreshed at release
time with `python3 tools/build_offline_rules.py --rules`, which reads this API, not MPI.

## Reminder emails

The daily cron (16:00 UTC, early morning in New Zealand) checks every saved area and emails the reminder address when
an area:

- was saved more than *N* days ago (default 30, set 7–365 on the Rules page), or
- has a date in MPI's own text (for example "From 15 October 2026: 2-year pāua closure…") that has passed since the
  rules were saved, which usually means MPI has changed the page, or
- was reported changed by the daily check, if MPI ever lets it through.

One email lists every area due, with the reasons and a link to the CMS Rules page. The same list is repeated at most
once a week; a new reason sends it sooner. Dates still to come are shown on the Rules page and in the preview.

The reminder address defaults to `PUBLIC_SUPPORT_EMAIL`; change it or switch reminders off on the Rules page. Sending
uses the account email setup (`RESEND_API_KEY`, `ACCOUNT_EMAIL_FROM`). The link uses `CMS_BASE_URL` in `wrangler.toml`.

## Admin API

All need the `x-account-admin-token` header; the CMS reaches them through `/api/cms/rules…`.

| Route | Purpose |
| --- | --- |
| `GET /v1/admin/rules` | Each area's saved review date, when it was saved, reasons to check it, dates to come; reminder settings; recent publishes |
| `POST /v1/admin/rules/preview` | `{ "area_id", "html" }` → what would change. Saves nothing. |
| `POST /v1/admin/rules/publish` | `{ "area_id", "html" }` → saves the area and returns the overview |
| `PUT /v1/admin/rules/reminders` | `{ "enabled", "email", "stale_days" }` |

Settings live in `app_settings` (`rules.reminders`, `rules.history`), added by migration `0014_app_settings.sql`.
The Python tools (`tools/crawl_mpi_rules.py`, `tools/import_manual_mpi_rules.py` with `/v1/rules/import`) still work
for bulk imports.
