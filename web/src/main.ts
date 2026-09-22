/**
 * Routing and screens.
 *
 * Sign in, then four landing pages over one catalogue, a category browser, and playback. The
 * screens are deliberately thin: the rules they follow live in ui/landing.ts and ui/focus.ts, and
 * the provider quirks in shared/xtream.ts, so there is one place to change each.
 */
import { detectPlatform, keyOf, registerPlatformKeys, type RemoteKey } from './platform/keys';
import { createPlayer, type MediaPlayer } from './platform/video';
import { readJson, writeJson, remove as removeStored } from './platform/storage';
import { cacheKey, clearCatalogue, readCatalogue, writeCatalogue } from './platform/cache';
import { setLocale, isRtl, locale, t } from './shared/i18n';
import { loadProvider, movieDetails, seriesDetails } from './shared/xtream';
import type { LoadedPlaylist, MovieDetails, PlaylistItem, ProviderLogin } from './shared/models';
import { itemKey } from './shared/models';
import {
  clearActivity, continueWatching, favoriteItems, recentlyAdded, rememberPosition,
} from './shared/library';
import { focus, handleKey, pushKeyHandler, setFocusDirection } from './ui/focus';
import { Backdrop } from './ui/backdrop';
import { renderLanding, disposeLanding, focusPageStart, type LandingRow } from './ui/landing';
import { renderNav, trackNavHighlight, type Section } from './ui/nav';
import { renderSeries } from './ui/series';
import { renderDetails } from './ui/details';
import { renderSearch } from './ui/search';
import { renderSettings } from './ui/settings';
import { createEpgLoader, clockTime } from './ui/epg';
import { askPin } from './ui/pin';
import { createPlayerOverlay, type StripEpisode } from './ui/player';
import { backgroundMode, liveChannelSort, videoScaling } from './shared/preferences';
import {
  applyCategoryOrder, hiddenCategories, hideCategory, moveCategory, moveCategoryToEnd,
} from './shared/categories';
import { openCategoryMenu } from './ui/categoryMenu';
import { appVersion, identity } from './platform/identity';
import { activate, ActivationPending } from './shared/activation';
import { loadM3u } from './shared/m3u';
import {
  isCategoryLocked, isChannelLocked, isUnlocked, markUnlocked, parental, relock,
  removePin, setPin, toggleCategoryLock,
} from './shared/parental';

const platform = detectPlatform();
const app = document.getElementById('app') as HTMLElement;
const video = document.getElementById('video') as HTMLVideoElement;
const backdropHost = document.getElementById('video-plane') as HTMLElement;
let player: MediaPlayer;
let backdrop: Backdrop;

/**
 * How long a channel must be the focused one before the preview tunes to it.
 *
 * Longer than the backdrop's debounce, because the cost is far higher: a backdrop that fires early
 * wastes an image request, a preview that fires early opens a stream. Long enough to walk a list
 * without tuning anything, short enough that stopping on a channel feels like it answered.
 */
const PREVIEW_DELAY_MS = 900;

const SAVED_LOGIN = 'login';
/** An M3U playlist has no account, so it is remembered by address instead of by login. */
const SAVED_M3U = 'm3u';

let login: ProviderLogin | null = null;
let catalogue: LoadedPlaylist | null = null;
let section: Section = 'home';
let detachNav: (() => void) | null = null;

/**
 * Where the browser should put the highlight when it is next entered, or null for its default.
 *
 * Set when a film's page is opened and cleared by the arrival that uses it. Module scope because
 * browseScreen is torn down and rebuilt on the way there and back - a closure would not survive
 * the round trip, which is the whole problem this solves.
 */
let browseReturn: { section: Section; category: string; focusKey: string } | null = null;

/**
 * Escapes a string for use inside an attribute selector.
 *
 * Category names and item keys come from the provider, and they contain quotes, brackets and
 * backslashes often enough to matter - a group called `Movies "4K"` would otherwise build a
 * selector that throws, taking the whole screen down with it. CSS.escape is not on the engine
 * these sets run, so the three characters that can break out are escaped by hand.
 */
