# Fishdays - NZ

**Plan your next fishing trip around New Zealand.**

Current project version: **1.0.63 (build code 64)**. The original code is preserved
at Git tag `v1.0.0`. For each future change or commit, run
`python3 tools/version.py bump`, update [the changelog](CHANGELOG.md), and run
`python3 tools/version.py check` before committing. See [the version policy](AGENTS.md).

Fishdays - NZ helps you find a place and time to fish, check nearby tides, and review fishing rules before you head out. The iOS and Android apps bring these tools together for both land and boat fishing.

## Find a fishing window

The first time the app opens it asks two questions: land or boat, and your location or a town. Home then shows **Today** (the day's tide curve with the time now marked, wind and its direction, temperature, the moon and sunset), the **best fishing window** for your plan, found automatically, shortcuts to rules, fish identification, the catch log and trips, and the spots nearest you.

Start with your current location or choose a town. Pick land or boat fishing, how far you want to travel (10–500 km), and when you want to go. You can search today, in three days, this week, this weekend, or choose your own dates within the next 16 days.

The app compares named fishing areas and suggests two-hour windows. Each suggestion shows a short outlook label, straight-line distance, a suggested time, reasons for the suggestion, and any weather or sea-condition warnings.

Suggestions normally fit between **7:00 AM and 9:00 PM**. Choose your own hours, including overnight, or select **Anytime** if you are happy to start early.

**Share a window.** Open a window and tap **Share this window** to send a link to WeChat, WhatsApp, Instagram, Facebook, Messages or anyone else. Friends with the app open it there; everyone else gets a web page that works on phones and computers, shows the window and offers the app. [How sharing works and what to set up](docs/share-fishing-window.md).

## See the tides

The Tide page selects the nearest supported tide station from your location. You can search for another station, move between days, and slide across the curve to inspect the estimated height at a particular time. Published high and low tide times and heights come from [Toitū Te Whenua Land Information New Zealand](https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions) and are shown above local Chart Datum. The station picker includes locations with direct LINZ daily predictions; offset-only locations require a separate calculation. A Moon card shows the day's moon phase, whether tides are spring (bigger range) or neap (smaller range), and the next full and new moon. The **Tides** home-screen widget (Android and iPhone, plus an iPhone lock-screen gauge) draws the tide curve with the highs and lows and a dot at now.

## Explore and prepare

- **Spots:** The Spots tab opens on the map. Switch to **List** for the fishing spots, nearest first when location is on, with a search box, Land/Boat filters, the nearest boat ramp or wharf and a warning near no-fishing areas; tap a spot to see it on the map. Search a place or tap the map, then check upcoming and recent conditions on a full-screen page. Compare daily and hourly weather, wind, offshore waves, tide events with the day's tide curve, rain and daylight. The +/− buttons zoom the map with one hand; nearby pins merge into a numbered marker when zoomed out. **Open in Maps** shows the place as a pin in Google Maps or Apple Maps, where directions are one more tap. From a selected place, **Find a fishing window nearby** searches around it. Expand each item for explanations and shore/boat guidance. History supports 3, 7, 30 or 92 recent days. Existing fishing spots and LINZ map layers remain available. Marine reserves and Hauraki Gulf protection areas are shaded (red: no fishing; amber: Seafloor Protection Areas), and places inside or near one show a warning. In Boat mode, public boat ramps from OpenStreetMap appear on the map, and boat windows list the nearest ramps with a note when low tide falls near the trip. In Land mode, coastal wharves, breakwaters and groynes from OpenStreetMap appear on the map, and land windows list the nearest three within 10 km; a mapped wharf isn't permission to fish there. [How place conditions work](docs/place-conditions.md).
- **Trips:** Keep a shortlist of spots and an active plan; both stay after the app closes. Starting a trip or saving a spot saves its forecast, a year of tides and the area's rules on the phone, so they still show without a signal. The evening before (or 2 hours before) a trip, the app checks the forecast again and notifies you whether it changed. **Tell someone** sends your trip plan and back-by time to a friend, and reminds you to check in.
- **Catch log:** Log each catch with its length, a photo and notes. The app checks the length against the MPI summary for the area, records the tide, wind, waves and moon at the time, and shows patterns once you have a few catches. Export it as CSV.
- **Fishing rules:** Choose the Fisheries New Zealand area that applies to your location (it's suggested from your position). Each species shows an illustration, its minimum size and daily limit as big figures with a size bar, and the area's combined finfish limit; tap a species for larger pictures (juvenile and adult, male and female where they differ) and every rule. **Check my catch** compares a length with the minimum. Species with no limit of their own in an area, such as John dory, mako or octopus, are listed with the combined finfish limit or MPI's "All others (combined)" shellfish limit they count toward. Every area's rules and the pictures are kept on the phone and updated from the Fishdays - NZ service when they change, so the page works on a boat with no signal; the app also carries a copy for a first launch with no signal. Open the [official area page](https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/) for complete limits and local restrictions.
- **Weather:** Open the Weather tab next to Tide for current conditions, an hourly outlook, and up to 16 forecast days with familiar weather icons and plain labels. Choose your current location or a named New Zealand coastal location, save preferred locations, and swipe between them. Forecasts use Open-Meteo; check official marine warnings for the place you plan to fish.
- **Account:** Create an account in the mobile app with an 8-character minimum password entered twice, or use Google or Apple once sign-in is configured. Manage your profile, connect sign-in options, turn on **Sync trips and catch log** to use them on your other devices (photos stay on the phone), and send feedback. The website is for administrators. [Sign-in setup](docs/account-sign-in.md).
- **Fish photo identification:** Sign in, then take or choose a photo to get a suggested species and related rule information. Each account has five identifications per day, shared across devices and reset at midnight New Zealand time. Failed requests do not use the allowance. The app asks before each upload unless you tick “Don’t ask me again” (undo it in Settings → Terms & privacy), and **Report this AI result** flags a wrong or offensive suggestion.

## Good to know

Fishdays - NZ searches a curated set of named areas, so a small search radius may have no results. A named wharf or beach on the map does not guarantee public access or that fishing is allowed. Window outlook labels summarise forecast conditions; they do not predict how many fish you will catch or guarantee a safe trip. Heights between published high and low tides on the curve are estimates, and weather can change actual water levels. Fish identification is a suggestion, so check the latest official rules for your exact location and species before keeping a catch. [Read about the location and rules sources](docs/data-sources.md).

Fishdays - NZ is an evolving project. Android preview builds are available from [Releases](https://github.com/csgo-adc/fishing-nz/releases).

Google Play release preparation, signing, privacy/deletion deployment, Data safety guidance and remaining account steps are in [the release checklist](docs/google-play-release.md). Store listing copy and graphics are in [assets/google-play](assets/google-play/listing.md).

App Store readiness, the steps only the Apple account holder can do, listing copy, App Privacy answers and App Review notes are in [the App Store checklist](docs/app-store-release.md).

The fish and silver-fern app icon uses the black, royal-blue, white and red palette from the supplied New Zealand reference. [Brand artwork and platform exports](assets/branding/README.md).
