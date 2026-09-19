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
