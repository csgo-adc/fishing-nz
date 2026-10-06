# Fishdays - NZ for iOS

Open `CatchCheckNZ.xcodeproj` in Xcode and run the **CatchCheckNZ** scheme on an iOS 17+ simulator or device.

For simulator builds, keep Xcode's default ad hoc signing enabled. Do not pass `CODE_SIGNING_ALLOWED=NO`: that omits the app's simulated signing identity and prevents Keychain session storage, so a successful server login cannot be saved. Use `xcodebuild -project iosApp/CatchCheckNZ.xcodeproj -scheme CatchCheckNZ -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build` from the repository root.

The app is a native SwiftUI counterpart to the Android app. It uses MapKit and Core Location, including MapKit tile overlays for LINZ aerial imagery and topographic basemaps. It also uses the system photo picker and camera, and Open-Meteo's public weather/marine APIs. The fish-identification result remains a deliberate demo adapter, just as it does on Android; do not treat it as an official rules decision.

Before distribution, select an Apple Developer team in Xcode's Signing & Capabilities panel and change the bundle identifier if `nz.fishingnz.catchcheck` is not registered to that team.

## Code-sharing boundary

`Models`, the spot/rule catalogue, scoring logic, and API contracts should become a Kotlin Multiplatform `shared` module once both apps move beyond this first iOS release. The SwiftUI/Compose views and platform integrations (MapKit vs MapLibre, camera, location permissions and Firebase) should remain platform-native.
