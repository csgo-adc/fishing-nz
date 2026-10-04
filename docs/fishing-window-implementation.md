# Fishing-window assessment and compact descriptions

Updated in Android and iOS on 4 October 2026. This describes the shipped mobile rules; the broader [target design](fishing-window-method.md) still contains future work.

## What the user sees

Each condition is a short data row followed by an emoji and feeling. For example: `Wind 17 km/h · gust 27 · E` and `🙂 Okay`. Numeric scores remain hidden. Production results use structured condition items rather than deriving their meaning from warning sentences.

The comfort labels are **😌 Comfortable**, **🙂 Okay**, **😕 Demanding**, **😣 Uncomfortable**, and **❓ Needs more data**. “Okay” replaces “Manageable” for easier reading. Detailed reasons and source information sit under **Details & sources**; iOS result cards open the details page to access them.

Comfort, **🔎 Local checks needed**, and forecast confidence are separate. The current single-model inputs do not justify “Supported” confidence or “Local checks complete.” Near-term results show **🤔 Limited confidence**; starts more than 48 hours away show **🗓️ Planning forecast**, and starts more than five days away show **🔭 Early outlook**. Details record incomplete coverage, unknown access/return time, forecasts fetched more than three hours ago, missing source timing/model agreement and unchecked official warnings. Fetch time is not model issue time. These time bands and freshness defaults are provisional product choices.

Search preferences are **Comfort** for land, **Wave comfort** for boats, or **Late incoming tide**. Tide is descriptive unless the user selects the tide preference. The late-incoming target is still 150 minutes before high water to 30 minutes afterwards, with at least 80% of the two-hour session inside that target and verified LINZ coverage. It is a timing preference, not a claim about catches or current speed.

## Land comfort

The former 60% wind / 40% rain average is replaced by a worst-factor comfort band. A calm forecast cannot average away a cold endpoint or a wet hour. Feels-like temperature already accounts for thermal wind effects; wind remains a separate mechanical casting/exposure factor.

| Factor | Comfortable | Okay | Demanding | Uncomfortable |
| --- | --- | --- | --- | --- |
| Maximum sustained wind / gust | ≤12 / ≤20 km/h | ≤20 / ≤30 km/h | ≤30 / ≤45 km/h | Above either demanding limit |
| Wettest intersecting hourly rain interval | ≤0.2 mm/h | ≤0.8 mm/h | ≤1.5 mm/h | >1.5 mm/h |
| Feels-like temperature | 12–26°C | 8–30°C outside the comfortable range | 5–33°C outside the okay range | <5°C or >33°C |

These are trial human-comfort preferences, not official safety limits or calibrated catch predictions. The overall band is the worst of the three factors. Missing temperature or incomplete visit weather coverage produces **Needs more data**, rather than a comfortable result. Rain probability is shown as the maximum **hourly** likelihood and does not change the land physical-comfort band. Rain rows show the total and wettest hourly interval; temperatures show their range; wind shows the maximum sustained wind, maximum gust and available directions.

**Shore options** persist between launches:

- A planned shore setting: wharf, harbour bank, beach, rocks, or not set. This is user intent, not a verified site profile. Each setting gets appropriate outstanding access/exposure checks; offshore waves never become a calculated wharf, surf or surge height.
- A comfort limit, defaulting to Comfortable or Okay. Known options outside the limit are explicitly marked; partial options are not described as meeting it. If none qualifies, the results explain that the listed windows are alternatives or need more data.
- Comfort, Easier casting, or Less rain preference. The latter two break comfort ties using gust/wind or accumulated rain.
- Access/setup and return time, each unset or 0–180 minutes. Zero must be selected explicitly. Known buffers extend the assessed visit; unknown buffers are shown as unknown. A known access start cannot be in the past. The whole-visit daylight option requires both times and full solar coverage.

Candidates keep the two-hour fishing session, but evaluate known arrival/return buffers as well. Instantaneous wind, temperature and waves include start, end and interior forecast hours. Non-hourly boundaries use linear interpolation only between valid adjacent hourly values. Gust/rain intervals are clipped to the visit; the wettest intersecting hour is retained. Known adverse marine hours remain visible even when weather coverage has a gap. Interpolation and interval assumptions do not promise minute-level accuracy.

Selection first prefers complete weather/temperature assessments, then those meeting the chosen comfort limit, then more daylight, a lower worst band, fewer demanding hours, fewer hours with any discomfort, the chosen casting/rain tie-break, and an earlier start. Exposure duration is a union of hourly discomfort, not a sum that counts several factors twice. Anytime permits darkness, with daylight still preferred within the allowed candidates. Known setup/return time is included in daylight; unknown time is not treated as a verified daylight return.

The same-day tide/comfort alternative is retained. In balanced mode, when that alternative is not distinct, a real calmer-but-wetter or drier-but-gustier peer may be shown. These peers must meet the comfort limit, have no worse band and at least as much daylight, and use the same fetched data and allowed hours.

Tide/wave coverage does not override land weather comfort. It remains part of the independent checks and confidence explanation. Broad exclusions remain wind ≥55 km/h, gust ≥70 km/h, offshore significant wave height ≥3 m, or a known thunderstorm code 95–99, including known arrival/return conditions. Passing these exclusions does not establish safe local access.

## Boat comfort

Boat ordering continues to give waves the most weight: `0.6 × waveComfort + 0.3 × windComfort + 0.1 × rainComfort`. Complete data comes first, then session daylight, then the unrounded comfort value. The hidden value maps to the shared comfort labels at 85%, 70% and 50%.

