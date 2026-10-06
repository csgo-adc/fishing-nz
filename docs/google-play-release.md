# Fishdays - NZ — Google Play release

Reviewed 6 October 2026 against the Android app, shared API, deployed provider settings and Google's current primary-source requirements. Publisher: **Tristan**, an individual developer. Public support/privacy email: **tc199558@gmail.com**. Package: `nz.fishingnz.app`.

## Current status

All eleven initial Play setup tasks are saved, including reviewer sign-in details, target audience and Data safety. They remain unsubmitted in Publishing overview. This is **not yet a signed, uploaded or approved Play release**: Latest releases and bundles shows no releases and no app bundles, and the dashboard shows zero opted-in testers. The supporting service, account-deletion migration and five-per-day identification migration were deployed on 6 October 2026. The target audience declaration was changed to 18 and over on 6 October 2026, with Google Play’s optional minor restriction selected. The change is saved in Publishing overview and has not been sent for review.

| Requirement | Repository status |
| --- | --- |
| Target API | Target/compile SDK 37; meets Google's current minimum 36 for new phone apps. The installed SDK is stable, although AGP 9.1 emits a compatibility warning. |
| App Bundle and signing | Release AAB builds. Upload-key configuration and a manual GitHub build workflow are provided. Production builds stop when signing credentials are missing. |
| 64-bit / 16 KB pages | All 20 packaged native libraries have matching 64-bit coverage; all arm64/x86_64 ELF LOAD segments meet 16 KB alignment. Bundle requests `PAGE_ALIGNMENT_16K`; release APK passes `zipalign -c -P 16`. |
| Account deletion | More → Account → Delete account, with permanent-deletion confirmation; authenticated API deletion removes the account and associated records transactionally. Web deletion uses an expiring email link and a separate confirmation POST. |
| Public privacy policy | `/privacy` on the existing API host; includes Tristan, contact, data flows, providers, retention and deletion. Linked before sign-up and from settings. |
| Photo disclosure | A separate agree/cancel dialog names Cloudflare and OpenAI before every Android upload. JPEG re-encoding removes original metadata. OpenAI response storage is disabled. |
| Permissions | Foreground location and camera are optional; gallery uses Android Photo Picker. No broad photo/storage, background location or advertising-ID permissions in the merged release manifest. |
| Credentials and networking | Session and OAuth preferences excluded from backup/device transfer. Cleartext network traffic disabled. |
| Analytics | Off by default; optional switch and local identifier reset in privacy settings. Account service events retained for 90 days, feedback for 365 days, with daily cleanup. |
| Government information | In-app independence disclaimer and official MPI source links; include the same explanation in the listing below. |
| Store artwork | 512 px RGBA icon, 1024×500 RGB feature graphic, and two actual 1080×1920 RGB screenshots under `assets/google-play`. |

Sources: [target API](https://developer.android.com/google/play/requirements/target-sdk), [16 KB compatibility](https://developer.android.com/guide/practices/page-sizes), [user data](https://support.google.com/googleplay/android-developer/answer/10144311), [account deletion](https://support.google.com/googleplay/android-developer/answer/13327111), [government information](https://support.google.com/googleplay/android-developer/answer/9514050), [store assets](https://support.google.com/googleplay/android-developer/answer/9866151).

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

For a new app, create a private **upload key** in Android Studio's signed-bundle flow and enroll in Play App Signing. For an existing Play app, use its registered upload key. Keep a protected backup; never commit or paste passwords or the keystore into chat.

Copy `signing.properties.example` to ignored `signing.properties` and set its four `PLAY_UPLOAD_*` values locally. Keep your real Firebase configuration at ignored `app/google-services.json`. Build with a version code higher than every version previously uploaded to Play:

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease \
  -PplayVersionCode=1 -PplayVersionName=0.1
python3 tools/check_android_native.py app/build/outputs/bundle/release/app-release.aab
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
```

Upload `app/build/outputs/bundle/release/app-release.aab`, not the debug APK in GitHub Releases. The local audit bundle produced with `-PallowUnsignedRelease=true` is **unsigned and cannot be uploaded**; rebuild after configuring your upload key.

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
- Reviewer instructions prepared: More → Account → Sign in; Home → What fish is this?; select an MPI area, Gallery photo, Identify fish, then Agree and upload. Planning, maps, tides and rules work without sign-in or location permission. AI results can be reported; feedback is under More → Settings → Feedback. No payment is required.
- Data safety: all ten data types and purposes below are saved, ready to send for review in Publishing overview. The optional “Committed to follow the Play Families policy” badge is not selected; the underlying policy requirements still apply.
- Audience: Play Console now says “The target age group for your app is: 18 and over” and “Your app restricted users determined to be minors.” Saved for review on 6 October 2026. The Android age screen and family mode were removed from the source. See [audience declarations](https://support.google.com/googleplay/android-developer/answer/9867159).

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
| App interactions | Account service events plus opt-in app opens/feature use and Firebase lifecycle events; analytics | Optional analytics is off by default, but account operations and fish identification still create service records. D1 events removed with account or after 90 days. |
| Device or other IDs | Opt-in Firebase app-instance identifier and SDK device data; analytics | Optional. Advertising ID is disabled. Analytics retention as above; historical provider-held records require provider deletion tools. |

The saved declaration includes encryption in transit, email/password and OAuth account creation, and the deployed account-deletion URL. All selected data is optional under Google's definition because core features work without an account, location, photo upload or analytics consent. All ten types are declared as collected and not processed ephemerally.

The saved declaration does not mark data as shared under Google's exemptions: Cloudflare and Resend process account/mail data on behalf of the publisher; Open-Meteo processes forecast requests and technical logs; the reviewed Analytics settings do not enable Google products and services sharing; photo transfers name OpenAI and require a separate user-initiated upload agreement. This is a declaration under Google's definitions, not a claim that no data leaves the device. Reassess if provider purposes, contracts or settings change. Collection is still declared. No payments, contacts, messages, microphone recordings or public social feed are implemented.

## Verification and remaining release work

Local verification: Android unit tests and iOS simulator build passed; Android release lint has 0 errors (existing warnings remain); 18 backend tests passed, rerun before service deployment on 6 October; shared API TypeScript check passed. The release AAB and APK build; packaged native checks, AAB 16 KB packaging request and release APK ZIP alignment pass. The renamed debug app was rebuilt and installed on the Android emulator, and the publisher reports successful account creation and email confirmation. Account-deletion tests use real local D1 and simulated email delivery. Production deletion mail, provider consent screens, signed-in Android deletion/photo-consent flows and Play review have not been verified live.

Before release, configure the upload key and build a signed AAB, verify live deletion and provider-held data deletion, finish Console declarations/reviewer access, and complete the required closed test. The current local audit AAB is unsigned and no upload signing configuration is present. Existing branding and provider-use findings remain in `docs/legal-risk-review.md`. This preparation does not establish brand clearance or Google approval.
