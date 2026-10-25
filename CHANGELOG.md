# Changelog

## 1.0.46 — 2026-10-25 (build code 47)

- **Wharves and breakwaters in land mode.** With **Land fishing** chosen, the map shows coastal wharves, breakwaters and
  groynes as brown pins (on Android once zoomed in) and tapping one opens its card ("Breakwater · © OpenStreetMap ·
  access and fishing rules not checked") with conditions, navigation and **Find a fishing window nearby**. A land
  window's details have a **Wharves and breakwaters nearby** card: the three nearest within 10 km with distance,
  direction and type ("780 m SW · wharf") and **Directions**, plus a reminder that ports, ferry wharves and some council
  wharves ban fishing. 516 structures from OpenStreetMap (`tools/build_shore_structures.py`, ODbL): sea coast only,
  without floating pontoons, private or no-fishing structures, short unnamed jetties, marina arms, industrial berths,
  port and marina areas, and no-fishing marine areas. Credited on the map and the card. Android and iPhone.
- The map's spot count no longer runs under the layer buttons on narrow phones; long text wraps instead.

## 1.0.45 — 2026-10-24 (build code 46)

- **First Google Play production release sent for review.** Google granted production access; the production release
  is bundle 45 (1.0.44) for all countries and regions, full rollout, publishing as soon as Google approves it.
  `docs/google-play-release.md` records the bundle checks, release notes, why the Data safety answers already cover
  account sync, and what to do once the app is live. Documentation only.

## 1.0.44 — 2026-10-22 (build code 45)

- **Google Play production access applied for.** `docs/google-play-release.md` records the closed test result (testers
  from friends, fishing clubs and a paid testing service; around 15 using the app almost daily; mostly positive
  feedback), the answers given in the 22 October production access application and what is left after approval: a
  signed bundle with a new version code, updated Data safety answers and a production release. Documentation only.

## 1.0.43 — 2026-10-20 (build code 44)

- **Boat ramps in boat mode.** With **Boat fishing** chosen, the map shows public boat ramps as teal pins (on Android
  once zoomed in) and tapping one opens its card with directions and conditions. A boat window's details have a
  **Boat ramps nearby** card: the three nearest ramps within 25 km with distance, direction ("3.3 km SW"), fee when
  known and **Directions**, plus a note when a low tide falls within an hour of the trip ("Low tide 11:05 AM, during
  your trip. Some ramps can't be used near low tide: check the ramp's limits."). 1,004 ramps from OpenStreetMap
  (`tools/build_boat_ramps.py`, ODbL), credited on the map and the card; access, condition and tide limits aren't
  checked. Android and iPhone.
- Release checklists (`docs/app-store-release.md`, `docs/google-play-release.md`) list what 1.0.34–1.0.43 change in
  the App Privacy and Data safety answers, the new widget extension and the API deploy.

## 1.0.42 — 2026-10-20 (build code 43)

- **iPhone Tides widget checked in the simulator.** It shows live tides (for example "Auckland tides · Next high 2:37 AM
  · 2.6 m · Then low 8:40 AM · 1.1 m"), its Station setting lists every LINZ station, and tapping it opens the Tide tab.
  The placeholder seen while testing 1.0.41 came from unsigned test builds: the system's intents service refuses an
  unsigned widget, so the station setting never arrived. Signed builds (as Xcode and the App Store make) work.
- The widget's second line reads "Then low …" instead of "Then Low …".
- Opening the app refreshes the widget, so it picks up the latest tide table.

## 1.0.41 — 2026-10-20 (build code 42)

- **Tides widget.** Android: a home-screen widget with the next two high or low tides, the moon, spring or neap tides
  and the current wind for the station on the Tide tab (it follows the station you choose or are nearest to). It
  refreshes every 3 hours and when the station changes, using the saved LINZ table offline. iPhone: a WidgetKit
  extension (`TideWidgetExtension`, bundle `nz.fishingnz.catchcheck.TideWidget`) in small, medium and Lock Screen sizes;
  the station is chosen in the widget's own settings (Edit Widget → Station). Tapping either opens the Tide tab.
  **Not yet verified:** on the iOS 26.5 simulator the iPhone widget stays on its placeholder because the system's
  intents service disconnects before passing the widget its settings; check it on a real iPhone.
- iPhone version numbers now live in the project's build settings so the app and its extension always match
  (`tools/version.py` is unchanged). The first archive with the extension needs Xcode to register its App ID
  (automatic signing).
- iPhone privacy manifest: declares file-timestamp access (reason C617.1, used by the offline cache since 1.0.37) in the
  app and the widget, and, for account sync, precise location and other user content as linked to the user.

## 1.0.40 — 2026-10-20 (build code 41)

- **Sync trips and catch log with your account (opt-in).** A new **Sync trips and catch log** switch on the Account
  page keeps saved spots, the active trip (with its back-by time) and the catch log with the account, so they appear on
  your other signed-in Android and iPhone devices. It is off until you turn it on. Catch photos never leave the phone.
  The newest change wins for each catch and for the trips list; deleting a catch removes it everywhere. Syncing happens
  when the app opens, after sign-in and a few seconds after a change; **Sync now** and the last sync time are on the
  same card. Turning it off asks whether to keep or delete the copy kept with the account. Signing out turns sync off,
  so another account on the same phone never receives your log. Deleting the account deletes the synced copy.
- **API:** new `/v1/me/sync` (GET, POST to merge, DELETE) and migration `0013_user_sync.sql` (`user_sync_items`).
  Merges run as one SQL statement, so a 600-catch log fits D1's per-request limits. Up to 2,000 catches; deleted
  entries are kept 180 days for other devices, then removed by the daily cleanup.
- **Privacy policy** (updated 20 October 2026) describes the trips and catch log data, sync, Tell someone and trip
  reminders; account deletion text in both apps and on the web now lists synced data.
- **Before release:** deploy the API (Actions → Deploy) to apply the migration; until then the apps say sync isn't
  available yet. Review App Store App Privacy and Google Play Data safety: with sync on, the account stores precise
  location (catch positions) and user content (catch log notes) linked to the user.

## 1.0.39 — 2026-10-20 (build code 40)

- **Tell someone.** The active trip has a **Tell someone** button that sends your trip plan to a friend or family
  member who isn't going, as AdventureSmart and Coastguard advise: where (with a map link), when, the time you'll be
  back (an hour after the window by default), who's going, details such as the car or boat, a link to the window's
  forecast, and what to do if they haven't heard from you: try calling, then call 111 and ask for the Police. At the
  back-by time a notification asks whether you're back and reminds you to tell them. Ending the trip cancels it. The
  forecast link is the same short share link as Share this window. Android and iPhone.
- iPhone: removed an unused setting that 1.0.38 added to the map screen by mistake.

## 1.0.38 — 2026-10-19 (build code 39)

- **Pre-trip forecast check.** Starting a trip books a check at 7 PM the evening before (or 2 hours before a trip later
  the same day). The app evaluates the trip's exact hours again with the latest forecast and your current plan
  options, and a notification says whether the outlook changed: "Now 😕 Demanding, was 😌 Comfortable. Gusts to
  45 km/h." Tapping it opens Trips. The active trip card shows when the check happens. Ending the trip cancels it.
  - Android: a WorkManager job runs the check once there is a connection; Android 13 and later ask for notification
    permission when you start a trip (the trip starts either way).
  - iPhone: the notification is booked with iOS and its text is rewritten with the re-checked forecast whenever the
    app opens or iOS gives it background time (Background App Refresh) before then; otherwise it reminds you to check.
- The window details say that starting a trip saves its forecast offline and checks it again before you go.

## 1.0.37 — 2026-10-19 (build code 38)

- **Saved spots and the active trip are kept.** Before, both were lost when the app closed. They are now stored on the
  phone as the same small window snapshot a share link carries, and come back marked "Saved · forecast may have
  changed". Deleting the account clears them.
- **Offline at the water.** Starting a trip or saving a spot fetches, while there is a signal, that place's forecast,
  a full year of LINZ tides for its station and the MPI rules for its area. Without a connection the conditions page
  shows the saved forecast ("No connection · showing the forecast saved Sun 18 Oct, 12:00 PM"), tide pages use the
  saved LINZ table (also reused for a month instead of downloading again) and the rules page shows the saved copy
  with when it was saved. The active trip has a **Check conditions** button, so the forecast opens without the map.
  Android and iPhone.

## 1.0.36 — 2026-10-19 (build code 37)

- **Catch log.** More → **Catch log** keeps your catches on this phone: species, length, kept or released, how
  many, place, time, notes and an optional photo. Saving a catch records the conditions at that time and place: the
  part of the tide from the nearest LINZ station (for example "Incoming · 2 h after low"), wind, offshore waves and
  water temperature from Open-Meteo, and the moon and spring/neap tides. With three or more catches, **Your patterns**
  sums up your own log (most-caught species, tide, time of day), without predicting anything. **Export as CSV** shares
  the log; **Delete all** clears it.
- **Size check.** As you log a fish, the app looks up the species in the saved MPI summary for the chosen area and
  compares the length with each matching minimum size (for example "28 cm is under the 30 cm minimum for Snapper
  (Auckland East - SNA1). Release it." and "meets the 27 cm minimum" for Auckland West), converts millimetre rules
  and sends tail-width rules to MPI. It shows the daily limit and how many of that species you have kept that day.
- **Log this catch** after identifying a fish fills in the species, photo and MPI area. The active trip has a
  **Log a catch** button that uses the trip's spot.
- Terms & privacy say the log stays on the phone and that saving a catch sends its place and time to Open-Meteo.
  Android and iPhone; both read the same JSON format (shared test fixture), ready for account sync.

## 1.0.35 — 2026-10-19 (build code 36)

- **Marine protected areas on the map.** DOC marine reserves and the Hauraki Gulf protection areas are drawn on the
  map: red where fishing is banned (marine reserves and High Protection Areas) and amber for Seafloor Protection Areas,
  where line fishing is allowed but trawling and dredging are not. **Map layers → Marine protected areas** turns them
  off. A dropped pin, a fishing area, its conditions page and a fishing window's details warn when the place is inside
  one, within 2 km of a no-fishing area or within 500 m of a Seafloor Protection Area (for example St Clair Beach is
  200 m from Ōrau Marine Reserve), with a link to DOC. The 69 boundaries are bundled in the apps, simplified to about
  30 m; `tools/build_marine_protection.py` refreshes them. Temporary closures and rāhui are not included. Android and
  iPhone.

## 1.0.34 — 2026-10-19 (build code 35)

- **Moon phase and spring/neap tides.** The Tide tab has a Moon card for the chosen day: the phase as seen from
  New Zealand (lit on the left while it waxes) and how much is lit, whether it is a spring tide (bigger range), a neap
  tide (smaller range) or between, and the dates of the next full and new moon. A place's conditions page on the map
  shows the same in one line under the day's tides. Worked out on the phone (Meeus's lunar formulas, within about half
  an hour of published phase times), so it needs no new data source. It describes the tide range, not catches.
  Android and iPhone.

## 1.0.33 — 2026-10-16 (build code 34)

Better fishing-window rules, on Android and iPhone.

- **Rocks and beaches now count the waves.** On rocks, offshore waves up to 1 m are Comfortable,
  1.5 m Okay, 2 m Demanding and more Uncomfortable (beaches up to 2.5 m before Uncomfortable). A
  long-period swell of 1 m or more counts one step worse because it surges. Before, a 2.5 m swell
  at rocks could still say "Comfortable". Wharves, harbours and "Not sure" are unchanged.
- **Tide choices.** "What matters most?" offers **Tide** with Incoming, Around high (2 hours before
  to 1 hour after high; replaces "late incoming"), Outgoing or Around low, all timed from the
  official LINZ highs and lows. Saved "late incoming" plans become Around high.
- **Sunrise or sunset.** A new choice that looks for the hour either side of sunrise or sunset.
  These windows are partly dark by design, so they are not ranked down for it, and the daylight
  row says "Around sunrise" or "Around sunset".
- **How long will you fish?** 1, 2, 3 or 4 hours (was always 2). Every hour of the session counts.
- **Nearer first.** When two spots are equally good, the nearer one is listed first, before an
  earlier start further away.
- **Cool mornings.** Feels like 6–12°C is now Okay and 2–6°C Demanding (before: 8 and 5°C), since
  warm clothes cover them; most winter mornings were falling outside the default comfort limit.
- **Boats get "How rough is OK?"** (Calm only, A little chop, I don't mind rough), the same as land.
- **Results by day.** A plan over several days shows each day under its own heading, in date
  order, and marks the **Best pick** overall.

## 1.0.32 — 2026-10-16 (build code 33)

- **A simpler fishing plan page.** It now asks four things: **Where?** (a place and a distance),
  **Fishing from** (land or boat), **When?** and **What time?**. Everything else moves under
  **More options**, already set to recommended values, with **Reset to recommended**. Android and
  iPhone; iPhone also gets a **Find fishing windows** button on the plan page.
- **Where?** is one place picker for your location, a town, a fishing spot or a tide station.
  Picking a spot or station searches **just this spot**; a distance searches around it instead.
  Distances are 10, 25, 50 (new default), 100 and 200 km.
- **When?** offers Today, Tomorrow, This weekend, Next 3 days (new default) and Next 7 days, plus
  Pick dates, and shows the dates (for example "Fri 16 – Sun 18 Oct"). "In 3 days", which meant
  only the single day three days away, is gone; saved plans move to Next 3 days.
- **What time?** offers **Daytime** (the new default: each day's real sunrise to sunset, including
  your walk), Morning (5 AM–12 PM), Evening (3 PM–10 PM), Any time and Pick hours. It replaces the
  fixed 7 AM–9 PM and the separate "Whole visit in daylight" switch.
- **More options:** **What matters most?** (Comfort, Less wind, Less rain or Late incoming tide;
  boats: Calm water or Late incoming tide), **How rough is OK?** (Calm only, A bit of wind or rain,
  I don't mind rough), **Where do you fish?** (Not sure, Wharf or harbour, Beach, Rocks) and **Walk to
  your spot**, one time for each way that defaults to a few minutes. Harbour bank merged into Wharf
  or harbour; separate walk-in and return times became one. Saved choices carry over.
- Window conditions say "short walk" or "30 min walk each way" for the walk.

## 1.0.31 — 2026-10-16 (build code 32)

- **Show on map.** Every fishing window card has a "Show on map" link next to "All conditions",
  and a window's details page has a "Show on map" button. It opens the Map tab on that fishing
  area with its card open, so Check conditions and directions are one tap away. Windows without a
  position (some older shared links) don't show it. Android and iPhone.
- **Share the conditions for a place.** The map's Check conditions page has a share button next to
  Refresh. It shares the day you're looking at in plain words: waves, wind, rain, feels like,
  daylight and the day's tides, with a Google Maps link to the spot. Android and iPhone.
- **Conditions in plain words.** "22 km/h · gust 28 · S" now reads "Breezy: 22 km/h, gusts to
  28 km/h, from the south" (the S was the wind direction, not seconds). Waves read "Up to 1.2 m,
  6–7 seconds apart", rain "0.4 mm in total, up to 40% chance in any hour", tides "Next high tide
  7:12 PM, 2.4 m" with "Tide station: …", daylight "All in daylight", and the shore plan "20 min
  walk in · 15 min back". The key numbers explain themselves too (wind "Breezy · gusts 28 · from S",
  waves "6–7 s apart", rain "chance · 0.4 mm total"). Same wording on the map's conditions page and
  the shared-window web page. Shared links keep their format, so old links and older app versions
  still work. Deploy the API for the web page wording.

## 1.0.30 — 2026-10-11 (build code 31)

- **Sign in with Apple on iPhone uses Apple's own sheet** (Face ID or passcode, no web page) and
  Apple's standard button, shown above Google. New API endpoints `POST /v1/auth/apple/start` and
  `/v1/auth/apple/native` check the signed Apple token against a single-use nonce and the app's
  bundle ID. The button appears once the API has the Apple key; setup is in
  `docs/account-sign-in.md`. Android keeps Apple's browser sign-in, which also needs a Services ID.
- **Deleting an account revokes its Apple sign-in** (App Store guideline 5.1.1(v)). The API keeps
  Apple's refresh token for this (migration `0012_apple_token_revocation.sql`) and revokes it on
  deletion, or straight away when a sign-in ends without an account. The privacy policy says so.
- Deploy the API to apply the migration and publish the policy change.

## 1.0.29 — 2026-10-11 (build code 30)

- **A multi-day plan now lists a window for each day.** Before, a search kept only one window per
  spot for the whole date range, so "Next 7 days" near Hamilton showed just two cards (Manu Bay
  Coast and Raglan, both Monday 10 AM). Each spot now offers its best window on every selected
  day, and the list keeps the best three spots on each day, best first. The same search now shows
  ten windows from Monday to Saturday. Android and iPhone.
- When two windows are equally good, the nearer spot is listed first on iPhone too, as on Android.

## 1.0.28 — 2026-10-11 (build code 29)

Fixes found while recording Apple's physical-iPhone review video of 1.0.27.

- **iPhone Weather no longer gets stuck on "Finding a fresh location…".** A real iPhone often
  answers a location request with a recent cached fix whose timestamp is older than the request,
  and the Weather tab ignored it. The app now counts a fix from when it arrives, and Weather uses
  a fix from the last minute straight away, as Android does. The simulator always gives fresh
  timestamps, so earlier testing missed this. A place's page now shows "Loading your forecast…"
  instead of a blank moment before its forecast appears.
- **Fishing rules no longer show "Saved rules are unavailable" above rules that loaded.** When a
  rules download was interrupted and restarted, the interrupted one reported an error after the
  new one had succeeded. Only the newest load can now update the screen. Android and iPhone.
- 1.0.27 (28) was uploaded to App Store Connect and replaced 1.0.20 (21) on the version under
  review. 1.0.28 (29) replaces it before the review reply is sent.

## 1.0.27 — 2026-10-11 (build code 28)

- **The shared-window web page uses the app's layout.**
  - The date and time are blue with a calendar icon, in the app's short form ("Wed 14 Oct",
    "10:00 AM–12:00 PM"), next to a coloured outlook badge.
  - Three key-number tiles follow (wind, tide and rain on land; waves, wind and tide on a boat). A
    tile turns amber when that condition is the problem.
  - An amber box lists what needs attention, followed by the forecast note.
  - The full conditions, map links and notes stay below.
  - On phones the badge moves under the time, so the page never scrolls sideways.
  - The rules match the apps (`window-highlights.ts`). Deploy the API to publish it.

## 1.0.26 — 2026-10-11 (build code 27)

- **Date and time stand out** on fishing window cards and window pages: they use the accent blue
  with a calendar icon. Android and iPhone.
- **No first-launch analytics pop-up.** Anonymous usage statistics are on by default and can be
  turned off in More → Settings → Terms & privacy. An earlier "Turn off" choice is kept. The privacy
  policy now says this (deploy the API to publish it).
- **Feedback page redesigned** on Android and iPhone:
  - The topic is three full-width choices: report a problem, suggest an idea, or something else.
  - The message box is larger, with a hint for the chosen topic and a character count.
  - The rating is now optional stars (tap a star again to clear it); it was required and defaulted
    to 5.
  - A full-width send button, with a clear "Thanks, your feedback was sent" note afterwards.

## 1.0.25 — 2026-10-11 (build code 26)

- **Fishing window cards highlight what matters** on Android and iPhone:
  - The day and two-hour window come first, with a coloured outlook badge: green for Comfortable or
    Okay, amber for Demanding, red for Uncomfortable, grey when data is missing.
  - Three key numbers sit in tiles: wind, tide and rain for land fishing, or waves, wind and tide for
    boat fishing. A tile turns amber when that condition is the problem.
  - An amber box lists only what needs attention, such as gusts, choppy or large waves, rain, cold or
    heat, part of the session after dark, or a window outside your comfort setting.
  - In the results list the full conditions fold away under **All conditions**. A window's own page
    shows the same highlights at the top, with the full conditions below.
  - Shared windows use the same layout, and windows without condition rows keep the previous one.
- New Android unit tests and iPhone checks cover the tiles, warnings, colours and times.

## 1.0.24 — 2026-10-11 (build code 25)

- The account screen no longer says "Account" twice. Signed in, its header shows your name (or your
  email if you have no name); signed out, it shows only the sign-in line. Android and iPhone.

## 1.0.23 — 2026-10-11 (build code 24)

Plainer wording on Android, iPhone and the shared-window web page. Required notices (privacy,
photo consent, analytics, MPI/LINZ independence and attributions) are unchanged.

- **Account and More:** "Welcome aboard" is now "Account". Removed the "Good to see you" heading,
  "Signed in securely", "Keep your account safe", the plain-text password line and the filler
  subtitles. "Choose how you get back on board" is now "Google or Apple".
- **Planning:** shorter help for the late incoming tide, boat and land preferences and custom hours.
  Removed the iPhone "Tap to change your options" line and its repeated footnote on Home.
- **Window cards:** "Outside your comfort preference" appears once. Reasons read "Most comfortable
  option", "Matches the late incoming tide" and "Not enough data to rate comfort". "Details &
  sources" now uses plain sentences ("Some wave data is missing", "Check footing, surge and a way off
  the rocks") and a short source line ("Weather and waves: Open-Meteo, updated 8:03 AM. Tide: LINZ …")
  without grid coordinates.
- **Forecast label:** the next two days are now "⏱️ Short-range forecast" instead of
  "🤔 Limited confidence".
- **Conditions, tide and rules:** removed the fetch-time and grid lines and the duplicate swipe
  hints, shortened the tide source note, and replaced the long boundary notes with "Near an area
  boundary? Check the MPI map."
- **Shared-window page:** shorter area-marker and "Check before you go" notes. Deploy the API to
  publish it.

## 1.0.22 — 2026-10-11 (build code 23)

Android and iPhone now offer the same features.

- **iPhone gains:**
  - **Search from a town.** The search location list has a Towns section with the same 26 towns as
    Android, searched within your radius. It is remembered across launches.
  - **Report this AI result** after a fish identification. It is sent as feedback with the suggested
    species and your message, never the photo.
  - **Map:** a **List** of every fishing area, nearby pins merged into a numbered marker, a spot
    count, and **Find a fishing window nearby** on a selected place (replacing "Spot details").
  - Results say when forecasts failed for nearby spots, and the camera shows Android's fish outline
    with "Hold the fish vertically — head up".
  - Show/hide buttons on password fields, plus screen-view analytics for Trips, Rules, Account,
    Settings, Feedback, Terms & privacy and Appearance, and a `spot_opened` event.
  - Signing out of all devices also restores the photo-upload prompt.
- **Android gains:**
  - **Don't ask me again** on the photo-upload consent, with **Ask me before sending photos again**
    in Terms & privacy. Signing out or deleting the account restores the prompt.
  - **Try again** when tide predictions fail to load, with a plain message for connection failures.
  - Results name the nearby areas that had no window that fits.
- The privacy policy covers the photo-consent option in both apps and AI-result reports. Deploy the
  API (Actions → Deploy) to publish it.

## 1.0.21 — 2026-10-10 (build code 22)

- Correct the public privacy policy to cover iPhone maps, photo consent and Keychain storage.
- Record the Guideline 2.1 information request and prepare a physical-iPhone QA and recording checklist.

## 1.0.20 — 2026-10-09 (build code 21)

- Configure iOS signing for paid team `8T7835869S`, target iPhone for the first release, and add the assigned App Store link to app sharing and the API download page.
- Upload version 1.0.20 (build 21) to App Store Connect with Associated Domains and Keychain entitlements preserved. Add three iPhone screenshots and record the remaining review-submission steps.

## 1.0.19 — 2026-10-09 (build code 20)

- **Short share links.** Sharing a fishing window now sends a link like
  `https://fishing.fishnz.space/w/k3F9xQ2m` instead of one about 800 characters long.
  - Tapping **Share this window** asks the service for a short link ("Creating link…" for a moment).
    If the service can't be reached, the app shares the long link as before, so sharing still works
    offline. Long links made in 1.0.18 keep working.
  - Opening a short link in the app fetches the window; if it has expired or the phone is offline, a
    short message says so. The web page shows "no longer available" for an unknown or expired link.
  - The service keeps a shared window for **60 days**, with no account, device or address. New
    endpoints are `POST /v1/share` (30 a hour per network address) and `GET /v1/share/<id>`, and a
    daily cleanup deletes expired rows. The privacy policy has a new "Sharing a fishing window"
    section. New database migration `0011_shared_windows.sql`.
- New checks: 10 more Worker tests (a real local D1), 7 more Android unit tests and 30 more iPhone
  checks.
- **Deploy the API (Actions → Deploy) before the apps depend on this**: short links need the new
  endpoints and migration. Until then the apps fall back to long links.

## 1.0.18 — 2026-10-09 (build code 19)

- **Share a fishing window** on Android and iPhone. A new **Share this window** button on a window's
  detail screen opens the share sheet with the place, time and outlook and a link, so it can go to
  WeChat, WhatsApp, Instagram, Facebook, Messages and so on.
  - The link is `https://fishing.fishnz.space/w/<token>`. The token is the window itself (place, time,
    outlook and conditions), so nothing is stored on the server and no account is needed.
  - Friends with the app open the window inside it, with a "Shared with you · forecast may have
    changed" note. The link is an App Link on Android and a Universal Link on iPhone (both need the
    setup below to open the app directly), with the app's own `nz.fishingnz.catchcheck://w/…` address
    as an iPhone fallback. A damaged link is ignored.
  - Everyone else gets a web page for phone and desktop (light and dark): the window, conditions,
    map links, an "Open in app" button, **Copy link**, and download buttons. Opened inside WeChat,
    Instagram or Facebook's browser it explains how to open the real browser. The page is served by
    the API Worker, allows only its own script, and is marked `noindex`.
  - The **Download for iPhone / Android** buttons are `#` for now ("link coming soon"). Set
    `IOS_DOWNLOAD_URL` and `ANDROID_DOWNLOAD_URL` in `server/fishial-proxy/wrangler.toml` once the apps
    are published.
  - To make links open the app directly, set `APPLE_TEAM_ID` (paid Apple team) and add Play's app
    signing certificate to `ANDROID_CERT_SHA256`; until then links open the web page. Steps in
    [docs/share-fishing-window.md](docs/share-fishing-window.md).
  - iOS: the Release configuration gets `CatchCheckNZRelease.entitlements` (Associated Domains). Debug is
    unchanged, because a free Personal Team cannot sign that capability.
- Each fishing window now carries its area's map position (a new coordinate on the window model in
  both apps), so a shared window can link to a map.
- New checks: 12 Worker tests, 8 Android unit tests and 45 iPhone regression checks
  (`tools/test_shared_window_swift.sh`). A fixed sample link is read by all three, so the format
  cannot drift between them.
- The API Worker needs a deploy for the page and the `.well-known` files. No database change.

## 1.0.17 — 2026-10-09 (build code 18)

- Android: bring the three iPhone features from 1.0.16 to Android.
  - The tide card on a place's conditions page now draws the day's **tide curve** with a time and
    height readout and slider, the same chart as the Tide tab (they share one composable), above the
    day's published highs and lows. It replaces the plain list of events.
  - The map has **+ and − zoom buttons** above the location button, so one thumb can zoom without
    pinching. Quick presses add up, and a button dims at the minimum or maximum zoom. The buttons
    move up with the location button when a place card is open.
  - The More page has **Share Fishdays - NZ**. It opens the Android share sheet with a short
    invitation and the app's Google Play link, which opens for the public once the app is published to
    production.
- iOS: keep the "Heights above Chart Datum" label beside the published highs and lows on the place
  page's tide card. 1.0.16 dropped it, but `docs/place-conditions.md` promises it stays visible.
- No change to the service.

## 1.0.16 — 2026-10-09 (build code 17)

- iOS: the tide card on a place's conditions page now draws the day's **tide curve** with a time
  readout and slider, the same chart as the Tide tab (the two share one view), above the day's
  published high and low tides. It replaces the plain list of events.
- iOS: the map has **+ and − zoom buttons** above the location button, so one thumb can zoom
  without pinching. Quick presses add up. The map buttons now follow light and dark appearance.
- iOS: add **Share Fishdays - NZ** to the More page. It opens the system share sheet. Until the
  app has an App Store page it sends a short text that says to search for the app. Set
  `AppShare.appStoreURL` to the app's App Store link once the app record exists
  (see `docs/app-store-release.md`).
- No change to the Android app or the service.

## 1.0.15 — 2026-10-09 (build code 16)

- iOS: ask before a fish photo is sent to OpenAI. A sheet names Cloudflare and OpenAI first.
  It has a **Don't ask me again** box that is off by default, is kept only if the person taps
  **Agree and upload**, is cleared on sign-out and account deletion, and can be switched
  back on in More → Settings → Terms & privacy. Apple requires permission before personal
  data goes to third-party AI (guideline 5.1.2(i)). Terms & privacy now names OpenAI, Cloudflare and
  Open-Meteo and shows the publisher's contact.
- iOS: add the Apple privacy manifest (`PrivacyInfo.xcprivacy`: no tracking, the data the app
  collects, and the `UserDefaults` reason) and declare that the app uses only exempt encryption.
- iOS: offer Google sign-in only together with Sign in with Apple (guideline 4.8). Until Apple
  sign-in is configured on the service, the iPhone app shows email and password only, with no
  disabled buttons or "available soon" text.
- Add `docs/app-store-release.md`: App Store readiness, the steps only the account holder can
  do, listing copy, App Privacy answers and App Review notes.
- No change to the Android app or the service.

## 1.0.14 — 2026-10-08 (build code 15)

- Tidy up the repository history. The Android APK workflow now keeps only the newest build in
  Releases (older `android-build-*` releases and tags are removed) and keeps the latest 5 runs of
  each workflow, deleting older finished runs. A cleanup problem never fails the build. The
  `v1.0.0` tag is never touched.
- Remove the one-off **Delete merged branch** workflow; the stale branch is gone.

## 1.0.13 — 2026-10-08 (build code 14)

- Add a manual **Delete merged branch** workflow (Actions → Delete merged branch). It deletes
  a branch only when every commit on it is already in `main`, and refuses `main` and the default
  branch. It exists because the deleting of branches is blocked from the coding sessions that
  work on this repo; it runs on GitHub instead.

## 1.0.12 — 2026-10-08 (build code 13)

- Fix a flaky API test: the sign-in timing check compared median times, which a busy CI
  runner could skew. It now compares the fastest runs, and was checked to still fail when
  unknown emails skip the password hash. No change to the app or the service.

## 1.0.11 — 2026-10-08 (build code 12)

- iOS: add **Delete account** to the Account screen, with a confirmation that matches
  Android. It removes the account and its analytics from the service, clears the phone's
  saved session, photo and trip shortlist, and turns usage statistics off. Apple requires
  this for apps that create accounts.
- iOS: our own anonymous usage statistics are now **on by default after a one-time notice**
  ("Keep sharing" / "Turn off"); nothing is collected before it is answered. The Terms &
  privacy switch is renamed and the screen now links to the full privacy policy.
- iOS: add **Change password** (or **Add a password** for Google/Apple accounts) and
  **Sign out of all devices** to the Account screen.
- **Review the Apple privacy labels in App Store Connect before releasing this build**
  (Identifiers: device ID, for analytics).

## 1.0.10 — 2026-10-08 (build code 11)

- Android: our own anonymous usage statistics are now **on by default after a one-time notice**
  ("Keep sharing" / "Turn off"). Nothing is collected before the notice is answered, and a
  person who had turned analytics off stays off. Google Analytics (Firebase) is now a
  separate switch that stays off unless turned on. Settings → Terms & privacy has both switches.
- Android: add **Change password** (or **Add a password** for Google/Apple accounts) and
  **Sign out of all devices** to the Account screen.
- Update the Play Data safety notes: usage statistics and the install id are collected by
  default. **Re-answer the Data safety form in Play Console before releasing this build.**

## 1.0.9 — 2026-10-08 (build code 10)

- API: account objects (`/v1/me`, sign-in, social sign-in and the admin user list) now include
  `has_password`, so the apps can offer "Change password" or "Add a password".
- API: accept `password_changed`, `signed_out_everywhere` and `account_deleted` analytics events.
- Privacy policy: anonymous usage statistics are on by default after a one-time notice and
  can be turned off in Settings; Google Analytics stays opt-in on Android; account deletion
  is available in the Android and iPhone apps. **Deploy this before the app updates that
  introduce the notice.**

## 1.0.8 — 2026-10-08 (build code 9)

- Add a manual **Deploy** workflow (Actions → Deploy). It checks the Cloudflare secrets, runs
  the API tests, applies pending D1 migrations, deploys the Worker, confirms the live
  version, and can deploy the admin CMS. It needs the repository secrets
  `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` and never runs on its own.
- Document the GitHub and local deploy routes in the API README.

## 1.0.7 — 2026-10-08 (build code 8)

- iOS: add the same optional behaviour tracking as Android, behind a new **Optional usage
  analytics** switch in More → Settings → Terms & privacy (off by default), with the same
  allowlisted events, random install id, device details and API-usage header. Turning it
  off discards queued events and asks the server to erase the device's data.
- iOS: stop sending the phone's coordinates with fish photos. The service never used
  them, and the privacy policy says they are not sent.
- iOS: the Forgot password button records an analytics event; the app version and build
  number now come from the project settings that `tools/version.py` updates
  (`Info.plist` previously fixed them at 0.1 and 1).

## 1.0.6 — 2026-10-08 (build code 7)

- Android: add behaviour tracking behind the existing optional analytics switch, which
  stays off by default. When on, the app makes a random install id and sends allowlisted
  events (app open, screen views, searches, spots opened, fish identification
  started/succeeded/failed, rules area and tide station choices, sign-in steps,
  feedback) with device model, Android version, app version, language and time
  zone, whether or not the person is signed in. API requests carry the id so usage can
  be counted. No location, photos, email or typed text is sent.
- Android: turning analytics off, or deleting the account, discards queued events and asks
  the server to erase the device's data.
- Update the in-app analytics explanation and the Play Data safety notes.

## 1.0.5 — 2026-10-08 (build code 6)

- Admin CMS: add a **Usage analysis** page (`/admin/analytics`) with an overview
  (active and anonymous devices, accounts, events, API calls), ranked accounts and
  devices with search, sort and paging, and a detail view for any account or device
  showing its API use by route and day, what it did, and a timeline that includes
  activity from before the account signed in.
- Forward the read-only analysis routes through the CMS proxy.

## 1.0.4 — 2026-10-08 (build code 5)

- Worker: add behaviour analytics that work without an account. Apps register a
  random device id with device details and send allowlisted events through
  `POST /v1/analytics/batch`; `DELETE /v1/analytics/device` erases a device.
- Worker: count API calls per day by route for each signed-in account and each
  device that sends `x-device-id`.
- Worker: add admin analysis endpoints under `/v1/admin/analytics/` (overview, ranked
  accounts and devices, and per-account and per-device detail with timelines).
- Add D1 migration `0010_device_analytics_and_api_usage.sql`. **Apply it with
  `wrangler d1 migrations apply RULES_DB --remote` before deploying the Worker.**
- Delete analytics after 90 days, and when an account is deleted. Update the
  privacy policy. Move shared HTTP helpers into `http.ts`.
- The apps do not send these events yet; Android and iOS instrumentation follow.

## 1.0.3 — 2026-10-08 (build code 4)

- Record the Git workflow in `AGENTS.md`: commit directly to `main` and leave
  Claude out of commit messages. No application changes.

## 1.0.2 — 2026-10-08 (build code 3)

- Fix the new Checks workflow: install `tools/requirements.txt` before running the
  Python tool tests, which need BeautifulSoup.

## 1.0.1 — 2026-10-08 (build code 2)

- Add password reset: **Forgot password?** on both sign-in screens opens a web
  flow with a 1-hour, single-use emailed link that sets a new password, ends all
  sessions and confirms the email. Add signed-in `POST /v1/me/password` and
  `POST /v1/auth/logout-all` (API only; no in-app screen yet).
- Throttle failed sign-ins, sign-ups, verification resends, reset requests and
  feedback. Unknown emails now take as long as wrong passwords at sign-in, and the
  rules-ingest token is compared in constant time.
- Add D1 migration `0009_password_reset_and_rate_limits.sql`. **Apply it with
  `wrangler d1 migrations apply RULES_DB --remote` before deploying the Worker.**
- Show the API version at `/__health`; keep the Worker `package.json` in step with
  `version.properties`; add `server/fishial-proxy/README.md`.
- Remove the unused FastAPI scaffold in `backend/`; the Cloudflare Worker is the
  backend. Update the privacy policy for reset emails and hashed abuse counters.

## 1.0.0 — 2026-10-07 (build code 1)

- Preserve the existing application code at Git tag `v1.0.0`.
- Establish shared version tracking and require a version bump for future
  changes and commits.
- The previously submitted Google Play bundle remains 0.1 (version code 1).
  Future uploads must use a higher build code.
