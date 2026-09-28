# Location and rules data

CatchCheck uses named coastal places to help plan a trip. A map pin marks a place to explore, not a verified public fishing access point or a statement that fishing is allowed there. Check access, signs, marine reserves, and local restrictions before fishing.

## Recreational fishing rules

[Fisheries New Zealand (MPI)](https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/) publishes five broad recreational fishing areas: Auckland/Kermadec, Central, Challenger, South-East, and Southland/Sub-Antarctic. MPI also publishes separate rule pages for the Kaikōura and Fiordland marine areas and the Chatham Rise. Its [amateur fisheries management area GIS layer](https://maps.mpi.govt.nz/wss/service/arcgis1/guest/MARINE/MARINE_FMAs/MapServer/9) contains the mapped management boundaries; simple latitude bands are not a valid replacement. Kaikōura and Fiordland also have special coastal boundaries within broader areas.

The app lets people select the MPI area that applies to their fishing location, links to the corresponding official page, and gives a short summary of cached MPI material. The official page remains the source for the full rule, species differences, closures, and recent changes. For example, MPI distinguishes Auckland East and Auckland West snapper limits on its [Auckland/Kermadec page](https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/auckland-kermadec-fishing-rules).

## Tide predictions

The [LINZ tide predictions list](https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions/tide-predictions-list-view) separates sites with daily prediction CSV files from **offset** sites that require a reference port calculation. CatchCheck uses the direct daily predictions for its tide graph and event times. The [daily station catalogue](../data/linz_daily_tide_stations.json) records the LINZ display name, CSV name, published coordinates, and source CSV URL. The catalogue has 87 stations with verified 2026 CSV files. The LINZ list labels Te Weka Bay as a daily site but links only an offset PDF, so it is excluded.

LINZ publishes event times and heights. Values between high and low tide events in the app are interpolated estimates. The apps omit sites whose local time zone is not handled by the current tide parser.

## Coastal places and map

The Android map offers [OpenStreetMap](https://www.openstreetmap.org/copyright) based maps, and the iOS map offers Apple Maps. Both apps also offer [LINZ Basemaps](https://basemaps.linz.govt.nz/) aerial imagery and topographic maps. LINZ permits its Basemaps key to be included in client-side apps. When a LINZ layer is selected, the map shows linked **LINZ CC BY 4.0** credit and the applicable [basemap contributor information](https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data). The LINZ tide predictions described above are a separate data source.

LINZ nautical chart and bathymetric elevation basemaps are not available through this integration. These map layers are for exploring places, not for navigation or determining water depth. Coastal place names and approximate pins are a planning catalogue. Official place names can be checked in the [LINZ Gazetteer](https://www.linz.govt.nz/guidance/place-naming/how-use-new-zealand-gazetteer). Neither a mapped wharf nor a tide prediction site implies fishing permission or safe access.