function cssEscape(value: string): string {
  return value.replace(/["\\\n]/g, (c) => (c === '\n' ? ' ' : `\\${c}`));
}

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
 * Torn down by whatever replaces the screen that set it.
 *
 * The Live TV preview is a playing stream, and Back is only one of the ways out of that screen -
 * the navigation bar, search and settings all leave too. Rather than remember to stop it at each
 * exit and miss one, the screen registers its teardown here and every navigation runs it, because
 * every navigation goes through clear().
 */
let leaveScreen: (() => void) | null = null;

function clear(): void {
  leaveScreen?.();
  leaveScreen = null;
  activationStop?.();
  activationStop = null;
  // Both pages hang listeners on the document rather than on their own elements, so emptying the
  // app is not enough to be rid of them - a landing left behind would keep answering focus moves
  // on the page that replaced it.
  disposeLanding();
  detachNav?.();
  detachNav = null;
  app.textContent = '';
}

/* ------------------------------------------------------------------ login */

/**
 * The way in that needs no typing.
 *
 * A viewer with a remote should not have to enter a server address, a username and a password on
 * an on-screen keyboard. The set shows its own MAC and a device key, the reseller assigns a
 * playlist to that pair, and the app collects it - which is the whole reason this exists.
 *
 * Polls while the page is open, backing off after failures. "Nothing assigned yet" is the normal
 * state here, not an error: the viewer is expected to be reading the numbers out to somebody.
 */
function activationPanel(onActivated: (login: ProviderLogin) => void): HTMLElement {
  const panel = el('div', { class: 'panel activation' });
  // dir=ltr on the value, not on the row. In Arabic the page runs right to left, and the bidi
  // algorithm handed "9A:D5:DA:DB:33:4D" inside a right-to-left paragraph is entitled to reorder
  // the segments around the colons - so the code on screen reads back differently from the one
  // the app sent. The label follows the page; only the identifier is pinned.
  const macRow = el('div', { class: 'code-row' }, el('span', {}, t('device_id')), el('strong', { dir: 'ltr' }, '…'));
  const keyRow = el('div', { class: 'code-row' }, el('span', {}, t('device_key')), el('strong', { dir: 'ltr' }, '…'));
  const status = el('div', { class: 'message' }, t('waiting_for_activation'));

  panel.append(
    el('h2', {}, t('activate_via_app')),
    el('p', {}, t('activation_instructions')),
    macRow,
    keyRow,
    status,
  );

  let stopped = false;
  let failures = 0;
  /** Bumped by the Refresh button to cut a wait short. */
  let wake: (() => void) | null = null;

  const refresh = el(
    'button',
    { class: 'button', 'data-focus': '', 'data-focus-id': 'refresh-activation' },
    t('cd_refresh_activation'),
  );
  // The loop already checks every few seconds, so this is not what makes activation work - it is
  // what makes the waiting bearable. Somebody who has just been told "it's assigned now" wants to
  // see it happen, not to sit watching a line of text and wonder whether anything is running.
  refresh.addEventListener('click', () => wake?.());
  panel.append(refresh);

  void (async () => {
    const device = await identity();
    const { mac, key } = device;
    macRow.lastElementChild!.textContent = mac;
    keyRow.lastElementChild!.textContent = key;
    // A generated address lives in the app's own storage and does not survive a reinstall, so
    // registering a playlist against it would work today and be orphaned tomorrow. Anyone looking
    // at this number needs to know that before they read it out to somebody.
    if (!device.stable) {
      panel.append(
        el(
          'div',
          { class: 'settings-note warn' },
          'This device could not be identified, so the code above is temporary and will change if '
            + 'the app is reinstalled. Do not register it.',
        ),
      );
    }

    while (!stopped) {
      status.textContent = t('waiting_for_activation');
      try {
        const result = await activate(mac, key);
        if (stopped) return;
        if (result.kind === 'm3u') {
          // An M3U has no account behind it, so there is nothing to sign in to and nothing to
          // refresh with - it is fetched once, here, and becomes the catalogue.
          status.textContent = t('loading_your_playlist');
          try {
            catalogue = await loadM3u(result.name, result.url);
            login = null;
            writeJson(SAVED_M3U, { name: result.name, url: result.url });
            showSection('home');
          } catch (error) {
            status.textContent = error instanceof Error ? error.message : t('playlist_could_not_be_loaded');
          }
          return;
        }
        onActivated(result.login);
        return;
      } catch (error) {
        if (stopped) return;
        if (error instanceof ActivationPending) {
          failures = 0;
          status.textContent = `${t('no_playlist_assigned_yet')}  ·  ${clockTime(Math.floor(Date.now() / 1000), locale())}`;
        } else {
          failures++;
          status.textContent = error instanceof Error ? error.message : String(error);
        }
      }
      // Waits, or gives up waiting the moment Refresh is pressed.
      await new Promise<void>((resolve) => {
        const delay = failures === 0 ? 5000 : Math.min(30000, 5000 * failures);
        const timer = window.setTimeout(finish, delay);
        wake = finish;
        function finish(): void {
          window.clearTimeout(timer);
          wake = null;
          resolve();
        }
      });
    }
  })();

  // Stops the loop when the page goes, so a signed-in app is not still polling behind it.
  activationStop = () => {
    stopped = true;
  };
  return panel;
}

let activationStop: (() => void) | null = null;

function loginScreen(message = ''): void {
  clear();
  document.body.classList.remove('playing');
  backdrop.reset();

  const name = el('input', { type: 'text', value: '4K Plus TV', 'data-focus': '', 'data-focus-id': 'name' });
  const address = el('input', { type: 'text', placeholder: 'http://example.com:80', 'data-focus': '', 'data-focus-id': 'address' });
  const username = el('input', { type: 'text', 'data-focus': '', 'data-focus-id': 'username' });
  // Masked, so the characters are not readable across a room. Nothing typed here is logged.
  const password = el('input', { type: 'password', 'data-focus': '', 'data-focus-id': 'password' });
  const status = el('div', { class: 'message' }, message);

  const connect = el('button', { class: 'button', 'data-focus': '', 'data-focus-id': 'connect' }, t('connect'));
  connect.addEventListener('click', () => {
    const entered: ProviderLogin = {
      name: name.value.trim() || '4K Plus TV',
      address: address.value.trim(),
      username: username.value.trim(),
      password: password.value,
    };
    if (!entered.address || !entered.username || !entered.password) {
      status.textContent = t('enter_all_fields');
      return;
    }
    void connectAndLoad(entered, status);
  });

  const field = (label: string, input: HTMLElement) =>
    el('label', { class: 'field' }, el('span', {}, label), input);

  // Two ways in, side by side, the way the television app offers them: the one that needs no
  // typing first, and manual entry for anyone who already has their provider details.
  app.append(
    el('h1', { class: 'welcome-title' }, t('welcome')),
    el('p', { class: 'welcome-sub' }, t('activation_subtitle')),
    el(
      'div',
      { class: 'welcome' },
      activationPanel((activated) => {
        activationStop?.();
        const message = app.querySelector<HTMLElement>('.manual .message');
        if (message) void connectAndLoad(activated, message);
      }),
      el(
        'div',
        { class: 'panel manual' },
        el('h2', {}, t('add_playlist_manually')),
        el('p', {}, t('add_playlist_manually_desc')),
        field(t('playlist_name_label'), name),
        field(t('server_address_label'), address),
        field(t('username_label'), username),
        field(t('password_label'), password),
        connect,
        status,
      ),
    ),
  );
  focus(name);
}

async function connectAndLoad(entered: ProviderLogin, status: HTMLElement): Promise<void> {
  status.textContent = t('loading_your_playlist');
  try {
    const loaded = await loadProvider(entered, {
      liveContainer: player.liveContainer,
      // Live TV is usable before the on-demand catalogues arrive, so it is shown as soon as it
      // lands rather than made to wait for tens of thousands of films and series.
      onPartial: (partial) => {
        status.textContent = `${t('loading_playlist_from_network')} (${partial.items.length})`;
      },
    });
    writeJson(SAVED_LOGIN, entered);
    login = entered;
    catalogue = loaded;
    void writeCatalogue(cacheKey(entered), loaded);
    showSection('home');
  } catch (error) {
    status.textContent = error instanceof Error ? error.message : String(error);
  }
}

/**
 * Opens on the catalogue already on the device, and fetches a fresh one behind it.
 *
 * The viewer is looking at their playlist in the time it takes to read a local database, instead
 * of waiting on several megabytes over whatever connection the television has. What arrives later
 * replaces it quietly - a page that rebuilt itself under the viewer's hands would move the
 * highlight out from under them, so the refresh only redraws when they are still on Home, where
 * nothing is in the middle of being done.
 */
async function resumeFromCache(saved: ProviderLogin): Promise<void> {
  const key = cacheKey(saved);
  const cached = await readCatalogue(key);
  if (!cached) return;

  login = saved;
  catalogue = cached.playlist;
  showSection('home');

  try {
    const fresh = await loadProvider(saved, { liveContainer: player.liveContainer });
    catalogue = fresh;
    void writeCatalogue(key, fresh);
    if (section === 'home' && app.querySelector('.landing')) showSection('home');
  } catch {
    // The cached catalogue is still on screen and still usable. A television that cannot reach
    // the provider this minute should not be an error message over a working playlist.
  }
}

/* --------------------------------------------------------------- sections */

function detailsLoader(item: PlaylistItem) {
  if (!login || !item.channelId) return Promise.resolve(null);
  if (item.kind === 'movie') {
    return movieDetails(login, item.channelId).then((d) => ({ description: d.description, backdropUrl: d.backdropUrl }));
  }
  if (item.kind === 'series') {
    return seriesDetails(login, item.channelId, item.logoUrl).then((d) => ({ description: d.description, backdropUrl: d.backdropUrl }));
  }
  return Promise.resolve(null);
}

function rowsFor(current: Section, items: PlaylistItem[]): LandingRow[] {
  const openAll = () => browseScreen(current);
  const openFavorites = () => browseScreen(current, true);

  if (current === 'home') {
    return [
      {
        id: 'home-recent',
        title: t('landing_recently_added'),
        items: recentlyAdded(items),
        minSlots: 5,
      },
      {
        id: 'home-continue',
        title: t('continue_watching_title'),
        items: continueWatching(items),
        minSlots: 5,
        hint: t('continue_watching_placeholder_subtitle'),
      },
    ];
  }

  const kind = current === 'live' ? 'live' : current === 'movies' ? 'movie' : 'series';
  // Each section names its own box and its own hint. The television app writes them out in full
  // rather than composing "All " + section, because several of the eight languages do not build
  // that phrase by putting two words next to each other.
  const allCategories =
    current === 'live'
      ? t('landing_all_channel_categories')
      : current === 'movies'
        ? t('landing_all_movie_categories')
        : t('landing_all_series_categories');
  const favoritesHint =
    current === 'live'
      ? t('landing_hint_favorites_channels')
      : current === 'movies'
        ? t('landing_hint_favorites_movies')
        : t('landing_hint_favorites_series');
  const mine = items.filter((item) => item.kind === kind);
  return [
    {
      id: `${current}-recent`,
      title: current === 'live' ? t('home_recently_watched_live') : t('section_recently_watched'),
      items: continueWatching(mine),
      minSlots: 5,
      tile: {
        title: allCategories,
        caption: t(current === 'live' ? 'landing_browse_channels' : 'landing_browse_library'),
        onOpen: openAll,
      },
      hint: t('landing_hint_recent'),
    },
    {
      id: `${current}-favorites`,
      title: t('section_favorites'),
      items: favoriteItems(mine),
      minSlots: 5,
      tile: { title: t('section_favorites'), caption: t('landing_open_favorites'), onOpen: openFavorites },
      hint: favoritesHint,
    },
  ];
}

function showSection(next: Section): void {
  if (!catalogue) return;
  section = next;
  clear();
  document.body.classList.remove('playing');
  // Read on every navigation rather than once at boot, so changing it in Settings takes effect on
  // the way back out instead of at the next launch. Classic also clears whatever artwork is
  // already up, or turning it on leaves the last title's picture behind and looks like nothing
  // happened.
  const classic = backgroundMode() === 'classic';
  backdrop.followsFocus = !classic;
  // Live TV gets a darker page. Channel logos are bright marks on transparent backgrounds and
  // they wash out against the ordinary background, which is the same reason the Android app
  // darkens this section.
  document.body.classList.toggle('section-live', next === 'live');
  // Home starts over on the app's own artwork rather than whichever title was last looked at.
  if (next === 'home' || classic) backdrop.reset();

  const bar = renderNav(app, {
    current: next,
    onSection: (chosen) => showSection(chosen),
    onEnter: () => focusPageStart(app),
    onSearch: () => searchScreen(),
    // Straight to the language list, not to the settings menu with language somewhere in it. The
    // globe is a shortcut or it is nothing.
    onLanguage: () => settingsScreen('language'),
    onSettings: () => settingsScreen(),
  });
  detachNav = trackNavHighlight(bar);

  renderLanding(app, {
    rows: rowsFor(next, catalogue.items),
    backdrop,
    followBackdropImmediately: next !== 'home',
    onPlay: (item) => playScreen(item),
    loadDetails: detailsLoader,
  });

  // Focus starts on the bar, so the section tab is lit and Down enters the page - the same place
  // the television app starts.
  focus(bar.querySelector<HTMLElement>(`[data-focus-id="tab-${next}"]`));
}

/* --------------------------------------------------- search and settings */

function searchScreen(): void {
  if (!catalogue) return;
  clear();
  renderSearch(app, {
    items: catalogue.items,
    backdrop,
    onOpen: (item) => playScreen(item),
    onBack: () => showSection(section),
  });
}

function settingsScreen(openAt?: 'language'): void {
  clear();
  renderSettings(app, {
    // The globe in the bar opens the language list directly; the gear opens the menu.
    openAt,
    // Read from the catalogue each time rather than from whatever the browse screens are showing,
    // so a hidden category still appears here - it is the only place one can be brought back.
    categories: (kind) => [
      ...new Set((catalogue?.items ?? []).filter((item) => item.kind === kind).map((item) => item.group)),
    ].sort((a, b) => a.localeCompare(b)),
    // Every word on screen changes, so the page behind is rebuilt rather than patched.
    onLanguageChanged: () => {
      setFocusDirection(isRtl());
      settingsScreen();
    },
    // Asked for rather than captured, so App info describes the catalogue that is loaded now.
    playlist: () => catalogue,
    appVersion: appVersion(),
    // One store here where Android keeps three, so the kind comes from the catalogue - see
    // clearActivity. Redrawn afterwards so the row the viewer just pressed is still under them.
    onClearMovieActivity: () => {
      clearActivity((catalogue?.items ?? []).filter((item) => item.kind === 'movie'));
      settingsScreen();
    },
    onClearSeriesActivity: () => {
      clearActivity((catalogue?.items ?? []).filter((item) => item.kind === 'series'));
      settingsScreen();
    },
    onClearLiveActivity: () => {
      clearActivity((catalogue?.items ?? []).filter((item) => item.kind === 'live'));
      settingsScreen();
    },
    onClearCache: () => {
      if (!login) return;
      // Drop what is stored and fetch again, which is what a viewer means by "refresh": the
      // provider has added channels and the copy on the set is behind.
      void clearCatalogue(cacheKey(login)).then(() => {
        const saved = login!;
        catalogue = null;
        clear();
        const status = el('div', { class: 'status' }, t('loading_your_playlist'));
        app.append(status);
        void connectAndLoad(saved, status);
      });
    },
    onSignOut: () => {
      // The catalogue goes with the account. Leaving one behind would mean the next sign-in
      // opened on somebody else's playlist.
      if (login) void clearCatalogue(cacheKey(login));
      removeStored(SAVED_LOGIN);
      // The M3U goes too. Leaving it would have the next start quietly reload the playlist the
      // viewer has just removed.
      removeStored(SAVED_M3U);
      login = null;
      catalogue = null;
      loginScreen();
    },
    onSetPin: () => {
      askPin({
        title: t('create_parental_pin'),
        mode: 'set',
        onDone: (pin) => void setPin(pin).then(() => settingsScreen()),
        onCancel: () => settingsScreen(),
      });
    },
    onRemovePin: () => {
      // Behind the PIN it is removing: otherwise the control protects nothing from the one person
      // it exists to keep out.
      askPin({
        title: t('enter_parental_pin'),
        mode: 'verify',
        onDone: () => {
          removePin();
          relock();
          settingsScreen();
        },
        onCancel: () => settingsScreen(),
      });
    },
    onLockCategories: () => lockCategoriesScreen(),
    onBack: () => showSection(section),
  });
}

/**
 * Which categories the PIN stands in front of.
 *
 * Every category in the catalogue, across all three kinds, because a viewer locking adult films
 * will also want the channels that carry them, and sending them to three separate lists to do one
 * job is how a control goes unused.
 */
function lockCategoriesScreen(): void {
  if (!catalogue) return;
  clear();
  const groups = [...new Set(catalogue.items.map((item) => item.group))].sort((a, b) => a.localeCompare(b));
  const list = el('div', { class: 'sidebar wide', 'data-focus-group': 'lock-categories' });
  for (const group of groups) {
    const row = el(
      'div',
      {
        class: 'settings-row',
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': `lock-${group}`,
        'aria-selected': String(isCategoryLocked(group)),
      },
      group,
    );
    row.addEventListener('click', () => {
      const locked = toggleCategoryLock(group);
      row.setAttribute('aria-selected', String(locked));
    });
    list.append(row);
  }
  app.append(
    el('div', { class: 'browser-title' }, t('lock_categories')),
    el('div', { class: 'settings-note' }, t('lock_categories_desc')),
    list,
  );
  focus(list.querySelector<HTMLElement>('[data-focus]'));

  const release = pushKeyHandler((key: RemoteKey) => {
    if (key !== 'back') return false;
    release();
    settingsScreen();
    return true;
  });
}

/**
 * Runs [action] once the viewer has proved they may, and does nothing if they cannot.
 *
 * Asked once per run rather than per item: a control that demands the PIN for every channel in a
 * locked category is a control that gets switched off.
 */
function behindPin(locked: boolean, action: () => void): void {
  if (!locked || isUnlocked()) {
    action();
    return;
  }
  askPin({
    title: t('enter_parental_pin'),
    mode: 'verify',
    onDone: () => {
      markUnlocked();
      action();
    },
    onCancel: () => undefined,
  });
}

/* ---------------------------------------------------------------- browser */

function browseScreen(current: Section, favoritesOnly = false): void {
  if (!catalogue) return;
  clear();
  const kind = current === 'live' ? 'live' : current === 'movies' ? 'movie' : 'series';
  let pool = catalogue.items.filter((item) => item.kind === kind);
  if (favoritesOnly) pool = favoriteItems(pool, Number.MAX_SAFE_INTEGER);

  // Hidden categories are filtered out of the pool, not just out of the sidebar - otherwise the
  // grid would still show their contents whenever a search or a favourites view reached across
  // categories, and "hidden" would only mean "hidden from this one list".
  const hidden = new Set(hiddenCategories(kind));
  pool = pool.filter((item) => !hidden.has(item.group));

  // Channel order, for Live TV only - the other two are grids of artwork where the provider's
  // order means much less. Applied to the pool rather than per category, so every category and
  // the Favorites row all run the same way round. localeCompare rather than a plain comparison,
  // because half these names are Arabic and a codepoint sort puts them in an order no reader of
  // Arabic would call alphabetical.
  if (kind === 'live') {
    const order = liveChannelSort();
    if (order !== 'default') {
      const direction = order === 'az' ? 1 : -1;
      pool = [...pool].sort((a, b) => direction * a.name.localeCompare(b.name, locale()));
    }
  }
  const groups = applyCategoryOrder(
    kind,
    [...new Set(pool.map((item) => item.group))].sort((a, b) => a.localeCompare(b)),
  );

  /*
   * The rows the television app puts above the provider's own categories.
   *
   * They are not groups - nothing in the catalogue carries them - so they are held beside the
   * category list and looked up by name when the grid is drawn. An empty one is left out rather
   * than shown empty, which is what Android does: a "Continue watching" with nothing behind it is
   * a row that teaches the viewer to ignore the top of the list.
   *
   * Live TV calls its resume list "Recently watched" and the others "Continue watching", because
   * a channel is not something you are part way through.
   */
  const specials: [string, PlaylistItem[]][] = [];
  const resumable = continueWatching(pool, 60);
  if (resumable.length) {
    specials.push([
      current === 'live' ? t('section_recently_watched') : t('section_continue_watching'),
      resumable,
    ]);
  }
  const faves = favoriteItems(pool, 60);
  if (faves.length) specials.push([t('section_favorites'), faves]);
  const specialNames = specials.map(([name]) => name);

  let selected = specialNames[0] ?? groups[0] ?? '';
  /** What the box above the category list is filtering by, across the whole section. */
  let search = '';
  let searchTimer: number | null = null;
  /** The category being hand-moved after the menu's first action - Up and Down nudge it. */
  let reordering: string | null = null;

  // Live TV is a list of channels beside a preview, the way the television app lays it out - not a
  // wall of posters. A channel has a logo and a name and no artwork worth a poster's space, and
  // what the viewer actually wants to know is what is on it right now, which needs the preview and
  // the listings next to the list rather than underneath a grid.
  const live = current === 'live';
  const grid = el('div', {
    class: live ? 'channel-list' : 'grid',
    'data-focus-group': live ? 'browser-channels' : 'browser-grid',
  });
  const sidebar = el('div', { class: 'sidebar', 'data-focus-group': 'browser-categories' });

  // What is on the focused channel now and next. Only Live TV has listings, and only for the one
  // channel the viewer settles on - see createEpgLoader.
  const guide = el('div', { class: 'guide' });
  const epg = createEpgLoader(login, (channel, result) => {
    if (focusedChannelId !== channel.channelId) return;
    guide.textContent = '';
    if (!result.now && !result.next) {
      guide.append(el('div', { class: 'guide-now' }, t('epg_no_info')));
      return;
    }
    if (result.now) {
      guide.append(
        el(
          'div',
          { class: 'guide-now' },
          t('epg_now_format', `${clockTime(result.now.startEpochSeconds, locale())}  ${result.now.title}`),
        ),
      );
      if (result.progress !== null) {
        guide.append(
          el('div', { class: 'progress' }, el('div', { class: 'progress-fill live', style: `width:${result.progress * 100}%` })),
        );
      }
    }
    if (result.next) {
      guide.append(
        el(
          'div',
          { class: 'guide-next' },
          t('epg_next_format', `${clockTime(result.next.startEpochSeconds, locale())}  ${result.next.title}`),
        ),
      );
    }
  });
  let focusedChannelId: string | null = null;

  /**
   * The box the preview picture is aimed at, and the machinery to keep it aimed there.
   *
   * Debounced like the backdrop, and for the same reason: holding the D-pad through thirty
   * channels must cost one stream, not thirty. A provider will throttle or simply refuse an app
   * that opens a connection per keypress, and on a set the tuning delay would make the list feel
   * broken rather than responsive.
   */
  const preview = el('div', { class: 'live-preview' });
  let previewTimer: number | null = null;
  let previewing: string | null = null;

  function stopPreview(): void {
    if (previewTimer !== null) {
      window.clearTimeout(previewTimer);
      previewTimer = null;
    }
    // Stopped whether or not this screen believes it started something. `previewing` is set after
    // play() resolves, so a stream that was opening when the viewer left would not have been
    // counted - and an open decoder keeps painting on the plane behind the page, which is how a
    // half-drawn frame ended up behind the Movies grid.
    previewing = null;
    player.stop();
    // The hole closes with the picture. Leaving it open would show the plane's black rather than
    // the page's own background on every screen that follows.
    document.body.classList.remove('previewing');
  }

  function schedulePreview(item: PlaylistItem): void {
    if (previewTimer !== null) window.clearTimeout(previewTimer);
    previewTimer = window.setTimeout(() => {
      previewTimer = null;
      if (previewing === item.streamUrl) return;
      // A locked category must not start playing behind its own PIN prompt.
      if (isCategoryLocked(item.group) && !isUnlocked()) return;
      if (isChannelLocked(itemKey(item)) && !isUnlocked()) return;
      player.stop();
      previewing = item.streamUrl;
      const box = preview.getBoundingClientRect();
      void player
        .play(item.streamUrl, box)
        .then(() => {
          // The page has to get out of the way, or there is nothing to see.
          //
          // AVPlay paints on a plane *behind* the page, and this page is opaque: a body gradient
          // over the whole screen and three full-size backdrop layers above that. The preview box
          // being transparent reveals the page's own background, not the video - so before this,
          // the decoder ran, the display rectangle was set correctly, and the viewer saw the
          // Live TV gradient. Full screen already solves this with body.playing; a preview needs
          // the same hole punched, and body.previewing is that.
          //
          // A hole exactly the size of the picture, not the whole screen. Taking the backdrop away
          // altogether was the first attempt and it made the rest of the page black, because
          // outside the decoder's rectangle the video plane has nothing on it - see the clip-path
          // these four values drive.
          const root = document.documentElement;
          root.style.setProperty('--preview-x', `${Math.round(box.x)}px`);
          root.style.setProperty('--preview-y', `${Math.round(box.y)}px`);
          root.style.setProperty('--preview-right', `${Math.round(box.right)}px`);
          root.style.setProperty('--preview-bottom', `${Math.round(box.bottom)}px`);
          document.body.classList.add('previewing');
        })
        .catch(() => {
          // A channel that will not tune is not an error worth a dialog while browsing - the
          // viewer is passing through. The listings beside it still say what is on.
          previewing = null;
          document.body.classList.remove('previewing');
        });
    }, PREVIEW_DELAY_MS);
  }

  function renderGrid(): void {
    grid.textContent = '';
    // The one place a locked category can be held back. Guarding the focus handler instead would
    // miss the category the page opens on, which is simply the first in the list - and if that
    // one happens to be locked, the contents are on screen before anybody has moved.
    if (isCategoryLocked(selected) && !isUnlocked()) {
      const prompt = el('div', { class: 'locked-panel' });
      prompt.append(el('div', { class: 'locked-mark-big' }, '\u{1F512}'), el('div', {}, t('enter_parental_pin')));
      const unlock = el('button', { class: 'button', 'data-focus': '', 'data-focus-id': 'unlock' }, t('unlock_action'));
      unlock.addEventListener('click', () =>
        behindPin(true, () => {
          renderGrid();
          focus(grid.querySelector<HTMLElement>('[data-focus]'));
        }),
      );
      prompt.append(unlock);
      grid.append(prompt);
      return;
    }
    // A special row is looked up by name; everything else is a real category on the items. A
    // search replaces whichever is showing and runs across the whole section, as it does on the
    // television - searching inside one category is rarely what somebody means by searching.
    const base = specials.find(([name]) => name === selected)?.[1]
      ?? pool.filter((entry) => entry.group === selected);
    const query = search.trim().toLowerCase();
    const shown = query
      ? pool.filter((entry) => entry.name.toLowerCase().includes(query))
      : base;
    if (query && !shown.length) {
      grid.append(el('div', { class: 'grid-empty' }, t('search_no_results', search.trim())));
      return;
    }
    for (const item of shown.slice(0, 400)) {
      const card = el('div', {
        class: live ? 'channel-row' : 'poster',
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': itemKey(item),
      });
      const art = el('img', { class: live ? 'channel-logo' : 'poster-art', alt: '' }) as HTMLImageElement;
      if (item.logoUrl) art.src = item.logoUrl;
      art.addEventListener('error', () => art.removeAttribute('src'));
      // Portrait artwork with the name beneath it, which is what the television app's browse grids
      // show - not the landscape cards the landing rows use. The two are different shapes on
      // purpose: a row is a shelf of stills, a grid is a wall of posters.
      card.append(art, el('div', { class: live ? 'channel-name' : 'poster-label' }, item.name));
      if (!live && item.year) card.append(el('div', { class: 'poster-year' }, item.year));
      card.addEventListener('focus', () => {
        if (item.kind !== 'live') backdrop.show(item.logoUrl);
        focusedChannelId = item.channelId;
        if (item.kind === 'live') {
          // The name first, so the panel is never empty while the listings are on their way.
          guide.textContent = '';
          guide.append(el('div', { class: 'guide-now' }, item.name));
          epg.request(item);
          schedulePreview(item);
        }
      });
      card.addEventListener('click', () =>
        behindPin(isCategoryLocked(item.group) || isChannelLocked(itemKey(item)), () => {
          // Handed over rather than left running: the preview and the full screen are the same
          // decoder, and two calls to play() without a stop between them is how a set ends up
          // showing the previous channel with the new one's sound.
          /*
           * A channel already on screen is handed over rather than stopped and reopened.
           *
           * The preview and full screen are the same decoder, so restarting it means several
           * seconds of black for a picture that was already running. When the channel being
           * opened is the one previewing, the timer is cancelled, the teardown that clear() would
           * otherwise run is taken off the hook, and playScreen is told to resize rather than
           * play. Any other channel still goes the long way round, because that genuinely is a
           * different stream.
           */
          if (item.kind === 'live' && previewing === item.streamUrl) {
            if (previewTimer !== null) {
              window.clearTimeout(previewTimer);
              previewTimer = null;
            }
            previewing = null;
            leaveScreen = null;
            playScreen(item, [], true);
            return;
          }
          stopPreview();
          // A channel is a thing you turn on; a film is a thing you choose. Picking a channel out
          // of the list means watch it now - there is nothing to read about it first, and the
          // listings beside the list have already said what is on. A film has a plot, a cast and a
          // running time, and starting it was the only way to see any of them.
          if (item.kind === 'movie') {
            // Noted on the way out, so Back can land on this poster in this category rather than
            // at the top of the list - see the matching block where the grid is first focused.
            browseReturn = { section: current, category: selected, focusKey: itemKey(item) };
            void detailsScreen(item);
          } else {
            playScreen(item);
          }
        }),
      );
      grid.append(card);
    }
  }

  /**
   * Draws the category list from [groups].
   *
   * Called again whenever the order or the hidden set changes, rather than re-entering
   * browseScreen. Re-entering would push a second key handler onto the stack without releasing the
   * first, and would throw away the closure that is holding which row is being carried - so the
   * hand-move would forget itself after a single nudge.
   */
  function renderSidebar(): void {
    sidebar.textContent = '';
    // The box sits above the list, where the television app puts it. Typing in it is the one thing
    // on this screen that is not a D-pad move, so it is first in the focus order rather than
    // buried under a hundred categories.
    const field = el('input', {
      class: 'category-search',
      type: 'text',
      placeholder: current === 'live' ? t('search_channels') : current === 'movies' ? t('search_all_movies') : t('search_all_series'),
      tabindex: '-1',
      'data-focus': '',
      'data-focus-id': 'browse-search',
      'data-focus-up': 'none',
    }) as HTMLInputElement;
    field.value = search;
    field.addEventListener('input', () => {
      search = field.value;
      // Debounced for the reason the television app debounces it: a provider list runs to tens of
      // thousands of titles, and re-filtering and re-drawing on every keystroke is what made
      // typing feel like it was lagging a letter behind.
      if (searchTimer !== null) window.clearTimeout(searchTimer);
      searchTimer = window.setTimeout(() => {
        searchTimer = null;
        renderGrid();
      }, 320);
    });
    sidebar.append(field);

    for (const group of [...specialNames, ...groups]) {
    const special = specialNames.includes(group);
    const row = el(
      'div',
      {
        class: special ? 'category special' : 'category',
        // Without this the browser refuses focus and the whole screen is unusable - see
        // ensureFocusable in ui/focus.ts.
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': group,
        'aria-selected': String(group === selected),
      },
      group,
    );
    if (group === reordering) row.classList.add('reordering');
    // Focus selects, as on the television: moving down the list changes what the grid shows
    // without needing a press for each one.
    row.addEventListener('focus', () => {
      selected = group;
      for (const other of sidebar.querySelectorAll('.category')) {
        other.setAttribute('aria-selected', String(other.getAttribute('data-focus-id') === group));
      }
      renderGrid();
    });
    // A held OK opens the menu, which is the television app's long press arriving the only way a
    // remote can express it on the web - see openCategoryMenu.
    row.addEventListener('keydown', (event) => {
      if (!event.repeat) return;
      // Continue watching, Recently watched and Favorites are not the provider's categories:
      // there is nothing behind them to hide, reorder or move, so they carry no menu.
      if (special) return;
      const key = keyOf(event, platform);
      if (key !== 'enter') return;
      event.preventDefault();
      event.stopPropagation();
      openCategoryMenu(app, {
        category: group,
        onHide: () => {
          hideCategory(kind, group);
          // The whole screen, because hiding changes the pool the grid is drawn from, not just the
          // list of rows. This is also the one action that cannot leave focus where it was: the
          // row it was on has gone.
          browseScreen(current, favoritesOnly);
        },
        onMoveManually: () => {
          reordering = group;
          renderSidebar();
          focusCategory(group);
        },
        onMoveToTop: () => {
          moveCategoryToEnd(kind, groups, group, true);
          reorderGroups();
          focusCategory(group);
        },
        onMoveToBottom: () => {
          moveCategoryToEnd(kind, groups, group, false);
          reorderGroups();
          focusCategory(group);
        },
        onDismiss: () => undefined,
      });
    });
    sidebar.append(row);
    }
  }

  /** Re-reads the stored order into [groups] and redraws the list. */
  function reorderGroups(): void {
    const reordered = applyCategoryOrder(kind, groups);
    groups.length = 0;
    groups.push(...reordered);
    renderSidebar();
  }

  /** Puts the highlight back on a named row after the list has been rebuilt under it. */
  function focusCategory(group: string): void {
    for (const row of sidebar.children) {
      if (row.getAttribute('data-focus-id') === group) {
        focus(row as HTMLElement);
        return;
      }
    }
  }

  // Registered for every section, not only Live TV. Movies and Series never start a preview of
  // their own, but they can be the screen a viewer lands on *from* Live TV, and whatever is still
  // decoding has to be stopped by the page arriving rather than by the page leaving.
  leaveScreen = stopPreview;
  // Belt and braces: the page is only transparent while something is deliberately playing full
  // screen. Anywhere else an opaque page is what guarantees a stray frame cannot show through,
  // whatever the decoder is doing.
  document.body.classList.remove('playing');

  renderSidebar();

  const back = el('div', { class: 'browser-title' }, t(current === 'live' ? 'nav_live_tv' : current === 'movies' ? 'nav_movies' : 'nav_series'));
  if (live) {
    // Three columns, as on the television: categories, channels, and what is on. The preview is an
    // empty box on purpose - AVPlay paints behind the page, so this element exists to be measured,
    // and the picture appears in the hole its background leaves.
    app.append(
      back,
      el(
        'div',
        { class: 'browser' },
        sidebar,
        grid,
        el('div', { class: 'browser-main' }, preview, guide),
      ),
    );
  } else {
    // The grid is a flex child of .browser directly, not wrapped: it has to be the thing that
    // takes the width left over by the category column, because that width is what decides how
    // wide its seven columns are.
    app.append(back, el('div', { class: 'browser' }, sidebar, grid));
  }
  renderGrid();

  /*
   * Coming back from a film's page lands on that film, in the category it was picked from.
   *
   * Without this, opening a film and pressing Back returned to the top of whichever category the
   * browser happens to open on - so looking at three films in a row meant finding your place in a
   * four-hundred-poster grid three times. The television app calls this restoreFocusKey and it is
   * the reason it is possible to browse there at all.
   *
   * Consumed on use. It describes one return, not a standing preference, and leaving it set would
   * have the next arrival from the navigation bar land somewhere the viewer did not ask for.
   */
  const returning = browseReturn && browseReturn.section === current ? browseReturn : null;
  browseReturn = null;

  const category = returning
    ? sidebar.querySelector<HTMLElement>(`.category[data-focus-id="${cssEscape(returning.category)}"]`)
    : null;
  if (category) {
    // Focusing it is what selects it and redraws the grid - see the row's own focus handler - so
    // the grid below is the right one by the time the poster is looked for.
    focus(category);
    const poster = grid.querySelector<HTMLElement>(`[data-focus-id="${cssEscape(returning!.focusKey)}"]`);
    if (poster) focus(poster);
  } else {
    // The category list, not the search box above it: arriving on this page should leave the
    // highlight where the next press is most likely to be wanted, and that is the list.
    focus(sidebar.querySelector<HTMLElement>('.category[data-focus]'));
  }

  /*
   * Live TV opens with the first channel already tuning behind the list.
   *
   * The preview is otherwise started by a channel row taking focus, and on arrival focus is on the
   * category list - so the picture stayed black until the viewer stepped right into the channels,
   * which made the screen look broken rather than idle. This is the same call the focus handler
   * makes, including its debounce, so stepping straight off onto another channel cancels it
   * before it ever becomes a request.
   */
  if (live) {
    const first = pool.find((item) => item.group === selected)
      ?? specials.find(([name]) => name === selected)?.[1][0];
    if (first) {
      focusedChannelId = first.channelId;
      guide.textContent = '';
      guide.append(el('div', { class: 'guide-now' }, first.name));
      epg.request(first);
      schedulePreview(first);
    }
  }

  const release = pushKeyHandler((key: RemoteKey) => {
    // Hand-moving takes over Up and Down entirely: while a row is being carried, those keys move
    // the row rather than the highlight, and OK or Back puts it down. Without the takeover the
    // highlight would walk away from the row it is supposed to be moving.
    if (reordering) {
      if (key === 'up' || key === 'down') {
        moveCategory(kind, groups, reordering, key === 'up');
        const carried = reordering;
        reorderGroups();
        focusCategory(carried);
        return true;
      }
      if (key === 'enter' || key === 'back') {
        reordering = null;
        renderSidebar();
        return true;
      }
      // Everything else is swallowed while a row is being carried. Wandering off to the grid
      // mid-move would leave a category picked up with nothing holding it.
      return true;
    }
    if (key !== 'back') return false;
    release();
    showSection(current);
    return true;
  });
}

/* ------------------------------------------------------------------- play */

/**
 * A series is opened, not played: its `series://` url is an identifier, not a stream. The episode
 * list is fetched here rather than on the landing page because it is several hundred kilobytes
 * for a long-running show, and nobody wants that for every series they scroll past.
 */
async function seriesScreen(item: PlaylistItem): Promise<void> {
  if (!login || !item.channelId) return;
  clear();
  document.body.classList.remove('playing');
  const status = el('div', { class: 'status' }, t('loading_seasons_episodes'));
  app.append(status);
  try {
    const details = await seriesDetails(login, item.channelId, item.logoUrl);
    clear();
    renderSeries(app, {
      series: item,
      details,
      backdrop,
      onEpisode: (episode) =>
        playScreen(
          {
            ...item,
            // The episode is what plays, and what a resume point belongs to - a series has no
            // single position of its own.
            name: `${item.name} • ${episode.title}`,
            streamUrl: episode.streamUrl,
            channelId: episode.id,
            kind: 'movie',
          },
          // Only the season being watched. Handing over every episode of every season would make
          // the strip a list nobody can get to the end of, and the season is the unit somebody
          // moving to "the next one" means.
          details.episodes
            .filter((entry) => entry.seasonNumber === episode.seasonNumber)
            .map((entry) => ({
              id: entry.id,
              label: `${item.name} • ${entry.title}`,
              thumbnailUrl: entry.thumbnailUrl,
              streamUrl: entry.streamUrl,
            })),
        ),
      onBack: () => showSection(section),
    });
  } catch (error) {
    status.textContent = error instanceof Error ? error.message : t('episodes_unavailable');
    const release = pushKeyHandler((key: RemoteKey) => {
      if (key !== 'back') return false;
      release();
      showSection(section);
      return true;
    });
  }
}

/**
 * A film's page, before playing it.
 *
 * Drawn twice on purpose. The first pass uses only what the catalogue already holds - poster,
 * title, category, and whether there is a position to resume from - so the page is on screen
 * immediately; the second replaces it once the provider has answered with the plot and credits.
 * Waiting for the lookup before drawing anything would put a spinner in front of every film, and
 * most of what somebody needs to decide is known before the request is even sent.
 *
 * The highlight is placed once, on the first pass. Moving it on the redraw would take the viewer
 * back to Play from wherever they had walked to in the meantime.
 */
async function detailsScreen(item: PlaylistItem): Promise<void> {
  clear();
  document.body.classList.remove('playing');

  const back = (): void => browseScreen(section);
  const open = (): void => playScreen(item);

  const draw = (details: MovieDetails | null, loading: boolean): HTMLElement =>
    renderDetails(app, { movie: item, details, loading, backdrop, onPlay: open, onBack: back });

  focus(draw(null, true));

  if (!login || !item.channelId) return;
  let details: MovieDetails | null = null;
  try {
    details = await movieDetails(login, item.channelId);
  } catch {
    // A panel that will not answer is not worth a dialog: the page already carries the poster, the
    // title and the Play button, and those are the parts somebody came here to use.
    details = null;
  }
  // Only if the viewer is still here. An await outlives the screen that started it, and redrawing
  // over whatever replaced this one is how a film's plot ends up on the settings page.
  if (!app.querySelector('.details')) return;
  const held = document.activeElement instanceof HTMLElement
    ? document.activeElement.getAttribute('data-focus-id')
    : null;
  clear();
  const play = draw(details, false);
  focus(app.querySelector<HTMLElement>(`[data-focus-id="${held}"]`) ?? play);
}

/**
 * Full-screen playback.
 *
 * [siblings] is the rest of the season when an episode is playing, which is what puts the strip
 * under the controls. Passed in rather than looked up here, because the season has already been
 * fetched by the screen that got us here and asking the provider for it twice would be a round
 * trip in front of the picture.
 */
function playScreen(item: PlaylistItem, siblings: StripEpisode[] = [], handover = false): void {
  if (item.kind === 'series') {
    void seriesScreen(item);
    return;
  }
  clear();
  document.body.classList.add('playing');
  // The preview's hole in the backdrop goes with the preview. Full screen takes the whole backdrop
  // away instead, which .playing above already does.
  document.body.classList.remove('previewing');

  let durationMs = 0;
  let positionMs = 0;

  const overlay = createPlayerOverlay({
    title: item.name,
    player,
    // A channel has no timeline to scrub and no end to run towards, so it gets a shorter set of
    // controls - see PlayerOverlayOptions.live.
    live: item.kind === 'live',
    episodes: siblings,
    currentEpisodeId: item.channelId,
    onEpisode: (episode) => {
      // The position belongs to the episode being left, not to the one arriving.
      rememberPosition(item, positionMs, durationMs);
      player.stop();
      overlay.destroy();
      release();
      // Re-entered rather than swapped in place: a new stream means a new duration, a new resume
      // point and a new title, and rebuilding is how all three stay in step. The season is handed
      // on so the strip is still there on the next episode.
      playScreen(
        { ...item, name: episode.label, streamUrl: episode.streamUrl, channelId: episode.id },
        siblings,
      );
    },
    onExit: (at) => {
      rememberPosition(item, at, durationMs);
      player.stop();
      overlay.destroy();
      release();
      showSection(section);
    },
  });
  app.append(overlay.element);

  player.on((event) => {
    if (event.type === 'error') overlay.setMessage(event.message);
    if (event.type === 'ready') {
      durationMs = event.durationMs;
      overlay.setPosition(positionMs, durationMs);
      // Applied on ready rather than before play: AVPlay rejects a display method on a stream it
      // has not prepared, so setting it earlier is silently thrown away and the viewer's choice
      // appears to have been forgotten between one title and the next.
      player.setScaling(videoScaling());
    }
    if (event.type === 'progress') {
      positionMs = event.positionMs;
      overlay.setPosition(positionMs, durationMs);
    }
    if (event.type === 'playing') overlay.setPaused(false);
    if (event.type === 'paused') overlay.setPaused(true);
    if (event.type === 'subtitle') overlay.setCaption(event.text);
  });

  const full = new DOMRect(0, 0, window.innerWidth, window.innerHeight);
  if (handover) {
    /*
     * Already playing. Grow the picture instead of starting it again.
     *
     * Opening a channel used to stop the preview and open the same stream over, which on AVPlay
     * means close, open, prepare, play - several seconds of black on the way into a channel that
     * was already decoding perfectly a moment earlier. Nothing about the stream has changed; only
     * the rectangle it is drawn in has. The television app gets this for free by putting its
     * fullscreen view in a dialog over the same player instance.
     */
    player.setRect(full);
    overlay.setPaused(false);
    player.setScaling(videoScaling());
  } else {
    void player.play(item.streamUrl, full).catch((error: unknown) => {
      overlay.setMessage(error instanceof Error ? error.message : String(error));
    });
  }

  // Every key goes to the overlay, and nothing falls through to the page underneath. An arrow key
  // reaching the page would move a highlight the viewer cannot see, on a screen that is not there.
  const release = pushKeyHandler((key: RemoteKey) => {
    overlay.handleKey(key);
    return true;
  });
}

/* ------------------------------------------------------------------- boot */

function boot(): void {
  registerPlatformKeys(platform);
  setLocale(navigator.language?.slice(0, 2) ?? 'en');
  setFocusDirection(isRtl());
  player = createPlayer(platform, video);
  backdrop = new Backdrop(backdropHost);

  window.addEventListener('keydown', (event) => {
    const key = keyOf(event, platform);
    if (!key) return;
    // Back is the one key a television acts on itself when the app ignores it - Samsung closes
    // the app - so it is always consumed here and answered by whatever is on top.
    event.preventDefault();
    handleKey(key);
  });

  // A way to exercise the pages without a provider account. Stripped from a production build by
  // the import.meta.env.DEV check, so it cannot reach a television: the screens can be checked
  // against made-up data instead of against somebody's real playlist and credentials.
  if (import.meta.env.DEV) {
    (window as unknown as Record<string, unknown>).__dev = {
      load(sample: LoadedPlaylist) {
        catalogue = sample;
        login = null;
        showSection('home');
      },
      section: (next: Section) => showSection(next),
      series(item: PlaylistItem, details: Parameters<typeof renderSeries>[1]['details']) {
        clear();
        renderSeries(app, {
          series: item,
          details,
          backdrop,
          onEpisode: () => undefined,
          onBack: () => showSection('series'),
        });
      },
    };
  }

  const saved = readJson<ProviderLogin | null>(SAVED_LOGIN, null);
  const savedM3u = readJson<{ name: string; url: string } | null>(SAVED_M3U, null);
  if (!saved && savedM3u) {
    // An M3U has no login to replay, so it is simply fetched again. There is no cache behind it
    // either: the whole playlist is one download, and a stale copy is worth less than the wait.
    clear();
    const status = el('div', { class: 'status' }, t('loading_your_playlist'));
    app.append(status);
    void loadM3u(savedM3u.name, savedM3u.url).then(
      (loaded) => {
        catalogue = loaded;
        showSection('home');
      },
      (error: unknown) => {
        status.textContent = error instanceof Error ? error.message : t('playlist_could_not_be_loaded');
      },
    );
    return;
  }
  if (!saved) {
    loginScreen();
    return;
  }

  // "Ask PIN on startup" means before anything is on screen, not after. A prompt over a page that
  // has already drawn the locked categories has not protected them.
  const guard = parental();
  if (guard.enabled && guard.pinHash && guard.askOnStartup) {
    const ask = () =>
      askPin({
        title: t('enter_parental_pin'),
        mode: 'verify',
        onDone: () => {
          markUnlocked();
          start(saved);
        },
        // No way past it: cancelling asks again rather than letting the app open unlocked, which
        // is the whole point of the setting. It re-asks rather than restarting boot, which would
        // register a second key listener and build a second player every time round.
        onCancel: ask,
      });
    ask();
    return;
  }
  start(saved);
}

function start(saved: ProviderLogin): void {
  // Straight to the catalogue already on the device when there is one; otherwise the sign-in
  // page with the saved details filled in, fetching while it shows.
  void (async () => {
    const cached = await readCatalogue(cacheKey(saved));
    if (cached) {
      void resumeFromCache(saved);
      return;
    }
    loginScreen('');
    const status = app.querySelector<HTMLElement>('.message');
    const fields = app.querySelectorAll<HTMLInputElement>('.field input');
    if (fields[0]) fields[0].value = saved.name;
    if (fields[1]) fields[1].value = saved.address;
    if (fields[2]) fields[2].value = saved.username;
    if (fields[3]) fields[3].value = saved.password;
    if (status) void connectAndLoad(saved, status);
  })();
}

boot();
