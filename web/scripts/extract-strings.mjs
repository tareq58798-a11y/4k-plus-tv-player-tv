/**
 * Turns the Android app's translations into a TypeScript module.
 *
 * This is what "shares everything with the Android TV app" actually means for text: the eight
 * languages are not copied into this project, they are read out of app/src/main/res every time it
 * is built. Fix a translation on Android and it is fixed on Samsung at the next build, and there
 * is no second set of strings for the two to disagree about.
 *
 * Android's XML escaping is not the web's. \' \" \n and \\ are Android escapes, &#8230; and
 * friends are XML entities, and %1$s is a positional argument - all of which have to be turned
 * into something a browser can render before the text is ever put on screen.
 */
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const RES = join(here, '..', '..', 'app', 'src', 'main', 'res');
const OUT = join(here, '..', 'src', 'shared', 'strings.generated.ts');

/** values -> en, values-ar -> ar. Anything else in res/ is not a translation. */
function localeOf(dir) {
  if (dir === 'values') return 'en';
  const match = /^values-([a-z]{2})(?:-r([A-Z]{2}))?$/.exec(dir);
  if (!match) return null;
  return match[2] ? `${match[1]}-${match[2]}` : match[1];
}

function decode(raw) {
  return raw
    // XML entities first: the numeric ones carry real characters (&#8230; is an ellipsis) and
    // would otherwise survive into the markup as literal text.
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&')
    // Then Android's own escapes. \\ is handled last so an escaped backslash does not eat the
    // character after it.
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, '\t')
    .replace(/\\'/g, "'")
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, '\\')
    .trim();
}

/** %1$s / %2$d become {1} / {2}; a bare %s becomes {1}, {2}... in the order it appears. */
function toPlaceholders(text) {
  let bare = 0;
  return text
    .replace(/%(\d+)\$[sd]/g, (_, index) => `{${index}}`)
    .replace(/%[sd]/g, () => `{${++bare}}`);
}

const STRING = /<string\s+name="([^"]+)"([^>]*)>([\s\S]*?)<\/string>/g;

function parse(xml) {
  const out = {};
  for (const [, name, attrs, body] of xml.matchAll(STRING)) {
    // translatable="false" marks values that are not text for a reader - keys, formats, URLs.
    // They still belong in the base language and nowhere else.
    if (/translatable\s*=\s*"false"/.test(attrs)) continue;
    out[name] = toPlaceholders(decode(body));
  }
  return out;
}

const locales = {};
for (const dir of readdirSync(RES, { withFileTypes: true })) {
  if (!dir.isDirectory()) continue;
  const locale = localeOf(dir.name);
  if (!locale) continue;
  let xml;
  try {
    xml = readFileSync(join(RES, dir.name, 'strings.xml'), 'utf8');
  } catch {
    continue;
  }
  locales[locale] = parse(xml);
}

const base = locales.en;
if (!base) throw new Error('No res/values/strings.xml found - nothing to extract.');

const keys = Object.keys(base).sort();
const others = Object.keys(locales).filter((l) => l !== 'en').sort();

/** Report what each translation is missing rather than filling it in silently. */
const gaps = others
  .map((locale) => {
    const missing = keys.filter((key) => locales[locale][key] === undefined).length;
    return missing ? ` *  ${locale}: ${missing} of ${keys.length} untranslated, falling back to English` : null;
  })
  .filter(Boolean);

const lit = (value) => JSON.stringify(value);

const body = `/*
 * GENERATED - do not edit.
 *
 * Written by scripts/extract-strings.mjs from app/src/main/res/values[-locale]/strings.xml.
 * Run \`npm run shared\` to regenerate. Edit the Android XML, never this file.
 *
 * ${keys.length} strings, ${1 + others.length} languages.
${gaps.length ? gaps.join('\n') + '\n' : ' *  Every language is complete.\n'} */

export type StringKey =
${keys.map((k) => `  | ${lit(k)}`).join('\n')};

export type StringTable = Partial<Record<StringKey, string>>;

export const EN: Record<StringKey, string> = {
${keys.map((k) => `  ${lit(k)}: ${lit(base[k])},`).join('\n')}
};

${others
  .map(
    (locale) => `export const ${locale.toUpperCase().replace('-', '_')}: StringTable = {
${keys
  .filter((k) => locales[locale][k] !== undefined)
  .map((k) => `  ${lit(k)}: ${lit(locales[locale][k])},`)
  .join('\n')}
};`,
  )
  .join('\n\n')}

export const TABLES: Record<string, StringTable> = {
  en: EN,
${others.map((l) => `  ${lit(l)}: ${l.toUpperCase().replace('-', '_')},`).join('\n')}
};

/** Languages that read right to left, which the layout mirrors for. */
export const RTL_LOCALES = new Set(['ar', 'ur', 'fa', 'he']);
`;

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, body, 'utf8');
console.log(
  `strings: ${keys.length} keys, ${1 + others.length} languages -> src/shared/strings.generated.ts`,
);
for (const gap of gaps) console.log(gap.replace(' * ', '  '));
