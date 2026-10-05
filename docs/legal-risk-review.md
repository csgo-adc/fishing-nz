# Fishing Days NZ: legal and licensing review

Reviewed 5 October 2026 against the repository and current primary-source guidance. This is a preliminary review of New Zealand legal exposure, data licences and app-store requirements, not legal clearance. Priorities below reflect what should be resolved before launch; they do not establish that an infringement or statutory breach has occurred.

The strongest concerns are the documented use of a copyrighted fern reference, commercial use of the free weather API, incomplete privacy disclosures and missing account deletion. Existing source links, map attribution, uncertainty warnings and protected session storage reduce some risks.

| Area | Priority | Recommended action |
| --- | --- | --- |
| Icon reference and brand rights | High; potential infringement | Obtain relevant permission or create an independently designed fern; clear the name and logo |
| Open-Meteo service access | High if commercial | Use authorised commercial access before subscriptions, advertising or other commercial operation |
| Privacy and third-party photo analysis | High | Publish a complete policy and explain photo sharing before upload |
| Account deletion | Store submission blocker | Add an in-app deletion path and a public web request route |
| Cached fishing rules and product claims | Medium to high | Limit cache age and handle failed checks and substantive changes |
| Data and software attribution | Medium | Complete licence links, adaptation notices and distribution notices |

## 1. Icon: specific reference rights need resolution

I inspected the current icon and `assets/branding/nz-palette-reference.png`. The latter visually appears to reproduce Kyle Lockwood's black, white and blue silver-fern flag. This identification is an inference from the image, not a verified provenance record.

The saved [prompt](/Users/apple/Project/fishing-nz/assets/branding/imagegen-prompts.txt:25) asks to use that reference's colours **and leaflet shapes**; line 28 requests leaflets simplified from the reference. No permission or licence for that reference was found in the inspected branding files. The reference image itself is tracked in Git.

The designer's [copyright notice](https://www.silverfernflag.org/copyright.html) claims rights in the flag and fern device and requires permission for branding use. The final fish mark has a different composition and a much simpler fern. Those differences matter, but they are insufficient for this review to determine whether protected expression was copied. Generating an image with AI and reconstructing it as a vector do not establish third-party clearance.

Resolve this by obtaining a licence covering the intended reference use and adaptation, or by producing an independently designed fern using botanical references with documented rights. Keep the design and permission records. Do not treat every fern motif or use of national colours as automatically prohibited.

