# In-app updates

From 1.0.59 both apps tell people about a newer version and let them update without leaving the app.

## How it works

| | Android | iPhone |
| --- | --- | --- |
| Who says an update exists | Google Play's in-app update API, for copies installed from Google Play. Copies installed another way use the service's latest build. | Apple's public App Store lookup (`itunes.apple.com/lookup`, New Zealand store). |
| What Update does | Play's own sheet confirms, the update downloads in the background while the app is in use, "Update downloaded · Restart" appears, and it installs by itself once the app has been out of sight for a minute. | Shows the App Store page over the app, with Apple's Update button. Apple doesn't let apps install updates themselves. |
| Code | `data/AppUpdates.kt`, `data/UpdatePolicy.kt`, `ui/UpdateViews.kt` | `Services/AppUpdates.swift`, `Models/UpdatePolicy.swift`, `Views/AppUpdateViews.swift` |

Both apps check when they come to the front, at most every 12 hours, and ask the service for notes in the app's
language: `GET /v1/app/version?platform=android|ios&lang=…`.

## When the pop-up shows

At most **three pop-ups per version**: the first time the store has it, **3 days** after the first, and **7 days**
after the second (day 10). The third says it won't ask again. After that only the **Update available** row at the top
of More and a badge on the More tab remain, until the update is installed. A newer version starts the count again.

The pop-up only appears on Home, about 2.5 seconds after it settles, and never:

- on the first day the app is used (including the first day after updating to 1.0.59),
- over first-launch setup, a fishing window, a spot or a More page,
- on a day another notice was shown (a shared window, say), or more than once a day,
- while a trip is under way (before its back-by time),
- when offline (no answer, no pop-up).

The **Update available** row in More is there whenever an update is available, so people can update at any time.

The rules live in `UpdatePolicy` on both platforms and are tested by `UpdatePolicyTest` (Android) and
`tools/test_update_policy_swift.sh` (iPhone).

## Announcing a release

Edit [`server/fishial-proxy/app-versions.json`](../server/fishial-proxy/app-versions.json) and deploy the API:

```json
{
  "minimum_build": { "android": 1, "ios": 1 },
  "releases": [
    { "version": "1.0.61", "build": 62, "platforms": ["android", "ios"],
      "notes": { "en": ["…", "…"], "zh-Hans": ["…"], "zh-Hant": ["…"], "ko": ["…"], "hi": ["…"] } }
  ]
}
```

- Add the release when it is sent to the stores. The pop-up appears only once the store really offers it to that
  phone (Play's staged rollout and Apple's review are respected), so adding it early is safe.
- Keep notes to three or four short lines. Languages without notes fall back to English; a release with no notes shows
  "A newer version of Fishdays - NZ is ready."
- The five newest releases per platform are sent.

## Requiring an update

Raise `minimum_build` for a platform to the oldest build that still works, and deploy. Older copies then show a
full-screen **Update required** page with no Later (Android uses Play's immediate update flow when allowed). Use it only
when an old version is broken or can no longer talk to the service. Only a fresh answer from the service can require
an update, so a phone with no signal is never locked out.

## Analytics

The pop-up reports `update_prompt_shown` (build, prompt number), `update_prompt_answered` (outcome `update` or `later`,
whether it was required) and, on Android, `update_installed` (`accepted`, `declined` or `downloaded`), through the
same anonymous usage statistics as the rest of the app.

## Testing

- Google Play's flow only works in a copy installed from Google Play: use an internal test track or internal app
  sharing with a lower build installed, then upload a higher one.
- The iPhone flow needs a version live in the App Store that is newer than the installed one.
- The rules can be checked without either store through the unit tests above.
