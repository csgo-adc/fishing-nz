# Map conditions and weather

The Android and iOS Map tabs now open **Explore conditions**. Users can search for a town, beach or named fishing area, select an existing marker, or tap an arbitrary position. The selected-place card has a primary **Check conditions** action. Existing map layers, attribution, location controls and planning/detail actions remain available. Search distinguishes published tide stations, land areas and boat areas.

## Checking dates

- **Upcoming** starts with today and displays the dates actually returned by either weather or marine data.
- **Recent days** starts with yesterday, with 3, 7, 30 or 92 days of history. The default request includes 3 recent days.
- Weather requests the documented maximum of **16 forecast days**. Marine requests **8 forecast days**. Both permit up to **92 past days**. Missing values can occur within those ranges; the date strip and availability text use returned data.
- Recent data is labelled **Recent model data**. It is model output, not measured weather, a record of what the forecast said on that date, or a fishing report.
- Dates, hourly timestamps, sunrise and sunset use the response's local timezone. Unix timestamps represent actual instants. They are not shifted a second time by the UTC offset. Complete marine days can contain 23, 24 or 25 hourly records across daylight-saving changes.

Provider references: [Weather API](https://open-meteo.com/en/docs), [Marine API](https://open-meteo.com/en/docs/marine-weather-api).

## Short descriptions

The main card shows wind, offshore waves, rain, feels-like temperature and daylight. Every item uses a data line and a short emoji feeling. Boat mode puts waves first; shore mode puts wind first. These daily summaries do not rate a particular fishing session or override the existing fishing-window assessment.

- Wind uses the day's maximum sustained wind and gust, with dominant direction, and the existing land wind bands.
- Offshore waves show the largest significant height and the range of mean periods. A known large wave remains visible even if some periods are unavailable. Other motion labels require a complete day of valid height/period data. “Choppy” requires a height of at least 0.5 m and a period of at most 5 seconds **at the same hour**. These are provisional comfort labels, not verified boat or shoreline limits.
- Shore waves say **Check exposure**. Boat labels include **Lower waves**, **More motion**, **Choppy** and **High waves**. A lower offshore height does not establish sheltered local conditions.
- Rain shows the daily precipitation total and the highest hourly rain probability. The latter is not the probability of rain somewhere during the entire day. Daily totals above 3 mm get “Wet day”; a positive smaller total gets “Some rain.”
- Feels-like temperature shows the daily range. Below 12°C is “Cold at times”; above 26°C is “Hot at times.” These include overnight hours and are general comfort guides.
- Daylight shows sunrise and sunset with “Plan your return.” It does not determine access, travel times or a complete-visit daylight assessment.

UV, minimum visibility, sea surface temperature and offshore swell are available under **UV, visibility & sea details**. UV at least 3 prompts sun protection. Visibility below 1 km gets “Poor at times.” Hourly cards show weather, temperature, feels-like temperature, wind, gusts, wave height and mean period, rain amount and rain chance. Hourly rain and gusts cover the preceding hour. The hourly timeline retains marine records if weather is unavailable.

The view displays the offshore model grid's distance from the pin. Offshore waves and swell do not describe lake waves, shoreline breaking waves, harbour currents or a boat's entire route. Source details expose grid coordinates, timezone and fetch time. Fetch time does not establish a model's issue time. Near-term data has **Limited confidence**; days 3–5 use **Planning forecast**, and later dates use **Early outlook**.

## Tide references and missing data

Named published tide stations and previously mapped exact-name station relationships can supply an initial LINZ reference. Generic search results and dropped pins require the user to choose a reference station. The picker orders stations by straight-line distance but never automatically treats the nearest station as representative: land barriers and different harbours matter.

Tide events are loaded independently for the chosen station and date using the existing annual LINZ source. They show high/low times and heights above **Chart Datum**, with a reference-suitability reminder. The view does not substitute marine mean-sea-level data for tidal heights or infer current speed from tides. Date/station changes clear prior tide events before loading; a tide failure has its own retry.

Weather and marine failures are independent. A usable source continues to supply dates and data if the other fails. Missing or invalid numeric values remain unavailable and display an em dash or **Needs more data**. Values are not filled with zeros or extended beyond their actual returned times. Provider units, array lengths and ordered timestamps are validated before accepting a source.

## Weather page

The Weather page now uses familiar cloud-and-rain, cloud-and-snow, fog, partly cloudy, thunderstorm, sun and moon symbols. Visible text distinguishes **Light rain**, **Heavy rain**, **Light showers**, drizzle, freezing precipitation and hail. Hourly cards include a text label beside the icon and use actual model day/night flags. Their clock labels describe the forecast hour instead of mislabelling an hourly record as “Now.”

The daily list requests 16 days, shows the returned day count, and includes calendar dates to distinguish repeated weekdays. Missing hourly or daily temperatures, wind speeds, weather codes and rain probabilities remain unavailable; numeric values display an em dash. Missing values at the end of the forecast no longer prevent the available current and near-term forecast from loading. The current rainfall card labels its 15-minute interval, and daily rain probability is identified as the maximum hourly value. Saved locations, paging, location permission handling and refresh remain intact.

## Verification

- Android: 63 unit tests pass; debug APK builds.
- iOS: simulator app builds; 76 existing fishing-window checks and 25 new offline place/weather checks pass. The new harness has 29 checks when including live Thames conditions and the Weather page service.
- Shared synthetic fixtures exercise New Zealand's 23-hour spring daylight-saving day, nullable rain probabilities and missing temperature, wind and weather codes at the end of an extended forecast. New checks cover provider limits, partial sources, wrong units/array alignment, paired wave height/period, and known high waves with incomplete data.
- Live Thames requests returned 16 upcoming weather days, 8 upcoming marine days, and both 3-day and 92-day weather history. Availability depends on the provider and location.
- Android emulator inspection verified map search, dropped pins and published-station selection, recent dates, the shore/boat ordering switch, actual Thames tide events and hourly wave height/period with day/night icons. The Weather page loads live Thames data, displays familiar night/cloud symbols with text, and retains the last day's available rain probability while showing dashes for its missing values. iOS interactive inspection remains unverified; its simulator build and production data services pass.

Run from the repository root:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
tools/test_fishing_windows_swift.sh
tools/test_place_conditions_swift.sh
# Live Thames services and actual horizons:
tools/test_place_conditions_swift.sh --live
# Maximum supported recent history:
tools/test_place_conditions_swift.sh --live-history
xcodebuild -project iosApp/CatchCheckNZ.xcodeproj -scheme CatchCheckNZ \
  -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

See [fishing-window criteria](fishing-window-implementation.md) for the separate session-level assessment and its remaining limits.
