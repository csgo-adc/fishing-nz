# Sharing a fishing window

Open a fishing window in the Android or iPhone app and tap **Share this window**. The share sheet sends a short message with the place, time and outlook, plus a link, to WeChat, WhatsApp, Instagram, Facebook, Messages or any other app that accepts text.

What the person who receives it sees:

| They have… | They are taken to… |
| --- | --- |
| The app, tapping the link in an ordinary app (Messages, WhatsApp, Mail, Chrome, Safari) | The window, inside the app. This needs the one-time setup below; until then it opens the web page. |
| The app, but the link opened inside WeChat, Instagram or Facebook's own browser | The web page. Those browsers refuse to hand links to apps, so the page shows a "tap ⋯ and open in browser" hint (also in Chinese for WeChat), and from the real browser **Open in app** works. |
| No app, on a phone | The web page: the window, **Download** buttons and a button that opens the app if it turns out to be installed. |
| No app, on a computer | The same page in a two-column layout, with **Copy link** and the download buttons. Light and dark. |

What people share is a **short link**, `https://fishing.fishnz.space/w/k3F9xQ2m`. The page behind it is served by the API Worker (`server/fishial-proxy/src/share.ts` and `share-page.ts`).

How a short link is made: tapping **Share this window** sends the window to `POST /v1/share`. The Worker keeps it for **60 days** and answers with an 8-character id. The button shows "Creating link…" for a moment. If the service can't be reached (offline, timeout), the app shares a **long link** instead, `/w/<whole window>`, which is about 800 characters but needs no server storage. Both kinds open the same page and the same app screen, so sharing never fails because of the network.

## Until the apps are published

- The **Download for iPhone / Android** buttons show `href="#"`, are greyed and say "link coming soon". Set the two real links (below) and they become normal buttons.
- iPhone and Android links fall back to the web page everywhere, because the app-opening setup needs a paid Apple Developer Team ID and Google's Play signing certificate.

## One-time setup (nothing here can be done without your accounts)

Deploying the Worker (Actions → Deploy) also applies the database migration that short links need. Everything else is a value in [`server/fishial-proxy/wrangler.toml`](../server/fishial-proxy/wrangler.toml) under `[vars]`. After editing, run **Actions → Deploy** (or `npm run deploy` in `server/fishial-proxy`).

| Variable | Set it to | Effect |
| --- | --- | --- |
| `IOS_DOWNLOAD_URL` | The App Store page, `https://apps.apple.com/app/id<Apple ID>` | The iPhone button becomes a real link |
| `ANDROID_DOWNLOAD_URL` | `https://play.google.com/store/apps/details?id=nz.fishingnz.app` | The Android button becomes a real link |
| `APPLE_TEAM_ID` | The 10-character Team ID of the **paid** Apple Developer membership (developer.apple.com → Membership details) | `/.well-known/apple-app-site-association` lists the app, so iPhones open `/w/*` links in the app |
| `ANDROID_CERT_SHA256` | Comma-separated SHA-256 fingerprints of every key that signs the app | `/.well-known/assetlinks.json` lists the app, so Android opens `/w/*` links in the app |

Values that are not `https://` links, a 10-character Team ID or a 32-byte colon-separated fingerprint are ignored, so a typo cannot publish a broken file.

### iPhone

1. Join the paid program and select that team in Xcode (see [the App Store checklist](app-store-release.md)). Set `APPLE_TEAM_ID` and deploy.
2. The **Release** configuration already carries the Associated Domains entitlement (`applinks:fishing.fishnz.space`, in `CatchCheckNZRelease.entitlements`). **Debug** deliberately does not: a free Personal Team cannot sign Associated Domains, and adding it would stop Run on a real iPhone. Once the paid team is selected you can point Debug's `CODE_SIGN_ENTITLEMENTS` at the Release file too, which lets you test Universal Links from Xcode.
3. Check Apple can see the file: `curl https://app-site-association.cdn-apple.com/a/v1/fishing.fishnz.space`. Apple caches it, so a new install of the app is the reliable test.
4. Apple only follows a Universal Link when it is tapped from another app (or from a different site in Safari). Typing the address in Safari, or tapping it on the same page, stays in the browser. The page's **Open in app** button covers that with the app's own `nz.fishingnz.catchcheck://w/<token>` address, which iOS asks the person to confirm.

### Android

`ANDROID_CERT_SHA256` already holds the **Play upload key** (`37:B7:79:…:74:59:F6`), which signs bundles built locally and by the *Android Play* workflow. Copies installed from Google Play are re-signed with Google's **app signing key**, a different certificate:

1. In Play Console open your app's **App signing** page (under Test and release) and copy the **SHA-256 certificate fingerprint** under "App signing key certificate".
2. Append it to `ANDROID_CERT_SHA256` (comma-separated) and deploy.
3. Check: `curl https://fishing.fishnz.space/.well-known/assetlinks.json`, then on a phone with the app, `adb shell pm get-app-links nz.fishingnz.app` should show `fishing.fishnz.space: verified`.

Without step 2, links from Play-installed copies open in the browser (Android 12 and later do not hand unverified links to apps), so the page's **Open in app** button is how people get into the app.

## The link format

`https://fishing.fishnz.space/w/<token>`. The token is the **unpadded base64url of UTF-8 JSON**:

