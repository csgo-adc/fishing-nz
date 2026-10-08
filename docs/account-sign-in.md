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

1. In your [Apple Developer account](https://developer.apple.com/account/), register or select the primary App ID for Fishdays - NZ and enable Sign in with Apple. The current iPhone bundle identifier is `nz.fishingnz.catchcheck`; use your actual registered identifier if it differs.
2. Register a **Services ID**, enable Sign in with Apple for it, and associate it with the primary App ID. This Services ID is the `APPLE_CLIENT_ID` used by the shared browser flow.
3. In the Services ID’s website configuration, add domain `fishing.fishnz.space` and this exact return URL:

   ```text
   https://fishing.fishnz.space/v1/auth/oauth/apple/callback
   ```

4. Create a Sign in with Apple key associated with the primary App ID, download its `.p8` private key, and record the key ID and your team ID.
5. From `server/fishial-proxy`, save these Worker secrets:

   ```sh
   npx wrangler secret put APPLE_CLIENT_ID
   npx wrangler secret put APPLE_TEAM_ID
   npx wrangler secret put APPLE_KEY_ID
   npx wrangler secret put APPLE_PRIVATE_KEY < /absolute/path/AuthKey_YOUR_KEY_ID.p8
   ```

The API generates a short-lived signed Apple client secret for each exchange, so there is no static six-month client secret to renew. Keep the `.p8` file out of Git and the apps. If you send email to Apple private relay addresses, configure your sending domain/email sources in Apple’s private email relay settings.

See [Apple’s cross-platform authorization flow](https://developer.apple.com/documentation/signinwithapple/incorporating-sign-in-with-apple-into-other-platforms) and [token validation](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens).

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
