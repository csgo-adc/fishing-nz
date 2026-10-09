# Fishdays - NZ for iOS

Open `CatchCheckNZ.xcodeproj` in Xcode and run the **CatchCheckNZ** scheme on an iOS 17+ simulator or device.

For simulator builds, keep Xcode's default ad hoc signing enabled. Do not pass `CODE_SIGNING_ALLOWED=NO`: that omits the app's simulated signing identity and prevents Keychain session storage, so a successful server login cannot be saved. Use `xcodebuild -project iosApp/CatchCheckNZ.xcodeproj -scheme CatchCheckNZ -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build` from the repository root.

The app is a native SwiftUI counterpart to the Android app. It uses MapKit and Core Location, including MapKit tile overlays for LINZ aerial imagery and topographic basemaps. It also uses the system photo picker and camera, and Open-Meteo's public weather/marine APIs. Fish identification calls the shared Fishdays - NZ API (OpenAI behind Cloudflare) only after the person agrees on a consent sheet; its result is a suggestion, not an official rules decision.

Before distribution, join the paid Apple Developer Program, select that team in Xcode's Signing & Capabilities panel, and change the bundle identifier if `nz.fishingnz.catchcheck` is not available to that team. [App Store release checklist](../docs/app-store-release.md).

## Shared fishing windows

**Share this window** on the spot detail screen sends a link; opening one (`onOpenURL`) shows that window. The Release configuration uses `CatchCheckNZRelease.entitlements`, which adds Associated Domains (`applinks:fishing.fishnz.space`) so links open the app; Debug keeps the plain entitlements file because a free Personal Team cannot sign that capability. The link format is tested with `tools/test_shared_window_swift.sh`. Test a link in the simulator with `xcrun simctl openurl booted "nz.fishingnz.catchcheck://w/<token>"`. Setup and the full picture: [share-fishing-window.md](../docs/share-fishing-window.md).

## Code-sharing boundary

`Models`, the spot/rule catalogue, scoring logic, and API contracts should become a Kotlin Multiplatform `shared` module once both apps move beyond this first iOS release. The SwiftUI/Compose views and platform integrations (MapKit vs MapLibre, camera, location permissions and Firebase) should remain platform-native.
