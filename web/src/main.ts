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
import { setLocale, locale, t } from './shared/i18n';
import { loadProvider, movieDetails, seriesDetails } from './shared/xtream';
import type { LoadedPlaylist, MovieDetails, PlaylistItem, ProviderLogin } from './shared/models';
import { itemKey } from './shared/models';
import {
  clearActivity, continueWatching, favoriteItems, recordWatched, watchedLately, isFavorite, recentlyAdded, rememberPosition,
  resumePosition, toggleFavorite,
} from './shared/library';
import { askResume } from './ui/resumeChoice';
import { iconElement } from './ui/icons';
import { focus, handleKey, pushKeyHandler } from './ui/focus';
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
import { lazyImage, posterArtwork, prefetchAfter } from './ui/images';
import { appVersion, identity } from './platform/identity';
import { activate, ActivationPending } from './shared/activation';
import { loadM3u } from './shared/m3u';
import {
  isCategoryLocked, isChannelLocked, isUnlocked, markUnlocked, parental,
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

/** A channel still playing as it comes back to the list, for the list to keep as its preview. */
let carriedPreview: string | null = null;

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
async function resumeFromCache(
  saved: ProviderLogin,
  cached: { playlist: LoadedPlaylist; ageMs: number },
): Promise<void> {
  const key = cacheKey(saved);
  login = saved;
  catalogue = cached.playlist;
  showSection('home');

  /*
   * Refreshed once a day, not on every launch.
   *
   * This fetched the whole account again every time the app opened: several megabytes downloaded,
   * parsed and written back to the database, all on the one thread that also answers the remote,
   * during exactly the minute the viewer is finding something to watch. The Android app refreshes
   * a cached playlist only when its auto-update interval has passed, and that interval defaults
   * to daily (PlaylistViewModel.shouldAutoRefresh); this is that default. The interval setting
   * itself is not offered here - see web/README.md - and Refresh playlist in Settings still
   * fetches on demand.
   */
  if (cached.ageMs >= AUTO_REFRESH_MS) void refreshInBackground(saved, key);
}

/** The Android app's default auto-update interval, "daily". */
const AUTO_REFRESH_MS = 24 * 60 * 60 * 1000;

async function refreshInBackground(saved: ProviderLogin, key: string): Promise<void> {
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
      items: watchedLately(mine),
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
    onBack: () => showSection(section),
  });
}

/** The star on a poster or channel row, in the state [item] is in now. */
function favouriteStar(item: PlaylistItem): SVGSVGElement {
  const on = isFavorite(item);
  return iconElement(on ? 'star' : 'starBorder', on ? 'fav-star is-on' : 'fav-star');
}

/**
 * How long OK has to be held to count as a hold rather than a press.
 *
 * Android's long-press timeout, which is what the television's combinedClickable waits for:
 * ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT, 400ms on current releases and 500 before. The
 * longer of the two, because a remote's OK is pressed firmly and a favourite toggled by a slow
 * ordinary press is the worse mistake.
 */
const HOLD_MS = 500;

/**
 * Holding OK on [target] runs [onHold]; a press and release is still an ordinary press.
 *
 * The television's long press, which it uses in two places this shares: on a channel row "press-
 * and-hold OK toggles favorite", and on a category it opens CategoryActionsDialog. Posters take the
 * favourite hold too, so the one gesture works wherever a title is listed - on Android a poster's
 * star is a touch target the remote cannot reach.
 *
 * Telling the two apart means the ordinary press cannot act on the way down any more, because
 * nobody knows yet whether it will be held. So OK is taken here, before the window's handler sees
 * it: the press waits for the key to come up and clicks then, and a key still down after HOLD_MS
 * is a hold instead.
 *
 * For as long as the key stays down after that, its repeats are swallowed at the window, wherever
 * the highlight has gone. Otherwise a hold that opens a menu goes on pressing OK on the menu's
 * first entry, which is what the category menu used to rely on the viewer letting go quickly
 * enough to avoid. It also used to wait for the browser to flag a keydown as a repeat, which is
 * not something every set's remote is promised to send; the timer does not depend on it.
 */