No comprehensive trademark clearance was performed. Search the name “Fishing Days NZ,” similar names and the composite fish/fern mark using [IPONZ's word and logo search](https://www.iponz.govt.nz/get-ip/trade-marks/search/). Generic national symbolism alone does not answer whether a particular mark conflicts with another business.

## 2. Open-Meteo: data licensing and API permission are separate

Both mobile apps directly call `api.open-meteo.com` and `marine-api.open-meteo.com`, including their weather pages, place conditions and recommendation engines. The inspected requests do not use commercial customer endpoints or subscription keys. The backend has a paid-plan field, but I did not find an active payment flow; this is a conditional finding, not proof of current commercial misuse.

[Open-Meteo's terms](https://open-meteo.com/en/terms) restrict its free service to non-commercial use and classify subscriptions, advertising and integration into commercial products as commercial. A free app can still be commercial. Its [pricing guidance](https://open-meteo.com/en/pricing) provides customer access under a subscription. Weather data's permissive licence does not override the hosted service's restrictions.

Before commercial operation, obtain appropriate access, route requests through a backend that protects the subscription credential, and confirm marine and history coverage. For a qualifying non-commercial prototype, respect the published request limits. Searches request data for many candidate spots, so central caching and usage controls are worthwhile.

## 3. Privacy: the notice does not describe all actual data flows

The [Android notice](/Users/apple/Project/fishing-nz/app/src/main/java/nz/fishingnz/app/ui/CatchCheckApp.kt:218) and [iOS notice](/Users/apple/Project/fishing-nz/iosApp/CatchCheckNZ/Views/CatchCheckRootView.swift:188) describe location, accounts, feedback and an unnamed image-analysis provider. Actual processing also includes:

- Fish images sent through the Cloudflare Worker to OpenAI.
- Account and feedback storage in Cloudflare D1; account email sent through Resend.
- Android Firebase Analytics, including an event on application startup.
- Account-linked usage events stored by the Worker and viewable alongside email addresses in its admin analytics.
- Coordinates sent directly to Open-Meteo and in fish-photo request headers to the Worker. The inspected Worker selects rules from the area header and does not use those coordinate headers.

The inspected web source has no public privacy-policy route. The notice lacks operator contact details, access/correction instructions, retention periods and a full explanation of recipients and analytics. [Privacy principle 3](https://www.privacy.org.nz/privacy-principles/3/) explains collection-notice obligations; [principle 9](https://www.privacy.org.nz/privacy-principles/9/) limits unnecessary retention. Account and event cleanup was not found, apart from expired sessions and verification-token handling.

Publish an accurate policy accessible from account creation and both apps. Set retention rules, remove unnecessary coordinate transmission, and review processor contracts and actual service settings. Overseas processing is not automatically prohibited: the [Privacy Commissioner's principle 12 guidance](https://www.privacy.org.nz/responsibilities/disclosing-personal-information-outside-new-zealand/decision-tree-page/) distinguishes an agent processing solely on your behalf from disclosure to a recipient using data for its own purposes.

The [OpenAI request](/Users/apple/Project/fishing-nz/server/fishial-proxy/src/index.ts:590) omits `store: false`. [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data) state that Responses application state is retained by default and that abuse-monitoring retention can apply separately. Set `store: false` if retrieval is unnecessary, but do not describe that as zero retention. Provider account settings and retention approvals were not inspected.

The iOS photo flow has an “Identify fish” action, but no clear OpenAI-sharing disclosure or explicit permission step. [Apple guideline 5.1.2(i)](https://developer.apple.com/app-store/review/guidelines/) requires clear disclosure and explicit permission before sharing personal data with third-party AI. Photos may include identifiable people or other personal information. Add a concise explanation naming OpenAI before the first upload and obtain the required permission. A camera or gallery permission is a different permission.

## 4. Account deletion: a concrete store-policy gap

Account creation exists in the apps and Worker. I found sign-out, profile editing and session deletion, but no account-deletion endpoint or user-facing deletion path in the inspected clients or website. Signing out removes a session; it leaves the server account and associated records.

[Apple requires](https://developer.apple.com/support/offering-account-deletion-in-your-app/) apps supporting account creation to let users initiate account deletion within the app. [Google Play requires](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en) an in-app path and a web resource for requesting deletion of the account and associated data.

Implement both routes, verify the requester's identity, remove associated personal records, and explain any justified retention and backup expiry. Complete store privacy declarations against actual SDK and backend behaviour. These are platform requirements; New Zealand's retention principle should not be described as an unrestricted statutory right to erase all data.

## 5. Fishing rules and safety: warnings help, but freshness needs work

Useful safeguards already exist: selectable MPI areas, official links, uncertain-identification warnings, local-restriction reminders, interpolated-tide labels and suppression of rules explicitly marked `source_changed`.

However, the [scheduled Worker](/Users/apple/Project/fishing-nz/server/fishial-proxy/src/index.ts:582) checks one of eight areas daily, so each area is normally checked once per eight days. It does not automatically import revised structured rules. The [serving logic](/Users/apple/Project/fishing-nz/server/fishial-proxy/src/index.ts:844) has no maximum age and does not suppress a cache merely because update checks have failed. At line 962, a matching MPI review date can mark a changed HTML response successful, which may miss substantive changes made without changing that date.

The local rule database was fetched on 26 September 2026. This is evidence about the local file only; the deployed database and current accuracy of every cached rule were not audited.

Add a freshness policy based on successful verification, handle failures visibly, compare substantive content, and check all supported areas at an appropriate interval while respecting source access rules. Prefer an official link when a reliable current limit cannot be established. [MPI advises](https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules) checking rules before each trip because they change regularly.

Keep forecasts, fishing-window preferences and AI confidence clearly qualified. Do not market them as guarantees of safety, legal permission or catch success. If operating in trade, [Commerce Commission guidance](https://www.comcom.govt.nz/business/dealing-with-typical-situations/selling-goods-and-services/selling-online/) requires accurate, substantiated representations and respect for consumer guarantees. General disclaimers should not be assumed to remove those duties or other liability.

## 6. Attribution: generally reusable sources, incomplete notices

MPI permits reuse of its licensed material under CC BY 4.0 with attribution; its [copyright policy](https://www.mpi.govt.nz/about-this-site/mpi-copyright) distinguishes adaptations and excludes third-party material and inappropriate emblem use. The app credits MPI and links to rule pages, but I did not find an explicit licence link and adaptation notice on the rule screens. Add them and check separately licensed material before reusing images or complete page HTML.

[Open-Meteo's data licence](https://open-meteo.com/en/licence) requires appropriate credit, a licence link and an indication of changes. Several screens credit Open-Meteo as plain text. Add a linked data-licence notice accessible from those outputs and explain derived scores.

[LINZ's copyright policy](https://www.linz.govt.nz/copyright) generally permits reuse under CC BY 4.0 unless otherwise specified. Tide pages name LINZ and explain interpolation; add a licence link and make the adaptation notice complete, subject to any source-specific exceptions. Basemap credits already link to LINZ copyright and contributors on both platforms, consistent with [LINZ attribution guidance](https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data). Check the allocated key's access level and usage conditions before public scale; its provisioning was not verified.

Android uses OpenFreeMap rather than the public OpenStreetMap raster-tile service. MapLibre attribution is enabled, which [OpenFreeMap says](https://openfreemap.org/) supplies the required credits automatically. Verify OpenMapTiles and OpenStreetMap credits remain accessible in every style and exported image; the custom footer alone does not contain the full set.

## Remaining verification

Confirm third-party licence and copyright notices are included in distributed builds, including icon and map dependencies. This review did not audit every transitive dependency or inspect final store submissions. No separate copied weather-icon asset pack was found; the inspected implementations use code-drawn symbols and platform/library icons.

The Worker hashes passwords and session tokens; mobile sessions use Android Keystore encryption and iOS Keychain. Login and photo-identification throttling were not found in the Worker. Infrastructure controls may exist outside the repository. Verify them and add limits where needed for account protection and API-cost control; [privacy principle 5](https://www.privacy.org.nz/privacy-principles/5/) requires reasonable security safeguards.

Recommended order: resolve the icon reference and commercial API permission, implement deletion and accurate privacy/AI consent, strengthen rule freshness, then complete credits and release notices. Obtain a New Zealand IP/privacy lawyer's review of the disputed-reference issue and final commercial terms before public commercial launch.
