# Changelog

## 1.0.10 — 2026-10-08 (build code 11)

- Android: our own anonymous usage statistics are now **on by default after a one-time notice**
  ("Keep sharing" / "Turn off"). Nothing is collected before the notice is answered, and a
  person who had turned analytics off stays off. Google Analytics (Firebase) is now a
  separate switch that stays off unless turned on. Settings → Terms & privacy has both switches.
- Android: add **Change password** (or **Add a password** for Google/Apple accounts) and
  **Sign out of all devices** to the Account screen.
- Update the Play Data safety notes: usage statistics and the install id are collected by
  default. **Re-answer the Data safety form in Play Console before releasing this build.**

## 1.0.9 — 2026-10-08 (build code 10)

- API: account objects (`/v1/me`, sign-in, social sign-in and the admin user list) now include
  `has_password`, so the apps can offer "Change password" or "Add a password".
- API: accept `password_changed`, `signed_out_everywhere` and `account_deleted` analytics events.
- Privacy policy: anonymous usage statistics are on by default after a one-time notice and
  can be turned off in Settings; Google Analytics stays opt-in on Android; account deletion
  is available in the Android and iPhone apps. **Deploy this before the app updates that
  introduce the notice.**

## 1.0.8 — 2026-10-08 (build code 9)

- Add a manual **Deploy** workflow (Actions → Deploy). It checks the Cloudflare secrets, runs
  the API tests, applies pending D1 migrations, deploys the Worker, confirms the live
  version, and can deploy the admin CMS. It needs the repository secrets
  `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` and never runs on its own.
- Document the GitHub and local deploy routes in the API README.

## 1.0.7 — 2026-10-08 (build code 8)

- iOS: add the same optional behaviour tracking as Android, behind a new **Optional usage
  analytics** switch in More → Settings → Terms & privacy (off by default), with the same
  allowlisted events, random install id, device details and API-usage header. Turning it
  off discards queued events and asks the server to erase the device's data.
- iOS: stop sending the phone's coordinates with fish photos. The service never used
  them, and the privacy policy says they are not sent.
- iOS: the Forgot password button records an analytics event; the app version and build
  number now come from the project settings that `tools/version.py` updates
  (`Info.plist` previously fixed them at 0.1 and 1).

## 1.0.6 — 2026-10-08 (build code 7)

- Android: add behaviour tracking behind the existing optional analytics switch, which
  stays off by default. When on, the app makes a random install id and sends allowlisted
  events (app open, screen views, searches, spots opened, fish identification
  started/succeeded/failed, rules area and tide station choices, sign-in steps,
  feedback) with device model, Android version, app version, language and time
  zone, whether or not the person is signed in. API requests carry the id so usage can
  be counted. No location, photos, email or typed text is sent.
- Android: turning analytics off, or deleting the account, discards queued events and asks
  the server to erase the device's data.
- Update the in-app analytics explanation and the Play Data safety notes.

## 1.0.5 — 2026-10-08 (build code 6)

- Admin CMS: add a **Usage analysis** page (`/admin/analytics`) with an overview
  (active and anonymous devices, accounts, events, API calls), ranked accounts and
  devices with search, sort and paging, and a detail view for any account or device
  showing its API use by route and day, what it did, and a timeline that includes
  activity from before the account signed in.
- Forward the read-only analysis routes through the CMS proxy.

## 1.0.4 — 2026-10-08 (build code 5)

- Worker: add behaviour analytics that work without an account. Apps register a
  random device id with device details and send allowlisted events through
  `POST /v1/analytics/batch`; `DELETE /v1/analytics/device` erases a device.
- Worker: count API calls per day by route for each signed-in account and each
  device that sends `x-device-id`.
- Worker: add admin analysis endpoints under `/v1/admin/analytics/` (overview, ranked
  accounts and devices, and per-account and per-device detail with timelines).
- Add D1 migration `0010_device_analytics_and_api_usage.sql`. **Apply it with
  `wrangler d1 migrations apply RULES_DB --remote` before deploying the Worker.**
- Delete analytics after 90 days, and when an account is deleted. Update the
  privacy policy. Move shared HTTP helpers into `http.ts`.
- The apps do not send these events yet; Android and iOS instrumentation follow.

## 1.0.3 — 2026-10-08 (build code 4)

- Record the Git workflow in `AGENTS.md`: commit directly to `main` and leave
  Claude out of commit messages. No application changes.

## 1.0.2 — 2026-10-08 (build code 3)

- Fix the new Checks workflow: install `tools/requirements.txt` before running the
  Python tool tests, which need BeautifulSoup.

## 1.0.1 — 2026-10-08 (build code 2)

- Add password reset: **Forgot password?** on both sign-in screens opens a web
  flow with a 1-hour, single-use emailed link that sets a new password, ends all
  sessions and confirms the email. Add signed-in `POST /v1/me/password` and
  `POST /v1/auth/logout-all` (API only; no in-app screen yet).
- Throttle failed sign-ins, sign-ups, verification resends, reset requests and
  feedback. Unknown emails now take as long as wrong passwords at sign-in, and the
  rules-ingest token is compared in constant time.
- Add D1 migration `0009_password_reset_and_rate_limits.sql`. **Apply it with
  `wrangler d1 migrations apply RULES_DB --remote` before deploying the Worker.**
- Show the API version at `/__health`; keep the Worker `package.json` in step with
  `version.properties`; add `server/fishial-proxy/README.md`.
- Remove the unused FastAPI scaffold in `backend/`; the Cloudflare Worker is the
  backend. Update the privacy policy for reset emails and hashed abuse counters.

## 1.0.0 — 2026-10-07 (build code 1)

- Preserve the existing application code at Git tag `v1.0.0`.
- Establish shared version tracking and require a version bump for future
  changes and commits.
- The previously submitted Google Play bundle remains 0.1 (version code 1).
  Future uploads must use a higher build code.
