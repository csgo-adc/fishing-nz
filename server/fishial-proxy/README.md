# Fishdays - NZ API (Cloudflare Worker)

The live backend for the Android, iOS and admin web apps, served at `https://fishing.fishnz.space`. It is a Cloudflare Worker with a D1 database (`RULES_DB`). It handles accounts, fish photo identification, MPI fishing rules, feedback and admin analytics.

## Accounts and passwords

| Route | Purpose |
| --- | --- |
| `POST /v1/auth/register`, `/v1/auth/resend-verification`, `/v1/auth/verify-email` | Email sign-up with a confirmation link |
| `POST /v1/auth/login`, `/v1/auth/logout` | Start and end one session |
| `POST /v1/auth/logout-all` | End every session for the signed-in account |
| `POST /v1/me/password` | Change password: `{ "current_password", "new_password" }`. Other sessions end; this one stays. |
| `GET/POST /forgot-password`, `GET/POST /reset-password` | Web pages for a forgotten password. The emailed link opens a form; opening it never uses the link up. |
| `GET/PATCH/DELETE /v1/me`, `GET /v1/me/permissions` | Profile, permissions, in-app account deletion |
| `GET/POST /delete-account` | Web deletion request that works without the app |
| `/v1/auth/providers`, `/v1/auth/oauth/*` | Google and Apple sign-in, see [account-sign-in.md](../../docs/account-sign-in.md) |

A reset link lasts 1 hour, works once, ends every session and also confirms the email address. It works for Google and Apple accounts that want to add a password. A "password was changed" notice goes to the account email after a reset or change.

## Shared fishing windows

| Route | Purpose |
| --- | --- |
| `POST /v1/share` | Keep a shared window for 60 days and return a short id: `{"token"}` in, `{"id","url","expires_at"}` out. No sign-in. 30 an hour per network address. |
| `GET /v1/share/<id>` | The window behind a short id, for the apps. `404` when unknown or expired. |
| `GET /w/<id or token>` | Web page for a window shared from the Android or iPhone app: phone and desktop layout, link-preview tags, download buttons, "Open in app". `<id>` is a stored short link and `<token>` holds the whole window, so it needs no storage. A bad or expired link gets a friendly 404 page. |
| `GET /.well-known/apple-app-site-association`, `/.well-known/assetlinks.json` | Let iPhones and Android open `/w/*` links in the app. Built from `APPLE_TEAM_ID` and `ANDROID_CERT_SHA256`. |
| `GET /share/icon.png` | Page icon and link-preview image |

`IOS_DOWNLOAD_URL` and `ANDROID_DOWNLOAD_URL` are the store buttons and stay `#` until the apps are published. The format, limits and setup steps are in [share-fishing-window.md](../../docs/share-fishing-window.md).

## Abuse limits

Counters live in `rate_limit_counters` as fixed windows. Keys are one-way hashes, and the daily cleanup deletes counters older than a day. A limited request returns `429` with `code: "rate_limited"` and a `Retry-After` header.

| Action | Limit |
| --- | --- |
| Failed sign-ins from one network | 30 per 15 minutes |
| Failed sign-ins for one email from one network | 5 per 15 minutes |
| Failed sign-ins for one email from anywhere | 20 per 15 minutes |
| Failed current-password checks when changing a password | 5 per 15 minutes per account |
| Sign-ups, verification resends, reset requests from one network | 10 per hour each |
| Reset requests for one email | 5 per hour, and one email per minute |
| Feedback | 10 per hour per account |
| Analytics events | 60 per minute per account |
| Fish identifications | 5 per New Zealand day per account |

Reset requests always receive the same answer whether or not the email is registered, throttled or undeliverable. Sign-in always runs the password hash, so unknown emails take as long as wrong passwords. Only failed sign-ins are counted, so a person who knows their password is not slowed down by it.

## Analytics and API usage

Behaviour analytics work with or without an account. Apps send them only when the person has turned analytics on.

