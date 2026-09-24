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
import {
  SKIP_CHOICES,
  backgroundMode,
  liveChannelSort,
  setLiveChannelSort,
  setBackgroundMode,
  setSkipSeconds,
  setSubtitleBackground,
  setVideoScaling,
  skipSeconds,
  subtitleBackground,
  videoScaling,
  type LiveChannelSort,
  type VideoScalingPreference,
} from '../shared/preferences';
import type { LoadedPlaylist } from '../shared/models';
import { cryptoAvailable, hasPin, parental, update } from '../shared/parental';
import {
  hiddenCategories, hideCategory, unhideCategory, type CategoryKind,
} from '../shared/categories';
import { focus, pushKeyHandler } from './focus';
import { iconElement, type IconName } from './icons';

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
   * A page to open straight into, instead of the root menu.
   *
   * Back from that page leaves settings altogether, back to wherever it was opened from - the
   * globe in the bar is a shortcut to one page, and the television's LanguageScreen returns to
   * `overlayReturn` the same way rather than to a settings menu the viewer never opened.
   */
  openAt?: 'language';
  /**
   * Every category the current playlist has, per kind, whether hidden or not.
   *
   * Asked for rather than passed as a value because hiding one changes the answer, and a list
   * captured when settings opened would stop matching what the page is showing.
   */
  categories: (kind: CategoryKind) => string[];
  onSignOut: () => void;
  onSetPin: () => void;
  onRemovePin: () => void;
  onLockCategories: () => void;

  onClearCache: () => void;

  /** The loaded catalogue, for the App info page. Asked for, so it reflects the current one. */
  playlist: () => LoadedPlaylist | null;
  /** The app's own version, which only the caller knows - it comes from the widget manifest. */
  appVersion: string;
  onClearMovieActivity: () => void;
  onClearSeriesActivity: () => void;
  onClearLiveActivity: () => void;
  onBack: () => void;
}

/** Which page is showing. ROOT is the menu; the rest mirror the television app's pages. */
type Page =
  | 'root'
  | 'playlist'
  | 'info'
  | 'playback'
  | 'appearance'
  | 'language'
  | 'history'
  | 'parental'
  | 'categories';

/**
 * The tint each root row's icon carries, as SettingsMenuRow is called in SettingsScreen.kt:
 * Orange, BrandBlue or Cyan from ui/theme/Theme.kt. A page's own card header is always Cyan.
 */
type Tint = 'orange' | 'blue' | 'cyan';

/** Each page's icon, the one SettingsScreen.kt gives both its menu row and its SettingsSection. */
const PAGE_ICON: Record<Exclude<Page, 'root'>, IconName> = {
  playlist: 'playlistPlay',
  info: 'info',
  playback: 'playCircle',
  appearance: 'wallpaper',
  language: 'language',
  history: 'history',
  categories: 'visibilityOff',
  parental: 'adminPanelSettings',
};

/** The three catalogues, in the order the navigation bar puts them, with the chip icons. */
const KINDS: { kind: CategoryKind; label: () => string; icon: IconName }[] = [
  { kind: 'live', label: () => t('nav_live_tv'), icon: 'liveTv' },
  { kind: 'movie', label: () => t('nav_movies'), icon: 'movie' },
  { kind: 'series', label: () => t('nav_series'), icon: 'videoLibrary' },
];

/** The focusable shell every pressable row shares. */
function pressable(id: string, className: string, extra: Record<string, string> = {}): HTMLDivElement {
  return el('div', { class: className, tabindex: '-1', 'data-focus': '', 'data-focus-id': id, ...extra });
}

/** A label with an optional quieter line under it, which takes the row's spare width. */
function rowText(label: string, description?: string): HTMLElement {
  const text = el('div', { class: 'settings-row-text' }, el('div', { class: 'settings-row-label' }, label));
  if (description) text.append(el('div', { class: 'settings-row-desc' }, description));
  return text;
}

