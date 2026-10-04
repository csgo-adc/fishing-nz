# Tide fixtures

`raglan-2026.csv` is the unmodified LINZ Raglan annual table captured on 27 September 2026.

Source: https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Raglan%202026.csv

`thames-2026.csv` is the unmodified LINZ Thames annual table captured on 4 October 2026.

Source: https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Thames%202026.csv

Thames contains a 02:59 low tide on 5 April, in the repeated NZ clock hour. A request for October must not be blocked by that unrelated event. Requests needing the repeated clock or that event to bracket a curve still reject an unverified time.

The table declares local standard or daylight time and tidal heights in metres. Heights are relative to the station's chart datum. Tests retain the original bytes so changes to parsing cannot silently change the reference tide times.

Attribution: Toitū Te Whenua Land Information New Zealand. See https://www.linz.govt.nz/copyright for reuse terms.
