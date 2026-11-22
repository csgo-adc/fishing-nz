# Launch scene and welcome page

From 1.0.80 both apps open with a short animated scene on every cold start (the app was not already running), and on
the first launch it settles into a welcome page before the two setup questions.

## What it shows

1. **Opening, about 1.6 s.** The night sea, with the app's fish in its pale-sea tile in the middle of the screen,
   exactly where the system left it. The sky brightens into dawn, the sun rises behind the horizon, three layers of
   waves roll in from below, and the tile rises and takes its rounded-square shape. "Kia ora" and **Fishdays - NZ**
   fade in under it. The fish bobs, a ripple spreads from the tile now and then, the stars twinkle, bubbles rise and
   the sun glints on the water. A tap cuts the opening short.
2. **Returning users:** the scene fades and zooms slightly into the app (0.5 s). Coming back to an app that was still
   running shows no scene.
3. **First launch:** the tide comes in, the tile and name glide to the top, and the welcome page fades in line by
   line: "Find the best time and place to fish, anywhere in Aotearoa.", three highlights (best fishing window; tides,
   wind, waves and moon; size and bag limits, even offline) and **Get started** with "Two quick questions, then you're
   fishing". Get started fades the scene away onto the land-or-boat and location questions, which are unchanged; the
   tour on Home follows as before. Until Get started is tapped, the welcome page comes back on the next launch.

A shared-window link that opens the app for the first time skips the welcome page, as it skips the setup questions.
With "Remove animations" (Android) or "Reduce Motion" (iPhone) on, the scene is still and shorter.

Nothing else may cover it: the update pop-up, the shared-window notice, the tour, a shared window's results and
sheets opened from a notification wait until it has gone.

## Seamless start

- **Android.** The theme's window background and `windowSplashScreenBackground` are the scene's night sea
  (`@color/launch_night`, `#0A1A3F`), so Android 12+'s system splash shows the round launcher icon on the same colour.
  The scene's first frame draws that round icon in the same place and size (160 dp), and `MainActivity` removes the
  system splash at once rather than fading it. The app's first frames are heavy, so the scene holds that first frame
  until three frames in a row come within 50 ms (at most 0.9 s) before the sunrise starts.
- **iPhone.** `UILaunchScreen` shows `LaunchIcon` (the 120 pt tile) on `LaunchBackground`, and the scene starts with
  the same tile in the same place, then begins 0.15 s later. While it covers the screen the app asks for the dark
  colour scheme so the status bar text is light.

## Code and artwork

| | Android | iPhone |
| --- | --- | --- |
| Scene and welcome page | `ui/LaunchScene.kt` (`LaunchSceneOverlay`, drawn over everything in `CatchCheckApp`) | `Views/LaunchScene.swift` (`LaunchSceneView`, an overlay in `CatchCheckRootView`) |
| Plays once per process | `LaunchScene.playing` | `LaunchScene.shared.playing` / `.covering` |
| Launch colours | `res/values/styles.xml`, `res/values/colors.xml` | `Info.plist` `UILaunchScreen`, `LaunchBackground` colour set |

The fish (`launch_fish` / `LaunchFish`), the pale-sea tile background (`launch_sea` / `LaunchSea`) and the iPhone
launch tile (`LaunchIcon`) are made from the brand artwork by `node tools/export_launch_art.cjs`.
