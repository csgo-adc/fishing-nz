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

## Develop and test

```sh
npm ci
npm test            # Miniflare + real local D1; no network or Cloudflare account needed
npx wrangler dev    # local Worker
```

`GET /__health` returns `{ "ok": true, "version": "..." }`. The version comes from `package.json`, which `python3 tools/version.py` keeps in step with the apps.

## Deploy

```sh
npx wrangler d1 migrations apply RULES_DB --remote   # run before deploying code that needs new tables
npm run deploy
```

Required secrets: `OPENAI_API_KEY`, `RULES_INGEST_TOKEN`, `ACCOUNT_ADMIN_TOKEN`, `RESEND_API_KEY`. Public settings (`AUTH_BASE_URL`, `ACCOUNT_EMAIL_FROM`, `PUBLIC_DEVELOPER_NAME`, `PUBLIC_SUPPORT_EMAIL`) are in `wrangler.toml`. The password pages need `RESEND_API_KEY` and `ACCOUNT_EMAIL_FROM`, just like email confirmation.
