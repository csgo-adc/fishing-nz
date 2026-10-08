# Fishdays - NZ

**Plan your next fishing trip around New Zealand.**

Current project version: **1.0.11 (build code 12)**. The original code is preserved
at Git tag `v1.0.0`. For each future change or commit, run
`python3 tools/version.py bump`, update [the changelog](CHANGELOG.md), and run
`python3 tools/version.py check` before committing. See [the version policy](AGENTS.md).

Fishdays - NZ helps you find a place and time to fish, check nearby tides, and review fishing rules before you head out. The iOS and Android apps bring these tools together for both land and boat fishing.

## Find a fishing window

Start with your current location or choose a town. Pick land or boat fishing, how far you want to travel (10–500 km), and when you want to go. You can search today, in three days, this week, this weekend, or choose your own dates within the next 16 days.

The app compares named fishing areas and suggests two-hour windows. Each suggestion shows a short outlook label, straight-line distance, a suggested time, reasons for the suggestion, and any weather or sea-condition warnings.

Suggestions normally fit between **7:00 AM and 9:00 PM**. Choose your own hours, including overnight, or select **Anytime** if you are happy to start early.

## See the tides

The Tide page selects the nearest supported tide station from your location. You can search for another station, move between days, and slide across the curve to inspect the estimated height at a particular time. Published high and low tide times and heights come from [Toitū Te Whenua Land Information New Zealand](https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions) and are shown above local Chart Datum. The station picker includes locations with direct LINZ daily predictions; offset-only locations require a separate calculation.

## Explore and prepare

- **Map:** Search a place or tap the map, then check upcoming and recent conditions on a full-screen page. Compare daily and hourly weather, wind, offshore waves, tide events, rain and daylight. Expand each item for explanations and shore/boat guidance. History supports 3, 7, 30 or 92 recent days. Existing fishing spots and LINZ map layers remain available. [How place conditions work](docs/place-conditions.md).
- **Trips:** Keep a shortlist of spots and an active plan while you are planning.
- **Fishing rules:** Choose the Fisheries New Zealand area that applies to your location for a short rule summary, then open the [official area page](https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/) for complete limits and local restrictions.
- **Weather:** Open the Weather tab next to Tide for current conditions, an hourly outlook, and up to 16 forecast days with familiar weather icons and plain labels. Choose your current location or a named New Zealand coastal location, save preferred locations, and swipe between them. Forecasts use Open-Meteo; check official marine warnings for the place you plan to fish.
- **Account:** Create an account in the mobile app with an 8-character minimum password entered twice, or use Google or Apple once sign-in is configured. Manage your profile, connect sign-in options, and send feedback. The website is for administrators. [Sign-in setup](docs/account-sign-in.md).
- **Fish photo identification:** Sign in, then take or choose a photo to get a suggested species and related rule information. Each account has five identifications per day, shared across devices and reset at midnight New Zealand time. Failed requests do not use the allowance.

## Good to know

Fishdays - NZ searches a curated set of named areas, so a small search radius may have no results. A named wharf or beach on the map does not guarantee public access or that fishing is allowed. Window outlook labels summarise forecast conditions; they do not predict how many fish you will catch or guarantee a safe trip. Heights between published high and low tides on the curve are estimates, and weather can change actual water levels. Fish identification is a suggestion, so check the latest official rules for your exact location and species before keeping a catch. [Read about the location and rules sources](docs/data-sources.md).

Fishdays - NZ is an evolving project. Android preview builds are available from [Releases](https://github.com/csgo-adc/fishing-nz/releases).

Google Play release preparation, signing, privacy/deletion deployment, Data safety guidance and remaining account steps are in [the release checklist](docs/google-play-release.md). Store listing copy and graphics are in [assets/google-play](assets/google-play/listing.md).

The fish and silver-fern app icon uses the black, royal-blue, white and red palette from the supplied New Zealand reference. [Brand artwork and platform exports](assets/branding/README.md).
