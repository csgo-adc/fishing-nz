# CatchCheck NZ

CatchCheck NZ is a cross-platform fishing companion that helps New Zealand anglers plan trips, identify fish, and check catch rules.

CatchCheck NZ is a native Kotlin + Jetpack Compose fishing companion for New Zealand. The name makes the promise clear: plan a catch, identify a fish, and check whether it is legal to keep.

## Repository apps

- `app/` — native Android app using Kotlin and Jetpack Compose
- `iosApp/` — native iOS app using Swift and SwiftUI
- `web/` — public Next.js website with an `/admin` workspace
- `backend/` — shared Python FastAPI service for all clients

Run the web app with `cd web && npm run dev`. Run the API with the setup instructions in [`backend/README.md`](backend/README.md).

Android package: `nz.fishingnz.app`

## Download an Android build

Every push to `main` builds a debug APK with GitHub Actions and publishes it as a prerelease on the repository's **Releases** page. Open the latest **Android build** prerelease and download `CatchCheckNZ-debug.apk`. The workflow summary links directly to the release.

The workflow creates temporary app configuration and never commits it. To include working Firebase Analytics and Google Maps in the downloadable APK, add repository Actions secrets named `GOOGLE_SERVICES_JSON` (the Firebase config JSON contents) and `MAPS_API_KEY`. Without these optional secrets the build still succeeds, but Firebase uses a CI-only config and Google Maps is unavailable. Fish identification uses the existing public API endpoint.

The repository also contains the native SwiftUI iOS app at [`iosApp/`](iosApp/README.md). Open `iosApp/CatchCheckNZ.xcodeproj` in Xcode to run it on iOS 17 or later. Android and iOS currently share product behaviour and API contracts, while their UI and device integrations remain native to each platform.

The launcher icon is an Android adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` use a separate navy background and safe-zone foreground so the fish/checkmark remains clear in circular, squircle, and legacy rectangular launcher shapes. The original generated concept is retained at `app/src/main/res/drawable/catchcheck_icon.png`.

Firebase is configured through `app/google-services.json`. Analytics records an `app_opened` event as a connection check.

Google Maps uses a local `secrets.properties` file. Copy `secrets.properties.example` to `secrets.properties` and set `MAPS_API_KEY`; this file is ignored by Git.

The Map tab is configured with the `fishing-nz` Google Cloud project. The current debug key is restricted to **Maps SDK for Android**, package `nz.fishingnz.app`, and the local debug SHA-1 certificate. Billing and the Maps SDK are enabled, and the map has been verified on the Android emulator. If tiles appear blank after a fresh install, wait a few seconds for Google Play services to initialize and relaunch the app; the screen reports a configuration message after 8 seconds if initialization still fails.

## Demo flow

- Choose land or boat fishing on the home screen.
- Choose “Find the best time” or “Find the best location”.
- View ranked sample recommendations with time, rating, distance, reasons and safety logic.
- Explore the live Google Map, selectable NZ tide stations, Trips and Fishing rules tabs.
- Choose a fish photo from the home screen and view the fish-identification + rules-check result card.

The home recommendations and spot catalogue are still local sample data. Weather is fetched from Open-Meteo using the current device coordinates. Tide forecasts use Open-Meteo Marine sea-level data, with selectable coastal stations across New Zealand and a real station-specific curve. Location uses Android's fused location provider. Tide data is a model forecast and should not replace official LINZ predictions or navigation information.

## Fish identification

Fish identification uses OpenAI vision through the existing server-side proxy. Android and iOS send the selected photo to `POST /v1/fish/identify`; the proxy asks `gpt-5.6-luna` for a likely species, prioritizing the New Zealand common name, and returns the scientific name and an AI confidence estimate. The OpenAI API key stays in the Worker secret and never goes into either mobile app. The existing API response remains compatible with both apps.

To configure the included Cloudflare Worker (the `server/fishial-proxy` folder and Worker name are retained so the existing deployment can be updated in place):

1. In `server/fishial-proxy`, run `npm install`, then set `OPENAI_API_KEY` with `npx wrangler secret put OPENAI_API_KEY`.
2. Apply the fishing-rules database migration with `npx wrangler d1 migrations apply catchcheck-rules --remote`.
3. Create a strong random token, save it in the ignored `server/fishial-proxy/.rules-ingest-token` file, and set the same token with `npx wrangler secret put RULES_INGEST_TOKEN`.
4. Deploy it with `npm run deploy`. The existing Worker API is available at `https://fishing.fishnz.space`.
5. Add `FISH_ID_API_BASE_URL=https://fishing.fishnz.space` to the ignored root `secrets.properties` file before building Android.
6. In Xcode, set the `FishIdentificationAPIBaseURL` value in the iOS app's `Info.plist` to the same address (or move it into an xcconfig for separate build environments).

The Worker has a daily Cron Trigger at 16:00 UTC. It attempts one fishing area per day, rotating through all eight areas over an eight-day cycle to honor MPI's 10-second `robots.txt` crawl delay. Successful pages are saved to D1 with their source, fetch time, review date, content hash, and original HTML. Failures are recorded at `GET https://fishing.fishnz.space/v1/rules/status`; a failed attempt leaves any previous saved rules intact. The mobile app reads rules through the Pages frontend and the public `GET https://fishing.fishnz.space/v1/rules?area=<area-id>` API.