/**
 * SettingsAction: an icon, a title with its description, and a chevron, on a 13dp tile.
 * Destructive ones take the error colour on both icon and title, as on the television.
 */
function actionRow(
  id: string,
  icon: IconName,
  label: string,
  description: string | undefined,
  onClick: () => void,
  destructive = false,
): HTMLElement {
  const row = pressable(id, destructive ? 'settings-row settings-action danger' : 'settings-row settings-action');
  row.append(
    iconElement(icon, 'settings-icon'),
    rowText(label, description),
    iconElement('chevronRight', 'settings-chevron'),
  );
  row.addEventListener('click', onClick);
  return row;
}

/** RadioSetting: a radio button, then the title and any description. */
function choiceRow(id: string, label: string, selected: boolean, onClick: () => void, description?: string): HTMLElement {
  const row = pressable(id, 'settings-row settings-choice', { 'aria-selected': String(selected) });
  row.append(
    iconElement(selected ? 'radioChecked' : 'radioUnchecked', 'settings-radio'),
    rowText(label, description),
  );
  row.addEventListener('click', onClick);
  return row;
}

/** SettingsSwitch: title and description, then the switch at the far end. */
function toggleRow(id: string, label: string, on: boolean, onClick: () => void, description?: string): HTMLElement {
  const row = pressable(id, 'settings-row settings-choice', { 'aria-checked': String(on) });
  row.append(
    rowText(label, description),
    iconElement(on ? 'toggleOn' : 'toggleOff', 'settings-switch'),
  );
  row.addEventListener('click', onClick);
  return row;
}

/** A heading inside a page, above the group of rows it introduces. */
function subheading(text: string): HTMLElement {
  return el('div', { class: 'settings-note strong' }, text);
}

