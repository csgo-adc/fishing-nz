# Fishing-window method: evidence, design and validation

Date: 27 September 2026. Status: broader target design, with an offline diagnostic prototype. A first implementation now replaces visible scores with explanations in Android and iOS, uses shared LINZ tide adapters within each app, fixes forecast intervals, and adds an explicit late-incoming preference. See [implementation and validation](fishing-window-implementation.md) for the exact shipped-in-source policy and remaining work; the complete shared-service and site-profile design below is not yet implemented.

## Decision

Recommend a window for a specific outing and an explicit preference. Establish access, weather and marine constraints before ranking. Use official local tide events, correctly aligned hourly forecasts and the whole session, including access time. Report the trade-off between a preferred tide and better weather when neither option is clearly superior.

The first release should describe **conditions and preference fit**. It should not claim a measured probability of catching fish, assign five stars as if calibrated, or certify a rock ledge safe from a regional wave forecast. A reliable planner can get dates, source selection, coverage, constraints and reproducibility right; a universal “best bite time” is not established by these inputs.

For the Raglan Wharf example in the pasted answer, illustrate “daylight, late incoming through high water” as a possible planning preference, inferred from the user's interest in 11 am–1 pm. These are not confirmed trip settings. Production defaults should remain neutral about tide phase until a user chooses a preference or a reviewed local profile provides evidence. This must not become an undocumented rule that all species at all shore sites feed best before high tide.

## What the pasted answer actually does

1. It assumes a daylight session near high water is desirable for this wharf.
2. It creates a roughly three-hour session around that period.
3. It ranks days mostly by the wind and rain during those sessions.
4. It treats the harbour wharf differently from the exposed coast.
5. It turns the selection into an arrival, fishing and departure plan.

That is a useful planning structure. It explains why a person might prefer late morning over a dry dawn session. Its stars are subjective, however; there is no reproducible formula, source snapshot or documented species response behind them.

Several factual and logical points need correction:

