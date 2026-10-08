# Changelog

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
