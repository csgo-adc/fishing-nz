# Fishing NZ Android app

Native Kotlin + Jetpack Compose Android app for Fishing NZ.

Android package: `nz.fishingnz.app`

Firebase is configured through `app/google-services.json`. Analytics records an `app_opened` event as a connection check.

Google Maps uses a local `secrets.properties` file. Copy `secrets.properties.example` to `secrets.properties` and set `MAPS_API_KEY`; this file is ignored by Git.

## Demo flow

- Choose land or boat fishing on the home screen.
- Choose “Find the best time” or “Find the best location”.
- View ranked sample recommendations with time, rating, distance, reasons and safety logic.
- Explore the live Google Map, selectable NZ tide stations, Trips and Fishing rules tabs.

The home recommendations and spot catalogue are still local sample data. Weather is fetched from Open-Meteo using the current device coordinates. Tide forecasts use Open-Meteo Marine sea-level data, with selectable coastal stations across New Zealand and a real station-specific curve. Location uses Android's fused location provider. Tide data is a model forecast and should not replace official LINZ predictions or navigation information.

Open the project in Android Studio and run the `app` configuration on an emulator or Android device.
