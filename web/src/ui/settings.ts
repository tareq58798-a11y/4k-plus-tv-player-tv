/**
 * Language, and the handful of things a viewer may need to undo.
 *
 * Small on purpose. The television app's settings page has a great deal more in it, and most of
 * it - playback buffering, subtitle appearance, aspect ratio, parental controls - belongs with
 * the features it configures, which are not built here yet. What is here is what the app already
 * does and a viewer might need to change.
 */
import { availableLocales, locale, setLocale, t } from '../shared/i18n';
import { focus, pushKeyHandler } from './focus';

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, string> = {},
  ...children: (Node | string)[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) node.setAttribute(name, value);
  for (const child of children) node.append(child);
  return node;
}

/**
 * Each language in its own words.
 *
 * A list that says "Arabic" and "Russian" in English is no use to somebody who has opened it
 * because they cannot read English. Intl.DisplayNames gives the endonym where the set supports
 * it; the table is the fallback, and on an engine without Intl it is the whole answer.
 */
const ENDONYM: Record<string, string> = {
  en: 'English',
  ar: 'العربية',
  es: 'Español',
  fr: 'Français',
  hi: 'हिन्दी',
  ru: 'Русский',
  tr: 'Türkçe',
  ur: 'اردو',
};

function languageName(code: string): string {
  try {
    const names = new (Intl as unknown as { DisplayNames: new (l: string[], o: object) => { of(c: string): string } })
      .DisplayNames([code], { type: 'language' });
    return names.of(code) || ENDONYM[code] || code;
  } catch {
    return ENDONYM[code] || code;
  }
}

export interface SettingsOptions {
  /** Redraws whatever is underneath, because changing language changes every word on screen. */
  onLanguageChanged: () => void;
  onSignOut: () => void;
  onClearCache: () => void;
  onBack: () => void;
}

export function renderSettings(host: HTMLElement, options: SettingsOptions): void {
  const page = el('div', { class: 'settings' });

  const languages = el('div', { class: 'settings-group', 'data-focus-group': 'languages' });
  languages.append(el('h3', { class: 'section-title' }, t('cd_language')));
  for (const code of availableLocales()) {
    const row = el(
      'div',
      {
        class: 'settings-row',
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': `lang-${code}`,
        'aria-selected': String(code === locale()),
      },
      languageName(code),
    );
    row.addEventListener('click', () => {
      setLocale(code);
      options.onLanguageChanged();
    });
    languages.append(row);
  }

  const actions = el('div', { class: 'settings-group', 'data-focus-group': 'settings-actions' });
  actions.append(el('h3', { class: 'section-title' }, t('settings_title')));

  const clear = el(
    'div',
    { class: 'settings-row', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'clear-cache' },
    t('refresh_playlist'),
  );
  clear.addEventListener('click', options.onClearCache);

  const signOut = el(
    'div',
    { class: 'settings-row danger', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'sign-out' },
    t('remove_playlist_action'),
  );
  signOut.addEventListener('click', options.onSignOut);

  actions.append(clear, signOut);
  page.append(languages, actions);
  host.append(el('div', { class: 'browser-title' }, t('settings_title')), page);
  focus(languages.querySelector<HTMLElement>('[data-focus]'));

  const release = pushKeyHandler((key) => {
    if (key !== 'back') return false;
    release();
    options.onBack();
    return true;
  });
}
