# Fishing-window explanations and data corrections

Implemented in the Android and iOS source on 27 September 2026; qualitative outlook labels were added on 29 September 2026. This note distinguishes the working mobile changes from the larger [target design](fishing-window-method.md).

## What the user sees

- A date and two-hour session with a short comfort outlook label and emoji, without numeric scores. Boat comfort gives waves the most weight; land comfort compares wind and rain. Incomplete data and material warnings get their own caution labels.
- **Why this time:** a conclusion generated from the selected preference and the session's actual wind, gusts, precipitation and official local tide events.
- **Conditions during your session:** factual tide, wind, rain, offshore wave and daylight information.
- A same-day alternative when the weather choice and late-incoming choice differ. It is evaluated from the same fetched data and allowed hours.
- All material warnings, including missing data, darkness, elevated offshore waves and the need to verify local access/exposure and official warnings.
- Sources, forecast retrieval time and returned model-grid coordinates. Retrieval time is not mislabelled as model issue time.

The search has two explicit choices: **Wave comfort** for boats or **Weather balance** for land (default), and **Late incoming tide**. The boat wave preference was requested on 4 October 2026 after an uncomfortable fishing trip. No tide preference is inferred from a town name or species. Saved/active trips show the explanation as well. Boat explanations include offshore wave height and the range of mean wave periods; their conditions list shows waves first.

## Exact current selection policy

1. Generate equal two-hour candidates starting on the forecast's hourly instants within the requested dates and allowed hours. Require all three instantaneous endpoints, including the final hour. Candidates do not extend beyond available data.
2. Read gust maxima and precipitation/probability from the two intervals ending at the middle and end instants. For 07–09, these labels are 08 and 09. Null or invalid essential weather cannot become zero or a complete session.
3. Preserve the existing broad thunderstorm, wind/gust and wave exclusion filters. A known adverse wave still excludes a candidate when another wave sample is missing. These coarse filters do **not** establish local shore or boat safety; every result remains a planning option requiring site and warning checks.
4. When **Late incoming tide** is selected, require verified LINZ coverage and at least 80% overlap with a target from 150 minutes before high water to 30 minutes afterwards. This is a configurable product preference, not a biological claim. If nothing fits, return no matching window rather than silently switching preference.
5. Prefer candidates with complete wave/period and tide data, then the greater proportion of the full fishing session in daylight, then the weather comfort ordering below. Ties favour the earlier start. Distance affects search radius/logistics, not fishing conditions. Dawn receives no separate bonus. Anytime permits a dark session but the default order still prefers daylight when available; the UI explains this.
6. Internal boat comfort uses `0.6 × waveComfort + 0.3 × windComfort + 0.1 × rainComfort`; land comfort retains `0.6 × windComfort + 0.4 × rainComfort`. These are provisional planning preferences, **not catch scores or safety limits**. The number is hidden; the UI maps it to Excellent, Good, Fair or Challenging, and replaces that label with a caution when essential data is incomplete or a material weather/wave warning applies. Wind retains the existing continuous comfort curves for maximum sustained wind and gusts; rain uses equal contributions from maximum hourly probability and mean forecast amount. It is not double-counted through a weather-code bonus. No missing-data weight renormalisation or arbitrary 79/89 score caps remain. A more complete preference/tolerance and multi-model policy is still part of the target design.
7. Generate the explanation from those same facts. State tide phase using consecutive official events. Never substitute tide-height change for current speed or infer slack water from high water.

Existing exclusion limits retained: land wind 55 km/h, gust 70 km/h, offshore significant wave height 3 m; boat wind 40 km/h, gust 55 km/h, offshore significant wave height 2 m. Their only role is to exclude gross adverse conditions under the prior policy. They are not acceptable-condition guarantees, especially for exposed rocks, harbour bars or locally exposed shorelines.

### Boat wave comfort

For each of the three instantaneous samples, keep height and mean period paired from the same hour. Compute `heightComfort = clamp((2.0 − heightMetres) / 1.7, 0, 1)` and subtract `0.25 × clamp((8 − meanPeriodSeconds) / 5, 0, 1) × clamp(heightMetres / 0.75, 0, 1)`. Clamp the result to 0–1 and use the lowest comfort across the entire session, including the final endpoint. Taller waves and shorter mean periods reduce comfort; periods above eight seconds earn no further bonus. Long-period warnings remain separate, since a longer period does not establish a safe trip.

