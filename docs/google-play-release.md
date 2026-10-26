# Fishdays - NZ — Google Play release

Reviewed 6 October 2026 against the Android app, shared API, deployed provider settings and Google's current primary-source requirements. Publisher: **Tristan**, an individual developer. Public support/privacy email: **tc199558@gmail.com**. Package: `nz.fishingnz.app`.

## Current status

**Live on Google Play since 27 October 2026; update 58 (1.0.57) and a new store listing sent for review the same day.**
Google approved the first production release, **45 (1.0.44)**, which became available in all 178 countries and regions
at 5:18 AM on 27 October.

The update is version **58 (1.0.57)**, built locally from commit `37cc8e2` with the upload key: 185 unit tests, release
lint, the 64-bit/16 KB check and `jarsigner` passed. Play accepted it (API 26+, target SDK 37, 20.2 MB for new
installs, 4.97 MB as an update, no devices dropped) and listed only the usual optional missing deobfuscation-file and
native debug symbol warnings. It replaces 45 as a **full rollout** to all targeted countries, release name
`58 (1.0.57)`, managed publishing **off**, so it goes live as soon as Google approves it. en-AU release notes:

> What's new:
> • New Home: today's tide curve, wind, moon and sunset, and the best fishing window found for you
> • Spots tab with a map and a nearest-first list, boat ramps, wharves and breakwaters
> • Fishing rules redesigned with species pictures, size bars and Check my catch, saved for use with no signal
> • Tides widget now draws the tide curve
> • Simpler account page and first-launch setup

The same submission changes the default (en-AU) store listing: the short and full descriptions in
[`assets/google-play/listing.md`](../assets/google-play/listing.md) (the full description now covers the features
added since October and says usage statistics are on after a notice, instead of "off by default"), and six new phone
screenshots replacing the two from version 0.1. Four screenshots of at least 1080 px are Google's minimum for
promotion. The AI asset declaration labels the icon and the Fishing rules screenshot (see the listing file). The app
name, icon, feature graphic, category, countries and Data safety answers are unchanged.

The 34 MB bundle is larger than the Chrome extension's 10 MB upload limit. This time it was split into four pieces
under 10 MB, passed into the release page and joined there; the joined file's SHA-256 matched the built bundle before it
went to Play's own upload field. Dragging the file from Finder into the release form also works.

With the app live, `ANDROID_DOWNLOAD_URL` now points at the Play page and Google's app signing key is in
`ANDROID_CERT_SHA256` (`server/fishial-proxy/wrangler.toml`, see [share-fishing-window.md](share-fishing-window.md)).
**Deploy the API** for shared-window pages to show the Play button and for Play-installed copies to open shared links.
The next Play upload needs a version code above 58.

### First production release (24 October 2026)

