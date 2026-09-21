/**
 * Settings, laid out the way the television app lays them out: a menu of pages, not a wall.
 *
 * The earlier version put every group in one flex row, which worked at three columns and was
 * already tight at four - measured at 1920x1080, a fifth column takes each one to 314px and labels
 * begin wrapping. Since the Android app has eight pages and this port is meant to follow it, the
 * shape has to be the one that reaches eight: a root list, each row opening a page, Back stepping
 * out one level at a time.
 *
 * Only the pages that exist are listed. A row leading to a page nobody has written is worse than a
 * missing row - it invites a press and answers with nothing.
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

  onClearCache: () => void;
  onBack: () => void;
}

/** Which page is showing. ROOT is the menu; the rest mirror the television app's pages. */
type Page = 'root' | 'playlist' | 'appearance' | 'language' | 'parental';

export function renderSettings(host: HTMLElement, options: SettingsOptions): void {
  let page: Page = 'root';

  const release = pushKeyHandler((key) => {
    if (key !== 'back') return false;
    // Back walks out one level at a time rather than leaving settings from four pages deep, which
    // is how the television app behaves and what a viewer who opened a page by mistake expects.
    if (page !== 'root') {
      page = 'root';
      draw();
      return true;
    }
    release();
    options.onBack();
    return true;
  });

  function open(next: Page): void {
    page = next;
    draw();
  }

  function title(): string {
    switch (page) {
      case 'playlist': return t('settings_playlists');
      case 'appearance': return t('settings_appearance');
      case 'language': return t('cd_language');
      case 'parental': return t('settings_parental_controls');
      default: return t('settings_title');
    }
  }

  /** A row on the root menu: a label that opens a page. */
  function menuRow(id: string, label: string, target: Page): HTMLElement {
    const row = el(
      'div',
      { class: 'settings-row', tabindex: '-1', 'data-focus': '', 'data-focus-id': `menu-${id}` },
      label,
    );
    row.addEventListener('click', () => open(target));
    return row;
  }

  function rootPage(): HTMLElement {
    const menu = el('div', { class: 'settings-menu', 'data-focus-group': 'settings-menu' });
    // Grouped as the television app groups them: what the viewer watches with, then what they
    // restrict. Pages not yet ported - playback, app info, privacy, category visibility - are
    // absent rather than listed and dead.
    menu.append(
      menuRow('playlist', t('settings_playlists'), 'playlist'),
      menuRow('appearance', t('settings_appearance'), 'appearance'),
      menuRow('language', t('cd_language'), 'language'),
      menuRow('parental', t('settings_parental_controls'), 'parental'),
    );
    return menu;
  }

  function languagePage(): HTMLElement {
    const languages = el('div', { class: 'settings-group', 'data-focus-group': 'languages' });
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
    return languages;
  }

  // Appearance. Two rows rather than a switch, because a switch on a television has to say what
  // it is a switch *for*, and by the time that label is written the two named choices are shorter
  // and clearer than the question.
  function appearancePage(): HTMLElement {
  const appearance = el('div', { class: 'settings-group', 'data-focus-group': 'appearance' });
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
    row.addEventListener('click', () => {
      setBackgroundMode(mode);
      draw();
    });
    appearance.append(row);
  }
  // One description, under the pair, for whichever is in force - not a description inside each
  // row. These columns are narrower than they look: four groups share the width, so a sentence
  // inside a row wraps to four lines and turns a 69-pixel row into a 205-pixel one, which makes
  // the two options look like two paragraphs rather than a choice.
  appearance.append(
    el(
      'div',
      { class: 'settings-note' },
      currentMode === 'classic' ? t('background_classic_desc') : t('background_modern_desc'),
    ),
  );
    return appearance;
  }

  function playlistPage(): HTMLElement {
  const actions = el('div', { class: 'settings-group', 'data-focus-group': 'settings-actions' });

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
    return actions;
  }

  function parentalPage(): HTMLElement {
  const guard = el('div', { class: 'settings-group', 'data-focus-group': 'parental' });

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
          draw();
        }),
        toggle('parental-startup', t('ask_pin_on_startup'), state.askOnStartup, () => {
          update({ askOnStartup: !parental().askOnStartup });
          draw();
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
    return guard;
  }

  function body(): HTMLElement {
    switch (page) {
      case 'playlist': return playlistPage();
      case 'appearance': return appearancePage();
      case 'language': return languagePage();
      case 'parental': return parentalPage();
      default: return rootPage();
    }
  }

  function draw(): void {
    host.textContent = '';
    const content = body();
    host.append(el('div', { class: 'browser-title' }, title()), content);
    focus(content.querySelector<HTMLElement>('[data-focus]'));
  }

  draw();
}
