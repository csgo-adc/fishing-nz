# Fishdays - NZ — App Store release

Reviewed 9 October 2026 against the iPhone app, the shared API and Apple's current primary-source requirements. Publisher: **Tristan**, an individual developer. Support/privacy email: **tc199558@gmail.com**. Bundle ID: `nz.fishingnz.catchcheck`. The Android equivalent is [the Google Play checklist](google-play-release.md).

## Current status

The iPhone code is ready for review submission, but **nothing has been uploaded and no App Store Connect record exists**: this Mac's Xcode only knows two free *Personal Teams* (`93N35X579F` and `8T7835869S`), which cannot use App Store Connect or TestFlight. Everything under “Only you can do these” is blocked on that.

| Requirement | Status |
| --- | --- |
| Built with Xcode 26+ and an iOS 26+ SDK ([required since 28 April 2026](https://developer.apple.com/news/upcoming-requirements/)) | Done. Xcode 27.0 / iOS 27.0 SDK, deployment target iOS 17. Simulator, unsigned Release device build, 101 fishing-window and 37 place-condition regression checks, 61 API tests and 21 tool tests pass. |
| Account deletion in the app ([5.1.1(v)](https://developer.apple.com/support/offering-account-deletion-in-your-app/)) | Done in 1.0.11: More → Account → Delete account, with a permanent-deletion confirmation. Production deletion with a disposable account has not been exercised. |
| Permission before sending a photo to third-party AI ([5.1.2(i)](https://developer.apple.com/app-store/review/guidelines/)) | Done in 1.0.15: a sheet names Cloudflare and OpenAI before the upload. “Don’t ask me again” is unticked by default, is kept only when the person taps **Agree and upload**, is cleared on sign-out and account deletion, and can be switched back on in More → Settings → Terms & privacy. Android still asks every time. |
| Login services ([4.8](https://developer.apple.com/app-store/review/guidelines/)) | Done by guard: Google sign-in is offered only when Sign in with Apple is also available. Today the live service reports `{"google":true,"apple":false}`, so the iPhone app shows **email and password only** and no “coming soon” text. |
| Privacy manifest and required-reason APIs | Done in 1.0.15: `PrivacyInfo.xcprivacy` declares no tracking, the collected data below, and `UserDefaults` (reason `CA92.1`). It is packaged in the app. |
| Export compliance | Done in 1.0.15: `ITSAppUsesNonExemptEncryption = NO` (the app only uses the operating system's HTTPS and Keychain), so App Store Connect will not ask on every build. |
| Version and build number | Done. `Info.plist` now reads `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION`, which `tools/version.py` keeps in step with `version.properties`. Every upload needs a higher build code. |
| Privacy policy link in the app | Done. Terms & privacy links to `https://fishing.fishnz.space/privacy`, names OpenAI, Open-Meteo, Cloudflare and the publisher contact. |
| Icon | 1024×1024 RGB with no alpha channel. Brand-rights question: see “Open risks”. |
| Permissions | Location (when in use), camera and write-only calendar, each with a purpose string. Gallery uses the system photo picker, so there is no photo-library permission. No tracking, no advertising ID, no background modes. |
| Government-information disclaimer | Present in the app and the listing copy below. |

## Only you can do these

Do them in this order.

1. **Join the paid Apple Developer Program** at <https://developer.apple.com/programs/enroll/> (99 USD per membership year, shown in local currency). Enroll as an *individual* with your legal name: Apple shows that legal name as the seller on the App Store, whereas the Play listing shows “Tristan”. A company name needs an organisation enrolment. Two-factor authentication must be on.
   *If you already pay for a membership under a different Apple Account, sign that account in to Xcode instead (Xcode → Settings → Accounts).*
2. **Sign in to Xcode with that account**, then open the project, select the **CatchCheckNZ** target → Signing & Capabilities → Team, and choose the paid team. This replaces `DEVELOPMENT_TEAM = 93N35X579F` (a free team) in `project.pbxproj`.
3. **Check the bundle ID.** If Xcode says `nz.fishingnz.catchcheck` is “not available”, the free Personal Team probably still holds it (it registers one when an app is run on a real iPhone). Either release it from that team in Certificates, Identifiers & Profiles, or pick a new ID and change `PRODUCT_BUNDLE_IDENTIFIER` (two places in `project.pbxproj`). The URL scheme `nz.fishingnz.catchcheck` and the server's allow-list can stay as they are.
4. **Create the app** in [App Store Connect](https://appstoreconnect.apple.com) → Apps → + → New App: platform iOS, name `Fishdays - NZ`, primary language English (U.K.), the bundle ID above, and a SKU such as `fishdays-nz-ios` (neither can be changed later).
5. **Keep a working reviewer account.** Fish identification needs sign-in, so App Review needs credentials ([2.1(a)](https://developer.apple.com/app-store/review/guidelines/)). Reuse the email-verified Play reviewer account or make another. Do not commit the password. The five-per-day identification limit applies to it, so test with other accounts.
6. **Provide a support URL and a review phone number.** Until a dedicated support page exists, `https://fishing.fishnz.space/privacy` lists the publisher contact. App Review Information also needs a phone number.
7. **Decide the three open questions** in the next section.

## Decisions

- **Sign in with Apple.** Fastest route: do nothing; the iPhone app is exempt from 4.8 while it offers only its own email and password accounts. Accounts created with Google on Android can still sign in on iPhone after using *Forgot password* once to set a password. To offer Google and Apple on iPhone, follow the Apple section of [account-sign-in.md](account-sign-in.md) (Services ID, key, four Worker secrets) **and first** make account deletion revoke the user's Apple tokens ([required by Apple](https://developer.apple.com/support/offering-account-deletion-in-your-app/); the Worker does not store or revoke them yet). Once `/v1/auth/providers` reports `apple: true`, the buttons appear without an app change.
- **iPad.** The project builds for iPhone and iPad. It runs on iPad with the phone layout stretched across the screen: usable, not tailored. Shipping iPad needs at least one **13-inch iPad screenshot** (2064×2752) and a pass through every screen on iPad. Shipping iPhone-only means setting `TARGETED_DEVICE_FAMILY = 1` (two places in `project.pbxproj`), and is the lower-risk first release.
- **Where to sell it.** The Play closed test targets New Zealand only. Choosing only New Zealand in Pricing and Availability keeps the two stores aligned and avoids the EU [trader-status declaration](https://developer.apple.com/news/upcoming-requirements/) that is required for EU storefronts. Price: Free.

## Build, check and upload

Bump the version before editing for each upload (`python3 tools/version.py bump`), then:

```sh
python3 tools/version.py check
xcodebuild -project iosApp/CatchCheckNZ.xcodeproj -scheme CatchCheckNZ -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/ios/FishdaysNZ.xcarchive \
  -allowProvisioningUpdates archive
```

Then open Xcode → Window → Organizer → Archives → select the archive → **Distribute App → App Store Connect → Upload**, and let Xcode manage signing. The build appears in App Store Connect after processing (usually minutes). `build/` is Git-ignored. Verified here: the unsigned Release device build, its `Info.plist` (version, build, encryption flag) and the packaged privacy manifest. **Not verified:** signing, archive validation and upload, because they need the paid team.

Use **TestFlight** first: internal testers (people in your App Store Connect team) can install the build as soon as it is processed with no review, and there is no minimum tester count or waiting period like Play's 12 testers for 14 days.

## What to enter in App Store Connect

**Version page**

| Field | Value |
| --- | --- |
| Name | Fishdays - NZ |
| Subtitle (30) | Tides, weather & fishing rules |
| Promotional text (170) | Plan fishing days around New Zealand: compare two-hour windows, check LINZ tides and weather, and read MPI fishing rule summaries before you head out. |
| Keywords (100) | fishing,tide,tides,weather,angler,marine,forecast,MPI,rules,snapper,boat,LINZ,New Zealand,fish ID |
| Categories | Primary Sports, secondary Weather |
| Support URL | `https://fishing.fishnz.space/privacy` (or a new support page) |
| Privacy policy URL | `https://fishing.fishnz.space/privacy` |
| Copyright | 2026 and your legal name |
| Content rights | Yes: it shows LINZ tide data, Open-Meteo data and MPI rule summaries with attribution and links |
| Age rating | Answer the current questionnaire (updated 31 January 2026) honestly: no objectionable content, no chat, no web browsing, no gambling. This is separate from Play's 18+ audience setting. |
| Release | Manual release after approval for the first version |

**Description** (about 2,150 of 4,000 characters)

> Plan your next fishing day around New Zealand with tides, weather and places to fish.
>
> Fishdays - NZ helps land and boat anglers compare fishing areas and suggested two-hour windows. Choose a town or use your current location, set how far you will travel, and pick your dates and preferred hours. Each suggestion explains the forecast conditions and shows relevant weather and sea-condition warnings.
>
> Explore a map of named fishing spots, check conditions for a place, compare hourly and daily weather, and keep a shortlist while you plan. See official LINZ high and low tide predictions, then slide along the estimated tide curve between those events.
>
> Choose an MPI fishing area and search species for rule summaries, with links to the official information. If you create an account you can also take or select a photo for an AI fish-species suggestion. You choose whether to send each photo to OpenAI through the Fishdays - NZ service, and results include uncertainty guidance.
>
> Location and camera access are optional: you can choose places manually and use the system photo picker. Planning, maps, tides, weather and rules work without an account. Anonymous usage statistics are shared after a one-time notice and can be turned off in Settings. You can delete your account inside the app.
>
> Fishdays - NZ is an independent app and is not affiliated with or endorsed by the New Zealand Government, Fisheries New Zealand, MPI or LINZ.
>
> Official fishing-rule source: https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/
> Official tide-prediction source: https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions
>
> Forecasts use Open-Meteo. A marked fishing spot does not guarantee public access or permission to fish. Suggested windows do not guarantee a safe trip or a catch. Tide heights between published events are estimates, and weather can change actual water levels. AI fish identification and cached rule summaries can be wrong or out of date. Always confirm the species, exact fishing area, current official rules, local restrictions and marine warnings before fishing or keeping a catch.

**Screenshots.** Take them from the iOS app (Apple does not accept Android screenshots). Upload 1–10 at **1320×2868** (iPhone 17 Pro Max simulator; larger sizes scale down; [1206×2622 from an iPhone 17 Pro is also accepted](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)), PNG or JPEG without transparency. Suggested set: plan and fishing windows, map with spots, tide curve, weather, fishing rules area, fish identification. Capture with `xcrun simctl io <device> screenshot file.png`. Use a signed-out simulator so no test account name appears. If you keep iPad, add 2064×2752.

**App Privacy** (Account Holder, Admin or App Manager only; must match `PrivacyInfo.xcprivacy`). Nothing is used for tracking and no data is sold or shared for advertising.

| Data type | Linked to the person | Purposes |
| --- | --- | --- |
| Contact info → Email address | Yes | App functionality |
| Contact info → Name (optional display name) | Yes | App functionality |
| Identifiers → User ID | Yes | App functionality, analytics |
| Identifiers → Device ID (random install ID) | Yes, once signed in | Analytics |
| Usage data → Product interaction | Yes, once signed in | Analytics |
| Diagnostics → Other diagnostic data (device model, iOS and app version, language, time zone; Apple lists no closer category) | Yes, once signed in | Analytics |
| Location → Precise location (coordinates sent to Open-Meteo for forecasts) | No | App functionality |
| User content → Photos or videos (fish photo to OpenAI via Cloudflare) | Yes | App functionality |
| User content → Customer support (feedback) | Yes | App functionality |

Usage statistics are on by default after a one-time notice (1.0.11), which is why Device ID and Product interaction are declared. Re-check these answers whenever providers or analytics behaviour change.

**App Review notes** (put the demo credentials in the Sign-in information fields, not here)

> Fishdays - NZ plans fishing trips in New Zealand: fishing windows, a map of spots, LINZ tide predictions, weather and MPI fishing-rule summaries. Planning, map, tide, weather and rules screens work without signing in or granting any permission.
>
> Signing in is optional and only needed for AI fish identification. Demo account details are in the Sign-in information fields. To test: Home → Choose photo (any fish photo) → pick an MPI rules area → Identify fish → Agree and upload. Each account has 5 identifications per day, reset at midnight New Zealand time. The photo is sent through our Cloudflare service to OpenAI only after the person agrees on that screen.
>
> Account deletion: More → Account → Delete account. The app uses its own email-and-password accounts on iPhone, so Sign in with Apple is not required (guideline 4.8). Privacy policy: https://fishing.fishnz.space/privacy.
>
> The app is independent and is not affiliated with the New Zealand Government, MPI or LINZ.

Keep the API and the demo account working for the whole review ([2.1(a)](https://developer.apple.com/app-store/review/guidelines/)).

## Submit

1. Upload the build and wait for processing; answer nothing for export compliance (the key is set).
2. On the version page choose the build, add the screenshots and text, fill App Review Information, and answer App Privacy and the age rating.
3. **Add for Review → Submit to App Review.** Reviews usually finish within a day or two; the first submission can take longer. Fix and resubmit if there is a rejection, and use a higher build code.
4. Release manually when approved. Do not publish any account, deletion or privacy change to the service that the submitted build does not match.

## Open risks (not code)

- **Icon and name.** [legal-risk-review.md](legal-risk-review.md) flags the silver-fern reference (rights not cleared) and the name (no trademark search). Apple can reject for [intellectual-property concerns (5.2)](https://developer.apple.com/app-store/review/guidelines/); a rights complaint after release is also possible.
- **Open-Meteo.** The free service is for non-commercial use. A free app with no ads is a reasonable fit today; revisit before any payments or advertising.
- **Fish identification is a guide.** Keep the in-app and listing warnings; incorrect species advice about legal limits is the likeliest user-harm complaint.
- **Live checks still pending:** production deletion of a disposable account from the iPhone app, the emailed deletion link, and a TestFlight run on a real iPhone (camera, location permission prompts, Keychain session after relaunch).