For each of the three instantaneous samples, keep height and mean period paired from the same hour. Compute `heightComfort = clamp((2.0 − heightMetres) / 1.7, 0, 1)` and subtract `0.25 × clamp((8 − meanPeriodSeconds) / 5, 0, 1) × clamp(heightMetres / 0.75, 0, 1)`. Clamp the result to 0–1 and use the lowest comfort across the session, including the final endpoint. Periods above eight seconds earn no further bonus. A longer period alone does not establish a safe trip.

Boat cold/heat now limits the displayed outlook using the same trial temperature bands, so calm waves with 7°C feels-like conditions do not say Comfortable. Temperature breaks equal wave/wind/rain ranking ties; it does not replace the wave-first weighting. Missing temperature leaves the overall assessment incomplete. Waves are displayed first, followed by wind, rain, feels-like temperature, tide and daylight.

This is an initial product preference for a less bumpy fishing experience. [MetService distinguishes local choppy seas from swell](https://about.metservice.com/learning/sea-state-and-swell-whats-the-difference), and [NWS explains why wave period matters to small boats](https://www.weather.gov/marine/WaveDetail). These sources support considering height and period; they do not prescribe the numeric curve or weights above. Vessel size/hull, route, current, mixed sea/swell systems and direction relative to the boat are not assessed. The total mean wave period is not a swell peak period or a measured boat-motion frequency.

Missing height/period earns zero wave credit; the result stays incomplete without redistributing the missing 60%. Known height ≥2 m still excludes the session even with a missing period. Wind ≥40 km/h, gust ≥55 km/h and thunderstorm codes 95–99 also exclude it. Product wave indicators flag paired height ≥0.5 m with mean period ≤5 seconds as Choppy, and elevated height ≥1.2 m as More motion. Long-period warnings keep height and period paired from the same hour. These are subjective planning defaults, not official warning thresholds. Boat route, launch and return remain outstanding checks.

## Source handling

**Tides:** each app shares one LINZ adapter between its Tide page and recommendations. Selected stations use their own published annual table. Radius searches use explicitly matching names or the published reference coordinate, with no nearest-across-land fallback. Parsing checks station identity, year, units, ordering, missing days, finite heights and alternating high/low events. NZST/NZDT is applied exactly once; ambiguous or nonexistent local times are rejected. Annual cache entries expire after 24 hours. Unavailable local tides stay unverified. [LINZ formats](https://www.linz.govt.nz/guidance/marine-information/tide-prediction-guidance/tide-prediction-formats).

On 4 October 2026, the Thames adapter was corrected to validate the annual table's published clock fields before converting only the requested dates and adjacent bracketing events to instants. An ambiguous clock on 5 April no longer hides October's valid tide heights. Requests that need the ambiguous event still fail verification, including curves that would use it as an adjacent endpoint. Both apps share this behavior between their Tide page and recommendations. The original Thames CSV is retained for regression checks; `tools/test_fishing_windows_swift.sh --live-tides` also verifies the fetched Thames table and cached date changes without depending on weather forecasts.

**Weather:** Open-Meteo automatic best match at the requested coordinate; explicit km/h, mm, Celsius feels-like temperature, UNIX timestamps and Pacific/Auckland. Returned units, array alignment, timestamp order, finite values, optional temperature arrays and probability ranges are checked. Missing land rain likelihood remains unknown and does not turn physical wetness into a second penalty. The returned grid is recorded in the result. [Weather interval definitions](https://open-meteo.com/en/docs).

**Waves:** Open-Meteo marine significant wave height and mean period, with explicit metre units. These remain offshore forecasts, not a calculated wave height at the wharf. `sea_level_height_msl` is no longer requested or used by either recommendation engine. Partial marine samples remain partial and never erase known adverse values. [Marine definitions](https://open-meteo.com/en/docs/marine-weather-api).

## Verification

- Android: **54 unit tests** and the debug APK build pass.
- iOS: the CatchCheckNZ simulator build passes. The Foundation-only harness runs the production services: **76 offline regression checks**, or **78** including live Thames tide loading and a cached date change.
- Both platforms cover cold/heat, wettest-hour rain, calm wet versus dry gusty conditions, missing temperature/likelihood/marine data, whole-visit daylight, adverse return weather, interpolated return waves, known marine values across weather gaps, paired height/period warnings and valid comfort alternatives.
- The frozen Raglan replay retains 30 September 07–09 for Comfort and 11–13 for Late incoming, with the official 13:23 NZDT high and 0.5 mm rain shown for the later session. The original snapshot has no feels-like temperature, so its assessment now correctly remains incomplete.
- Boat checks retain wave-first ordering, final endpoints, missing-period behavior and the known 2 m exclusion; new checks cover cold/heat labels and temperature tie-breaks.
- A live production Swift run successfully loaded tomorrow's Thames land and boat weather, waves and tide events, including real feels-like temperatures. Live forecasts can change.
- The simulator app was installed and launched. A Home screenshot was inspected. The computer-use tool could not access Simulator, so interactive inspection of the new condition rows and shore settings remains unverified.

Reproduce from the repository root:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
tools/test_fishing_windows_swift.sh
# Live Thames table and cached date change:
tools/test_fishing_windows_swift.sh --live-tides
# Current live weather/waves for tomorrow at Thames, on land and by boat:
tools/test_fishing_windows_swift.sh --live
xcodebuild -project iosApp/CatchCheckNZ.xcodeproj -scheme CatchCheckNZ \
  -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

## Remaining limits

Verified access/exposure profiles, official warning ingestion, wind-direction-based shelter, model comparison, forecast-as-issued archives and a shared assessment backend are not implemented. Those checks stay outstanding. Shore choices do not verify a named spot. Boat return/route timing is not modelled; known land visit buffers are modelled, with hourly timing limits. Clothing, experience, wading depth, current speed, swell direction, UV and individual motion sensitivity are not resolved by the current comfort bands. Field evidence is still needed to calibrate them and to support any species-specific catch prediction.