- LINZ's Raglan table has **30 September low 07:02, 0.2 m; high 13:23, 3.2 m**, rather than the pasted 07:07 and 13:12, 3.01 m. The afternoon highs on 1, 2 and 3 October are 14:08, 14:59 and 16:00. These are published local times, already adjusted for daylight saving. [LINZ Raglan 2026](https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Raglan%202026.csv)
- Its pasted citation markers are not retrievable sources. Its original hourly forecast cannot be reconstructed from the text. A new forecast may disagree because the provider, place, model or issue time differs.
- High water does not establish the time of slack current. Local geography controls that relationship. Neither the slope of an interpolated tide-height curve nor the time of high water provides measured current speed. [NOAA tidal-current FAQ](https://tidesandcurrents.noaa.gov/faq.html)
- The exact wharf, fishing method and species matter. Research on NZ snapper describes differences among fish and habitat; it does not establish a universal late-incoming rule. [Hartill et al., Mahurangi Harbour study](https://www.publish.csiro.au/mf/MF02095)
- An offshore wave forecast cannot be converted into wharf chop by an invented “shelter percentage”. A precise arrival time is a useful itinerary choice, not proof of minute-level forecast skill.

My earlier chat recommendations also moved too readily between Friday, Thursday and Wednesday. Those changes did not come from one documented method applied to one frozen dataset. The replacement needs a recorded objective and a reproducible explanation for every change.

## Defects found in the original engines

The original mobile engines had the following problems. The provider, interval, tide, missing-data, daylight and explanation fixes are now implemented as documented in the implementation note. Full site profiles, official warning ingestion and a shared server remain future work. See [Android engine](../app/src/main/java/nz/fishingnz/app/data/RecommendationEngine.kt) and [iOS engine](../iosApp/CatchCheckNZ/Services/FishingScoringService.swift).

| Current behaviour | Consequence | Required behaviour |
| --- | --- | --- |
| Recommendations use offshore `sea_level_height_msl`; the Tide page uses LINZ | The two screens can disagree about tide phase | Both use the same curated LINZ station mapping |
| An N-hour window contains N hourly points | The final instantaneous wind/wave endpoint is omitted; a two-hour tide comparison covers one hour | Integrate complete intervals and include bracketing instant samples |
| Rain, rain probability and gusts at the start label are treated as the next hour | The evaluated weather is shifted one hour early | Store explicit validity intervals for these fields |
| Wave rejection is evaluated only when every wave value exists | `[3.5 m, null]` can bypass the known 3 m cutoff | Preserve all known adverse values; separately flag incomplete coverage |
| Missing factors are dropped and remaining weights enlarged | Losing bad data can raise a score | Missing data cannot improve apparent quality or confidence |
| A single `boat` boolean describes site type | A wharf and exposed rocks share the same land rules | Use explicit site profiles and independently reviewed exposure policies |
| A selected tide station is converted to a fishing spot | A prediction point looks like a verified access point | Keep tide station and fishing site as separate entities |
| Midpoint determines daylight points | A partly dark session can receive full daylight points | Check all fishing and access minutes |
| Every best surviving window is called suitable | Even a poor or incompletely assessed day can look recommended | Distinguish excluded, unassessed and usable-under-policy candidates |
| One warning is selected for the result card | Darkness, source limitations or access concerns disappear | Display all material reasons affecting the decision |
| Distance contributes ten points at the selected place | Every Raglan session gets a free ten points | Use travel for logistics and user constraints |

The original 15% daylight factor alone gave a near-sunrise daytime window only **4.8 more total points** than ordinary daylight. It could not explain the whole 7–9 versus 11–1 difference. An explanation must show the actual factors from the actual snapshot. The new implementation removes that sunrise bonus.

The screenshot selected **Anytime**, so an early or partly dark session is not automatically a selection error. The app should display darkness clearly and enforce whole-visit daylight only when that constraint applies. A different forecast fetched later cannot reconstruct the exact 89/100 shown in the screenshot.

## Inputs for a real outing

Separate the following records:

**Fishing site:** verified site ID and coordinate; wharf/harbour shore/surf beach/exposed rocks/harbour entrance/boat; access and opening restrictions; public-access evidence and review date; access route; tidal cut-off hazards; directional exposure information and its source; mapped tide station and mapping justification. Unknown properties stay unknown. A search for “Raglan” must not silently mean “Raglan Wharf”.

**User's trip:** dates, arrival availability, fixed core duration, allowed hours, whether the entire visit must be in daylight, fishing method, optional species, tide preference, tolerance for rain/wind, and travel origin if requested. An explicit night-fishing selection is respected and labelled with darkness duration. Boat and bar-crossing advice require separate validated policies; this proposal's initial scope is shore planning.

**Evidence:** source, model, issued time when known, fetched time, requested/returned coordinates, units, vertical datum, valid instant or interval, coverage, source response hash and quality flags. Keep predicted values distinct from observations.

Tide stations must be associated with the correct harbour or connected coastline. Choosing the geographically nearest station across land is not an acceptable automatic fallback. Use published secondary-port corrections when supported; otherwise state that the local tide is unverified. A site-specific tide preference needs either a user choice or recorded local evidence, never an inference from the place name.

## API and data plan

| Input | Proposed source and access | Role and limitations |
| --- | --- | --- |
| High/low tide events | LINZ annual station CSV; secondary-port corrections where applicable | Source of local tide events, phase and astronomical heights above Chart Datum |
| Wind and weather | Open-Meteo with explicit global models covering NZ; initially evaluate ECMWF IFS and GFS separately | Wind speed/direction, gusts, precipitation, weather code; preserve per-model values |
| Rain probability / uncertainty | A verified supported probability or ensemble product | Optional; record its definition/model and do not fabricate it when unavailable |
| Offshore waves | Open-Meteo marine; evaluate ECMWF WAM and GFS wave 0.25 separately | Significant wave height, direction and period; swell components when actually supplied |
| Daylight | Sunrise/sunset from the forecast API or a tested astronomical calculation | Whole-visit daylight constraint, including setup/return buffers |
| Official warnings | MetService severe-weather CAP feed; separately confirm marine-warning coverage | Warning lifecycle, geography and validity; absence is meaningful only for a known covered product |
| Local forecast option | MetService Point Forecast API, subject to account access and evaluation | Compare local-model performance before adopting; higher resolution alone does not prove accuracy |
| Travel | A routing adapter using a supplied origin | Arrival feasibility and departure estimate, without fishing-quality points |

### Tide adapter

Example published resource: `https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Raglan%202026.csv`.

Read adjacent days/year boundaries before classifying events. CSV rows contain a variable number of ordered events, not fixed high/low columns. Preserve published local times and convert once using `Pacific/Auckland`; never add a manual daylight-saving hour. Reject an ambiguous/nonexistent wall time until the adapter can resolve it from authoritative information. Record the station and datum. [LINZ formats](https://www.linz.govt.nz/guidance/marine-information/tide-prediction-guidance/tide-prediction-formats) and [tidal levels](https://www.linz.govt.nz/guidance/marine-information/tide-prediction-guidance/standard-port-tidal-levels).

Between-event interpolation may support a labelled estimated height curve. Tide phase can be computed from elapsed time between official low and high events without inventing sub-hour predictions. Do not use an interpolated derivative as a current-speed score. Global MSL model heights and LINZ Chart Datum heights cannot be compared or subtracted without a valid datum transformation.

### Weather adapter

Verified request shape, repeated separately for each model:

```text
GET https://api.open-meteo.com/v1/forecast
  latitude=<actual site latitude>
  longitude=<actual site longitude>
  models=ecmwf_ifs                 # repeat for gfs_global
  hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,weather_code
  daily=sunrise,sunset
  wind_speed_unit=kmh
  precipitation_unit=mm
  timeformat=unixtime
  timezone=Pacific/Auckland
  forecast_days=7
```

Explicit-model common-field requests returned data in this investigation. Optional apparent temperature, visibility and probability fields need capability checks; null values remain unknown. Store each model's returned grid independently. Land/nearest/sea cell choices need site review: a harbour-edge coordinate can select an inland weather cell. Never describe its wind as a direct observation at the wharf.

Open-Meteo defines wind as instantaneous, gusts as preceding-hour maximum, precipitation as preceding-hour amount and precipitation probability over the preceding hour. Normalize these to explicit intervals. For an 11:00–13:00 session, rain/gust interval endpoints are 12:00 and 13:00. Use instant wind at 11:00, 12:00 and 13:00. A maximum hourly rain probability is not a probability of rain anywhere during the whole trip. [Weather API definitions](https://open-meteo.com/en/docs).

### Marine adapter

```text
GET https://marine-api.open-meteo.com/v1/marine
  latitude=<reviewed nearby offshore reference latitude>
  longitude=<reviewed nearby offshore reference longitude>
  models=ecmwf_wam                # repeat for ncep_gfswave025
  hourly=wave_height,wave_direction,wave_period,swell_wave_height,swell_wave_direction,swell_wave_period
  cell_selection=sea
  timeformat=unixtime
  timezone=Pacific/Auckland
  forecast_days=7
```

The live checks returned total-wave data for both models, but ECMWF swell-component arrays were null while GFS supplied them. Keep provenance by variable; do not turn null swell into zero or combine unrelated periods/directions. The requested Raglan town coordinate can resolve to an offshore grid many kilometres away. [Official marine schema](https://raw.githubusercontent.com/open-meteo/open-meteo/main/openapi/marine.yml).

The marine tide/sea-level model has limited coastal accuracy and is unsuitable as the primary harbour tide source. Wave height is significant wave height, not the largest individual wave or measured surf at the wharf. Mean wave period and swell/peak period must retain their definitions. The result card must say “offshore forecast” and cannot promise a local wave height or run-up from it. [Marine API definitions](https://open-meteo.com/en/docs/marine-weather-api).

An exposure policy can consider incoming wave direction, period, shore orientation and known local constraints. It must not use an invented attenuation multiplier to transform 2.6 m offshore into a supposedly safe 0.4 m at a wharf. Unreviewed shelter produces an unassessed condition, not a numerical safety bonus.

### Warnings, access and commercial operation

NEMA documents MetService's severe-weather feed at `https://alerts.metservice.com/cap/rss`. Handle alert updates/cancellations, polygons/areas and valid times. Confirm coastal/marine coverage separately; do not assume this feed is comprehensive for marine hazards. Unavailable or stale alerts are an unknown check. [NEMA CAP guidance](https://www.civildefence.govt.nz/guidance-training/guidelines/technical-standards/common-alerting-protocol).

The MetService Point Forecast API is a possible commercial adapter; its Tide API is currently described as closed beta, so local tides should not depend on that product. [Point Forecast documentation](https://developer.metservice.com/docs/api-catalog/point-forecast-api/) and [Tide API status](https://data.metservice.com/product/tide-api).

Public endpoint access in this analysis is not a production licence. Confirm Open-Meteo commercial-plan and ensemble/history entitlements, MetService terms and required attribution before launch. Keep provider keys on the server. [Open-Meteo pricing](https://open-meteo.com/en/pricing), [LINZ copyright](https://www.linz.govt.nz/copyright), [MetService data policy](https://about.metservice.com/our-data-access-policy).

## Calculation method

### 1. Establish eligibility and coverage

For every candidate visit, evaluate known closures, access requirements, official warnings, required forecast coverage and the site's reviewed exposure policy. Include the time needed to get to and leave the fishing position.

Return one of:

- **Excluded:** a known restriction or configured hazard limit is breached. Other good factors cannot compensate.
- **Needs checks:** essential data, access or exposure assessment is missing. Show provisional planning options and the exact missing checks; do not label the trip safe or suitable.
- **Eligible under the configured policy:** required checks and user constraints pass. This is a planning status, not a safety guarantee.

Always inspect known adverse samples before considering completeness. One hazardous wave sample plus a null sample remains an adverse condition. No generic land-wide “below 3 m is acceptable” rule should replace review of wharf, beach, rocks and harbour entrance separately. Do not manufacture new supposedly safe universal wind/wave cutoffs while redesigning the score.

### 2. Generate fair candidate sessions

Use a selected duration, such as a two-hour core. Evaluate a three-hour extension separately so a two-hour session is not automatically favoured merely because averaging drops its worse last hour. Search every 15 minutes and include relevant tide/daylight boundaries. All candidates in one result must use the same frozen response bundle.

For fractional hours, integrate precipitation by interval overlap and label the allocation assumption. Use overlapping gust maxima conservatively rather than scaling a gust by the fraction of an hour. Interpolate instantaneous endpoint values only between known bracketing samples. A gap remains a gap. Use all solar intervals across midnight. The last forecast sample does not license a session that extends beyond coverage.

Quarter-hour starts express scheduling choices. NZ quarter-hour weather from this API is interpolated; ECMWF's native steps become coarser at longer leads. Do not print “10:45 is optimal” as a weather precision claim. [ECMWF forecast resolution](https://open-meteo.com/en/docs/ecmwf-api).

### 3. Compute explicit preference fit

For an illustrative **late incoming through high water** preference, an initial configurable target band is:

```text
target = [official local high tide − 150 minutes, high tide + 30 minutes]
tide preference fit = 100 × overlap(session, target) / session duration
```

This is a transparent planning preference, not a validated biological equation. Other profiles may prefer mid-flood, ebb, low-water access or no tide bias. A local fishing profile should name its evidence and applicable method/species. An absent tide preference supplies no invented catch bonus.

On 30 September, the example target is **10:53–13:53**. The 11:00–13:00 core fits entirely; 07:00–09:00 has zero overlap. Both are largely within the rising-water part of the cycle, but they fulfil different preferences. Zero preference fit does not mean zero chance of catching fish.

Calculate daylight as the proportion of the entire visit inside local sunrise/sunset intervals. If daylight is required, any dark access/fishing segment fails that preference. If night is explicitly allowed, display the dark duration. Dawn/dusk is an optional user or evidence-backed local preference; no automatic 15% sunrise bonus is needed.

### 4. Evaluate weather comfort without pretending it predicts fish

Keep a vector of understandable facts:

```text
wind: maximum/representative sustained speed, maximum gust, direction
rain: estimated total, maximum hourly intensity, optional maximum hourly probability
visibility/temperature: when supported and relevant
marine: offshore significant wave height/period/direction plus site assessment status
daylight: minutes of daylight and darkness during visit
tide: official events and named preference fit
```

Use the user's rain and wind tolerances as constraints or comfort bands. These are comfort settings, not safety cutoffs. Avoid counting drizzle three times through rain chance, rain amount and a weather-code penalty. Probability describes uncertainty about rain; amount describes potential wetness. Weather codes should identify conditions such as thunder rather than silently supplying arbitrary fish-activity points.

For a known shore orientation, direction can help explain headwind/crosswind and exposed swell. Do not infer shelter from direction alone. Temperature, pressure, lunar phase and cloud cover can be shown as context; adding catch bonuses requires local validation.

### 5. Select a primary option and a meaningful alternative

Use a deterministic decision policy rather than a new unvalidated blend of six percentages:

1. Apply eligibility and the user's hard constraints.
2. If the user chose a tide preference, form a preferred set with **at least 80% target-band overlap**. This is an initial product rule, explicitly versioned and adjustable, not a scientific threshold.
3. Apply the chosen comfort tolerances to that set. Do not silently relax them to produce a recommendation. If it is empty, return “no window meets all preferences” with clearly marked alternatives.
4. Remove dominated options: an option is dominated only when another has at least as good tide fit and no worse gust, sustained wind and rainfall, with at least one real improvement. Never compare incomplete candidates as if their unknown values were favourable.
5. Among the remaining options, use the user's selected priority: calmer wind, less rain or stronger tide fit. To illustrate the user's interest in 11 am–1 pm, show how tide fit followed by gusts, sustained wind and rainfall would rank the options after tolerances are satisfied. Do not treat that example as a confirmed setting. Without a chosen priority or reviewed profile, present the trade-off instead of inventing one catch-quality winner. Keep tie-breaks documented. Fixed-site travel distance never enters this comparison.
6. Offer the calmest eligible daylight option as a separate alternative if it meaningfully differs. Explain the trade-off. If priorities disagree, keep both visible.
7. Repeat the comparison under each available model/scenario. If the winner changes, report lower ranking confidence and “similar options” or a provisional choice. Model votes are not probabilities of safety or catching fish.

An internal numeric fit index can be added later after user preference testing. It must keep its declared meaning and cannot compensate for a failed constraint. No missing-data renormalisation, permanent distance bonus or arbitrary 79/89 cap should substitute for a separate confidence assessment.

### 6. Describe the same calculation that selected the window

Use deterministic facts and reason codes to produce the card and explanation. An optional language model may improve wording, but all dates, tides, wind values, risks and rankings must come from that result object.

Example wording for the Raglan comparison, subject to the actual frozen weather and site checks:

> **If you prefer late incoming tide: 11 am–1 pm.** Falls before the official 1:23 pm high. Rain may increase toward midday. **Calmer alternative: 7–9 am**, shortly after the 7:02 am low. Offshore wave conditions and local access still need assessment.

The application should never independently describe the later session as “dry” if its own interval data forecast rain, or say “strong current” when it only knows tide heights.

## Forecast confidence and reproducibility

Store an immutable bundle: raw provider responses, source URLs with secrets redacted, retrieval times, response hashes, units, model and grid metadata, site/profile versions and scoring version. `generationtime_ms` is not a forecast issue time. When exact issuance cannot be established, say so. Provider model metadata or Single Runs can improve traceability; an automatic best-match response should not be presented as one named run. [Model-update documentation](https://open-meteo.com/en/docs/model-updates).

Maintain distinct confidence flags for tide station fit, site exposure, spatial weather fit, forecast lead, completeness, source freshness and model disagreement. A one-week forecast is provisional even when an API supplies precise hourly numbers. Model agreement helps assess robustness but can share systematic errors. Ensemble ranges need proper calibration and must not be described as guaranteed bounds. [Ensemble documentation](https://open-meteo.com/en/docs/ensemble-api).

Cache annual tide tables with update checks. Cache weather by coordinate/model/response version, refresh in line with provider updates and expose staleness. Deduplicate nearby requests and keep rate limits, retries and circuit breakers in the server. Alert refresh policy must follow provider guidance and must not conceal an outage. Record the snapshot used for each shown recommendation so a user can understand why it later changes.

## Shared implementation contract

Keep one normalization and recommendation implementation behind a shared service. The existing FastAPI service is a suitable integration point; the two apps should consume the same response instead of continuing separate Kotlin and Swift algorithms. This is a proposed integration, not an endpoint already deployed.

```text
POST /api/v1/fishing-windows
request:
  verified site ID or coordinate with unknown-site status
  date range + Pacific/Auckland
  fixed core duration + optional extension
  allowed hours + full-visit daylight/night preference
  named tide preference + method/species if supplied
  explicit weather tolerances and priority
  optional travel constraints

response:
  generatedAt, snapshotId, methodVersion, siteProfileVersion
  sources: station/model, issue/fetch times, coordinates, units, datum
  primary option and meaningful alternatives
  each option: start/end, eligibility, confidence flags, preference fit
               full-window facts, exclusions/checks, reason codes
  dates with no result: specific reason (adverse / missing data / no preference fit)
```

A displayed fishing site must be confirmed in the catalogue before using a wharf profile. Until then the backend may return a regional planning comparison, clearly marked as unassessed. Forecast coverage should govern how far ahead results extend; the existence of 16 days of weather does not mean all marine variables cover 16 days.

## Validation and release gates

The [diagnostic prototype](../tools/review_fishing_windows.py), [18 regression tests](../tools/tests/test_review_fishing_windows.py), [frozen Raglan comparison](../data/analysis/raglan-2026-09-27/comparison.md) and [source manifest](../data/analysis/raglan-2026-09-27/manifest.json) are saved in this repository. The raw data was retrieved on **27 September 2026 at approximately 13:17 NZDT**. Analysis version 2 reuses the unchanged version-1 capture, with source hashes checked on replay.

For Wednesday 30 September, equal two-hour sessions produce:

| Fact from the saved forecast | 07:00–09:00 | 11:00–13:00 |
| --- | ---: | ---: |
| Maximum sustained wind | 6.6 km/h | 10.6 km/h |
| Maximum gust | 14.0 km/h | 22.0 km/h |
| Forecast precipitation total | 0 mm | 0.50 mm |
| Maximum hourly precipitation probability | 0% | 76% |
| Maximum offshore significant wave height | 2.80 m | 2.64 m |
| Fishing-session daylight | 100% | 100% |
| Overlap with illustrative late-incoming target | 0 minutes | 120 minutes |

This supports **calmer/drier early weather versus stronger later tide-preference fit**. It does not show that one session will catch more fish, confirm wharf conditions, or reproduce the app's earlier score. The comparison also includes equal two-hour afternoon cores for Thursday, Friday and Saturday, and the longer pasted sessions separately. The captured default/best-match forecast is for diagnosis; the proposed comparison between explicit models is not implemented in this prototype.

All 18 diagnostic tests pass. They check interval alignment, end-of-session conditions, partial coverage, known adverse samples, whole-session daylight, NZ daylight-saving conversion, source station identity, unit mismatch, invalid probabilities and modified-response detection. Offline replay passes against the saved source hashes. Run from the repository root:

```sh
python3 -m unittest discover -s tools/tests -p 'test_review_fishing_windows.py' -v
python3 tools/review_fishing_windows.py --snapshot data/analysis/raglan-2026-09-27
```

The prototype compares specified sessions; it does not yet implement the complete candidate search, eligibility policies, warning ingestion, model comparison, user ranking or mobile integration. These passing tests verify the diagnostic calculations only. The broader release cases below remain integration requirements, not completed test claims.

Required behavioural cases:

| Case | Expected result |
| --- | --- |
| Same acceptable weather, late-incoming preference, Raglan 30 September | 11–13 beats 07–09 on declared tide preference |
| Same weather, no tide/dawn preference | No invented catch winner |
| Dawn is calmer and midday fits tide better | Explain both advantages; apply user priority |
| Midday violates a hard/site constraint | Exclude midday despite preferred tide |
| Known high wave plus missing second value | Preserve adverse assessment and incomplete-data flag |
| Poor factor becomes missing | Apparent quality/confidence must not improve |
| 07–09 rain/gust calculation | Use intervals ending 08 and 09; include 09 instant wind/wave |
| Conditions deteriorate in the final hour | Deterioration changes the decision |
| Partly dark session with daylight required | Reject even if midpoint is in daylight |
| Crossing midnight | Evaluate both dates' forecasts and solar intervals |
| 27 September NZ daylight-saving change | Official local times convert exactly once |
| Ambiguous local time on autumn transition | Resolve explicitly or reject; no silent guess |
| Near station is across a peninsula | Require a valid water-body association |
| Exposed rocks selected instead of wharf | Reassess the site; do not reuse wharf eligibility |
| Warning fetch fails | Unknown warning status, not “no warnings” |
| One model has null swell | Unknown swell; do not treat as calm |
| API forecast ends during session | No fully assessed result beyond coverage |
| Search radius changes but site does not | Site conditions and preference fit unchanged |
| Forecast or method changes | Record new versions and explain the changed inputs |
| Android and iOS render the same response | Identical times, values, statuses and material warnings |

Implementation order: correct provider/time/null/tide contracts; review initial site profiles; implement the shared decision policy and explanations; connect both apps; replay fixed cases and conduct field/user trials. Safety-policy thresholds require appropriate local review before use. Do not tune weights until the desired user's preferences are explicit and data errors are eliminated.

Validate forecast reliability against appropriate observations and forecast-as-issued archives. Validate usability with users whose preferences are recorded. A future catch-success model requires local trip logs including unsuccessful trips, effort, method, species, season and angler effects, tested on later held-out trips. Ranking retrospective favourable weather is not a validation of better catches.
