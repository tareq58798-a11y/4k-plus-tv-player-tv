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
import type { LoadedPlaylist, PlaylistItem, ProviderLogin } from './shared/models';
import { itemKey } from './shared/models';
import { continueWatching, favoriteItems, recentlyAdded, rememberPosition } from './shared/library';
import { focus, handleKey, pushKeyHandler, setFocusDirection } from './ui/focus';
import { Backdrop } from './ui/backdrop';
import { renderLanding, disposeLanding, focusPageStart, type LandingRow } from './ui/landing';
import { renderNav, trackNavHighlight, type Section } from './ui/nav';
import { renderSeries } from './ui/series';
import { renderSearch } from './ui/search';
import { renderSettings } from './ui/settings';
import { createEpgLoader, clockTime } from './ui/epg';
import { askPin } from './ui/pin';
import { identity } from './platform/identity';
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

const SAVED_LOGIN = 'login';
/** An M3U playlist has no account, so it is remembered by address instead of by login. */
const SAVED_M3U = 'm3u';

let login: ProviderLogin | null = null;
let catalogue: LoadedPlaylist | null = null;
let section: Section = 'home';
let detachNav: (() => void) | null = null;

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

function clear(): void {
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
  const macRow = el('div', { class: 'code-row' }, el('span', {}, t('device_id')), el('strong', {}, '…'));
  const keyRow = el('div', { class: 'code-row' }, el('span', {}, t('device_key')), el('strong', {}, '…'));
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
  // Home starts over on the app's own artwork rather than whichever title was last looked at.
  if (next === 'home') backdrop.reset();

  const bar = renderNav(app, {
    current: next,
    onSection: (chosen) => showSection(chosen),
    onEnter: () => focusPageStart(app),
    onSearch: () => searchScreen(),
    onSettings: () => settingsScreen(),
    subtitle: `${catalogue.items.length} ${t('items_label')}`,
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

function settingsScreen(): void {
  clear();
  renderSettings(app, {
    // Every word on screen changes, so the page behind is rebuilt rather than patched.
    onLanguageChanged: () => {
      setFocusDirection(isRtl());
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
    onChanged: () => settingsScreen(),
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

  const groups = [...new Set(pool.map((item) => item.group))].sort((a, b) => a.localeCompare(b));
  let selected = groups[0] ?? '';

  const grid = el('div', { class: 'grid', 'data-focus-group': 'browser-grid' });
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
    for (const item of pool.filter((entry) => entry.group === selected).slice(0, 400)) {
      const card = el('div', { class: 'card', tabindex: '-1', 'data-focus': '', 'data-focus-id': itemKey(item) });
      const art = el('img', { class: 'art', alt: '' }) as HTMLImageElement;
      if (item.logoUrl) art.src = item.logoUrl;
      art.addEventListener('error', () => art.removeAttribute('src'));
      card.append(art, el('div', { class: 'label' }, item.name));
      card.addEventListener('focus', () => {
        if (item.kind !== 'live') backdrop.show(item.logoUrl);
        focusedChannelId = item.channelId;
        if (item.kind === 'live') {
          // The name first, so the panel is never empty while the listings are on their way.
          guide.textContent = '';
          guide.append(el('div', { class: 'guide-now' }, item.name));
          epg.request(item);
        }
      });
      card.addEventListener('click', () =>
        behindPin(isCategoryLocked(item.group) || isChannelLocked(itemKey(item)), () => playScreen(item)),
      );
      grid.append(card);
    }
  }

  for (const group of groups) {
    const row = el(
      'div',
      { class: 'category', 'data-focus': '', 'data-focus-id': group, 'aria-selected': String(group === selected) },
      group,
    );
    // Focus selects, as on the television: moving down the list changes what the grid shows
    // without needing a press for each one.
    row.addEventListener('focus', () => {
      selected = group;
      for (const other of sidebar.children) {
        other.setAttribute('aria-selected', String(other.getAttribute('data-focus-id') === group));
      }
      renderGrid();
    });
    sidebar.append(row);
  }

  const back = el('div', { class: 'browser-title' }, t(current === 'live' ? 'nav_live_tv' : current === 'movies' ? 'nav_movies' : 'nav_series'));
  app.append(back, el('div', { class: 'browser' }, sidebar, el('div', { class: 'browser-main' }, grid, current === 'live' ? guide : el('div'))));
  renderGrid();
  focus(sidebar.querySelector<HTMLElement>('[data-focus]'));

  const release = pushKeyHandler((key: RemoteKey) => {
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
        playScreen({
          ...item,
          // The episode is what plays, and what a resume point belongs to - a series has no
          // single position of its own.
          name: `${item.name} • ${episode.title}`,
          streamUrl: episode.streamUrl,
          channelId: episode.id,
          kind: 'movie',
        }),
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

function playScreen(item: PlaylistItem): void {
  if (item.kind === 'series') {
    void seriesScreen(item);
    return;
  }
  clear();
  document.body.classList.add('playing');

  const bar = el('div', { class: 'player-bar' }, item.name, el('div', { class: 'hint' }, t('press_back_to_return')));
  app.append(bar);

  let durationMs = 0;
  let positionMs = 0;
  player.on((event) => {
    if (event.type === 'error') bar.firstChild!.textContent = event.message;
    if (event.type === 'ready') durationMs = event.durationMs;
    if (event.type === 'progress') positionMs = event.positionMs;
  });
  void player.play(item.streamUrl, new DOMRect(0, 0, window.innerWidth, window.innerHeight)).catch((error: unknown) => {
    bar.firstChild!.textContent = error instanceof Error ? error.message : String(error);
  });

  const release = pushKeyHandler((key: RemoteKey) => {
    if (key === 'back') {
      rememberPosition(item, positionMs, durationMs);
      player.stop();
      release();
      showSection(section);
      return true;
    }
    if (key === 'playpause' || key === 'pause') { player.pause(); return true; }
    if (key === 'play') { player.resume(); return true; }
    if (key === 'rewind') { player.seekBy(-10_000); return true; }
    if (key === 'forward') { player.seekBy(10_000); return true; }
    // Nothing else reaches the page: an arrow key must not move a highlight that is off screen.
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
