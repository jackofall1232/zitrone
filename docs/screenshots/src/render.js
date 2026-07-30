#!/usr/bin/env node
/*
 * Renders the generated pages (pages/*.html) to the Play-Store-spec PNGs in
 * docs/screenshots/: portrait 1080x1920 (9:16), 24-bit PNG, no alpha channel.
 *
 * Geometry: viewport 432x768 CSS px at deviceScaleFactor 2.5 => 1080x1920
 * physical px, i.e. 1 CSS px = 1 dp on a 2.5x-density (~"xxhdpi") phone.
 *
 * Usage: NODE_PATH=<dir with puppeteer/sharp> node render.js
 */
'use strict';

const path = require('path');
const puppeteer = require('puppeteer');
const sharp = require('sharp');

const PAGES = path.join(__dirname, 'pages');
const OUT = path.join(__dirname, '..');

/** Per-page scroll positioning, evaluated in the page after load. */
const SCROLL = {
  '06-settings-network.html': `
    const col = document.getElementById('scrollcol');
    const net = document.getElementById('sec-network');
    col.style.transform = 'translateY(' + (-(net.offsetTop - 6)) + 'px)';
  `,
  '07-settings-privacy.html': `
    const col = document.getElementById('scrollcol');
    const notif = document.getElementById('sec-notifications');
    const overflow = Math.max(0, notif.offsetTop - 768 + 12);
    col.style.transform = 'translateY(' + (-overflow) + 'px)';
  `,
};

const SHOTS = [
  '01-chat-list.html',
  '02-chat.html',
  '03-lock-screen.html',
  '04-add-contact.html',
  '05-lemon-drop.html',
  '06-settings-network.html',
  '07-settings-privacy.html',
  '08-onboarding.html',
];

(async () => {
  const browser = await puppeteer.launch({
    args: ['--no-sandbox', '--font-render-hinting=none', '--allow-file-access-from-files', '--force-color-profile=srgb'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 432, height: 768, deviceScaleFactor: 2.5 });

  for (const name of SHOTS) {
    await page.goto('file://' + path.join(PAGES, name), { waitUntil: 'networkidle0' });
    await page.evaluate(async () => { await document.fonts.ready; });
    await page.waitForFunction('window.__wmDone === true', { timeout: 15000 });
    if (SCROLL[name]) await page.evaluate(SCROLL[name]);
    // Let the compositor settle after the watermark/backgrounds land.
    await new Promise((r) => setTimeout(r, 150));
    const raw = await page.screenshot({ type: 'png' });
    const outName = name.replace(/\.html$/, '.png');
    // Play spec: 24-bit PNG, no alpha. Flatten onto the app background.
    await sharp(raw)
      .flatten({ background: '#0D0C00' })
      .removeAlpha()
      .png({ compressionLevel: 9 })
      .toFile(path.join(OUT, outName));
    console.log('rendered', outName);
  }
  await browser.close();

  for (const name of SHOTS) {
    const outName = name.replace(/\.html$/, '.png');
    const meta = await sharp(path.join(OUT, outName)).metadata();
    console.log(outName, meta.width + 'x' + meta.height, 'channels=' + meta.channels, 'alpha=' + meta.hasAlpha);
  }
})();
