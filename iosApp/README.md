# CatchCheck NZ for iOS

Open `CatchCheckNZ.xcodeproj` in Xcode and run the **CatchCheckNZ** scheme on an iOS 17+ simulator or device.

The app is a native SwiftUI counterpart to the Android app. It uses MapKit and Core Location, the system photo picker and camera, and Open-Meteo's public weather/marine APIs. The fish-identification result remains a deliberate demo adapter, just as it does on Android; do not treat it as an official rules decision.

Before distribution, select an Apple Developer team in Xcode's Signing & Capabilities panel and change the bundle identifier if `nz.fishingnz.catchcheck` is not registered to that team.

## Code-sharing boundary

`Models`, the spot/rule catalogue, scoring logic, and API contracts should become a Kotlin Multiplatform `shared` module once both apps move beyond this first iOS release. The SwiftUI/Compose views and platform integrations (MapKit vs Google Maps, camera, location permissions and Firebase) should remain platform-native.
