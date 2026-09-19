/**
 * Turns the Android app's design tokens into CSS custom properties and a TypeScript module.
 *
 * Same idea as the strings: colours, spacing, radii and timings are read out of Tokens.kt rather
 * than transcribed, so the two apps cannot drift apart on what "accent" or "card radius" means.
 *
 * Android colours are 0xAARRGGBB - alpha first. CSS #rrggbbaa puts it last, so every value has to
 * be reordered rather than pasted across; getting that wrong silently produces the right hue at
 * the wrong opacity, which is exactly the kind of thing nobody notices until the whole app looks
 * slightly washed out.
 *
 * Sizes are in dp against Android's 160dpi baseline. A 1080p television is 1920 CSS pixels wide
 * for the layouts these numbers were measured on, so dp maps to px one for one here.
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = join(
  here, '..', '..', 'app', 'src', 'main', 'java', 'com', 'fourkplus', 'tvplayer', 'ui', 'design', 'Tokens.kt',
);
const OUT_TS = join(here, '..', 'src', 'shared', 'tokens.generated.ts');
const OUT_CSS = join(here, '..', 'src', 'shared', 'tokens.generated.css');

const kt = readFileSync(SRC, 'utf8');

/** 0xAARRGGBB -> #rrggbb, or #rrggbbaa when it is not fully opaque. */
function cssColor(hex) {
  const value = hex.replace(/^0x/i, '').padStart(8, 'F');
  const a = value.slice(0, 2);
  const rgb = value.slice(2).toLowerCase();
  return a.toLowerCase() === 'ff' ? `#${rgb}` : `#${rgb}${a.toLowerCase()}`;
}

/** PageTop -> page-top, for a CSS custom property name. */
const kebab = (name) => name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();

function section(objectName) {
  const match = new RegExp(`object ${objectName} \\{([\\s\\S]*?)\\n\\}`).exec(kt);
  if (!match) throw new Error(`object ${objectName} not found in Tokens.kt`);
  return match[1];
}

const tone = section('Tone');
const dims = section('Dims');
const motion = section('Motion');

const colors = [...tone.matchAll(/val (\w+) = Color\((0x[0-9A-Fa-f]{8})\)/g)].map(([, name, hex]) => ({
  name,
  css: cssColor(hex),
}));

/** ActionGradient / CategoryGradient: listOf(Color(..), Color(..)). Greedy to the last paren on
 *  the line, because the Color() calls inside carry parens of their own. */
const gradients = [...tone.matchAll(/val (\w+) = listOf\((.+)\)/g)].map(([, name, body]) => ({
  name,
  stops: [...body.matchAll(/Color\((0x[0-9A-Fa-f]{8})\)/g)].map(([, hex]) => cssColor(hex)),
}));

const sizes = [...dims.matchAll(/val (\w+): Dp = (\d+(?:\.\d+)?)\.dp/g)].map(([, name, value]) => ({
  name,
  px: Number(value),
}));

/* Anchored to end of line so `const val PosterFocusScale = 1.06f` is not read as the integer 1 -
   it is a scale factor, not a duration, and it is emitted separately below. */
const durations = [...motion.matchAll(/\bval (\w+) = (\d+)[ \t]*$/gm)].map(([, name, value]) => ({
  name,
  ms: Number(value),
}));
const debounce = /val BackdropDebounceMs = (\d+)L/.exec(motion);
if (debounce) durations.push({ name: 'BackdropDebounceMs', ms: Number(debounce[1]) });

const scale = /const val PosterFocusScale = ([\d.]+)f/.exec(motion);

/** CubicBezierEasing(a, b, c, d) -> cubic-bezier(a, b, c, d). */
const easings = [...motion.matchAll(/val (\w+): Easing = CubicBezierEasing\(([^)]+)\)/g)].map(
  ([, name, args]) => ({
    name,
    css: `cubic-bezier(${args.split(',').map((n) => Number(n.replace(/f/g, '').trim())).join(', ')})`,
  }),
);

const header = `/*
 * GENERATED - do not edit.
 * Written by scripts/extract-tokens.mjs from
 * app/src/main/java/com/fourkplus/tvplayer/ui/design/Tokens.kt.
 * Run \`npm run shared\` to regenerate. Edit the Kotlin, never this file.
 */`;

const css = `${header}
:root {
${colors.map((c) => `  --tone-${kebab(c.name)}: ${c.css};`).join('\n')}
${gradients
  .map((g) => `  --tone-${kebab(g.name)}: linear-gradient(90deg, ${g.stops.join(', ')});`)
  .join('\n')}

${sizes.map((s) => `  --dim-${kebab(s.name)}: ${s.px}px;`).join('\n')}

${durations.map((d) => `  --motion-${kebab(d.name)}: ${d.ms}ms;`).join('\n')}
${easings.map((e) => `  --motion-${kebab(e.name)}: ${e.css};`).join('\n')}
  --motion-poster-focus-scale: ${scale ? scale[1] : '1.06'};
}
`;

const ts = `${header}

export const Tone = {
${colors.map((c) => `  ${c.name}: '${c.css}',`).join('\n')}
} as const;

export const Gradient = {
${gradients.map((g) => `  ${g.name}: ['${g.stops.join("', '")}'],`).join('\n')}
} as const;

/** Android dp, which is one CSS pixel each at the 1080p layout these were measured for. */
export const Dims = {
${sizes.map((s) => `  ${s.name}: ${s.px},`).join('\n')}
} as const;

export const Motion = {
${durations.map((d) => `  ${d.name}: ${d.ms},`).join('\n')}
  PosterFocusScale: ${scale ? scale[1] : '1.06'},
${easings.map((e) => `  ${e.name}: '${e.css}',`).join('\n')}
} as const;
`;

mkdirSync(dirname(OUT_TS), { recursive: true });
writeFileSync(OUT_TS, ts, 'utf8');
writeFileSync(OUT_CSS, css, 'utf8');
console.log(
  `tokens: ${colors.length} colours, ${gradients.length} gradients, ${sizes.length} sizes, ` +
    `${durations.length} durations -> src/shared/tokens.generated.{ts,css}`,
);
