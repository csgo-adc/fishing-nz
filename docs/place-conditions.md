# Map conditions and weather

The Android and iOS Map tabs open **Explore conditions**. Users can search for a town, beach or named fishing area, select an existing marker, or tap an arbitrary position. The selected-place card has a primary **Check conditions** action, opening a full-screen conditions page with a fixed back control and refresh. Scrolling down cannot dismiss this page. Android uses a full-screen Compose window; iOS uses a full-screen cover. Returning to the map preserves the selected place and map view. Existing map layers, attribution, location controls and planning/detail actions remain available. iPhone adds **+ and −** zoom buttons above the location button for one-handed zooming. Search distinguishes published tide stations, land areas and boat areas.

## Checking dates

- **Upcoming** starts with today and displays the dates actually returned by either weather or marine data.
- **Recent days** starts with yesterday, with 3, 7, 30 or 92 days of history. The default request includes 3 recent days.
- Weather requests the documented maximum of **16 forecast days**. Marine requests **8 forecast days**. Both permit up to **92 past days**. Missing values can occur within those ranges; the date strip and availability text use returned data.
- Recent data is labelled **Recent model data**. It is model output, not measured weather, a record of what the forecast said on that date, or a fishing report.
- Dates, hourly timestamps, sunrise and sunset use the response's local timezone. Unix timestamps represent actual instants. They are not shifted a second time by the UTC offset. Complete marine days can contain 23, 24 or 25 hourly records across daylight-saving changes.

