/**
 * Builds the web app and lays it out as a Tizen widget, ready to sign and install.
 *
 * A .wgt is a zip of the built files with config.xml and the icon beside them at the top level -
 * not in a subfolder, which is the usual first mistake and produces a package a set accepts and
 * then fails to launch.
 *
 * Signing is left to Samsung's own tool. It needs a certificate profile from Certificate Manager,
 * and a set will refuse a widget whose signature does not match the package id in config.xml, so
 * doing it by hand here would only invent a second way to get it wrong.
 *
 *   node scripts/package-tizen.mjs               stage only
 *   node scripts/package-tizen.mjs --sign NAME   stage, then package and sign with that profile
 */
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const dist = join(root, 'dist');
const staging = join(root, 'tizen-package');

if (!existsSync(dist)) {
  console.error('dist/ is missing. Run `npm run build` first.');
  process.exit(1);
}

rmSync(staging, { recursive: true, force: true });
mkdirSync(staging, { recursive: true });
// Source maps stay in dist/ and out of the widget. They are worth having when debugging against
// a set over the remote inspector, but they are 2.5 MB of the package and a television only ever
// reads them with devtools open.
cpSync(dist, staging, { recursive: true, filter: (from) => !from.endsWith('.map') });
cpSync(join(root, 'tizen', 'config.xml'), join(staging, 'config.xml'));
cpSync(join(root, 'tizen', 'icon.png'), join(staging, 'icon.png'));

// The placeholder prefix would produce a widget that installs nowhere, so it is worth saying so
// here rather than after a failed install on the set.
const config = readFileSync(join(staging, 'config.xml'), 'utf8');
if (config.includes('4KPlusTVXX')) {
  console.warn(
    '\n  ! config.xml still has the placeholder package prefix "4KPlusTVXX".\n' +
      '    Samsung issues the real ten characters when the app is registered in Seller Office,\n' +
      '    and a television will only install a widget whose certificate matches it.\n',
  );
}

console.log(`staged: ${staging}`);

const signIndex = process.argv.indexOf('--sign');
if (signIndex === -1) {
  console.log('\nTo package and sign:\n  node scripts/package-tizen.mjs --sign <profile-name>');
  process.exit(0);
}

const profile = process.argv[signIndex + 1];
if (!profile) {
  console.error('--sign needs a certificate profile name, as shown in Tizen Certificate Manager.');
  process.exit(1);
}

try {
  execFileSync('tizen', ['package', '-t', 'wgt', '-s', profile, '--', staging], {
    stdio: 'inherit',
    shell: true,
  });
  console.log(`\nPackaged. Install with:\n  sdb install ${join(staging, '*.wgt')}`);
} catch {
  console.error(
    '\n`tizen` is not on PATH. It lives in <tizen-studio>/tools/ide/bin - add that folder to PATH,\n' +
      'or call it with its full path.',
  );
  process.exit(1);
}
