# Location and rules data

Fishdays - NZ uses named coastal places to help plan a trip. A map pin marks a place to explore, not a verified public fishing access point or a statement that fishing is allowed there. Check access, signs, marine reserves, and local restrictions before fishing.

## Recreational fishing rules

[Fisheries New Zealand (MPI)](https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/) publishes five broad recreational fishing areas: Auckland/Kermadec, Central, Challenger, South-East, and Southland/Sub-Antarctic. MPI also publishes separate rule pages for the Kaikōura and Fiordland marine areas and the Chatham Rise. Its [amateur fisheries management area GIS layer](https://maps.mpi.govt.nz/wss/service/arcgis1/guest/MARINE/MARINE_FMAs/MapServer/9) contains the mapped management boundaries; simple latitude bands are not a valid replacement. Kaikōura and Fiordland also have special coastal boundaries within broader areas.

The app lets people select the MPI area that applies to their fishing location, links to the corresponding official page, and gives a short summary of cached MPI material. The official page remains the source for the full rule, species differences, closures, and recent changes. For example, MPI distinguishes Auckland East and Auckland West snapper limits on its [Auckland/Kermadec page](https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/auckland-kermadec-fishing-rules).

Both apps keep every area's summary on the phone. The service's `/v1/rules/manifest` lists a version for each area (the saved page's hash, changed again when MPI updates the page and it waits for review) and the species pictures with their SHA-256 hashes. The apps check it when they open (at most every 12 hours) and when the rules page opens, and download only areas and pictures that changed. `tools/build_offline_rules.py --rules` also bundles every area's summary into the apps for a first launch with no signal; the page says when it is showing that copy.

### Species illustrations

The species pictures on the rules page are illustrations drawn for Fishdays - NZ with an image model from written descriptions of each species (shape, colours and markings), not copied from photographs, then checked species by species; a few were redrawn and the New Zealand flounders mirrored so their eyes are on the right side. `data/species_pictures.json` lists each species, the MPI rule names it covers and its pictures, including juvenile, adult, male and female forms where they look different. Species marked `"combined": true` are common catches with no MPI rule of their own (John dory, leatherjacket, pōrae, sweep, blue maomao, red snapper, blue mackerel); the apps list them under the area's combined finfish limit unless the area has a rule for them. Scarlet wrasse and southern lemon sole were left out because no reliable colour description was found to check a drawing against. `tools/build_offline_rules.py --pictures <folder>` publishes them to `server/fishial-proxy/public/species/`, which the worker serves as static files. The app labels them as illustrations: they are a guide, and people should check the species and the current MPI rules before keeping a catch.

## Tide predictions

The [LINZ tide predictions list](https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions/tide-predictions-list-view) separates sites with daily prediction CSV files from **offset** sites that require a reference port calculation. Fishdays - NZ uses the direct daily predictions for its tide graph and event times. The [daily station catalogue](../data/linz_daily_tide_stations.json) records the LINZ display name, CSV name, published coordinates, and source CSV URL. The catalogue has 87 stations with verified 2026 CSV files. The LINZ list labels Te Weka Bay as a daily site but links only an offset PDF, so it is excluded.

LINZ publishes event times and heights. Values between high and low tide events in the app are interpolated estimates. The apps omit sites whose local time zone is not handled by the current tide parser.

Map conditions and **Find fishing windows** share the same automatic tide reference loader. Locations, search results and dropped pins use their linked station or the nearest daily prediction station. If that source cannot serve the selected dates, both apps try the next nearby stations, up to three candidates. Each tide row names the station actually used and shows its distance. The planner uses the loaded predictions for tide phase and late-incoming preferences; these describe the reference station, and timing at the fishing spot can differ. A manual station choice remains selected; **Use nearest available station** on the map returns to automatic selection. Map tide dates remain available when weather and marine requests fail.

LINZ filenames and CSV headers sometimes spell the same station differently. Both parsers accept apostrophe spacing (for example, `Man o‘War Bay` versus `Man O' War Bay`) and six explicitly verified header aliases, while still rejecting a different station. Source structure, units, dates, chronological order and finite heights are validated for the annual table; high/low classification and local clock conversion are validated for the requested days and the adjacent events used by the curve. Rounded equal-height events on unrelated dates no longer hide valid tides. Regression fixtures preserve the published [Man o‘War Bay](https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Man%20o%E2%80%98War%20Bay%202026.csv) and [Mana Marina](https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Mana%20Marina%202026.csv) tables.

A live audit on 5 October 2026 verified today's predictions for all **82 stations supported by the apps** and automatic tide coverage for all **139 fishing entries plus six sample dropped pins**. The full catalogue records 87 daily CSV sites; the apps exclude five sites in the Chatham Islands, Raoul Island and Antarctica. Run `tools/test_place_conditions_swift.sh --audit-tides` to repeat the live map coverage check, and `tools/test_fishing_windows_swift.sh --live-tides` to compare live Thames and Waitawa reference predictions with their regression fixtures and verify Waitawa's live fishing-window search.

## Coastal places and map

Both apps use the same [fishing-location catalogue](../data/fishing_locations.json): **139 entries, with 83 land areas and 56 boat areas**, reviewed on 5 October 2026. This expands Android's previous 73-entry list and replaces the separate iOS list. The catalogue includes named beaches, bays, river entrances and harbour areas across both main islands. Land and boat entries can describe different parts of the same place.

Of these entries, **82 have individually recorded published coordinate sources**: [LINZ Gazetteer](https://gazetteer.linz.govt.nz/), LINZ daily tide CSV headers, or DOC coastal visitor pages. The remaining 57 retain the original approximate coordinates and are explicitly marked `legacy_approximate`; their individual coordinate sources are unverified. Gazetteer records identify the named geographical feature, LINZ records identify tide reference sites, and DOC records identify coastal campsite or access areas. These are different kinds of reference points, not a guarantee of a precise casting position or boat launch. For example, [Uretiti](https://www.doc.govt.nz/uretiti), [Fletcher Bay](https://www.doc.govt.nz/fletchercampsite) and [Marfells Beach](https://www.doc.govt.nz/parks-and-recreation/places-to-go/marlborough/places/marfells-beach-area/things-to-do/campsites/marfells-beach-campsite/) publish coastal coordinates and describe fishing nearby. Mission Bay now uses its Auckland Gazetteer bay coordinate rather than the former central-city pin.

Catalogue points were screened against DOC's published [marine reserves](https://services1.arcgis.com/3JjYDyG3oajxU6HO/arcgis/rest/services/DOC_Marine_Reserves/FeatureServer/0), [south-east marine reserves](https://services1.arcgis.com/3JjYDyG3oajxU6HO/arcgis/rest/services/SE_marine_reserves_type1_CURRENT/FeatureServer/0) and [Hauraki Gulf marine protection areas](https://services1.arcgis.com/3JjYDyG3oajxU6HO/arcgis/rest/services/Hauraki_Gulf_Tikapa_Moana_Marine_Protection_Act/FeatureServer/0). Any point overlapping one of these polygons was conservatively excluded; the Smaills Beach candidate overlapped Ōrau Marine Reserve. This is a point check on the review date, not an assessment of a whole bay, route, adjoining coast, all local rules, temporary closures, rāhui or public access. Protection types can have different rules. Check the current MPI area page and local restrictions before fishing.

The offline lists in both apps are generated from that shared catalogue. To add or correct a point, record its coordinates, fishing type and source in the JSON, then run `python3 tools/sync_fishing_locations.py`. Run `python3 tools/sync_fishing_locations.py --check` to validate coordinate bounds, duplicate identifiers, provenance and that both app lists still match.

Verification: both apps build, 73 Android unit tests and 143 Swift checks (including live tide and fishing-window checks) pass, and the catalogue check confirms identical lists. The Android emulator displays 139 spots and finds/selects the new Uretiti Beach entry. All 40 LINZ reference entries were checked against 32 live daily CSV headers, all 11 DOC coordinates against their live visitor pages, and the 31 Gazetteer coordinates against the current Gazetteer export.

The Android map offers [OpenStreetMap](https://www.openstreetmap.org/copyright) based maps, and the iOS map offers Apple Maps. Both apps also offer [LINZ Basemaps](https://basemaps.linz.govt.nz/) aerial imagery and topographic maps. LINZ permits its Basemaps key to be included in client-side apps. When a LINZ layer is selected, the map shows linked **LINZ CC BY 4.0** credit and the applicable [basemap contributor information](https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data). The LINZ tide predictions described above are a separate data source.

LINZ nautical chart and bathymetric elevation basemaps are not available through this integration. These map layers are for exploring places, not for navigation or determining water depth. Coastal place names and approximate pins are a planning catalogue. Official place names can be checked in the [LINZ Gazetteer](https://www.linz.govt.nz/guidance/place-naming/how-use-new-zealand-gazetteer). Neither a mapped wharf nor a tide prediction site implies fishing permission or safe access.

## Marine protected areas on the map

Both maps can show DOC marine reserves and the Hauraki Gulf / Tīkapa Moana protection areas (on by default; **Map layers → Marine protected areas** turns them off). Red areas ban fishing: marine reserves (no taking of any marine life) and High Protection Areas (no recreational or commercial fishing; customary fishing aside). Amber Seafloor Protection Areas allow line fishing but ban trawling and dredging, and some also restrict nets, pots or longlines. The wording follows [DOC's description of the new Hauraki Gulf protections](https://doc.govt.nz/our-work/revitalising-the-gulf/new-marine-protections-in-the-hauraki-gulf).

A dropped pin, a searched place, a fishing area and a fishing window get a notice when they are inside a protected area, within 2 km of a no-fishing area, or within 500 m of a Seafloor Protection Area. On the review date four listed spots were that close: Port Jackson (200 m from the Cape Colville High Protection Area), St Clair Beach (200 m from Ōrau Marine Reserve), Marsden Point (600 m from Whangārei Harbour Marine Reserve) and Leigh Harbour / Omaha Cove (1.5 km from Cape Rodney-Okakari Point Marine Reserve).

`python3 tools/build_marine_protection.py` downloads the three DOC layers listed above, keeps current areas, drops the five south-east reserves that repeat DOC's national layer, simplifies boundaries to about 30 m and writes [data/marine_protection.json](../data/marine_protection.json) with identical copies in the Android assets and iPhone resources (69 areas, about 290 KB, retrieved 19 October 2026). `--check` confirms the copies match. Islands cut out of an area stay outside it. The national marine reserves layer is [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); the south-east and Hauraki Gulf layers are Crown copyright under DOC's terms, which ask maps to acknowledge "Crown Copyright: Department of Conservation Te Papa Atawhai", say when the data was acquired and that it may be out of date. The apps credit DOC on the map and say this under each notice. The boundaries are approximate, not for navigation or legal interpretation, and do not include temporary closures or rāhui; notices link to [DOC marine reserves](https://www.doc.govt.nz/marinereserves) or the [Hauraki Gulf Marine Park](https://www.doc.govt.nz/haurakigulfmarinepark) page.

## Boat ramps

In Boat mode the maps show public boat ramps (teal pins; on Android once zoomed in), and a boat window's details list
the three nearest within 25 km with directions. When a low tide from the nearest LINZ station falls within an hour of
the trip, the card says so, because some ramps can't be used near low tide. The ramps are OpenStreetMap
`leisure=slipway` features: `python3 tools/build_boat_ramps.py` downloads them from the Overpass API, leaves out
private, no-access, permit and customer-only ramps, ship slipways on rails and abandoned or disused ones, merges points
within 30 m and writes [data/boat_ramps.json](../data/boat_ramps.json) with identical copies in the apps (1,004 ramps,
46 named, 79 KB, retrieved 20 October 2026). `--from <file>` builds from a saved Overpass reply and `--check` confirms
the copies match. 49 of the 56 boat fishing areas have a ramp within 15 km. The data is © OpenStreetMap contributors
under the [Open Database License](https://opendatacommons.org/licenses/odbl/1-0/); the apps credit OpenStreetMap on
the map and the ramp card, and data/boat_ramps.json is the derived database, offered under the same licence. Public
access, condition and tide limits are not recorded, and the apps say so.

## Wharves and breakwaters

In Land mode the maps show coastal wharves, breakwaters and groynes (brown pins; on Android once zoomed in), and a land
window's details list the three nearest within 10 km with directions. The structures are OpenStreetMap
`man_made=pier`, `breakwater` and `groyne` ways within 100 m of the sea coastline:
`python3 tools/build_shore_structures.py` downloads them from the Overpass API (the coastline filter takes 8 to 10
minutes) and leaves out floating pontoons; private, no-access and `fishing=no` structures; abandoned or disused ones;
unnamed jetties under 80 m, which are mostly private bach jetties (this takes the Marlborough Sounds from 641 mapped
structures to 27); marina arms ("B Pier") and tanker, oil, fuel, berth and similar industrial names; anything inside an
OpenStreetMap port or marina area or inside a marine reserve or High Protection Area from
[data/marine_protection.json](../data/marine_protection.json); and twelve features checked by hand that are a fuel
terminal, fishing company wharves, marina or tour-only landings, a building, a boardwalk or a ramp. Pieces of one
structure within 40 m are merged and the pin goes at the middle of the structure. It writes
[data/shore_structures.json](../data/shore_structures.json) with identical copies in the apps (516 structures: 197
wharves, 260 breakwaters and 59 groynes, 78 named, 44 KB, retrieved 25 October 2026). `--from <file>` builds from a
saved Overpass reply and `--check` confirms the copies match. 44 of the 83 land fishing areas have a structure within
2 km and 61 within 10 km; exposed surf beaches such as Muriwai, Hokitika and Kāpiti have none.

The data is © OpenStreetMap contributors under the [Open Database License](https://opendatacommons.org/licenses/odbl/1-0/);
the apps credit OpenStreetMap on the map and the card, and data/shore_structures.json is the derived database, offered
under the same licence. A mapped wharf isn't permission to fish: ports, ferry wharves and some council wharves ban it,
OpenStreetMap rarely records this, and the filter can't catch every commercial wharf. The apps say so on the pin's card
("access and fishing rules not checked") and on the nearby card, and tell people to check the signs.
