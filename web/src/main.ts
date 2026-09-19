/**
 * Routing and screens.
 *
 * Sign in, then four landing pages over one catalogue, a category browser, and playback. The
 * screens are deliberately thin: the rules they follow live in ui/landing.ts and ui/focus.ts, and
 * the provider quirks in shared/xtream.ts, so there is one place to change each.
 */
import { detectPlatform, keyOf, registerPlatformKeys, type RemoteKey } from './platform/keys';
import { createPlayer, type MediaPlayer } from './platform/video';
import { readJson, writeJson } from './platform/storage';
import { setLocale, isRtl, t } from './shared/i18n';
import { loadProvider, movieDetails, seriesDetails } from './shared/xtream';
import type { LoadedPlaylist, PlaylistItem, ProviderLogin } from './shared/models';
import { itemKey } from './shared/models';
import { continueWatching, favoriteItems, recentlyAdded, rememberPosition } from './shared/library';
import { focus, handleKey, pushKeyHandler, setFocusDirection } from './ui/focus';
import { Backdrop } from './ui/backdrop';
import { renderLanding, disposeLanding, focusPageStart, type LandingRow } from './ui/landing';
import { renderNav, trackNavHighlight, type Section } from './ui/nav';
import { renderSeries } from './ui/series';

const platform = detectPlatform();
const app = document.getElementById('app') as HTMLElement;
const video = document.getElementById('video') as HTMLVideoElement;
const backdropHost = document.getElementById('video-plane') as HTMLElement;
let player: MediaPlayer;
let backdrop: Backdrop;

const SAVED_LOGIN = 'login';

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
  // Both pages hang listeners on the document rather than on their own elements, so emptying the
  // app is not enough to be rid of them - a landing left behind would keep answering focus moves
  // on the page that replaced it.
  disposeLanding();
  detachNav?.();
  detachNav = null;
  app.textContent = '';
}

/* ------------------------------------------------------------------ login */

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

  app.append(
    el(
      'div',
      { class: 'panel' },
      el('h1', {}, t('welcome')),
      el('p', {}, t('activation_subtitle')),
      field(t('playlist_name_label'), name),
      field(t('server_address_label'), address),
      field(t('username_label'), username),
      field(t('password_label'), password),
      connect,
      status,
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
    showSection('home');
  } catch (error) {
    status.textContent = error instanceof Error ? error.message : String(error);
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

  function renderGrid(): void {
    grid.textContent = '';
    for (const item of pool.filter((entry) => entry.group === selected).slice(0, 400)) {
      const card = el('div', { class: 'card', tabindex: '-1', 'data-focus': '', 'data-focus-id': itemKey(item) });
      const art = el('img', { class: 'art', alt: '' }) as HTMLImageElement;
      if (item.logoUrl) art.src = item.logoUrl;
      art.addEventListener('error', () => art.removeAttribute('src'));
      card.append(art, el('div', { class: 'label' }, item.name));
      card.addEventListener('focus', () => {
        if (item.kind !== 'live') backdrop.show(item.logoUrl);
      });
      card.addEventListener('click', () => playScreen(item));
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
  app.append(back, el('div', { class: 'browser' }, sidebar, grid));
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
  if (saved) {
    loginScreen('');
    const status = app.querySelector<HTMLElement>('.message');
    const fields = app.querySelectorAll<HTMLInputElement>('.field input');
    if (fields[0]) fields[0].value = saved.name;
    if (fields[1]) fields[1].value = saved.address;
    if (fields[2]) fields[2].value = saved.username;
    if (fields[3]) fields[3].value = saved.password;
    if (status) void connectAndLoad(saved, status);
  } else {
    loginScreen();
  }
}

boot();
