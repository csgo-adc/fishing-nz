// Artwork for the animated launch scene and welcome page (docs/launch-scene.md).
// Run from the repository root: node tools/export_launch_art.cjs
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('../web/node_modules/sharp');

const root = path.resolve(__dirname, '..');
const brand = path.join(root, 'assets/branding');
const androidNoDpi = path.join(root, 'app/src/main/res/drawable-nodpi');
const ios = path.join(root, 'iosApp/CatchCheckNZ/Resources/Assets.xcassets');

// Both apps draw the fish at this share of the tile's width, centred, so the iPhone launch screen
// matches the first frame of the animated scene.
const FISH_WIDTH = 0.78;
// The tile's corner radius as a share of its side (SwiftUI continuous corners at 32 of 120 pt).
const CORNER = 32 / 120;
// The launch screen tile is 120 pt.
const LAUNCH_TILE_POINTS = 120;

async function save(buffer, filename) {
  await fs.mkdir(path.dirname(filename), { recursive: true });
  await fs.writeFile(filename, buffer);
}

async function saveJson(value, filename) {
  await save(Buffer.from(JSON.stringify(value, null, 2) + '\n'), filename);
}

async function main() {
  // Sharp trims before it resizes within one pipeline, so the mark is drawn first and trimmed in a second step.
  const mark = await sharp(path.join(brand, 'fishing-days-mark.svg')).resize(2048, 2048).png().toBuffer();
  const fish = await sharp(mark).trim().png().toBuffer();
  const fish720 = await sharp(fish).resize({ width: 720 }).png().toBuffer();
  const sea = await sharp(path.join(brand, 'fishing-days-sea-background.svg')).resize(480, 480).removeAlpha().png().toBuffer();

  await save(fish720, path.join(androidNoDpi, 'launch_fish.png'));
  await save(sea, path.join(androidNoDpi, 'launch_sea.png'));

  await save(fish720, path.join(ios, 'LaunchFish.imageset/launch_fish.png'));
  await saveJson({ images: [{ idiom: 'universal', filename: 'launch_fish.png' }], info: { author: 'xcode', version: 1 } },
    path.join(ios, 'LaunchFish.imageset/Contents.json'));
  await save(sea, path.join(ios, 'LaunchSea.imageset/launch_sea.png'));
  await saveJson({ images: [{ idiom: 'universal', filename: 'launch_sea.png' }], info: { author: 'xcode', version: 1 } },
    path.join(ios, 'LaunchSea.imageset/Contents.json'));

  // The iPhone launch screen: the pale-sea tile with the fish, shown centred at 120 pt before the app draws.
  const images = [];
  for (const scale of [2, 3]) {
    const size = LAUNCH_TILE_POINTS * scale;
    const fishWidth = Math.round(size * FISH_WIDTH);
    const fishPng = await sharp(fish).resize({ width: fishWidth }).png().toBuffer();
    const { height: fishHeight } = await sharp(fishPng).metadata();
    const radius = Math.round(size * CORNER);
    const mask = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}"><rect width="${size}" height="${size}" rx="${radius}" ry="${radius}"/></svg>`);
    const tile = await sharp(sea).resize(size, size)
      .composite([
        { input: fishPng, left: Math.round((size - fishWidth) / 2), top: Math.round((size - fishHeight) / 2) },
        { input: mask, blend: 'dest-in' },
      ])
      .png().toBuffer();
    const filename = `launch_icon@${scale}x.png`;
    await save(tile, path.join(ios, 'LaunchIcon.imageset', filename));
    images.push({ idiom: 'universal', filename, scale: `${scale}x` });
  }
  images.unshift({ idiom: 'universal', scale: '1x' });
  await saveJson({ images, info: { author: 'xcode', version: 1 } }, path.join(ios, 'LaunchIcon.imageset/Contents.json'));

  // The night-sea colour behind the launch screen, the same as Android's window background.
  await saveJson({
    colors: [{ idiom: 'universal', color: { 'color-space': 'srgb', components: { red: '0x0A', green: '0x1A', blue: '0x3F', alpha: '1.000' } } }],
    info: { author: 'xcode', version: 1 },
  }, path.join(ios, 'LaunchBackground.colorset/Contents.json'));
}

main().catch((error) => { console.error(error); process.exit(1); });
