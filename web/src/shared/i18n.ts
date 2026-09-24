/**
 * Text lookup over the tables extracted from the Android app.
 *
 * A key missing from a translation falls back to English rather than showing the key itself: an
 * untranslated line is a small blemish, a raw identifier on screen is a bug the viewer can see.
 */
import { EN, RTL_LOCALES, TABLES, type StringKey } from './strings.generated';
import { LOCAL_EN, type LocalStringKey } from './strings.local';

let current = 'en';

export function availableLocales(): string[] {
  return Object.keys(TABLES);
}

export function setLocale(locale: string): void {
  current = TABLES[locale] ? locale : 'en';
  document.documentElement.lang = current;
  document.documentElement.dir = isRtl() ? 'rtl' : 'ltr';
}

export function locale(): string {
  return current;
}

/**
 * [tag] with Latin digits, for formatting dates and times.
 *
 * Arabic's own numbering in the browser's date and time formatting is Arabic-Indic, so the clock
 * in the bar, programme times and dates came out as ١١:٠٨ once the language was Arabic, while
 * every other number in the app - counts, the player's clocks, versions - stayed 0-9. The owner
 * asked for English digits in every language. The Unicode locale extension "nu-latn" asks the
 * formatter for 0-9 and changes nothing else: month and day names stay in the viewer's language.
 */
export function latinDigits(tag: string): string {
  return tag.includes('-u-') ? `${tag}-nu-latn` : `${tag}-u-nu-latn`;
}

export function isRtl(): boolean {
  return RTL_LOCALES.has(current);
}

/**
 * `t('key')`, with `{1}`, `{2}`... filled from the arguments in order.
 *
 * The shared table wins over the local one, so if a string later gains an Android counterpart the
 * translated version takes over on its own and the English stand-in stops being used.
 */
export function t(key: StringKey | LocalStringKey, ...args: (string | number)[]): string {
  const table = TABLES[current];
  const shared = key as StringKey;
  const template =
    (table && table[shared]) || EN[shared] || LOCAL_EN[key as LocalStringKey] || key;
  if (!args.length) return template;
  return template.replace(/\{(\d+)\}/g, (match, index: string) => {
    const value = args[Number(index) - 1];
    return value === undefined ? match : String(value);
  });
}
