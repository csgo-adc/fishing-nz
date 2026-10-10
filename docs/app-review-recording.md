# App Review physical-iPhone checklist

For Apple's 10 October 2026 Guideline 2.1 request for Fishdays - NZ 1.0.20 (21).

## Before recording

1. Borrow an iPhone or arrange a tester. Install the latest iOS available to that device and record its model and exact iOS version. Use a device able to run the latest release requested by Apple.
2. Install the submitted build **1.0.20 (21)** through TestFlight. Confirm the version; do not substitute a simulator or a different build.
3. Have a working internet connection, a fish photo containing no people/private information, and a disposable email inbox for registration and deletion. Keep the App Review demo account intact.
4. Test launch, forecast loading, map, tides, rules, photo consent and identification, login persistence after relaunch, and deletion. Record any failure and fix it before claiming the build is ready.

## Record one clear typical flow

Start iPhone screen recording before launching the app. Avoid notifications and unrelated private information.

1. Launch from the Home Screen. Show initial notices and location choices.
2. Choose Auckland manually; search a land-fishing window and open its explanation/conditions. Show sharing without messaging anyone.
3. Open Map and a place's conditions, Tide station/date/curve, Weather hourly/daily forecasts, and More > Rules with an MPI area and official source link.
4. More > Account > Create account. Use the disposable inbox, complete email verification, and sign in. Avoid exposing a reusable password in the recording.
5. Home > Choose photo > select fish photo and MPI area > Identify fish > Agree and upload. Show the consent sheet and result or uncertainty explanation. Do not exceed five successful identifications per New Zealand day.
6. Sign out and sign in again, then relaunch to check session persistence.
7. More > Account > Delete account. Confirm deletion of the disposable account. Show the signed-out result and verify the deleted account cannot sign in again.
8. There are no paid feature unlocks or public user-content posting in this build, so paid-content, reporting and blocking demonstrations are not applicable.

## Complete Apple's response

Attach the recording in App Store Connect or provide a reviewer-accessible video link. Update Notes with that attachment/link, model, exact iOS version and completed QA details. Reply to App Review with all six requested sections, using the saved Notes as the starting point. Provide actual rights documentation where applicable; unresolved permissions must not be presented as cleared. Keep the demo login valid and service available. Then update/resubmit the review and retain manual release.