## Accounts, feedback, and feature access

The Cloudflare Worker and its `catchcheck-rules` D1 database provide the account backend, email verification, and usage analytics. Confirm a sending domain with Resend, create an API key, then configure both email secrets, apply the account tables, and deploy:

```sh
cd server/fishial-proxy
npx wrangler secret put RESEND_API_KEY
npx wrangler secret put ACCOUNT_EMAIL_FROM
npx wrangler d1 migrations apply catchcheck-rules --remote
npx wrangler secret put ACCOUNT_ADMIN_TOKEN
npm run deploy
```

Set `ACCOUNT_EMAIL_FROM` to a sender on the domain verified with Resend, for example `CatchCheck NZ <accounts@your-domain.nz>`. Registration creates a pending account and sends a 24-hour confirmation link. Only confirmed users can sign in. The confirmation page asks the user to press a button, so automated email scanners do not activate accounts just by opening the link. Resend sends transactional messages using its authenticated email API ([Resend API example](https://resend.com/docs/api-reference/emails/send-email)).

Keep the admin token private. It lets an administrator list accounts and feedback and set a user's plan using the admin endpoints below. New accounts start on `free`; only an administrator can set `paid`. Paid access currently has to be granted manually. Payment processing and automatic subscription renewal are not connected yet.

The API provides `POST /v1/auth/register`, `/v1/auth/login`, `/v1/auth/resend-verification`, and `/v1/auth/logout`, `GET`/`PATCH /v1/me`, `GET /v1/me/permissions`, `POST /v1/feedback`, and `POST /v1/analytics/events`. Confirmed users receive bearer sessions; unconfirmed accounts cannot sign in. Profiles store email, display name, and a two-letter country code. Passwords are stored as PBKDF2 hashes, confirmation tokens and session tokens are stored as hashes, and sessions expire after 30 days.

Admin endpoints use `x-account-admin-token: <ACCOUNT_ADMIN_TOKEN>`: `GET /v1/admin/analytics`, `GET /v1/admin/users`, `GET /v1/admin/feedback`, and `PATCH /v1/admin/users/<user-id>/plan` with `{"plan":"paid"}` or `{"plan":"free"}`. The `/admin` web CMS uses an HTTP-only admin session cookie and shows total/confirmed/pending users, plan counts, daily sign-ups, daily active users, feature usage by platform, user plans, and recent feedback. Feature analytics contain event type, feature, platform, and timestamp for signed-in users; fish-identification use and feedback are logged by the Worker. The fish-identification endpoint checks the account plan server-side: signed-out requests receive `401`, free accounts receive `403`, and paid accounts can continue. Other existing public functionality remains available.

The website, Android app, and iOS app include email registration with confirmation, sign-in, profile editing, feedback submission, and plan-aware fish-identification controls. Mobile sessions are stored in Android Keystore-backed encrypted storage and the iOS Keychain; the website keeps its bearer token in an HTTP-only cookie through its server-side account proxy. Set `FISH_ID_API_BASE_URL=https://fishing.fishnz.space` in the web app’s server environment when deploying the Next.js app with Route Handler support. Password reset, account self-service deletion, and billing integration are not part of this first account release. Analytics are recorded for signed-in accounts only.

For a manual import, install the crawler dependency with `python3 -m pip install -r tools/requirements.txt`, then run `python3 tools/crawl_mpi_rules.py --api-base-url https://fishing.fishnz.space/v1/rules --source direct`. The crawler follows the 10-second delay, imports only complete page fetches, and keeps a local SQLite copy in `data/mpi_fishing_rules.sqlite3`. It needs the ignored token file. If MPI blocks the crawler's network, it stops without importing partial or challenge-page data.

The UI shows confidence as an AI suggestion only. It intentionally does not declare a catch legal: connect its species output to an authoritative, region-aware MPI rules source before presenting size or bag-limit advice.

The Rules tab on Android and iOS opens the CatchCheck rules page at [fishnz.space](https://fishnz.space). That Pages frontend requests cached area data from the Cloudflare Worker API at `https://fishing.fishnz.space`, which reads the `catchcheck-rules` D1 database. If no cached record is available, the page links to MPI's official area rules. The fish-photo result still uses a small offline Auckland/Kermadec lookup as a demonstration and should not be treated as a live legal check.

Open the project in Android Studio and run the `app` configuration on an emulator or Android device.

## Architecture

The app follows MVVM with a Compose UI:

- `MainActivity.kt` only creates the activity and starts the app.
- `ui/` contains the app shell and screen composables.
- `viewmodel/` owns `FishingUiState`, user actions, and coroutine lifecycle.
- `data/` contains repository boundaries for weather, tide, and fish identification.
- `model/` contains shared data models and sample catalogue data.

This keeps API calls and mutable state out of composables, making the vision provider and official rules repository replaceable without rewriting the screens.
