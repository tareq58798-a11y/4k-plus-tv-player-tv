/**
 * Language, and the handful of things a viewer may need to undo.
 *
 * Small on purpose. The television app's settings page has a great deal more in it, and most of
 * it - playback buffering, subtitle appearance, aspect ratio, parental controls - belongs with
 * the features it configures, which are not built here yet. What is here is what the app already
 * does and a viewer might need to change.
 */
import { availableLocales, locale, setLocale, t } from '../shared/i18n';
import { backgroundMode, setBackgroundMode } from '../shared/preferences';
import { cryptoAvailable, hasPin, parental, update } from '../shared/parental';
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
  onSetPin: () => void;
  onRemovePin: () => void;
  onLockCategories: () => void;
  /** Redraws this page after a toggle, so the row shows its new state. */
  onChanged: () => void;
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

  // Appearance. Two rows rather than a switch, because a switch on a television has to say what
  // it is a switch *for*, and by the time that label is written the two named choices are shorter
  // and clearer than the question.
  const appearance = el('div', { class: 'settings-group', 'data-focus-group': 'appearance' });
  appearance.append(el('h3', { class: 'section-title' }, t('settings_appearance')));
  const currentMode = backgroundMode();
  for (const mode of ['modern', 'classic'] as const) {
    const row = el(
      'div',
      {
        class: 'settings-row',
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': `background-${mode}`,
        'aria-selected': String(mode === currentMode),
      },
      mode === 'classic' ? t('background_classic') : t('background_modern'),
    );
    row.append(
      el('div', { class: 'settings-note' }, mode === 'classic' ? t('background_classic_desc') : t('background_modern_desc')),
    );
    row.addEventListener('click', () => {
      setBackgroundMode(mode);
      options.onChanged();
    });
    appearance.append(row);
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

  const guard = el('div', { class: 'settings-group', 'data-focus-group': 'parental' });
  guard.append(el('h3', { class: 'section-title' }, t('settings_parental_controls')));

  if (!cryptoAvailable()) {
    // Said plainly rather than offering a control that would store the digits in the clear.
    guard.append(el('div', { class: 'settings-note' }, t('epg_no_info')));
  } else {
    const state = parental();
    const pinRow = el(
      'div',
      { class: 'settings-row', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'pin-set' },
      hasPin() ? t('change_pin_desc') : t('create_parental_pin'),
    );
    pinRow.addEventListener('click', options.onSetPin);
    guard.append(pinRow);

    if (hasPin()) {
      const toggle = (id: string, label: string, on: boolean, act: () => void) => {
        const row = el(
          'div',
          { class: 'settings-row', tabindex: '-1', 'data-focus': '', 'data-focus-id': id, 'aria-selected': String(on) },
          label,
        );
        row.addEventListener('click', act);
        return row;
      };
      guard.append(
        toggle('parental-enabled', t('enable_parental_control'), state.enabled, () => {
          update({ enabled: !parental().enabled });
          options.onChanged();
        }),
        toggle('parental-startup', t('ask_pin_on_startup'), state.askOnStartup, () => {
          update({ askOnStartup: !parental().askOnStartup });
          options.onChanged();
        }),
        toggle('parental-categories', t('lock_categories'), state.lockedCategories.length > 0, options.onLockCategories),
      );
      const removeRow = el(
        'div',
        { class: 'settings-row danger', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'pin-remove' },
        t('parental_pin_removed'),
      );
      removeRow.addEventListener('click', options.onRemovePin);
      guard.append(removeRow);
    }
  }

  page.append(languages, appearance, actions, guard);
  host.append(el('div', { class: 'browser-title' }, t('settings_title')), page);
  focus(languages.querySelector<HTMLElement>('[data-focus]'));

  const release = pushKeyHandler((key) => {
    if (key !== 'back') return false;
    release();
    options.onBack();
    return true;
  });
}
