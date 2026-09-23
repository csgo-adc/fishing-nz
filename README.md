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

Every push to `main` builds a debug APK with GitHub Actions. Open the repository's **Actions** tab, select the latest **Android APK** run, then download the **CatchCheckNZ-debug** artifact from its summary. The artifact is kept for 90 days.

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
4. Deploy it with `npm run deploy`. The existing Worker API is available at `https://fishing.ct518.online`.
5. Add `FISH_ID_API_BASE_URL=https://fishing.ct518.online` to the ignored root `secrets.properties` file before building Android.
6. In Xcode, set the `FishIdentificationAPIBaseURL` value in the iOS app's `Info.plist` to the same address (or move it into an xcconfig for separate build environments).

The Worker has a daily Cron Trigger at 16:00 UTC. It attempts one fishing area per day, rotating through all eight areas over an eight-day cycle to honor MPI's 10-second `robots.txt` crawl delay. Successful pages are saved to D1 with their source, fetch time, review date, content hash, and original HTML. Failures are recorded at `GET https://fishing.ct518.online/v1/rules/status`; a failed attempt leaves any previous saved rules intact. The mobile app reads rules through the Pages frontend and the public `GET https://fishing.ct518.online/v1/rules?area=<area-id>` API.

For a manual import, install the crawler dependency with `python3 -m pip install -r tools/requirements.txt`, then run `python3 tools/crawl_mpi_rules.py --api-base-url https://fishing.ct518.online/v1/rules --source direct`. The crawler follows the 10-second delay, imports only complete page fetches, and keeps a local SQLite copy in `data/mpi_fishing_rules.sqlite3`. It needs the ignored token file. If MPI blocks the crawler's network, it stops without importing partial or challenge-page data.

The UI shows confidence as an AI suggestion only. It intentionally does not declare a catch legal: connect its species output to an authoritative, region-aware MPI rules source before presenting size or bag-limit advice.

The Rules tab on Android and iOS opens the CatchCheck rules page at [catchcheck-nz-rules.pages.dev](https://catchcheck-nz-rules.pages.dev). That Pages frontend requests cached area data from the Cloudflare Worker API, which reads the `catchcheck-rules` D1 database. If no cached record is available, the page links to MPI's official area rules. The fish-photo result still uses a small offline Auckland/Kermadec lookup as a demonstration and should not be treated as a live legal check.

Open the project in Android Studio and run the `app` configuration on an emulator or Android device.

## Architecture

The app follows MVVM with a Compose UI:

- `MainActivity.kt` only creates the activity and starts the app.
- `ui/` contains the app shell and screen composables.
- `viewmodel/` owns `FishingUiState`, user actions, and coroutine lifecycle.
- `data/` contains repository boundaries for weather, tide, and fish identification.
- `model/` contains shared data models and sample catalogue data.

This keeps API calls and mutable state out of composables, making the vision provider and official rules repository replaceable without rewriting the screens.
