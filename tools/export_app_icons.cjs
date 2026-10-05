// Platform exports of the clean vector version of the ImageGen concept.
// Run from the repository root; artwork and reference colours live in assets/branding.
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('../web/node_modules/sharp');

const root = path.resolve(__dirname, '..');
const brand = path.join(root, 'assets/branding');
const android = path.join(root, 'app/src/main/res');
const ios = path.join(root, 'iosApp/CatchCheckNZ/Resources/Assets.xcassets');

async function save(buffer, filename) {
  await fs.mkdir(path.dirname(filename), { recursive: true });
  await fs.writeFile(filename, buffer);
}

async function main() {
  const seaSource = path.join(brand, 'fishing-days-sea-background.svg');
  async function seaBackground(size) {
    return sharp(seaSource).resize(size, size).removeAlpha().png().toBuffer();
  }
  await save(await seaBackground(1024), path.join(brand, 'fishing-days-sea-background.png'));
  const source = await sharp(path.join(brand, 'fishing-days-mark.svg')).resize(2048, 2048).png().toBuffer();
  await save(source, path.join(brand, 'fishing-days-mark.png'));
  const { data, info } = await sharp(source).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  let left = info.width, top = info.height, right = 0, bottom = 0;
  for (let y = 0; y < info.height; y++) for (let x = 0; x < info.width; x++) {
    if (data[(y * info.width + x) * 4 + 3] > 16) {
      left = Math.min(left, x); right = Math.max(right, x);
      top = Math.min(top, y); bottom = Math.max(bottom, y);
    }
  }
  if (right <= left || bottom <= top) throw new Error('The source must contain a transparent foreground mark.');
  const width = right - left + 1, height = bottom - top + 1;
  const mark = await sharp(source).extract({ left, top, width, height }).png().toBuffer();
  let radius = 0;
  for (let y = top; y <= bottom; y++) for (let x = left; x <= right; x++) {
    if (data[(y * info.width + x) * 4 + 3] > 16) {
      radius = Math.max(radius, Math.hypot(x - (left + right) / 2, y - (top + bottom) / 2));
    }
  }

  async function foreground(size, safeRadius) {
    const scale = safeRadius / (radius + 2);
    const resized = await sharp(mark).resize(Math.round(width * scale), Math.round(height * scale)).png().toBuffer();
    return sharp({ create: { width: size, height: size, channels: 4, background: '#00000000' } })
      .composite([{ input: resized, gravity: 'center' }]).png().toBuffer();
  }
  async function tile(size) {
    const fg = await foreground(size, size * 0.40);
    return sharp(await seaBackground(size)).composite([{ input: fg }]).removeAlpha().png().toBuffer();
  }
  async function masked(buffer, shape) {
    const { width: size } = await sharp(buffer).metadata();
    const shapes = {
      circle: `<circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="white"/>`,
      rounded: `<rect width="${size}" height="${size}" rx="${size * .225}" fill="white"/>`,
      squircle: `<path d="M ${size / 2} 0 C ${size} 0 ${size} 0 ${size} ${size / 2} S ${size} ${size} ${size / 2} ${size} S 0 ${size} 0 ${size / 2} S 0 0 ${size / 2} 0 Z" fill="white"/>`,
      teardrop: `<path d="M ${size / 2} 0 H ${size} V ${size / 2} A ${size / 2} ${size / 2} 0 1 1 ${size / 2} 0 Z" fill="white"/>`,
    };
    return sharp(buffer).ensureAlpha().composite([{ input: Buffer.from(`<svg width="${size}" height="${size}">${shapes[shape]}</svg>`), blend: 'dest-in' }]).png().toBuffer();
  }

  const master = await tile(1024);
  await save(master, path.join(brand, 'fishing-days-icon-1024.png'));
  await save(await tile(512), path.join(brand, 'fishing-days-google-play-512.png'));

  for (const [density, factor] of [['mdpi', 1], ['hdpi', 1.5], ['xhdpi', 2], ['xxhdpi', 3], ['xxxhdpi', 4]]) {
    const size = 108 * factor;
    // Everything stays inside the 66 dp safe circle, including animation margin.
    const fg = await foreground(size, 31.5 * factor);
    await save(fg, path.join(android, `drawable-${density}`, 'fishing_days_foreground.png'));
    await save(await seaBackground(size), path.join(android, `drawable-${density}`, 'fishing_days_background.png'));
    const raw = await sharp(fg).ensureAlpha().raw().toBuffer();
    for (let i = 0; i < raw.length; i += 4) {
      // The black eye is the only black region of the mark; keep it as a cutout.
      if (raw[i] < 45 && raw[i + 1] < 45 && raw[i + 2] < 45) raw[i + 3] = 0;
      raw[i] = raw[i + 1] = raw[i + 2] = 255;
    }
    const mono = await sharp(raw, { raw: { width: size, height: size, channels: 4 } }).png().toBuffer();
    await save(mono, path.join(android, `drawable-${density}`, 'fishing_days_monochrome.png'));
    const legacy = await tile(48 * factor);
    await save(legacy, path.join(android, `mipmap-${density}`, 'ic_launcher.png'));
    await save(await masked(legacy, 'circle'), path.join(android, `mipmap-${density}`, 'ic_launcher_round.png'));
  }

  const images = [];
  const slots = [
    ['iphone', '20x20', ['2x', '3x']], ['iphone', '29x29', ['2x', '3x']],
    ['iphone', '40x40', ['2x', '3x']], ['iphone', '60x60', ['2x', '3x']],
    ['ipad', '20x20', ['1x', '2x']], ['ipad', '29x29', ['1x', '2x']],
    ['ipad', '40x40', ['1x', '2x']], ['ipad', '76x76', ['1x', '2x']],
    ['ipad', '83.5x83.5', ['2x']], ['ios-marketing', '1024x1024', ['1x']],
  ];
  const outputs = new Map();
  for (const [idiom, size, scales] of slots) for (const scale of scales) {
    const pixels = Number(size.split('x')[0]) * Number(scale[0]);
    const filename = `FishingDays-${pixels}.png`;
    if (!outputs.has(pixels)) {
      const buffer = await tile(pixels);
      outputs.set(pixels, buffer);
      await save(buffer, path.join(ios, 'AppIcon.appiconset', filename));
    }
    images.push({ filename, idiom, scale, size });
  }
  const assetInfo = { info: { author: 'xcode', version: 1 } };
  await save(JSON.stringify(assetInfo, null, 2) + '\n', path.join(ios, 'Contents.json'));
  await save(JSON.stringify({ images, ...assetInfo }, null, 2) + '\n', path.join(ios, 'AppIcon.appiconset/Contents.json'));

  await save(await tile(256), path.join(root, 'web/public/fishing-days-icon.png'));
  await save(await tile(192), path.join(root, 'web/src/app/icon.png'));
  await save(await tile(180), path.join(root, 'web/src/app/apple-icon.png'));
  // An ICO directory containing standard PNG payloads for modern browsers.
  const faviconSizes = [16, 32, 48];
  const payloads = await Promise.all(faviconSizes.map(async size =>
    sharp(await tile(size)).ensureAlpha().png().toBuffer()));
  const header = Buffer.alloc(6 + 16 * payloads.length);
  header.writeUInt16LE(1, 2); header.writeUInt16LE(payloads.length, 4);
  let offset = header.length;
  payloads.forEach((payload, index) => {
    const entry = 6 + index * 16;
    header[entry] = header[entry + 1] = faviconSizes[index];
    header.writeUInt16LE(1, entry + 4); header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(payload.length, entry + 8); header.writeUInt32LE(offset, entry + 12);
    offset += payload.length;
  });
  await save(Buffer.concat([header, ...payloads]), path.join(root, 'web/src/app/favicon.ico'));

  const previewText = `<svg width="1440" height="940"><rect width="1440" height="940" fill="#F2F3F7"/><g font-family="Arial, sans-serif" fill="#111827"><text x="72" y="88" font-size="46" font-weight="bold">Fishing Days NZ</text><text x="72" y="130" font-size="21" fill="#596275">White fish and silver-fern tail. Very light sea blue with gentle ripples.</text><text x="72" y="484" font-size="20">iOS / iPadOS</text><text x="344" y="484" font-size="20">Android circle</text><text x="618" y="484" font-size="20">Android squircle</text><text x="926" y="484" font-size="20">Android teardrop</text><text x="72" y="584" font-size="25" font-weight="bold">Small-size check</text><text x="72" y="630" font-size="19" fill="#596275">Actual 32, 48 and 64 pixel icons</text><text x="668" y="584" font-size="25" font-weight="bold">Android themed icon</text><text x="668" y="630" font-size="19" fill="#596275">One clear silhouette for the device's theme</text><text x="72" y="870" font-size="17" fill="#596275">PALE SEA  #D0EEF9        ROYAL BLUE  #071FB3        WHITE  #FFFFFF        RED  #D10000</text></g></svg>`;
  const composite = [];
  for (const [shape, x] of [['rounded', 72], ['circle', 344], ['squircle', 618], ['teardrop', 926]]) {
    let icon;
    if (shape === 'rounded') icon = await tile(228);
    else {
      // Android clips the central 72 dp viewport of its 108 dp layers.
      const adaptive = await foreground(432, 126);
      const adaptiveTile = await sharp(await seaBackground(432)).composite([{ input: adaptive }]).png().toBuffer();
      icon = await sharp(adaptiveTile).extract({ left: 72, top: 72, width: 288, height: 288 }).resize(228).png().toBuffer();
    }
    composite.push({ input: await masked(icon, shape), left: x, top: 210 });
  }
  for (const [size, x] of [[32, 80], [48, 170], [64, 278]]) {
    composite.push({ input: await masked(await tile(size), 'rounded'), left: x, top: 690 });
  }
  const mono = await sharp(path.join(android, 'drawable-xxxhdpi/fishing_days_monochrome.png'))
    .extract({ left: 72, top: 72, width: 288, height: 288 }).resize(116).png().toBuffer();
  const theme = await sharp({ create: { width: 116, height: 116, channels: 4, background: '#373A68' } }).composite([{ input: mono }]).png().toBuffer();
  composite.push({ input: await masked(theme, 'circle'), left: 684, top: 668 });
  await save(await sharp(Buffer.from(previewText)).composite(composite).png().toBuffer(), path.join(brand, 'fishing-days-icon-preview.png'));

  const comparisonText = `<svg width="1040" height="660"><rect width="1040" height="660" fill="#EEF1F6"/><g font-family="Arial, sans-serif" fill="#111827"><text x="64" y="74" font-size="35" font-weight="bold">Fishing Days NZ — a pale ocean background</text><text x="64" y="114" font-size="19" fill="#596275">White body restored. Blue belly stays clear against gentle sea ripples.</text><text x="155" y="530" font-size="23" font-weight="bold">Previous · white background</text><text x="586" y="530" font-size="23" font-weight="bold">Updated · pale sea blue</text><text x="64" y="610" font-size="18" fill="#596275">White silver-fern tail · Royal-blue belly · Red fin · Subtle sea-blue edge</text></g></svg>`;
  const previous = await sharp(path.join(brand, 'fishing-days-icon-white-previous.png')).resize(320).png().toBuffer();
  await save(await sharp(Buffer.from(comparisonText)).composite([
    { input: await masked(previous, 'rounded'), left: 154, top: 168 },
    { input: await masked(await tile(320), 'rounded'), left: 586, top: 168 },
  ]).png().toBuffer(), path.join(brand, 'fishing-days-icon-background-comparison.png'));
  console.log('Exported Android adaptive + monochrome icons, iPhone/iPad asset catalog, store icons, web icons and shape preview.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