function holdOk(target: HTMLElement, onHold: () => void): void {
  let timer: number | null = null;
  let pressing = false;

  const isOk = (event: KeyboardEvent): boolean => keyOf(event, platform) === 'enter';

  const swallow = (event: KeyboardEvent): void => {
    if (!isOk(event)) return;
    event.preventDefault();
    event.stopPropagation();
  };

  const release = (event: KeyboardEvent): void => {
    if (!isOk(event)) return;
    event.preventDefault();
    event.stopPropagation();
    const wasPress = timer !== null;
    finish();
    if (wasPress) target.click();
  };

  function finish(): void {
    if (timer !== null) window.clearTimeout(timer);
    timer = null;
    pressing = false;
    window.removeEventListener('keydown', swallow, true);
    window.removeEventListener('keyup', release, true);
  }

  target.addEventListener('keydown', (event) => {
    if (!isOk(event)) return;
    event.preventDefault();
    event.stopPropagation();
    if (pressing) return;
    pressing = true;
    // Capturing at the window, so the rest of this press is ours even after the highlight moves.
    window.addEventListener('keydown', swallow, true);
    window.addEventListener('keyup', release, true);
    timer = window.setTimeout(() => {
      timer = null;
      onHold();
    }, HOLD_MS);
  });
  // The highlight leaving before the hold has counted - nothing else can move it mid-press, but
  // the page can go - is a press that never finished, and must not click later.
  target.addEventListener('blur', () => {
    if (timer !== null) finish();
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

  /*
   * Categories in the provider's order: the order they first appear in its list, which is how the
   * television builds it (`channels.map { it.group }.distinct()`, no sort). They were sorted A to
   * Z here, which is not an order any provider chose - panels put their own country or their
   * headline categories first on purpose. Taken before the channel sort below, which would
   * otherwise reorder the categories along with the channels. A viewer's own arrangement, from the
   * category menu, still goes on top of it.
   */
  const providerGroups = [...new Set(pool.map((item) => item.group))];

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
  const groups = applyCategoryOrder(kind, providerGroups);

  // Each category's entries, gathered once. Choosing a category used to filter the whole section
  // - tens of thousands of entries on a full account - every time the highlight reached it, so
  // running down the category list did that scan once per row passed.
  const byGroup = new Map<string, PlaylistItem[]>();
  for (const item of pool) {
    const members = byGroup.get(item.group);
    if (members) members.push(item);
    else byGroup.set(item.group, [item]);
  }

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
  const specials: [string, PlaylistItem[]][] = [
    [
      current === 'live' ? t('section_recently_watched') : t('section_continue_watching'),
      watchedLately(pool, 60),
    ],
    [t('section_favorites'), favoriteItems(pool, 60)],
  ];
  const specialNames = specials.map(([name]) => name);

  /*
   * How many channels, films or series each category holds, shown at the end of its row.
   *
   * Something the television app does not draw - its CategoryPill is the name alone - and added
   * here at the owner's request; recorded in web/README.md. Counted from the pool, so it is the
   * number the grid will actually show: hidden categories are already gone from it, and in the
   * favourites-only view only favourites count. The two rows above the categories count what they
   * list, which is capped at the sixty the grid is given.
   */
  const counts = new Map<string, number>(specials.map(([name, items]) => [name, items.length]));
  for (const item of pool) counts.set(item.group, (counts.get(item.group) ?? 0) + 1);

  /*
   * Listed whether or not they have anything in them, which is what the television app does -
   * `val special = listOf("Continue watching", "Recently watched", "Favorites")`, with no check.
   *
   * They were conditional here, and the result was that a set with nothing starred and nothing
   * part-watched showed no Favorites row at all - so the one place a viewer would look to find
   * out where favourites go was missing precisely when they had not made any yet. An empty row
   * that says "this is where they appear" is worth more than a tidy list.
   *
   * The one deviation: the highlight opens on the first category that has something in it rather
   * than on the first in the list. Android opens on Continue watching regardless, which is fine
   * on a set that has been in use and poor on a fresh install - it would greet a viewer with an
   * empty grid while fifty thousand titles sit one press away.
   */
  let selected = specials.find(([, items]) => items.length)?.[0] ?? groups[0] ?? specialNames[0] ?? '';
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
   * The machinery that keeps the background picture on whatever channel is under the highlight.
   *
   * Debounced like the backdrop, and for the same reason: holding the D-pad through thirty
   * channels must cost one stream, not thirty. A provider will throttle or simply refuse an app
   * that opens a connection per keypress, and on a set the tuning delay would make the list feel
   * broken rather than responsive.
   *
   * There is no element to aim at any more. The picture fills the screen and the lists sit over
   * it, so the rectangle is the window - see schedulePreview.
   */
  let previewTimer: number | null = null;
  // Adopted from full screen when the viewer has just come back from it - see onExit in playScreen.
  let previewing: string | null = live ? carriedPreview : null;
  carriedPreview = null;
  if (previewing) document.body.classList.add('previewing');

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
      /*
       * The whole screen, not a pane.
       *
       * The television app puts its preview in a box beside the channel list; here it is the
       * background, with the two columns over it. That is a deliberate difference and it is the
       * better arrangement on a set this size - a 636 by 358 window on a 1920 panel is a
       * thumbnail, and what a viewer running down a channel list actually wants to see is the
       * channel.
       *
       * It also removes the hole entirely. There is nothing left to cut around, because the
       * picture is behind everything, and going full screen from here becomes a change of
       * controls rather than a change of picture.
       */
      const box = new DOMRect(0, 0, window.innerWidth, window.innerHeight);
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
          // The whole backdrop goes, not a rectangle of it, because the picture is now the whole
          // screen - see the rect above. An earlier version cut a hole around a preview pane, and
          // the hole went when the pane did.
          document.body.classList.add('previewing');
          /*
           * Confirmed a moment later, because prepare succeeding is not the same as playing.
           *
           * A provider's list outlives its streams: a channel it still carries an entry for but no
           * longer serves will open, prepare, report success, and then tear the decoder down
           * without telling the page. Nothing was watching for that, so the hole stayed cut over a
           * decoder in state NONE - a black rectangle where the picture should be, which is what
           * "the preview is gone" turned out to be. Measured on the set: the first channel of the
           * first category is one of these, and every channel after it played.
           *
           * Closing the hole puts the artwork back, which is the honest thing to show for a
           * channel that is not arriving.
           *
           * Watched repeatedly rather than looked at once. A channel that is merely slow passes
           * through IDLE and READY on its way to PLAYING, so a single check a few seconds in
           * would close the hole on a stream that was about to turn up - measured exactly that
           * while testing this, with a fresh tune reading IDLE at two seconds and READY at eight.
           * Twelve seconds, then give up.
           */
          let looks = 0;
          const watch = window.setInterval(() => {
            // Somebody has moved on, or the screen has gone. Whoever did that owns the class now.
            if (previewing !== item.streamUrl) {
              window.clearInterval(watch);
              return;
            }
            if (player.isPlaying()) {
              window.clearInterval(watch);
              return;
            }
            if (++looks < 6) return;
            window.clearInterval(watch);
            previewing = null;
            document.body.classList.remove('previewing');
          }, 2000);
        })
        .catch(() => {
          // A channel that will not tune is not an error worth a dialog while browsing - the
          // viewer is passing through. The listings beside it still say what is on.
          previewing = null;
          document.body.classList.remove('previewing');
        });
    }, PREVIEW_DELAY_MS);
  }

  /**
   * How much of a category is drawn before the page gets a frame.
   *
   * Enough to fill the screen and the row below it - seven columns of posters, or a column of
   * channel rows - and the rest follows a batch per frame. Drawing all four hundred at once is what
   * made moving through the category list stick: every row passed built a full grid, with its
   * images, stars and listeners, before the next key press could be looked at. Now each row passed
   * costs a screenful, and a category the viewer only passes through never draws the rest at all.
   */
  const FIRST_BATCH = live ? 24 : 42;
  const LATER_BATCH = live ? 30 : 35;
  /** The frame that will draw the next batch of the current category, if any is left. */
  let filling: number | null = null;
  /** An entry that must be on the page as soon as the grid is drawn - see the return below. */
  let reveal: string | null = null;

  function renderGrid(): void {
    if (filling !== null) {
      window.cancelAnimationFrame(filling);
      filling = null;
    }
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
      ?? byGroup.get(selected)
      ?? [];
    const query = search.trim().toLowerCase();
    const shown = query
      ? pool.filter((entry) => entry.name.toLowerCase().includes(query))
      : base;
    // Said out loud rather than left as a blank area. An empty Favorites is the normal state
    // before anybody has starred anything, and a screen that simply shows nothing there looks
    // broken rather than empty.
    if (!shown.length) {
      grid.append(
        el(
          'div',
          { class: 'grid-empty' },
          query
            ? t('search_no_results', search.trim())
            : live
              ? t('no_channels_yet')
              : t('no_movies_found'),
        ),
      );
      return;
    }
    const entries = shown.slice(0, 400);
    const wanted = reveal === null ? -1 : entries.findIndex((entry) => itemKey(entry) === reveal);
    reveal = null;
    let drawn = 0;
    const drawUpTo = (end: number): void => {
      const batch = document.createDocumentFragment();
      for (; drawn < Math.min(end, entries.length); drawn++) batch.append(entryCard(entries[drawn]!, drawn < FIRST_BATCH));
      grid.append(batch);
    };
    const drawRest = (): void => {
      filling = null;
      drawUpTo(drawn + LATER_BATCH);
      if (drawn < entries.length) filling = window.requestAnimationFrame(drawRest);
    };
    drawUpTo(Math.max(FIRST_BATCH, wanted + FIRST_BATCH));
    if (drawn < entries.length) filling = window.requestAnimationFrame(drawRest);

    /** [onScreen]: part of the first screenful, whose artwork is asked for without waiting. */
    function entryCard(item: PlaylistItem, onScreen: boolean): HTMLElement {
      const card = el('div', {
        class: live ? 'channel-row' : 'poster',
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': itemKey(item),
      });
      const art = el('img', { class: live ? 'channel-logo' : 'poster-art', alt: '' }) as HTMLImageElement;
      // Channel logos are left as they are; posterArtwork only ever changes a TMDB link.
      lazyImage(art, live ? item.logoUrl : posterArtwork(item.logoUrl), onScreen);
      // Portrait artwork with the name beneath it, which is what the television app's browse grids
      // show - not the landscape cards the landing rows use. The two are different shapes on
      // purpose: a row is a shelf of stills, a grid is a wall of posters.
      card.append(art, el('div', { class: live ? 'channel-name' : 'poster-label' }, item.name));
      if (!live && item.year) card.append(el('div', { class: 'poster-year' }, item.year));
      // The star the television draws on every poster and channel row: filled Orange when the
      // title is a favourite, an outline otherwise. Holding OK is what changes it.
      let star = favouriteStar(item);
      card.append(star);
      holdOk(card, () => {
        toggleFavorite(item);
        const next = favouriteStar(item);
        star.replaceWith(next);
        star = next;
        // The Favorites row is a snapshot taken when the page opened; bring it and its count up
        // to date, so the row says what the star just did.
        const favourites = specials[1]!;
        favourites[1] = favoriteItems(pool, 60);
        const count = sidebar.querySelector(`.category[data-focus-id="${cssEscape(favourites[0])}"] .category-count`);
        if (count) count.textContent = String(favourites[1].length);
      });
      card.addEventListener('focus', () => {
        // The next two rows' artwork, so it is there by the time the highlight is.
        prefetchAfter(card, live ? 8 : 14);
        if (item.kind !== 'live') {
          backdrop.show(item.logoUrl);
          // And the backgrounds of the posters either side and above and below, so the next step
          // in any direction finds its picture already fetched and decoded - the grid's version of
          // what the landing rows warm (PreloadBackdrops). Seven to a row.
          const at = entries.indexOf(item);
          backdrop.preloadWhenSettled([at + 1, at - 1, at + 7, at - 7].map((i) => entries[i]?.logoUrl));
        }
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
          /*
           * Noted first, before any of the branching below.
           *
           * Back and OK use this to return to this row in this category rather than to the top of
           * whichever one the browser opens on. It was written further down, after the handover
           * branch had already returned - so the one path a channel normally takes, pressing OK on
           * the channel the preview is already showing, never set it, and leaving that channel
           * still landed on the page. Every kind needs it and every path has to set it: a film and
           * a series open a page, a channel opens full screen, and a viewer picking a channel is
           * usually part way down a list of hundreds.
           */
          browseReturn = { section: current, category: selected, focusKey: itemKey(item) };
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
            if (filling !== null) window.cancelAnimationFrame(filling);
            filling = null;
            leaveScreen = null;
            // The list as the viewer is seeing it - sorted and filtered - so Up and Down in full
            // screen move to the channel that is actually next on their screen.
            playScreen(item, [], true, shown);
            return;
          }
          stopPreview();
          // A channel is a thing you turn on; a film is a thing you choose. Picking a channel out
          // of the list means watch it now - there is nothing to read about it first, and the
          // listings beside the list have already said what is on. A film has a plot, a cast and a
          // running time, and starting it was the only way to see any of them.
          if (item.kind === 'movie') {
            void detailsScreen(item);
          } else {
            playScreen(item, [], false, live ? shown : []);
          }
        }),
      );
      return card;
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
    categoryList.textContent = '';
    // While the category box has something in it, only the provider's categories whose names
    // contain it, and none of the rows above them - Android's serverCategories filter, which also
    // drops Recently watched and Favorites while a query is typed. Case-insensitive, as there.
    const categoryNeedle = categoryQuery.trim().toLocaleLowerCase();
    const listed = categoryNeedle
      ? groups.filter((group) => group.toLocaleLowerCase().includes(categoryNeedle))
      : [...specialNames, ...groups];
    if (!listed.length) {
      categoryList.append(el('div', { class: 'category-empty' }, t('no_categories_match')));
      return;
    }

    for (const group of listed) {
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
      // dir=auto, so a Latin name in the Arabic list is cut at its own end rather than its start.
      el('span', { class: 'category-name', dir: 'auto' }, group),
      el('span', { class: 'category-count' }, String(counts.get(group) ?? 0)),
    );
    if (group === reordering) row.classList.add('reordering');
    // Focus selects, as on the television: moving down the list changes what the grid shows
    // without needing a press for each one.
    row.addEventListener('focus', () => {
      selected = group;
      // Choosing a category ends a search of the titles, as on the television (channelQuery = ""
      // on entering a category): the grid is that category's again, not the search's results.
      if (search) {
        search = '';
        entryField.value = '';
      }
      for (const other of sidebar.querySelectorAll('.category')) {
        other.setAttribute('aria-selected', String(other.getAttribute('data-focus-id') === group));
      }
      renderGrid();
    });
    // A held OK opens the menu - CategoryActionsDialog, on the television's long press. Continue
    // watching, Recently watched and Favorites are not the provider's categories: there is
    // nothing behind them to hide, reorder or move, so they carry no menu, as on Android.
    // OK puts a carried row down. It used to reach the key handler below, but holdOk now takes OK
    // on these rows itself and turns it into this click, or into a hold.
    const putDown = (): void => {
      reordering = null;
      renderSidebar();
      focusCategory(group);
    };
    /*
     * OK on a category goes into it: the highlight moves to its first film, channel or series.
     * Focusing the row has already drawn that category, so what OK adds is the step across. It did
     * nothing at all before, which left Right as the only way in, and a press that does nothing is
     * one the viewer assumes did not register. A locked category's first stop is its Unlock button.
     */
    row.addEventListener('click', () => {
      if (reordering) {
        putDown();
        return;
      }
      if (selected !== group) return;
      focus(grid.querySelector<HTMLElement>('[data-focus]'));
    });
    if (!special) holdOk(row, () => {
      if (reordering) {
        putDown();
        return;
      }
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
    categoryList.append(row);
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
    for (const row of categoryList.children) {
      if (row.getAttribute('data-focus-id') === group) {
        focus(row as HTMLElement);
        return;
      }
    }
  }

  // Registered for every section, not only Live TV. Movies and Series never start a preview of
  // their own, but they can be the screen a viewer lands on *from* Live TV, and whatever is still
  // decoding has to be stopped by the page arriving rather than by the page leaving.
  leaveScreen = () => {
    if (filling !== null) window.cancelAnimationFrame(filling);
    filling = null;
    stopPreview();
  };
  // Belt and braces: the page is only transparent while something is deliberately playing full
  // screen. Anywhere else an opaque page is what guarantees a stray frame cannot show through,
  // whatever the decoder is doing.
  document.body.classList.remove('playing');

  /*
   * Two search boxes, where the television puts them: one at the top of the category column that
   * narrows the list of categories, and one at the top of the channels or the posters that searches
   * every title in the section. There used to be only the second, sitting over the categories,
   * which read as a search of the categories and was not one.
   *
   * Both are made once and kept. The category list is redrawn under its box as the query changes;
   * redrawing the box as well would take the highlight, and the on-screen keyboard, away from the
   * viewer in the middle of typing.
   */
  let categoryQuery = '';
  let categoryTimer: number | null = null;
  const categoryField = el('input', {
    class: 'category-search',
    type: 'text',
    placeholder: t('search_categories'),
    tabindex: '-1',
    'data-focus': '',
    'data-focus-id': 'category-search',
    'data-focus-up': 'none',
  }) as HTMLInputElement;
  categoryField.addEventListener('input', () => {
    categoryQuery = categoryField.value;
    if (categoryTimer !== null) window.clearTimeout(categoryTimer);
    categoryTimer = window.setTimeout(() => {
      categoryTimer = null;
      renderSidebar();
    }, 200);
  });
  const categoryList = el('div', { class: 'category-list' });
  sidebar.append(categoryField, categoryList);

  const entryField = el('input', {
    class: 'category-search entry-search',
    type: 'text',
    placeholder: current === 'live' ? t('search_channels') : current === 'movies' ? t('search_all_movies') : t('search_all_series'),
    tabindex: '-1',
    'data-focus': '',
    'data-focus-id': 'browse-search',
    'data-focus-up': 'none',
    // Down goes to the first result, not to whichever poster happens to sit under the middle of a
    // box as wide as the grid.
    'data-focus-down': `[data-focus-group="${live ? 'browser-channels' : 'browser-grid'}"] [data-focus]`,
  }) as HTMLInputElement;
  entryField.addEventListener('input', () => {
    search = entryField.value;
    // Debounced for the reason the television app debounces it: a provider list runs to tens of
    // thousands of titles, and re-filtering and re-drawing on every keystroke is what made
    // typing feel like it was lagging a letter behind.
    if (searchTimer !== null) window.clearTimeout(searchTimer);
    searchTimer = window.setTimeout(() => {
      searchTimer = null;
      renderGrid();
    }, 320);
  });
  const entryColumn = el('div', { class: live ? 'browse-column live' : 'browse-column' }, entryField, grid);

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
        entryColumn,
        // No preview pane any more: the picture is the whole background, so this column carries
        // only what is on - see the rect in schedulePreview.
        el('div', { class: 'browser-main' }, guide),
      ),
    );
  } else {
    // The column holding the title search and the grid is what takes the width left over by the
    // category column (see .browse-column), because that width is what decides how wide the
    // grid's seven columns are.
    app.append(back, el('div', { class: 'browser' }, sidebar, entryColumn));
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
    // the grid below is the right one by the time the poster is looked for. The poster may be far
    // down a category that is otherwise drawn a batch at a time, so the grid is told to include it.
    reveal = returning!.focusKey;
    focus(category);
    const poster = grid.querySelector<HTMLElement>(`[data-focus-id="${cssEscape(returning!.focusKey)}"]`);
    if (poster) focus(poster);
  } else {
    // The category list, not the search box above it: arriving on this page should leave the
    // highlight where the next press is most likely to be wanted, and that is the list.
    //
    // And on the category `selected` chose, not simply the first row. Focusing a row is what
    // selects it, so landing on the first one regardless quietly undid the choice made above -
    // Series and Live TV both opened on an empty Continue watching even though the code had
    // already worked out which category had something in it.
    focus(
      sidebar.querySelector<HTMLElement>(`.category[data-focus-id="${cssEscape(selected)}"]`)
        ?? sidebar.querySelector<HTMLElement>('.category[data-focus]'),
    );
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
  // Not when coming back to a channel: focusing its row has already asked for it, and this would
  // replace it a moment later with the first channel of the category.
  if (live && !category) {
    /*
     * The first real channel, not the first row.
     *
     * This provider - and it is a common habit - uses rows like "####### 1001 #######" as
     * headings inside a category. They are entries in the playlist like any other, they are
     * always first, and they carry no stream: measured on the set, the first row of every
     * category tried was one of these, and every one reported the decoder in state NONE while
     * every real channel after it played.
     *
     * So Live TV was opening on the one row in the category guaranteed not to work, which is what
     * was reported as the preview having stopped working. They are left in the list, because they
     * are the provider's own organisation of it and hiding them would be editing what the viewer
     * subscribed to - but nothing is gained by tuning one.
     */
    const isSeparator = (item: PlaylistItem): boolean => /^\s*#{3,}/.test(item.name);
    const inCategory = pool.filter((item) => item.group === selected);
    const first = inCategory.find((item) => !isSeparator(item))
      ?? inCategory[0]
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
      onEpisode: (episode) => {
        // The series goes on Series' Recently watched when one of its episodes is played.
        recordWatched(item);
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
        );
      },
      // Back to the browser when that is where this was opened from, and to the landing when it
      // was not - a series reached from a Home row should not drop the viewer into a category
      // browser they never asked for. browseReturn is only set by the grid, and it is not
      // consumed until the browser next draws, so its presence is the question being asked.
      onBack: () => (browseReturn ? browseScreen(section) : showSection(section)),
    });
  } catch (error) {
    /*
     * A dead end needs a way out that can be seen.
     *
     * This used to be the message and nothing else: measured on the emulator with the provider
     * unreachable, the screen had zero focusable elements and focus sat on BODY. Back worked, but
     * nothing on screen said so and there was no highlight anywhere - which on a remote is
     * indistinguishable from the app having stopped responding.
     */
    status.textContent = error instanceof Error ? error.message : t('episodes_unavailable');
    const back = el(
      'div',
      { class: 'button ghost', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'series-error-back' },
      t('press_back_to_return'),
    );
    const leave = (): void => {
      release();
      showSection(section);
    };
    back.addEventListener('click', leave);
    app.append(back);
    focus(back);
    const release = pushKeyHandler((key: RemoteKey) => {
      if (key !== 'back') return false;
      leave();
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
function playScreen(
  item: PlaylistItem,
  siblings: StripEpisode[] = [],
  handover = false,
  /**
   * The channels either side of this one, so Up and Down can change channel in full screen.
   *
   * Passed in rather than looked up here for the same reason the season is: the screen that got
   * us here already has the list, in the order the viewer is seeing it - which matters, because
   * the next channel has to be the next one on their screen, not the next one in the catalogue.
   */
  channels: PlaylistItem[] = [],
  /**
   * Where to start, in milliseconds. Left out, a film or episode with a saved position asks
   * first - resume there, or play from the beginning - and comes back here with the answer.
   */
  startAtMs?: number,
): void {
  if (item.kind === 'series') {
    void seriesScreen(item);
    return;
  }
  /*
   * Asked before anything is torn down, so the page it was opened from - the film's page, the
   * season, a landing row - is still there behind the question, and Cancel leaves the viewer on
   * it. It never started from the saved position at all: positions were recorded on the way out
   * and nothing read them back on the way in.
   */
  if (startAtMs === undefined && !handover && item.kind !== 'live') {
    const saved = resumePosition(item);
    if (saved !== null) {
      askResume({
        title: item.name,
        positionMs: saved,
        onResume: () => playScreen(item, siblings, handover, channels, saved),
        onStartOver: () => playScreen(item, siblings, handover, channels, 0),
        onCancel: () => undefined,
      });
      return;
    }
  }
  // Live TV's Recently watched: every channel watched full screen, whichever way it was reached -
  // chosen from the list, handed over from the preview, or zapped to.
  if (item.kind === 'live') recordWatched(item);
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
    // The mark beside the name in the channel banner, the way the television app's
    // LiveChannelPreview draws it. Ignored for a recording, which has a title instead.
    logoUrl: item.logoUrl,
    onZap: channels.length > 1
      ? (forward) => {
          const here = channels.findIndex((c) => itemKey(c) === itemKey(item));
          const next = here < 0 ? null : channels[here + (forward ? 1 : -1)] ?? null;
          // The ends of the list are the ends. Wrapping would take somebody holding Up at the top
          // of their channels to the bottom of them, which is not what the press asked for.
          if (!next) return false;
          player.stop();
          overlay.destroy();
          release();
          playScreen(next, [], false, channels);
          return true;
        }
      : undefined,
    episodes: siblings,
    currentEpisodeId: item.channelId,
    onEpisode: (episode) => {
      const next: PlaylistItem = { ...item, name: episode.label, streamUrl: episode.streamUrl, channelId: episode.id };
      const go = (startAt: number): void => {
        // The position belongs to the episode being left, not to the one arriving.
        rememberPosition(item, positionMs, durationMs);
        player.stop();
        overlay.destroy();
        release();
        // Re-entered rather than swapped in place: a new stream means a new duration, a new
        // resume point and a new title, and rebuilding is how all three stay in step. The season
        // is handed on so the strip is still there on the next episode.
        playScreen(next, siblings, false, [], startAt);
      };
      // Asked here rather than in playScreen, while this episode is still playing behind the
      // question - so Cancel leaves the viewer watching it rather than on an empty screen.
      const saved = resumePosition(next);
      if (saved === null) {
        go(0);
        return;
      }
      askResume({
        title: next.name,
        positionMs: saved,
        onResume: () => go(saved),
        onStartOver: () => go(0),
        onCancel: () => undefined,
      });
    },
    onExit: (at) => {
      rememberPosition(item, at, durationMs);
      // A shape picked in the player was for this title. The player object outlives it, and
      // without this the next title, or the preview this channel goes back to, opened in it.
      player.setScaling(videoScaling());
      /*
       * A channel going back to its list keeps playing, as that list's preview.
       *
       * Going in is already a handover - the preview grows to full screen without reopening the
       * stream. Coming out was not: the stream was stopped here, and the list then waited its
       * preview delay and opened the same channel again from nothing, so leaving a channel meant
       * seconds of black before the picture behind the list came back. The list adopts the running
       * stream instead (see carriedPreview in browseScreen), and the highlight goes back to the
       * channel that is playing, which after zapping is not the one that was opened.
       *
       * Only back to the list: a channel opened from a landing row goes back to a page with no
       * preview, and there it has to stop.
       */
      if (item.kind === 'live' && browseReturn) {
        carriedPreview = item.streamUrl;
        browseReturn = { ...browseReturn, focusKey: itemKey(item) };
        player.on(() => undefined);
      } else {
        player.stop();
      }
      overlay.destroy();
      release();
      // Back to the list this was opened from, and to the landing when it was opened from there.
      // Leaving a channel dropped the viewer on the landing however deep into a category they
      // had been, which for a list of hundreds is the whole way back to the start.
      if (browseReturn) browseScreen(section);
      else showSection(section);
    },
  });
  app.append(overlay.element);
  // After it is in the page, not before - see PlayerOverlay.focusFirst.
  overlay.focusFirst();

  /*
   * What is on, for a channel being watched.
   *
   * The listings are beside the channel list right up to the moment somebody commits to watching,
   * and then they vanish - which is when they are most wanted, because by then the name of the
   * channel is not the question any more.
   *
   * Asked again every couple of minutes. A programme that started before the viewer arrived ends
   * while they are still there, and a panel that says what was on half an hour ago is worse than
   * one that says nothing.
   */
  let guideTimer: number | null = null;
  if (item.kind === 'live' && login) {
    const guide = createEpgLoader(login, (_channel, result) => {
      overlay.setGuide(
        result.now ? t('epg_now_format', `${clockTime(result.now.startEpochSeconds, locale())}  ${result.now.title}`) : null,
        result.next ? t('epg_next_format', `${clockTime(result.next.startEpochSeconds, locale())}  ${result.next.title}`) : null,
        result.progress,
      );
    });
    guide.request(item);
    guideTimer = window.setInterval(() => guide.request(item), 120000);
    leaveScreen = () => {
      guide.cancel();
      if (guideTimer !== null) window.clearInterval(guideTimer);
    };
  }

  /** The saved position to go to once the stream can be seeked, if resuming was chosen. */
  let pendingStart = startAtMs ?? 0;
  /** When the position was last written during playback - see the progress handler. */
  let savedAt = Date.now();

  player.on((event) => {
    if (event.type === 'error') overlay.setMessage(event.message);
    if (event.type === 'ready') {
      durationMs = event.durationMs;
      // A stream can be seeked once it is prepared, and 'ready' is that moment on both players.
      if (pendingStart > 0 && (durationMs <= 0 || pendingStart < durationMs)) {
        player.seekTo(pendingStart);
        positionMs = pendingStart;
      }
      pendingStart = 0;
      overlay.setPosition(positionMs, durationMs);
      // Applied on ready rather than before play: AVPlay rejects a display method on a stream it
      // has not prepared, so setting it earlier is silently thrown away and the viewer's choice
      // appears to have been forgotten between one title and the next.
      player.setScaling(videoScaling());
    }
    if (event.type === 'progress') {
      positionMs = event.positionMs;
      overlay.setPosition(positionMs, durationMs);
      /*
       * Written as it plays, not only on the way out. Leaving by Back records it, but a set
       * switched off, the Home button, or the app closed from the task list never reach onExit,
       * and the position went with them. Every fifteen seconds, and only once past the first
       * minute: rememberPosition forgets anything earlier than that, and a clock that reads 0 for
       * a moment while a resume seek lands must not wipe the point it is seeking to.
       */
      if (item.kind !== 'live' && positionMs >= 60_000 && Date.now() - savedAt >= 15_000) {
        savedAt = Date.now();
        rememberPosition(item, positionMs, durationMs);
      }
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
    // The Settings shape from the first frame, not whatever the previous title was left in.
    player.setScaling(videoScaling());
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
  player = createPlayer(platform, video);
  backdrop = new Backdrop(backdropHost);

  window.addEventListener('keydown', (event) => {
    /*
     * Typing in a search box is typing, not remote buttons.
     *
     * Space is mapped to Play/Pause and Backspace to Back, so that a keyboard can stand in for a
     * remote - and inside a text box that ate every space (searching "film 7" searched "film7")
     * and made deleting a letter leave the page. In a box, those two, and any other key that
     * produces a character, go to the box. The remote's own Back is a different key (10009 on
     * Samsung) and still works from inside one.
     */
    const target = event.target;
    if (
      target instanceof HTMLInputElement &&
      (event.key === ' ' || event.key === 'Backspace' || event.key === 'Delete' || event.key.length === 1)
    ) {
      return;
    }
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
    // Handed over rather than read a second time: a full catalogue is megabytes to copy out of
    // the database, and it was being read twice on every launch.
    const cached = await readCatalogue(cacheKey(saved));
    if (cached) {
      void resumeFromCache(saved, cached);
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