export function renderSettings(host: HTMLElement, options: SettingsOptions): void {
  let page: Page = options.openAt ?? 'root';

  const release = pushKeyHandler((key) => {
    if (key !== 'back') return false;
    // Back walks out one level at a time rather than leaving settings from four pages deep, which
    // is how the television app behaves and what a viewer who opened a page by mistake expects.
    // Except from the page settings was opened straight into, which is its first level.
    if (page !== 'root' && page !== options.openAt) {
      open('root');
      return true;
    }
    release();
    options.onBack();
    return true;
  });

  /** Where the highlight goes on the next draw, when it should not start at the top. */
  let focusNext: string | null = null;

  function open(next: Page): void {
    // Coming back up to the menu lands on the row that was opened, not on the first row.
    focusNext = next === 'root' && page !== 'root' ? `menu-${page}` : null;
    page = next;
    draw();
  }

  /** Redraws the page in place, keeping the highlight on the row that was just pressed. */
  function redraw(id: string): void {
    focusNext = id;
    draw();
  }

  function title(): string {
    switch (page) {
      case 'playlist': return t('settings_playlists');
      case 'info': return t('settings_app_info');
      case 'playback': return t('settings_playback');
      case 'appearance': return t('settings_appearance');
      case 'language': return t('cd_language');
      case 'history': return t('settings_privacy_history');
      case 'parental': return t('settings_parental_controls');
      case 'categories': return t('settings_category_visibility');
      default: return t('settings_title');
    }
  }

  /** SettingsMenuRow: a tinted icon, the page's name, and a chevron. */
  function menuRow(target: Exclude<Page, 'root'>, label: string, tint: Tint): HTMLElement {
    const row = pressable(`menu-${target}`, 'settings-menu-row');
    row.append(
      iconElement(PAGE_ICON[target], `settings-icon tint-${tint}`),
      el('span', { class: 'settings-row-label' }, label),
      iconElement('chevronRight', 'settings-chevron'),
    );
    row.addEventListener('click', () => open(target));
    return row;
  }

  function rootPage(): HTMLElement {
    const menu = el('div', { class: 'settings-menu', 'data-focus-group': 'settings-menu' });
    // The television app's own order, grouping and tints: two panels, what the viewer watches
    // with and how it looks, then what they keep and restrict. Then the version, under both.
    menu.append(
      el(
        'div',
        { class: 'settings-panel' },
        menuRow('playlist', t('settings_playlists'), 'orange'),
        menuRow('info', t('settings_app_info'), 'blue'),
        menuRow('playback', t('settings_playback'), 'cyan'),
        menuRow('appearance', t('settings_appearance'), 'orange'),
        menuRow('language', t('cd_language'), 'cyan'),
      ),
      el(
        'div',
        { class: 'settings-panel' },
        menuRow('history', t('settings_privacy_history'), 'cyan'),
        menuRow('categories', t('settings_category_visibility'), 'orange'),
        menuRow('parental', t('settings_parental_controls'), 'blue'),
      ),
      el('div', { class: 'settings-footer' }, t('footer_version', options.appVersion)),
    );
    return menu;
  }

  /** A label and a value, for the read-only facts on the App info page. */
  function factRow(label: string, value: string): HTMLElement {
    return el(
      'div',
      { class: 'settings-fact' },
      el('span', { class: 'settings-fact-label' }, label),
      el('span', { class: 'settings-fact-value' }, value),
    );
  }

  /**
   * What the app is and what it is signed in to. Read-only throughout.
   *
   * The Android version reports the Android release here; this reports the Tizen one, read from
   * the user agent because a web app has no other way to ask. The playlist facts come from the
   * catalogue the app already has rather than from a fresh request - this page is a statement of
   * what is loaded, and going to the provider to draw it would let it disagree with the rest of
   * the app.
   */
  function infoPage(): HTMLElement {
    const group = el('div', { class: 'settings-group' });
    const playlist = options.playlist();
    const tizen = /Tizen ([\d.]+)/.exec(navigator.userAgent)?.[1];

    group.append(subheading(t('application_label')));
    group.append(factRow(t('app_name_label'), t('app_name')));
    group.append(factRow(t('version_label'), options.appVersion));
    if (tizen) group.append(factRow('Tizen', tizen));

    group.append(el('div', { class: 'settings-divider' }));
    group.append(subheading(t('active_playlist_label')));
    group.append(factRow(t('name_label'), playlist?.name || t('no_active_playlist')));
    group.append(factRow(t('status_label'), playlist?.accountStatus || t('not_provided')));
    group.append(
      factRow(
        t('expiry_date_label'),
        playlist?.expiryEpochSeconds
          ? new Date(playlist.expiryEpochSeconds * 1000).toLocaleDateString(locale())
          : t('not_provided'),
      ),
    );
    group.append(factRow(t('items_label'), String(playlist?.items.length ?? 0)));
    return group;
  }

  /**
   * The playback choices that mean something on a television.
   *
   * Four of the Android page's rows are not here, and deliberately: the player engine and the
   * connection mode are ExoPlayer settings with no AVPlay counterpart, and start-muted and
   * embedded-subtitle handling are decisions the decoder makes for us on this platform. Listing
   * them would be offering a switch that does nothing.
   *
   * The two that are here are the two the player's own panel writes, so changing one in either
   * place shows up in the other.
   */
  function playbackPage(): HTMLElement {
    const group = el('div', { class: 'settings-group', 'data-focus-group': 'playback' });

    group.append(subheading(t('skip_interval')));
    const currentSkip = skipSeconds();
    for (const seconds of SKIP_CHOICES) {
      const id = `skip-${seconds}`;
      group.append(choiceRow(id, t('skip_seconds_format', String(seconds)), seconds === currentSkip, () => {
        setSkipSeconds(seconds);
        redraw(id);
      }));
    }

    group.append(subheading(t('video_scaling')));
    const currentScaling = videoScaling();
    const SCALINGS: { mode: VideoScalingPreference; label: () => string; desc: () => string }[] = [
      // Three, not seven, exactly as the television app's Settings screen does it: this is the
      // standing default a viewer sets once, and the four named frames in the player's own menu
      // are answers to what is on screen right now. Both write the same preference, so a frame
      // chosen in the player leaves none of these three marked until one is picked again - which
      // is true on the television too.
      { mode: 'fit', label: () => t('video_fit'), desc: () => t('video_fit_desc') },
      { mode: 'zoom', label: () => t('video_fill'), desc: () => t('video_fill_desc') },
      { mode: 'stretch', label: () => t('video_stretch'), desc: () => t('video_stretch_desc') },
    ];
    for (const entry of SCALINGS) {
      const id = `scaling-${entry.mode}`;
      group.append(choiceRow(id, entry.label(), entry.mode === currentScaling, () => {
        setVideoScaling(entry.mode);
        redraw(id);
      }, entry.desc()));
    }

    // Live TV only, and the whole section says so. On Movies and Series the provider's order
    // barely registers - those are walls of artwork the eye searches rather than lists it reads -
    // and a sort control that silently applied to all three would be doing something different
    // from what it says.
    group.append(el('div', { class: 'settings-divider' }));
    group.append(subheading(t('live_channel_sort')));
    const currentSort = liveChannelSort();
    const SORTS: { order: LiveChannelSort; label: () => string }[] = [
      { order: 'default', label: () => t('sort_default') },
      { order: 'az', label: () => t('sort_az') },
      { order: 'za', label: () => t('sort_za') },
    ];
    for (const entry of SORTS) {
      const id = `sort-${entry.order}`;
      group.append(choiceRow(id, entry.label(), entry.order === currentSort, () => {
        setLiveChannelSort(entry.order);
        redraw(id);
      }));
    }

    group.append(el('div', { class: 'settings-divider' }));
    group.append(subheading(t('subtitles_label')));
    const backing = subtitleBackground();
    group.append(toggleRow('subtitle-backing', t('subtitle_background'), backing, () => {
      setSubtitleBackground(!backing);
      redraw('subtitle-backing');
    }));
    return group;
  }

  /**
   * Forgetting things, which is the only thing this page does.
   *
   * Each row clears one kind of history and says so before it is pressed. Nothing here asks for
   * confirmation, matching the television app - these are small, named, and losing a resume point
   * is not the kind of loss a dialog earns.
   */
  function historyPage(): HTMLElement {
    const group = el('div', { class: 'settings-group', 'data-focus-group': 'history' });
    group.append(
      actionRow('clear-movies', 'movie', t('clear_movie_activity'), t('clear_movie_activity_desc'), options.onClearMovieActivity),
      actionRow('clear-series', 'videoLibrary', t('clear_series_activity'), t('clear_series_activity_desc'), options.onClearSeriesActivity),
      actionRow('clear-live', 'liveTv', t('clear_live_activity'), t('clear_live_activity_desc'), options.onClearLiveActivity),
    );
    return group;
  }

  /*
   * Picking a language redraws this page where it is, in the new language, with the highlight on
   * the language just chosen. It used to hand off to the caller, which rebuilt settings from the
   * root menu - so a viewer who had come here from the globe in the bar was left on a settings menu
   * they had never opened. The television recreates its Activity and comes back on the same
   * screen; this is that, without the restart.
   */
  function languagePage(): HTMLElement {
    const languages = el('div', { class: 'settings-group', 'data-focus-group': 'languages' });
    for (const code of availableLocales()) {
      const id = `lang-${code}`;
      languages.append(choiceRow(id, languageName(code), code === locale(), () => {
        setLocale(code);
        redraw(id);
      }));
    }
    return languages;
  }

  // Appearance. Two choices, then one description for whichever is in force, under the pair - as
  // the television's segmented button does it, rather than a sentence inside each row.
  function appearancePage(): HTMLElement {
    const appearance = el('div', { class: 'settings-group', 'data-focus-group': 'appearance' });
    const currentMode = backgroundMode();
    for (const mode of ['modern', 'classic'] as const) {
      const id = `background-${mode}`;
      appearance.append(choiceRow(id, mode === 'classic' ? t('background_classic') : t('background_modern'), mode === currentMode, () => {
        setBackgroundMode(mode);
        redraw(id);
      }));
    }
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
    actions.append(
      actionRow('clear-cache', 'refresh', t('refresh_playlist'), t('refresh_playlist_desc'), options.onClearCache),
      actionRow('sign-out', 'deleteForever', t('remove_playlist_action'), t('remove_playlist_desc'), options.onSignOut, true),
    );
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
    wrap.append(el('div', { class: 'settings-note' }, t('category_visibility_desc')));

    const tabs = el('div', { class: 'kind-tabs' });
    for (const entry of KINDS) {
      const id = `kind-${entry.kind}`;
      const tab = pressable(id, 'settings-row kind-tab', { 'aria-selected': String(entry.kind === visibilityKind) });
      tab.append(iconElement(entry.icon, 'settings-icon'), el('span', { class: 'settings-row-label' }, entry.label()));
      tab.addEventListener('click', () => {
        visibilityKind = entry.kind;
        redraw(id);
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
      const id = `visibility-${name}`;
      const row = pressable(id, `settings-row settings-choice${isHidden ? ' muted' : ''}`);
      row.append(
        iconElement(isHidden ? 'visibilityOff' : 'visibility', 'settings-icon'),
        el('span', { class: 'settings-row-label' }, name),
        el('span', { class: 'row-state' }, isHidden ? t('hide_category_action') : t('action_see_all')),
      );
      row.addEventListener('click', () => {
        if (isHidden) unhideCategory(visibilityKind, name);
        else hideCategory(visibilityKind, name);
        redraw(id);
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
      return guard;
    }
    const state = parental();
    if (hasPin()) {
      guard.append(
        toggleRow('parental-enabled', t('enable_parental_control'), state.enabled, () => {
          update({ enabled: !parental().enabled });
          redraw('parental-enabled');
        }, t('enable_parental_control_desc')),
        toggleRow('parental-startup', t('ask_pin_on_startup'), state.askOnStartup, () => {
          update({ askOnStartup: !parental().askOnStartup });
          redraw('parental-startup');
        }, t('ask_pin_on_startup_desc')),
        el('div', { class: 'settings-divider' }),
      );
    }
    guard.append(actionRow(
      'pin-set',
      'pin',
      hasPin() ? t('change_pin') : t('create_parental_pin'),
      hasPin() ? t('change_pin_desc') : t('create_parental_pin_desc'),
      options.onSetPin,
    ));
    if (hasPin()) {
      guard.append(
        actionRow('parental-categories', 'lock', t('lock_categories'), t('lock_categories_desc'), options.onLockCategories),
        actionRow('pin-remove', 'deleteForever', t('remove_pin'), undefined, options.onRemovePin, true),
      );
    }
    return guard;
  }

  function body(): HTMLElement {
    switch (page) {
      case 'playlist': return playlistPage();
      case 'info': return infoPage();
      case 'playback': return playbackPage();
      case 'history': return historyPage();
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
    host.append(el('div', { class: 'browser-title settings-title' }, title()));
    if (page === 'root') {
      host.append(content);
    } else {
      // SettingsSection: the page's own card, with its icon and name at the head in Cyan.
      host.append(
        el(
          'div',
          { class: 'settings-section' },
          el('div', { class: 'settings-section-head' }, iconElement(PAGE_ICON[page], 'settings-icon'), title()),
          content,
        ),
      );
    }
    const wanted = focusNext
      ? content.querySelector<HTMLElement>(`[data-focus-id="${CSS.escape(focusNext)}"]`)
      : null;
    focusNext = null;
    focus(wanted ?? content.querySelector<HTMLElement>('[data-focus]'));
  }

  draw();
}
