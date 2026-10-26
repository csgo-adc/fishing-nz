# Google Play listing — Fishdays - NZ

## Listing fields

**App name:** Fishdays - NZ

**Developer:** Tristan

**Support email:** tc199558@gmail.com

**Short description:** Plan NZ fishing days: tides, weather, fishing windows, spots and bag limits

**Privacy policy URL (deploy before use):** https://fishing.fishnz.space/privacy

**Account deletion URL (deploy before use):** https://fishing.fishnz.space/delete-account

**Translations:** Simplified Chinese (zh-CN), Traditional Chinese (zh-TW), Korean (ko-KR) and Hindi (hi-IN) are in
`listing-translations.md`. Change them together with this English text.

## Full description

Plan your next fishing day around New Zealand with tides, weather, fishing spots and size and bag limits in one free app.

FIND A FISHING WINDOW
Tell Fishdays - NZ whether you fish from land or a boat and where you are. Home shows today's tide curve, wind, temperature, the moon and sunset, then finds the best two-hour fishing window near you for today, tomorrow, this weekend or the next three days. Each window shows the wind, tide and rain, with warnings for gusts, rain, swell and cold. Share a window with friends in one tap.

SPOTS, RAMPS AND WHARVES
Browse named fishing spots on a map or in a list, nearest first. Boat mode shows public boat ramps; land mode shows wharves, breakwaters and groynes. Marine reserves and other no-fishing areas are marked, with a warning when a spot is in or near one.

TIDES
See official high and low tide times and heights for the nearest tide station, and slide across the estimated tide curve between them. The Tides home-screen widget shows the curve, the next tide, the moon and the wind.

SIZE AND BAG LIMITS
Choose your MPI fishing area to see minimum sizes and daily limits for finfish, sharks and rays, shellfish and crayfish, with pictures of each species. Check my catch compares a length with the minimum size. The rules are kept on your phone for when there's no signal.

CATCH LOG AND TRIPS
Log each catch with its length, a photo and notes. The app checks the length against your area's minimum and records the tide, wind, waves and moon at the time. Save spots and plan trips: the forecast, tides and rules are saved for use offline, the app checks the forecast again before you go, and Tell someone shares your plan and back-by time.

WHAT FISH IS THIS?
Signed in, take or choose a photo for an AI species suggestion with the matching rules, up to five a day. You choose whether to send each photo, which goes to OpenAI through the Fishdays - NZ service. Results include uncertainty guidance and can be reported in the app.

WEATHER
Current conditions, an hourly outlook and up to 16 days of forecasts for your location or saved places.

LANGUAGES
Use the app in English, 简体中文 (Simplified Chinese), 繁體中文 (Traditional Chinese), 한국어 (Korean) or हिन्दी (Hindi). It follows your phone's language, or choose one in Settings → Language. Fishing rules quoted from MPI stay in English, as published.

PRIVACY
Location, camera and notifications are optional: choose places by hand or use the system photo picker. Planning, maps, tides and rules work without an account. An optional account syncs trips and the catch log between your devices. Anonymous usage statistics are explained when you first open the app and can be turned off in Settings. Delete your account in the app or on our website.

Fishdays - NZ is an independent app and is not affiliated with or endorsed by the New Zealand Government, Fisheries New Zealand, MPI or LINZ.

Official fishing rules:
https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/

Official tide predictions:
https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions

Forecasts use Open-Meteo. A marked fishing spot does not guarantee public access or permission to fish. Suggested windows do not guarantee a safe trip or a catch. Tide heights between published events are estimates, and weather can change actual water levels. AI fish identification and saved rule summaries can be wrong or out of date. Always confirm the species, exact fishing area, current official rules, local restrictions and marine warnings before fishing or keeping a catch.

## Artwork and accessibility descriptions

- `icon-512.png`: 512×512, 32-bit PNG with opaque alpha; exported from existing brand artwork. Resolve brand rights before publication.
- `feature-graphic-1024x500.png`: 1024×500, RGB PNG. Alt text: “Fishdays - NZ helps plan a fishing day around tides, weather and places to fish.”
- `phone-1.png` to `phone-6.png`: the current phone screenshots, 1080×1920 RGB PNGs, in Play's order:
  1. Home. Alt text: “Today's tide curve, wind, moon and sunset, and the best fishing window near you.”
  2. Best fishing window. Alt text: “A two-hour fishing window with wind, tide, rain and a cold warning.”
  3. Spots map. Alt text: “Fishing spots and no-fishing areas on a map around Auckland.”
  4. Fishing rules. Alt text: “Snapper minimum size and daily limit with a size bar and species picture.”
  5. Tide curve. Alt text: “Estimated tide curve with the time now marked and the official highs and lows.”
  6. Weather. Alt text: “Current forecast and the next 24 hours for your location.”

The screenshots were taken on 27 October 2026 from the signed 1.0.57 (58) release build on a 1080×1920, 420 dpi
Android 37.1 emulator (AVD `FishdaysPlay`), with the location set to Auckland and live forecasts, tides and rules,
without invented results or added device frames. The status bar uses Android's demo mode (clock set to the real
time, full battery and Wi-Fi). Refresh them when the interface changes: `node` with `web/node_modules/sharp`
removes the alpha channel, as `tools/export_google_play_assets.cjs` does.

**AI asset declaration.** Play Console's store listing asks which assets were generated or edited with AI. Labelled:
the app icon (it began as an ImageGen concept, see `assets/branding/README.md`) and screenshot 4, whose species
pictures were drawn with an image model (see `docs/data-sources.md`). Not labelled: the feature graphic (drawn from
the included SVG) and the other screenshots. Keep this accurate when assets change.