This curve is an initial product preference for a less bumpy fishing experience, not a calibrated vessel-motion model. [MetService distinguishes local choppy seas from swell](https://about.metservice.com/learning/sea-state-and-swell-whats-the-difference), and [NWS describes the effects of short periods on small boats](https://www.weather.gov/marine/WaveDetail). These sources support considering waves and period; they do not prescribe the numeric weights or comfort curve above. Vessel size/hull, route, wave direction, current and mixed sea/swell systems are not assessed by this preference. The API's total mean wave period is not a swell peak period or a measured boat-motion frequency.

If any wave height or period is missing or invalid, boat wave comfort receives zero credit and the result stays incomplete. Do not distribute the missing 60% to wind/rain. A known height at or above 2 m still excludes the session even when another height or period is missing. Existing elevated-wave warnings start at 1.2 m for boats. A new comfort warning appears when the same valid sample has height at least 0.5 m and mean period at most five seconds; this changes the boat outlook and wave indicator to a concern. Those warning values are product defaults, not official marine warning thresholds. Late-incoming mode still requires the tide fit above and applies the same wave comfort when comparing fitting boat sessions.

## Source handling

**Tides:** each app shares one LINZ adapter between its Tide page and recommendations. Selected stations use their own published annual table. Radius searches use explicitly matching names or the published reference coordinate, with no nearest-across-land fallback. Parsing checks station identity, year, units, ordering, missing days, finite heights and alternating high/low events. NZST/NZDT is applied exactly once; ambiguous or nonexistent local times are rejected. Annual cache entries expire after 24 hours. Unavailable local tides stay unverified. [LINZ formats](https://www.linz.govt.nz/guidance/marine-information/tide-prediction-guidance/tide-prediction-formats).

On 4 October 2026, the Thames adapter was corrected to validate the annual table's published clock fields before converting only the requested dates and adjacent bracketing events to instants. An ambiguous clock on 5 April no longer hides October's valid tide heights. Requests that need the ambiguous event still fail verification, including curves that would use it as an adjacent endpoint. Both apps share this behavior between their Tide page and recommendations. The original Thames CSV is retained for regression checks; `tools/test_fishing_windows_swift.sh --live-tides` also verifies the fetched Thames table and cached date changes without depending on weather forecasts.

**Weather:** Open-Meteo automatic best match at the requested coordinate; explicit km/h, mm, UNIX timestamps and Pacific/Auckland. Returned units, array alignment, timestamp order, finite values and probability ranges are checked. The returned grid is recorded in the result. [Weather interval definitions](https://open-meteo.com/en/docs).

**Waves:** Open-Meteo marine significant wave height and mean period, with explicit metre units. These remain offshore forecasts, not a calculated wave height at the wharf. `sea_level_height_msl` is no longer requested or used by either recommendation engine. Partial marine samples remain partial and never erase known adverse values. [Marine definitions](https://open-meteo.com/en/docs/marine-weather-api).

## Verification

- Android unit tests and debug APK build pass: **42 tests**, including 13 LINZ tests and 17 interval/selection/explanation tests.
- iOS simulator build passes for the CatchCheckNZ scheme.
- A Foundation-only harness runs the production Swift tide/parser/selection code: **40 offline regression checks pass**, or **42** including the live Thames fetch and cached date change.
- Both platforms replay the saved Raglan snapshot: **30 September 07–09 for Weather balance; 11–13 for Late incoming tide; official high 13:23 NZDT**. The later session's explanation includes 0.5 mm of forecast rain instead of describing it as dry.
- Both platforms check that calmer boat waves outrank rougher seas despite additional wind/rain, shorter mean periods reduce comfort, the final marine endpoint is included, missing period data earns no wave credit, the known 2 m exclusion survives missing periods, and late-incoming mode retains the boat wave preference. Land ranking remains unchanged.
- A live check using the production Swift service successfully loaded the current Open-Meteo weather/marine responses and LINZ Raglan table. Live results can differ from the frozen regression snapshot as forecasts update.
- The iOS build was installed and launched in the simulator. Interactive visual inspection could not be completed because the computer-use tool could not access Simulator; layout was checked in source, not certified from a screenshot.

Reproduce from the repository root:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
tools/test_fishing_windows_swift.sh
# Live Thames tides only; independent of the weather forecast horizon:
tools/test_fishing_windows_swift.sh --live-tides
# Optional network check, when the fixed September 2026 date remains in the forecast range:
tools/test_fishing_windows_swift.sh --live
xcodebuild -project iosApp/CatchCheckNZ.xcodeproj -scheme CatchCheckNZ \
  -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

## Remaining limits

Verified access/exposure profiles, official warning ingestion, wind-direction-based site exposure, explicit model comparison, forecast-as-issued archives and a shared backend are not implemented in this change. The mobile apps label these checks as outstanding instead of claiming the location is safe. Fishing-session daylight excludes unknown setup/walk/return time. This is a more auditable conditions planner; field evidence is still needed for any species-specific catch prediction.