```json
{"v":1,"n":"Takapuna Beach","a":"Auckland","b":0,"s":1791694800,"e":1791702000,"la":-36.7871,"lo":174.7705,
 "o":["🙂","Okay"],"f":["🗓️","Planning forecast"],"r":"Lower discomfort","t":1791608400,
 "c":[["Wind","12 km/h · gust 20 · SW","😌","Comfortable"],["Tide","Next high 7:12 PM · 2.4 m CD\nAuckland (Waitematā)","🕒","Tide timing"]]}
```

| Key | Meaning | Limit |
| --- | --- | --- |
| `v` | Format version, always `1` | |
| `n`, `a` | Place name, area | 80 characters each |
| `b` | `1` boat, `0` land | |
| `s`, `e` | Start and end, epoch seconds | End after start, at most 24 hours, 2024–2099 |
| `la`, `lo` | Area marker, 4 decimals (optional, both or neither) | |
| `o` | Window outlook `[emoji, label]` | 8 / 40 characters |
| `f` | Forecast confidence `[emoji, label]` (optional) | |
| `r` | "Why this window" | 200 characters |
| `t` | When it was shared, epoch seconds (optional) | |
| `c` | Conditions, each `[title, value, emoji, label]` | 8 rows; 24 / 120 / 8 / 40 characters; value may have up to 3 lines |

The JSON above, base64url-encoded, is the **token**. It is what the app posts to the service, and it is also the long link's path. A real window with six conditions makes a token of about 750 characters. Text outside the limits is cut with "…", and anything malformed is refused: the page shows a friendly "this link can't be opened" (HTTP 404) and the apps tell the person.

### The short-link service

| Call | Does |
| --- | --- |
| `POST /v1/share` with `{"token": "<token>"}` | Validates the window, stores it, answers `201 {"id", "url", "expires_at"}`. `400 invalid_window`, `413 too_large`, `429 rate_limited`. No sign-in needed. |
| `GET /v1/share/<id>` | `200 {"token"}`, or `404 not_found` when the id is unknown or expired. The apps call it when opened with a short link. |
| `GET /w/<id>` | The web page. An unknown or expired id gets a friendly 404 page, "no longer available". |

- **Ids** are 8 characters from `23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz` (no 0 O 1 I l), chosen at random. That is about 1.3 × 10^14 possibilities, so a link can't be guessed. No real token is 8 characters, so `/w/<x>` is unambiguous.
- **Storage** is the `shared_windows` table (migration `0011_shared_windows.sql`): the id, the token, and two timestamps. It holds no account, device or address. The daily cleanup deletes rows past `expires_at`.
- **Limits** per network address are 30 new links an hour, and 3000 an hour overall. The counters are the existing one-way-hashed `rate_limit_counters`. A request body over 8 KB is refused.

The same fixed link (the "golden token") is decoded by the Worker tests, the Android unit tests and the iPhone regression checks, so the three can't drift apart without a test failing. The code is [`SharedWindowLink.kt`](../app/src/main/java/nz/fishingnz/app/data/SharedWindowLink.kt), [`SharedWindow.swift`](../iosApp/CatchCheckNZ/Models/SharedWindow.swift) and [`share.ts`](../server/fishial-proxy/src/share.ts). Change the format in all three together and bump `v` if old links must stop working.

## Design notes and trade-offs

- **A snapshot, not live.** The page and the apps show the forecast as it was when shared, and say so. The apps' windows come from an engine that exists once in Kotlin and once in Swift; the page deliberately does not re-run it. People who want today's forecast open the app and search.
- **What is stored.** Only for short links: the window itself (place, times, forecast summary) for 60 days, with no account, device or address. The privacy policy says so (section "Sharing a fishing window"). The page has no tracking and loads nothing from other sites; map buttons are plain links. Confirm that Play's Data safety form and Apple's App Privacy answers need no new category for "a forecast summary the app sends so a link can work, not linked to the person"; I believe they don't, but those answers are yours to give.
- **Forged links.** Anyone can make a link, so the Worker treats it as untrusted: strict limits, control and bidirectional characters stripped, everything HTML-escaped, `noindex`, and a Content Security Policy that allows only the page's one inline script, by hash. A forged link can show a few lines of plain text on our domain, never a link, form or script.
- **Long links remain** as the offline fallback and so links made before short links existed keep working.
- **Not done:** Safari's Smart App Banner (needs the App Store ID) and a QR code on the desktop page. Both are small additions once the App Store page exists.
- The page's icon and link-preview image are the store artwork, embedded in [`share-icon.ts`](../server/fishial-proxy/src/share-icon.ts) so the Worker needs no static hosting. After changing `assets/branding/fishing-days-google-play-512.png`, replace the string in that module with `base64 -i assets/branding/fishing-days-google-play-512.png | tr -d '\n'`.

## Testing

```sh
cd server/fishial-proxy && npm test                    # includes tests/share-window.test.mjs
tools/test_shared_window_swift.sh                      # iPhone link format
./gradlew :app:testDebugUnitTest --tests '*SharedWindowLinkTest'
```

Open a link on a device or simulator (replace `<token>` with a real one, for example the golden token in the tests):

```sh
xcrun simctl openurl booted "nz.fishingnz.catchcheck://w/<token>"
adb shell am start -a android.intent.action.VIEW -d "https://fishing.fishnz.space/w/<token>" nz.fishingnz.app
```

To preview the web page, run the Worker locally (`npx wrangler dev` in `server/fishial-proxy`) and open `/w/<token>`; narrow the window to see the phone layout.
