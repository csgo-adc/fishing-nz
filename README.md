# CatchCheck NZ Android app

CatchCheck NZ is a native Kotlin + Jetpack Compose fishing companion for New Zealand. The name makes the promise clear: plan a catch, identify a fish, and check whether it is legal to keep.

Android package: `nz.fishingnz.app`

The launcher icon is an Android adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` use a separate navy background and safe-zone foreground so the fish/checkmark remains clear in circular, squircle, and legacy rectangular launcher shapes. The original generated concept is retained at `app/src/main/res/drawable/catchcheck_icon.png`.

Firebase is configured through `app/google-services.json`. Analytics records an `app_opened` event as a connection check.

Google Maps uses a local `secrets.properties` file. Copy `secrets.properties.example` to `secrets.properties` and set `MAPS_API_KEY`; this file is ignored by Git.

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
