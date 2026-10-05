# Fishing Days NZ icon

The mark combines a white fish moving forward with a simplified white silver-fern tail, royal-blue belly `#071FB3`, red dorsal fin `#D10000`, and black eye `#000000`. These colours are sampled from the supplied New Zealand reference. A very light sea-blue background fades from `#E1F5FC` to `#BFE7F5`, with a few quiet ripples. A subtle blue edge `#4B91AF` separates the white body and fern from the pale water; the deeper blue belly remains distinct.

The built-in ImageGen tool produced the concept and refinements. The production mark is a clean vector reconstruction of that concept, preserving the fish, fern tail and colours while removing textured edges. The exact prompt set is saved in [imagegen-prompts.txt](imagegen-prompts.txt); it retains the earlier working name to preserve the original prompts. The generated concept and the user's reference are retained beside it.

- [fishing-days-mark.svg](fishing-days-mark.svg): editable, transparent vector master.
- [fishing-days-mark.png](fishing-days-mark.png): transparent 2048 px raster master.
- [fishing-days-sea-background.svg](fishing-days-sea-background.svg): editable, full-bleed sea and ripple background.
- [fishing-days-icon-1024.png](fishing-days-icon-1024.png): opaque, unmasked square for iOS and general use.
- [fishing-days-google-play-512.png](fishing-days-google-play-512.png): unmasked store artwork.
- [fishing-days-icon-preview.png](fishing-days-icon-preview.png): shape and small-size comparisons.
- [fishing-days-icon-background-comparison.png](fishing-days-icon-background-comparison.png): previous white-background icon beside the revised pale-ocean icon.

Run `node tools/export_app_icons.cjs` from the repository root to regenerate the platform assets, using the existing web workspace's Sharp dependency.

Android uses separate fish foreground and pale-ocean background layers. The water extends across the entire 108 dp canvas, while the whole mark fits inside the central 66 dp safe circle. A dedicated monochrome silhouette includes an eye cutout for Android themed icons. The preview covers circle, squircle, rounded-square and teardrop masks; the launcher chooses the final device shape. See [Android's adaptive-icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

iOS includes opaque icons for iPhone, iPad, Settings, Spotlight and the App Store in the AppIcon asset catalogue. The source images have square corners so the operating system can apply its own mask. See [Apple's asset-catalog configuration](https://developer.apple.com/documentation/xcode/configuring-your-app-icon).

Both apps display **Fishing Days NZ**. Existing bundle IDs, account storage keys and the internal Xcode target name remain compatible with previous installations. The website and account confirmation text use the new public name as well.