Production access was granted (applied for on 22 October, see [Production access application](#production-access-application-on-22-october-2026)). The production release is version **45 (1.0.44)**, built locally from commit `847615d` with the upload key: unit tests, release lint, the 64-bit/16 KB check and `jarsigner` passed, and Play accepted it (API 26+, target SDK 37, 19.6 MB for new installs). It targets **all 178 countries and regions plus rest of world**, as a **full rollout**, with managed publishing **off**, so it goes live as soon as Google approves it. Play listed only the optional missing deobfuscation-file and native debug symbol warnings. Release name `45 (1.0.44)`; en-AU release notes:

> First public release of Fishdays - NZ.
>
> Plan fishing days around New Zealand with suggested fishing windows for beaches, rocks, wharves and boats, official LINZ tides, weather and swell, MPI size and bag limits, marine reserve warnings, boat ramps, fish identification, a catch log with size checks, offline use and a Tides home-screen widget.

The saved Data safety answers already cover account sync: precise location and other user-generated content are declared as optional and collected for app functionality, with account deletion, so they were left unchanged. Play Console's App content page had no outstanding declarations. Google says reviews are typically completed within 7 days. Adding production countries also updates open testing, which shares country targeting with production; closed testing stays **Active**. The bundle was larger than the browser extension's 10 MB file limit, so the publisher dragged it into the release form.

All initial Play setup tasks are saved, including reviewer sign-in details, target audience and Data safety. The target audience declaration is **18 and over**. The Advertising ID declaration is saved as **No**. A signed version-code-1 bundle was rebuilt from the current source, validated locally and **accepted by Google Play**. The New Zealand-only Alpha closed test release `0.1 - NZ closed test` and 13 other changes were submitted on 6 October 2026. The supporting service, account-deletion migration and five-per-day identification migration were deployed on 6 October 2026.

| Requirement | Repository status |
| --- | --- |
| Target API | Target/compile SDK 37; meets Google's current minimum 36 for new phone apps. The installed SDK is stable, although AGP 9.1 emits a compatibility warning. |
| App Bundle and signing | Version-code-1 release AAB is signed with the locally configured upload key and uploaded to Google Play. The key and password configuration are private and Git-ignored; both need an encrypted backup. A manual GitHub build workflow is also provided. Production builds stop when signing credentials are missing. |
| 64-bit / 16 KB pages | All 20 packaged native libraries have matching 64-bit coverage; all arm64/x86_64 ELF LOAD segments meet 16 KB alignment. Bundle requests `PAGE_ALIGNMENT_16K`; release APK passes `zipalign -c -P 16`. |
| Account deletion | More → Account → Delete account, with permanent-deletion confirmation; authenticated API deletion removes the account and associated records transactionally. Web deletion uses an expiring email link and a separate confirmation POST. |
| Public privacy policy | `/privacy` on the existing API host; includes Tristan, contact, data flows, providers, retention and deletion. Linked before sign-up and from settings. |
| Photo disclosure | A separate agree/cancel dialog names Cloudflare and OpenAI before every Android upload. JPEG re-encoding removes original metadata. OpenAI response storage is disabled. |
| Permissions | Foreground location and camera are optional; gallery uses Android Photo Picker. No broad photo/storage, background location or advertising-ID permissions in the merged release manifest. |
| Credentials and networking | Session and OAuth preferences excluded from backup/device transfer. Cleartext network traffic disabled. |
| Analytics | Our own anonymous usage statistics are **on by default** after a one-time in-app notice ("Keep sharing" / "Turn off"); nothing is sent before it is answered, and an earlier off choice stays off. They send allowlisted behaviour events and a random install id with device model, Android version, app version, language and time zone to our API, signed in or not. Turning them off in More → Settings → Terms & privacy erases the server copy and resets the id. Google Analytics (Firebase) is a separate switch and stays **off unless turned on**. Analytics events, device records and API request counts are kept 90 days, account service events 90 days, feedback 365 days, with daily cleanup. |
| Government information | In-app independence disclaimer and official MPI source links; include the same explanation in the listing below. |
| Store artwork | 512 px RGBA icon, 1024×500 RGB feature graphic, and six actual 1080×1920 RGB phone screenshots of 1.0.57 under `assets/google-play`. |

Sources: [target API](https://developer.android.com/google/play/requirements/target-sdk), [16 KB compatibility](https://developer.android.com/guide/practices/page-sizes), [user data](https://support.google.com/googleplay/android-developer/answer/10144311), [account deletion](https://support.google.com/googleplay/android-developer/answer/13327111), [government information](https://support.google.com/googleplay/android-developer/answer/9514050), [store assets](https://support.google.com/googleplay/android-developer/answer/9866151).

## Changes in 1.0.34–1.0.43 that affect the listing

- **Data safety:** with the opt-in **Sync trips and catch log**, the account stores precise location (catch positions)
  and other user-generated content (catch log entries and notes), linked to the user, collected for app functionality,
  encrypted in transit and deletable. Update the Data safety answers before releasing.
- **Permissions:** `POST_NOTIFICATIONS` (asked when a trip starts, for the pre-trip forecast check and check-in
  reminder). WorkManager runs those checks; no exact alarms.
- **Widget:** the Tides home-screen widget (`TideWidget`) needs no extra permission.
- **API deploy:** run Actions → Deploy so migration `0013_user_sync.sql` and `/v1/me/sync` are live before release.

## Deploy the supporting service

Deployment completed on 6 October 2026: the account-deletion migration and then `0007_identification_daily_limit.sql` and its same-day usage backfill were applied remotely, and the Worker was deployed with the daily cleanup schedule. The quota table and updated public privacy policy were checked on the live service. The privacy and deletion URLs were accepted in Play Console. End-to-end production deletion mail and deletion still need a disposable-account check.

From `server/fishial-proxy`, apply **all** pending D1 migrations, including `0007_identification_daily_limit.sql`, then deploy:

```sh
npx wrangler d1 migrations apply RULES_DB --remote
npm run deploy
```

The publisher name/email are set in `wrangler.toml`. Confirm that `RESEND_API_KEY` and the verified sender `ACCOUNT_EMAIL_FROM` work for confirmation and deletion emails, and that `OPENAI_API_KEY` works for identification. Configure only the OAuth providers you intend to offer, following `docs/account-sign-in.md`.

Check these public pages without signing in:

- Privacy policy: `https://fishing.fishnz.space/privacy`
- Account deletion: `https://fishing.fishnz.space/delete-account`

With a disposable test account, check actual mail delivery and deletion from both routes. Opening an emailed link must never delete the account; the user must confirm on the page. Check expired links, repeat submissions, failed delivery and already-deleted accounts. Verify the deployed daily cleanup is running.

Verified in Google Analytics on 6 October: Google Signals is off, Google products and services data sharing is off, event retention is two months, user retention is 14 months, and user retention resets with new activity. Aggregated standard reports are outside these retention controls. The public policy records these settings. Recheck any advertising integrations or provider changes before release. A local analytics identifier reset **does not erase historical provider-held records**; handle requests for those records through the provider's deletion tools. Also handle processor-held mail/security records where appropriate. Cloudflare D1 recovery history can retain deleted data for [up to 30 days](https://developers.cloudflare.com/d1/reference/time-travel/); do not restore deleted accounts without reapplying deletions.

## Build the bundle to upload

The private **upload key** for this new app is configured at `/Users/apple/.android/fishdays-nz-play/upload-keystore.jks` with the ignored `signing.properties`. Play App Signing is shown as active in the release form. Keep an encrypted backup of **both** the keystore and signing properties; never commit or paste either into chat.

Copy `signing.properties.example` to ignored `signing.properties` and set its four `PLAY_UPLOAD_*` values locally. Keep your real Firebase configuration at ignored `app/google-services.json`. Build with a version code higher than every version previously uploaded to Play:

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease \
  -PplayVersionCode=1 -PplayVersionName=0.1
python3 tools/check_android_native.py app/build/outputs/bundle/release/app-release.aab
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
```

The signed `app/build/outputs/bundle/release/app-release.aab` was uploaded to the Alpha track and accepted as version **1 (0.1)**. Use a higher version code for the next upload. A build produced with `-PallowUnsignedRelease=true` is **unsigned and cannot be uploaded**.

Alternatively run the manual **Google Play bundle** GitHub workflow after configuring repository secrets `GOOGLE_SERVICES_JSON`, `PLAY_UPLOAD_KEYSTORE_BASE64`, `PLAY_UPLOAD_STORE_PASSWORD`, `PLAY_UPLOAD_KEY_ALIAS` and `PLAY_UPLOAD_KEY_PASSWORD`. It builds and saves a signed artifact; it does not submit to Play automatically.

## Play Console setup

1. Register/verify a **personal** developer account using your real legal identity. The public developer display name can be Tristan. Complete the account/contact/device verification requested in your Console; choose the supported countries deliberately.
2. Create Fishdays - NZ as an app and configure Play App Signing. Suggested category: Sports; the current app contains no ads or purchase flow. If paid digital features are later sold in the app, review Play Billing before adding payment links.
3. Add the listing in `assets/google-play/listing.md`, icon, feature graphic and phone screenshots. Enter the privacy and account-deletion URLs only after deployment and successful checks.
4. Complete Data safety, account deletion, ads, government-app, content rating, target-audience, financial and health declarations requested by the Console. This is an independent app, not a government app; it communicates MPI rules. The IARC content rating is Everyone / PEGI 3 / 3+ / All ages. The intended audience is **18 and over**; content rating and target audience are separate declarations.
5. For **App access**, provide a dedicated, email-verified reviewer account and working password in Play Console. Fish identification requires sign-in, so “all functionality available without access restrictions” is inaccurate. Give steps for sign-in, selecting a photo/area, agreeing to upload, and sending an AI-result report. Reviewers should not need their own account or a fresh email verification. Keep provider credentials, service access and that account working throughout review.
6. Start internal testing and review the pre-launch report. Then run closed testing if required. [New personal accounts created after 13 November 2023](https://support.google.com/googleplay/android-developer/answer/14151465) need at least 12 opted-in testers continuously for 14 days before applying for production access. Record actual engagement, feedback and fixes. Production access and app approval remain Google's decisions.

### Console preparation on 6 October 2026

- Saved: privacy URL, ads (none), government apps (no), financial features (none), health features (none), content rating, Sports category/contact, and the main store listing with artwork and phone screenshots.
- Reviewer account: publisher reports that it was created and email-confirmed in the Android emulator. The publisher saved its credentials directly in Play Console; the dashboard confirms Sign in details is complete. Credentials are not stored in this repository.
- Reviewer instructions saved: More → Account → Sign in; Home → What fish is this?; select an MPI area, Gallery photo, Identify fish, then Agree and upload. The five-identifications-per-day limit and daily reset are explained. Planning, maps, tides, weather and rules work without sign-in or location permission. AI results can be reported; feedback is under More → Settings → Feedback. No payment is required.
- Data safety: all ten data types and purposes below are saved and were submitted for review. The optional “Committed to follow the Play Families policy” badge is not selected; the app now targets adults only.
- Audience: Play Console now says “The target age group for your app is: 18 and over” and “Your app restricted users determined to be minors.” Saved for review on 6 October 2026. The Android age screen and family mode were removed from the source. See [audience declarations](https://support.google.com/googleplay/android-developer/answer/9867159).
- Advertising ID: Play Console shows the saved **No** declaration; the merged release manifest removes the AD_ID permission.
- Alpha closed test: New Zealand is the only targeted country; an email list named `testers` was selected with 2 addresses at first; the feedback contact is `tc199558@gmail.com`. Version 1 (0.1) and 18+ release notes were submitted for Google review. Play flagged optional missing deobfuscation and native debug symbol files, with no release-blocking errors.

### Closed test result

The publisher recruited testers from friends who fish, local New Zealand fishing clubs and a paid testing service, and found recruiting easy. Around 15 testers used the app almost every day, and feedback was mostly positive. Play Console's **Testing feedback** page had no private feedback entries on 22 October, so feedback came through other channels. Versions 1.0.33–1.0.43 (16–20 October) shipped during the test: wave ratings at rocks and beaches, saved spots and trips kept after the app closes, offline use, the catch log with MPI size checks, marine protected areas, boat ramps, the Tides widget and the pre-trip forecast check.

### Production access application on 22 October 2026

Submitted from Dashboard → **Apply for production**. The form has three steps, each answer limited to 300 characters; Google says the answers are not shown on Google Play. The answers given:

- **About your closed test:** recruitment and engagement as above; recruiting rated **Easy**; feedback summarised as mostly positive, collected through the in-app Feedback option and direct messages.
- **About your app:** audience is adults (18+) in New Zealand who fish recreationally from beaches, rocks, wharves or boats; value is fishing windows, LINZ tides, MPI size and bag limits, marine reserve warnings, boat ramps, fish identification, the catch log and offline use in one free app; expected first-year installs **0 – 10K**.
- **Your production readiness:** the 1.0.33–1.0.43 changes above, and readiness based on about 15 testers using the app almost daily for more than 14 days, automated tests on every release, and the release build meeting Play's target API, 64-bit and 16 KB page requirements.

If Google declines, the email says why; address it, keep the closed test running and apply again.

## Data safety working answers

These answers describe this Android implementation and must be checked against **deployed provider settings**. Optional data must still be declared. See [Google's form guidance](https://support.google.com/googleplay/android-developer/answer/10787469) and [Analytics SDK disclosures](https://support.google.com/analytics/answer/11582702).

| Data type | Collected / purpose | User choice and retention |
| --- | --- | --- |
| Name | Optional profile/provider display name; account management | Optional. Removed with account. |
| Email | Registration, provider sign-in, account/deletion mail and support | Account is optional for core planning; email required for account features. Removed with account in D1; processor mail records follow provider rules. |
| User IDs | Internal account ID and Google/Apple subject; account management and analytics | Account features only. Removed with account and connected identities. |
| Other personal info | Country code in the optional account profile; account management | Optional account feature. Removed with account. |
| Precise location | Optional device coordinates sent to Open-Meteo; app functionality and personalization | User can deny location and choose places manually. API troubleshooting logs can include coordinates and IP addresses for 90 days. Not declared ephemeral. |
| Approximate location | Coarse coordinates and optional Firebase location inferred from masked IP; app functionality, personalization and analytics | Optional location/analytics. Open-Meteo logs 90 days; Analytics event data two months and user data 14 months, with reset on new activity. |
| Photos | Chosen fish photo through Cloudflare to OpenAI; app functionality and fraud prevention/security/compliance | Optional, explicit upload agreement. Not saved in our account database. Provider abuse-monitoring retention can apply, so not declared ephemeral. |
| Other user-generated content | Feedback and AI-result reports; app functionality and analytics | Optional. Removed with account or after 365 days. |
| App interactions | Account service events, per-account API request counts, and by default (after the notice) screen views, searches, fish identification outcomes and sign-in steps with no location, photos, email or typed text; Firebase lifecycle events only if the person turns Google Analytics on; analytics | Collected by default for people who keep the notice's "Keep sharing" choice; the person can turn it off at any time. Declare it as collected for analytics, not shared. D1 events removed with account or after 90 days. |
| Device or other IDs | Random install id made by the app (not the advertising ID or a hardware id) with device model and OS version, collected by default after the notice; Firebase app-instance identifier and SDK device data only if Google Analytics is turned on; analytics | Advertising ID is disabled. The install id is erased from our database when statistics are turned off, the account is deleted, or after 90 days; historical provider-held records require provider deletion tools. Declare as collected for analytics. |

The saved declaration includes encryption in transit, email/password and OAuth account creation, and the deployed account-deletion URL. All selected data is optional under Google's definition because core features work without an account, location, photo upload or analytics consent. All ten types are declared as collected and not processed ephemerally.

The saved declaration does not mark data as shared under Google's exemptions: Cloudflare and Resend process account/mail data on behalf of the publisher; Open-Meteo processes forecast requests and technical logs; the reviewed Analytics settings do not enable Google products and services sharing; photo transfers name OpenAI and require a separate user-initiated upload agreement. This is a declaration under Google's definitions, not a claim that no data leaves the device. Reassess if provider purposes, contracts or settings change. Collection is still declared. No payments, contacts, messages, microphone recordings or public social feed are implemented.

## Verification and remaining release work

**Shared-window links.** To make links from **Share this window** open the app on Android, add Play's *app signing key* SHA-256 (Play Console → your app → App signing) to `ANDROID_CERT_SHA256` in `server/fishial-proxy/wrangler.toml` and deploy the API; the upload key is already listed. Set `ANDROID_DOWNLOAD_URL` to the Play page when the app is public. Both were set in 1.0.58; deploy the API. See [share-fishing-window.md](share-fishing-window.md).

Local verification: 73 Android unit tests passed on 6 October; Android release lint has 0 errors (existing warnings remain); the iOS simulator build and backend tests passed in earlier work; the shared API TypeScript check passed. The signed release AAB passes `jarsigner`, bundletool validation, packaged 64-bit and 16 KB checks. The Android emulator shows the current app, and the publisher reports successful reviewer-account creation and email confirmation. Account-deletion tests use real local D1 and simulated email delivery. Production deletion mail, provider consent screens, signed-in Android deletion/photo-consent flows and Play review have not been verified live.

The More → Share row sends the app's Play URL (`https://play.google.com/store/apps/details?id=nz.fishingnz.app`), which is public since the app went live on 27 October 2026. Production access was granted and the first production release (45, 1.0.44) went live on 27 October 2026, and update 58 (1.0.57) was sent for review the same day (see [Current status](#current-status)); the next Play upload needs a version code above 58. Verify live deletion and provider-held data deletion. Back up the private upload key and local signing configuration securely. Existing branding and provider-use findings remain in `docs/legal-risk-review.md`. This preparation does not establish brand clearance or Google approval.
