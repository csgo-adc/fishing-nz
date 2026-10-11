# Changelog

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
