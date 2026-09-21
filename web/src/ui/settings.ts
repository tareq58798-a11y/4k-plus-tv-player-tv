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
import {
  hiddenCategories, hideCategory, unhideCategory, type CategoryKind,
} from '../shared/categories';
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
  /**
   * Every category the current playlist has, per kind, whether hidden or not.
   *
   * Asked for rather than passed as a value because hiding one changes the answer, and a list
   * captured when settings opened would stop matching what the page is showing.
   */
  categories: (kind: CategoryKind) => string[];
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
type Page = 'root' | 'playlist' | 'appearance' | 'language' | 'parental' | 'categories';

/** The three catalogues, in the order the navigation bar puts them. */
const KINDS: { kind: CategoryKind; label: () => string }[] = [
  { kind: 'live', label: () => t('nav_live_tv') },
  { kind: 'movie', label: () => t('nav_movies') },
  { kind: 'series', label: () => t('nav_series') },
];

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
      case 'categories': return t('settings_category_visibility');
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
    // restrict. Pages not yet ported - playback, app info, privacy - are absent rather than
    // listed and dead.
    menu.append(
      menuRow('playlist', t('settings_playlists'), 'playlist'),
      menuRow('appearance', t('settings_appearance'), 'appearance'),
      menuRow('language', t('cd_language'), 'language'),
      menuRow('categories', t('settings_category_visibility'), 'categories'),
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

  /**
   * Which categories are hidden, and the only way to get one back.
   *
   * This page is not optional. A held OK on a category hides it, and without somewhere to undo
   * that, hiding is a one-way door - a viewer who hides the wrong row has no way to find it again
   * short of clearing the app's storage.
   *
   * One kind at a time, as on Android, because a playlist can carry hundreds of categories in each
   * and a single list of all three is not something anybody can find anything in.
   */
  let visibilityKind: CategoryKind = 'live';

  function categoriesPage(): HTMLElement {
    const wrap = el('div', { class: 'settings-group', 'data-focus-group': 'category-visibility' });

    const tabs = el('div', { class: 'kind-tabs' });
    for (const entry of KINDS) {
      const tab = el(
        'div',
        {
          class: 'settings-row kind-tab',
          tabindex: '-1',
          'data-focus': '',
          'data-focus-id': `kind-${entry.kind}`,
          'aria-selected': String(entry.kind === visibilityKind),
        },
        entry.label(),
      );
      tab.addEventListener('click', () => {
        visibilityKind = entry.kind;
        draw();
      });
      tabs.append(tab);
    }
    wrap.append(tabs);

    const all = options.categories(visibilityKind);
    const hidden = new Set(hiddenCategories(visibilityKind));

    if (!all.length && !hidden.size) {
      wrap.append(el('div', { class: 'settings-note' }, t('no_live_categories')));
      return wrap;
    }

    // Hidden categories are listed first. They are the reason anybody opens this page, and they
    // are also the ones that have vanished from every other screen - so burying them among two
    // hundred visible rows would make the fix harder to reach than the mistake was to make.
    const names = [...new Set([...hidden, ...all])].sort((a, b) => {
      const byHidden = Number(hidden.has(b)) - Number(hidden.has(a));
      return byHidden !== 0 ? byHidden : a.localeCompare(b);
    });

    const list = el('div', { class: 'category-visibility-list' });
    for (const name of names) {
      const isHidden = hidden.has(name);
      const row = el(
        'div',
        {
          class: `settings-row${isHidden ? ' muted' : ''}`,
          tabindex: '-1',
          'data-focus': '',
          'data-focus-id': `visibility-${name}`,
          'aria-selected': String(!isHidden),
        },
        name,
      );
      row.append(el('span', { class: 'row-state' }, isHidden ? t('hide_category_action') : t('action_see_all')));
      row.addEventListener('click', () => {
        if (isHidden) unhideCategory(visibilityKind, name);
        else hideCategory(visibilityKind, name);
        draw();
      });
      list.append(row);
    }
    wrap.append(list);
    return wrap;
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
      case 'categories': return categoriesPage();
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