Provider references: [Weather API](https://open-meteo.com/en/docs), [Marine API](https://open-meteo.com/en/docs/marine-weather-api).

## Short descriptions

The main card shows offshore waves, wind, rain, feels-like temperature and daylight, with waves first. Every item uses a data line and a short emoji feeling. Tap a row's chevron to expand its details. There is one conditions view: the former shore/boat switch only changed order and descriptions while showing the same data. Expanded wave and wind details now include both shore and boat guidance. These daily summaries do not rate a particular fishing session or override the existing fishing-window assessment.

- Wind uses the day's maximum sustained wind and gust, with dominant direction, and the existing land wind bands.
- Offshore waves show the largest significant height and the range of mean periods. A known large wave remains visible even if some periods are unavailable. Other motion labels require a complete day of valid height/period data. “Choppy” requires a height of at least 0.5 m and a period of at most 5 seconds **at the same hour**. These are provisional comfort labels, not verified boat or shoreline limits.
- Wave labels include **Lower waves**, **More motion**, **Choppy** and **High waves**. A known significant height of at least 2 m gets **High waves** even when other data is incomplete. These general indicators describe offshore model conditions, not suitability for a particular craft or shoreline. A lower offshore height does not establish sheltered local conditions.
- Rain shows the daily precipitation total and the highest hourly rain probability. The latter is not the probability of rain somewhere during the entire day. Daily totals above 3 mm get “Wet day”; a positive smaller total gets “Some rain.”
- Feels-like temperature shows the daily range. Below 12°C is “Cold at times”; above 26°C is “Hot at times.” These include overnight hours and are general comfort guides.
- Daylight shows sunrise and sunset with “Plan your return.” It does not determine access, travel times or a complete-visit daylight assessment.

UV, minimum visibility, sea surface temperature and offshore swell are available under **UV, visibility & sea details**, each with its own expandable explanation. Rain details include the wettest available hour and a visual cloud/drop legend. Tide details explain the reference station and datum while keeping event heights and their Chart Datum label visible. UV at least 3 prompts sun protection. Visibility below 1 km gets “Poor at times.” Hourly cards show weather, temperature, feels-like temperature, wind, gusts, wave height and mean period, rain amount and rain chance. Hourly rain and gusts cover the preceding hour. The hourly timeline retains marine records if weather is unavailable.

The view displays the offshore model grid's distance from the pin. Offshore waves and swell do not describe lake waves, shoreline breaking waves, harbour currents or a boat's entire route. Source details expose grid coordinates, timezone and fetch time. Fetch time does not establish a model's issue time. Near-term data has **Limited confidence**; days 3–5 use **Planning forecast**, and later dates use **Early outlook**.

## Tide references and missing data

Named published tide stations and previously mapped exact-name station relationships supply an initial LINZ reference. For other fishing areas, generic search results and dropped pins, the conditions page automatically selects the nearest supported LINZ station by straight-line distance. The tide card shows the reference station and its distance from the selected place. Users can change or clear it in the station picker; their choice stays in place while changing dates or refreshing the open conditions page. Opening conditions for another place selects its own initial reference.

A nearby station is a reference, not verified local tide timing: land barriers and different harbours matter. Tide details retain the reminder to check that the station represents the selected place. The fishing-window assessment still requires an explicitly identified station and does not use this nearest-station fallback.

Tide events are loaded independently for the chosen station and date using the existing annual LINZ source. They show high/low times and heights above **Chart Datum**, with a reference-suitability reminder. On iPhone the card also draws the day's tide curve with a time and height readout and slider, the same chart as the Tide tab; Android lists the events only. The view does not substitute marine mean-sea-level data for tidal heights or infer current speed from tides. Date/station changes clear prior tide events before loading; a tide failure has its own retry.

Weather and marine failures are independent. A usable source continues to supply dates and data if the other fails. Missing or invalid numeric values remain unavailable and display an em dash or **Needs more data**. Values are not filled with zeros or extended beyond their actual returned times. Provider units, array lengths and ordered timestamps are validated before accepting a source.

## Weather page

The Weather page now uses familiar cloud-and-rain, cloud-and-snow, fog, partly cloudy, thunderstorm, sun and moon symbols. Visible text distinguishes **Light rain**, **Heavy rain**, **Light showers**, drizzle, freezing precipitation and hail. Hourly cards include a text label beside the icon and use actual model day/night flags. Their clock labels describe the forecast hour instead of mislabelling an hourly record as “Now.”

Rain symbols retain a cloud with **1, 2 or 3 drops underneath** for light, moderate or heavy rain/showers. Drizzle uses smaller drops with the same three levels. Freezing precipitation retains its explicit text label and the available light/heavy level. Drop counts follow the WMO weather code, not rain probability or daily precipitation totals. Thunderstorm and hail symbols stay distinct: their codes do not establish a rain intensity. Both platforms use the same mapping in the Weather page and all map condition weather icons.

The daily list requests 16 days, shows the returned day count, and includes calendar dates to distinguish repeated weekdays. Missing hourly or daily temperatures, wind speeds, weather codes and rain probabilities remain unavailable; numeric values display an em dash. Missing values at the end of the forecast no longer prevent the available current and near-term forecast from loading. The current rainfall card labels its 15-minute interval, and daily rain probability is identified as the maximum hourly value. Saved locations, paging, location permission handling and refresh remain intact.

## Verification

- Android: 65 unit tests pass; debug APK builds.
- iOS: simulator app builds; 76 existing fishing-window checks and 29 offline place/weather checks pass. The place/weather harness has 33 checks when including live Thames conditions and the Weather page service.
- Nearest tide selection checks cover dropped pins, generic place results, changed locations and keeping an existing station link on both platforms.
- Android emulator inspection verified that a dropped pin loads nearby LINZ events without opening the picker, displays the reference distance, and keeps a manually changed station after refreshing.
- Shared synthetic fixtures exercise New Zealand's 23-hour spring daylight-saving day, nullable rain probabilities and missing temperature, wind and weather codes at the end of an extended forecast. New checks cover provider limits, partial sources, wrong units/array alignment, paired wave height/period, and known high waves with incomplete data.
- Live Thames requests returned 16 upcoming weather days, 8 upcoming marine days, and both 3-day and 92-day weather history. Availability depends on the provider and location.
- Android emulator inspection verified map search, dropped pins and published-station selection, recent dates, actual Thames tide events and hourly wave height/period with day/night icons. The full-screen page stays open when dragging down at the top, retains its fixed back control while scrolling, and returns to the selected Thames pin. Wave and rain drawers expand; the rain legend shows clouds with 1, 2 and 3 drops. The Weather page displays live Thames forecasts with light and moderate drizzle icons, and retains available values when other fields are missing. iOS interactive inspection remains unverified; its simulator build and production data services pass.

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
