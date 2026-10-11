# Google and Apple sign-in setup

The Android and iPhone apps share the Fishdays - NZ account API. Password registration accepts **8–128 characters** and requires two matching password entries in both mobile apps. Email/password accounts still require email confirmation. People who forget a password can use **Forgot password?** on the sign-in screen, which opens `/forgot-password` on the API; see the [API guide](../server/fishial-proxy/README.md). The website is an administrator workspace; users create accounts and sign in through the mobile apps.


Google and Apple use the system browser and a server authorization-code flow. New provider accounts can sign in immediately after the API verifies their identity and verified email. Apple’s Hide My Email addresses are supported. Existing users should sign in with their current method, open **Account → Sign-in options**, and connect a provider. Matching email addresses alone never merge accounts.

The buttons remain unavailable until the corresponding provider credentials are configured. One provider can be enabled independently of the other.

## Google

1. In [Google Cloud Console](https://console.cloud.google.com/auth/overview), create or select the Fishdays - NZ project and configure the OAuth consent screen with the app’s name, support email, homepage, privacy policy, and terms links.
2. Create an OAuth client with application type **Web application**. This type supports the shared API’s HTTPS callback; it does not require a public user website. The apps open the system browser for provider authentication, then return directly to the mobile app.
3. Add this exact authorized redirect URI:

   ```text
   https://fishing.fishnz.space/v1/auth/oauth/google/callback
   ```

4. From `server/fishial-proxy`, save both values as Worker secrets:

   ```sh
   npx wrangler secret put GOOGLE_CLIENT_ID
   npx wrangler secret put GOOGLE_CLIENT_SECRET
   ```

5. While the Google consent screen is in testing mode, add the accounts you will use as test users. Complete Google’s publishing requirements before opening sign-in to everyone.

See [Google’s OpenID Connect setup and flow](https://developers.google.com/identity/openid-connect/openid-connect).

## Apple

The iPhone uses Apple's own sign-in sheet (Face ID or passcode, no web page) from 1.0.30. Android has no such sheet, so it uses Apple's web sign-in in the browser, like Google. Both use one Sign in with Apple key; Android also needs a Services ID. `APPLE_TEAM_ID` (`8T7835869S`) and `APPLE_BUNDLE_ID` (`nz.fishingnz.catchcheck`) are plain variables in `wrangler.toml`.

### iPhone

1. In your [Apple Developer account](https://developer.apple.com/account/resources/identifiers/list), open the App ID `nz.fishingnz.catchcheck`, tick **Sign in with Apple** (enable as a primary App ID) and save. The app's entitlements request this capability; Xcode's automatic signing also turns it on the first time it signs a build with them.
2. Under **Keys**, add a key (for example "Fishdays Sign in with Apple"), tick **Sign in with Apple**, choose **Configure** → primary App ID `nz.fishingnz.catchcheck`, then register it. Download the `.p8` file (Apple offers it only once) and note the Key ID.
3. From `server/fishial-proxy`, save the key as Worker secrets:

   ```sh
   npx wrangler secret put APPLE_KEY_ID
   npx wrangler secret put APPLE_PRIVATE_KEY < /absolute/path/AuthKey_YOUR_KEY_ID.p8
   ```

4. Deploy the API, which applies migration `0012_apple_token_revocation.sql`. `GET /v1/auth/providers` then reports `"apple_native": true`, and iPhone 1.0.30 or later shows **Continue with Apple** above Google. (Guideline 4.8: the iPhone offers Google only while Apple is offered too.)

How it works: `POST /v1/auth/apple/start` returns a single-use nonce that expires after 10 minutes. The app hands it to the Apple sheet, which puts it in the signed identity token. `POST /v1/auth/apple/native` checks the token against Apple's keys (issuer, audience = the bundle ID, expiry, nonce), exchanges the authorization code with Apple, and signs in or creates the account with the same linking rules as the browser flow. Apple sends the person's name only the first time; the app forwards it then.

### Android (browser flow)

1. Register a **Services ID** (for example `nz.fishingnz.catchcheck.web`), enable Sign in with Apple for it and group it with the primary App ID `nz.fishingnz.catchcheck`.
2. In the Services ID's website configuration, add domain `fishing.fishnz.space` and this exact return URL:

   ```text
   https://fishing.fishnz.space/v1/auth/oauth/apple/callback
   ```

3. Save the Services ID: `npx wrangler secret put APPLE_CLIENT_ID`. The key from the iPhone steps signs this flow too. `GET /v1/auth/providers` then reports `"apple": true`, and Android (and iPhone builds before 1.0.30) show Apple.

### Token revocation

App Store guideline 5.1.1(v) requires apps with Sign in with Apple to revoke the person's Apple tokens when they delete their account. The API keeps Apple's refresh token, and the client ID it was issued to, with each Apple identity. Deleting the account, in either app or on the `/delete-account` web page, asks Apple to revoke it. A sign-in that ends without an account (for example, the email already belongs to another account) revokes the new token straight away. Revocation is best effort: if Apple cannot be reached, the account is still deleted and the failure is logged. Apple identities created before 1.0.30 have no stored token; the next Apple sign-in stores one.

The API generates a short-lived signed Apple client secret for each request, so there is no static six-month client secret to renew. Keep the `.p8` file out of Git and the apps. If you send email to Apple private relay addresses, configure your sending domain/email sources in Apple's private email relay settings.

See [Apple's cross-platform authorization flow](https://developer.apple.com/documentation/signinwithapple/incorporating-sign-in-with-apple-into-other-platforms), [token validation](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens) and [revoking tokens](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens).

## Daily fish identification allowance

Every account, on either plan, has five completed fish identification requests per New Zealand calendar day (`Pacific/Auckland`). The shared API reserves each use atomically before calling the AI provider, so requests from multiple devices share the same allowance. Invalid uploads and provider failures do not use an allowance. Any completed result, including a non-fish or uncertain result, counts as one use.

Sign-in responses, `GET /v1/me/permissions`, successful identifications and daily-limit errors include `fish_identity_quota` with `limit`, `used`, `remaining`, `day` and `time_zone`. A sixth request returns HTTP 429 with `code: daily_identification_limit` before calling the provider. The identification screen displays the remaining allowance; the Account page does not repeat it. Usage records are deleted with the account and expire after 90 days. The server alone enforces the limit; mobile clients display its count and pass through limit errors. Successful checks made earlier on 6 October 2026 were backfilled into the account quota.

Apply migration `0007_identification_daily_limit.sql` before deploying the API; the updated API requires its table. Updated mobile builds are needed to show the allowance and redesigned account page. Older app versions still obey the server limit.

## Deploy and check

1. Apply the database migration and deploy the shared API:

   ```sh
   cd server/fishial-proxy
   npx wrangler d1 migrations apply RULES_DB --remote
   npm run deploy
   ```

2. Build and distribute the updated Android and iPhone apps through the usual release process. Deploy the admin website changes from `web` with `npm run deploy`; its home page opens the admin workspace and public account pages are removed.
3. Check `GET https://fishing.fishnz.space/v1/auth/providers`: `google` and `apple` indicate which providers have all required configuration. Verify live credentials by actually signing in; availability alone does not validate them.
4. Test new accounts, returning accounts, cancellation, sign-out, and connecting a provider from an existing signed-in profile. For Apple, test Hide My Email and a second sign-in, when Apple no longer sends the user’s name.

`AUTH_BASE_URL` is the shared API’s public origin. Provider callbacks land on the API, which returns to the initiating mobile app. Only these exact mobile return addresses are allowed: `nz.fishingnz.app://auth/callback` and `nz.fishingnz.catchcheck://auth/callback`, registered in the respective apps. Website return addresses are rejected.

For local work, use an ignored `server/fishial-proxy/.dev.vars` with the provider secrets. Set `AUTH_BASE_URL` to your development API origin and configure the mobile app’s API base URL to reach it. Google allows registered localhost server callbacks; Apple needs a registered public HTTPS callback, so use a separate development Services ID and HTTPS development host for Apple testing. For mobile device tests, an HTTPS development host reachable from the device is simplest.

## Checks in the repository

The API tests exercise signed Google/Apple responses against a real local D1 database, including token validation, nonce/state checks, expired and replayed grants, cancellation, safe account linking, and Apple form posts. Provider requests are simulated; live Google/Apple consent screens require the credentials above.

```sh
cd server/fishial-proxy
npm test
```

Website checks: `npm run lint` and `npm run build` from `web`. Android checks: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`. iPhone build: the `CatchCheckNZ` scheme in Xcode.
