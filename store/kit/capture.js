/* Play Store capture — renders each panel to PNG at exact store dimensions.
   Usage: node capture.js  (run once:  npm i --no-save playwright && npx playwright install chromium) */
const path = require('path');

let chromium;
try { ({ chromium } = require('playwright')); }
catch (e) {
  console.error('Playwright not installed. Run:\n  npm i --no-save playwright\n  npx playwright install chromium');
  process.exit(1);
}

const OUT = path.join(__dirname, 'out');
const SHOTS = [
  ['s1', 1080, 1920], ['s2', 1080, 1920], ['s3', 1080, 1920],
  ['s4', 1080, 1920], ['s5', 1080, 1920], ['s6', 1080, 1920],
  ['feature', 1024, 500],
];

(async () => {
  const { mkdirSync } = await import('fs');
  mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage();

  for (const [id, w, h] of SHOTS) {
    await page.setViewportSize({ width: w, height: h });
    await page.goto('file://' + path.join(__dirname, id + '.html'));
    await page.waitForTimeout(150);
    const el = page.locator('#' + id);
    await el.screenshot({ path: path.join(OUT, id + '.png') });
    console.log('rendered', id + '.png', w + 'x' + h);
  }

  await browser.close();
  console.log('done ->', OUT);
})();
