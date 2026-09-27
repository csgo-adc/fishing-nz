# Raglan window comparison

Frozen retrieval: 2026-09-27T00:17:37.468812+00:00 through 2026-09-27T00:17:41.560566+00:00.

Analysis: raglan-window-diagnostic-2. Original capture: raglan-window-diagnostic-1; raw responses unchanged.

These are forecasts for the Raglan tide-station coordinate (-37.8, 174.883333). Exact fishing site, exposure, access and official warnings are **unassessed**. No window is certified safe or predicted to catch more fish.

Illustrative planning preference, inferred from the user's interest in late incoming tide: high tide minus 150 minutes through high tide plus 30 minutes. Not a confirmed trip setting or a catch, current or safety model.

Two-hour rows compare the app's early session with a later core. Longer rows reproduce the pasted itinerary for inspection; different durations are not ranked against each other.

| NZ date / window | Minutes | Wind max km/h | Gust max km/h | Rain mm estimate | Max hourly rain chance | Offshore Hs max m | Mean wave period max s | Daylight | Preferred tide overlap | Compared-field coverage |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Wed 30 Sep 07:00–09:00 | 120 | 6.6 | 14.0 | 0.00 | 0% | 2.80 | 12.1 | 100% | 0 min (0%) | Complete |
| Wed 30 Sep 11:00–13:00 | 120 | 10.6 | 22.0 | 0.50 | 76% | 2.64 | 12.1 | 100% | 120 min (100%) | Complete |
| Wed 30 Sep 10:45–13:45 | 180 | 11.7 | 24.1 | 0.70 | 76% | 2.65 | 12.1 | 100% | 172 min (96%) | Complete |
| Thu 01 Oct 08:00–10:00 | 120 | 14.6 | 27.0 | 0.00 | 6% | 1.98 | 11.8 | 100% | 0 min (0%) | Complete |
| Thu 01 Oct 12:00–14:00 | 120 | 19.8 | 37.8 | 0.00 | 19% | 1.90 | 11.6 | 100% | 120 min (100%) | Complete |
| Thu 01 Oct 11:15–14:15 | 180 | 19.8 | 38.2 | 0.00 | 20% | 1.92 | 11.6 | 100% | 157 min (87%) | Complete |
| Fri 02 Oct 05:00–07:00 | 120 | 15.9 | 28.1 | 0.00 | 2% | 1.64 | 9.9 | 4% | 0 min (0%) | Complete |
| Fri 02 Oct 13:00–15:00 | 120 | 26.5 | 47.2 | 0.20 | 45% | 1.68 | 9.0 | 100% | 120 min (100%) | Complete |
| Fri 02 Oct 11:30–14:45 | 195 | 26.4 | 47.2 | 0.33 | 45% | 1.68 | 9.1 | 100% | 136 min (70%) | Complete |
| Sat 03 Oct 06:00–08:00 | 120 | 18.3 | 34.6 | 0.10 | 8% | 1.76 | 8.7 | 55% | 0 min (0%) | Complete |
| Sat 03 Oct 14:00–16:00 | 120 | 22.8 | 44.6 | 0.20 | 37% | 1.78 | 8.8 | 100% | 120 min (100%) | Complete |
| Sat 03 Oct 14:15–16:30 | 135 | 22.6 | 44.6 | 0.23 | 37% | 1.77 | 8.9 | 100% | 135 min (100%) | Complete |

Hs is offshore significant wave height. The table gives its highest forecast value during the session, not the largest individual wave or wave height at a wharf. Mean wave period is not peak swell period. Complete coverage applies only to the compared fields, not to warnings, access or site suitability.

Maximum values retain known samples even when coverage is incomplete. Missing values never erase a known elevated wave. Rain chance is the maximum hourly probability, not the chance of rain at any point across the whole trip. Rain totals for fractional hours allocate hourly totals by time overlap; sub-hour rain timing is unknown.

Wind and waves include the session endpoints, interpolated between available hourly values. Gusts, rainfall and rain probabilities refer to the preceding hour: a 07:00–09:00 session uses entries labelled 08:00 and 09:00. Daylight covers the full fishing session; access/setup/return times are not supplied and remain unassessed. Interpolation does not add forecast precision.

## Official Raglan tide events

| Date | Events, NZ local time |
|---|---|
| 2026-09-30 | High 00:57 (3.3 m); Low 07:02 (0.2 m); High 13:23 (3.2 m); Low 19:24 (0.4 m) |
| 2026-10-01 | High 01:41 (3.2 m); Low 07:46 (0.4 m); High 14:08 (3.1 m); Low 20:12 (0.5 m) |
| 2026-10-02 | High 02:29 (3.0 m); Low 08:33 (0.5 m); High 14:59 (3.0 m); Low 21:05 (0.7 m) |
| 2026-10-03 | High 03:23 (2.8 m); Low 09:26 (0.7 m); High 16:00 (2.8 m); Low 22:07 (0.9 m) |

LINZ event times already include daylight saving. Heights use Chart Datum. Interpolated offshore sea level is retained in the raw marine snapshot for inspection but is not used for tide preferences or local current estimates.

## Sources and reproducibility

- [weather](https://api.open-meteo.com/v1/forecast?latitude=-37.8&longitude=174.883333&forecast_days=8&timezone=Pacific%2FAuckland&timeformat=unixtime&hourly=wind_speed_10m%2Cwind_direction_10m%2Cwind_gusts_10m%2Cprecipitation%2Cprecipitation_probability%2Cweather_code&daily=sunrise%2Csunset&cell_selection=land&wind_speed_unit=kmh&precipitation_unit=mm), retrieved 2026-09-27T00:17:39.486336+00:00; raw file `weather.json`; SHA-256 `e475d9848b87cd0b04200cef8b89fce0f415d8a3a0f55f0abd3f6ebdfde73a58`.
  Returned grid coordinate: {"latitude": -37.785587, "longitude": 174.93976, "elevation": 21.0}. Model issue time is not provided; retrieval time is recorded separately.
- [marine](https://marine-api.open-meteo.com/v1/marine?latitude=-37.8&longitude=174.883333&forecast_days=8&timezone=Pacific%2FAuckland&timeformat=unixtime&hourly=wave_height%2Cwave_period%2Cwave_direction%2Cswell_wave_height%2Cswell_wave_period%2Cswell_wave_direction%2Csea_level_height_msl&cell_selection=sea&length_unit=metric), retrieved 2026-09-27T00:17:41.411885+00:00; raw file `marine.json`; SHA-256 `e251a54319ee6f97d17ef25010b359efb67e8532ecc29a396ddea06a5f07dd25`.
  Returned grid coordinate: {"latitude": -37.791668, "longitude": 174.79167, "elevation": 21.0}. Model issue time is not provided; retrieval time is recorded separately.
- [tides](https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Raglan%202026.csv), retrieved 2026-09-27T00:17:41.559680+00:00; raw file `raglan-2026.csv`; SHA-256 `dbe486d9934ac6bbed5e6e6c9fb6ed9683a529234baab1b8f989a06701986f4a`.

All units, raw responses and response metadata are frozen in this directory. Offline replay validates hashes before analysis. The method produces an evidence comparison rather than a combined score or recommended winner.

API interval definitions: [weather](https://open-meteo.com/en/docs), [marine](https://open-meteo.com/en/docs/marine-weather-api).
