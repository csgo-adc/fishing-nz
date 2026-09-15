# CatchCheck NZ

CatchCheck NZ is a native Kotlin + Jetpack Compose fishing companion for New Zealand. The name makes the promise clear: plan a catch, identify a fish, and check whether it is legal to keep.

Android package: `nz.fishingnz.app`

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

The photo picker, preview, loading state and result card are implemented in `MainActivity.kt`. `identifyFishPhoto()` is currently a deliberately marked demo adapter that returns a sample snapper result. For production, replace that adapter with a secured server-side vision endpoint and connect its species output to an authoritative, region-aware MPI rules dataset. Do not ship a provider API key in the Android client, and always show uncertainty plus the official-rules disclaimer.

Open the project in Android Studio and run the `app` configuration on an emulator or Android device.

## Architecture

The app follows MVVM with a Compose UI:

- `MainActivity.kt` only creates the activity and starts the app.
- `ui/` contains the app shell and screen composables.
- `viewmodel/` owns `FishingUiState`, user actions, and coroutine lifecycle.
- `data/` contains repository boundaries for weather, tide, and fish identification.
- `model/` contains shared data models and sample catalogue data.

This keeps API calls and mutable state out of composables, making the vision provider and official rules repository replaceable without rewriting the screens.
