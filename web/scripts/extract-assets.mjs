/**
 * Copies the artwork out of the Android app, for the same reason as the strings and the tokens:
 * one source, no second copy to fall out of date.
 *
 * Only the pieces the web app actually draws. Android carries several densities of each launcher
 * icon, which a television has no use for - it renders one fixed 1920x1080 canvas.
 */
import { copyFileSync, mkdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const RES = join(here, '..', '..', 'app', 'src', 'main', 'res');
const OUT = join(here, '..', 'public');

const WANTED = [
  // The app's own backdrop - the astronaut. Shown on Home until the viewer moves onto a title,
  // and behind every page that has no artwork of its own.
  ['drawable-nodpi/bg_app_default.png', 'bg_app_default.png'],
  ['drawable-nodpi/brand_logo_dark.png', 'brand_logo.png'],
];

mkdirSync(OUT, { recursive: true });
for (const [from, to] of WANTED) {
  const source = join(RES, ...from.split('/'));
  copyFileSync(source, join(OUT, to));
  const kb = Math.round(statSync(source).size / 1024);
  console.log(`asset: ${to} (${kb} KB) <- res/${from}`);
}
