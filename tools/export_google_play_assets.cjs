// Render store assets from vector artwork and actual Android screenshots.
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('../web/node_modules/sharp');

async function main() {
  const root = path.resolve(__dirname, '..');
  const output = path.join(root, 'assets/google-play');
  await fs.mkdir(output, { recursive: true });
  await sharp(path.join(root, 'assets/branding/fishing-days-google-play-512.png'))
    .ensureAlpha().png().toFile(path.join(output, 'icon-512.png'));
  await sharp(path.join(output, 'feature-graphic.svg')).removeAlpha().png()
    .toFile(path.join(output, 'feature-graphic-1024x500.png'));
  for (const [index, source] of process.argv.slice(2).entries()) {
    const metadata = await sharp(source).metadata();
    if (Math.max(metadata.width, metadata.height) > Math.min(metadata.width, metadata.height) * 2) {
      throw new Error('Capture screenshots at a supported aspect ratio, for example 1080x1920.');
    }
    await sharp(source).removeAlpha().png().toFile(path.join(output, `phone-${index + 1}.png`));
  }
  console.log('Google Play artwork exported to assets/google-play');
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