| Route | Purpose |
| --- | --- |
| `POST /v1/analytics/batch` | Register or refresh a device and store up to 50 events. A valid `Authorization: Bearer` token also links the device and events to that account; an invalid one is ignored. |
| `DELETE /v1/analytics/device` | Erase everything stored for the device in `x-device-id`. The apps call it when analytics is switched off. |
| `POST /v1/analytics/events` | Older signed-in route for `app_opened` / `feature_used`; still accepted. |

**Device identity.** The app makes a random UUID on first use of analytics (never an advertising or hardware id), keeps it on the device, and sends it as `device.id` in batches and as the `x-device-id` header on API calls. `device` also carries `platform`, `os_version`, `device_model`, `app_version`, `locale` and `time_zone`. Signing in links the device to the account, so an account's timeline includes what its devices did before sign-in.

**Events.** Only names in `EVENT_NAMES` and properties in `PROP_KEYS` (`src/analytics.ts`) are stored; anything else is dropped, so coordinates, emails and free text cannot be recorded by mistake. Each event may carry `offset_ms` (how long ago it happened) so queued events keep their order without trusting the phone's clock.

**API usage.** Every `/v1` call with a valid account session and/or an `x-device-id` header adds one to `api_usage_daily`, grouped by day, account, device and route (`fish_identify`, `rules`, `auth`, `account`, `feedback`, `other`). Errors (status 400 and up) are counted separately. Admin and analytics calls are not counted, and neither is the call that deletes an account.

**Retention.** Events, device records and usage counts are deleted after 90 days by the daily cleanup. Deleting an account removes its events and usage and those of every device linked to it.

Admin routes (header `x-account-admin-token`), all `GET`:

| Route | Returns |
| --- | --- |
| `/v1/admin/analytics/overview?days=30` | Active devices, anonymous devices, active accounts, events and API calls; daily series; events, screens and routes ranked; devices by app version |
| `/v1/admin/analytics/subjects?type=user\|device&search=&sort=calls\|events\|recent\|newest&scope=anonymous\|linked&limit=&offset=` | Accounts or devices ranked with API calls, errors and event counts |
| `/v1/admin/analytics/users/:id` | One account: devices, API use by route and day, event counts, a 100-event timeline, fish identification use, feedback count |
| `/v1/admin/analytics/devices/:id` | One device: its details and linked account, API use, event counts and timeline |

## Develop and test

```sh
npm ci
npm test            # Miniflare + real local D1; no network or Cloudflare account needed
npx wrangler dev    # local Worker
```

`GET /__health` returns `{ "ok": true, "version": "..." }`. The version comes from `package.json`, which `python3 tools/version.py` keeps in step with the apps.

## Deploy

**From GitHub (recommended).** Add two repository secrets under Settings → Secrets and variables → Actions: `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`. The token needs Account → Workers Scripts: Edit and D1: Edit, plus Zone → Workers Routes: Edit and DNS: Edit on the `fishnz.space` zone. Then open Actions → **Deploy** → Run workflow on `main`. It checks the credentials, runs these tests, applies pending database migrations, deploys the Worker, confirms `/__health` reports this version, and (optional) deploys the admin CMS. It never runs on its own.

**From your machine:**

```sh
npx wrangler d1 migrations apply RULES_DB --remote   # run before deploying code that needs new tables
npm run deploy
```

Required secrets: `OPENAI_API_KEY`, `RULES_INGEST_TOKEN`, `ACCOUNT_ADMIN_TOKEN`, `RESEND_API_KEY`. Public settings (`AUTH_BASE_URL`, `ACCOUNT_EMAIL_FROM`, `PUBLIC_DEVELOPER_NAME`, `PUBLIC_SUPPORT_EMAIL`) are in `wrangler.toml`. The password pages need `RESEND_API_KEY` and `ACCOUNT_EMAIL_FROM`, just like email confirmation.
