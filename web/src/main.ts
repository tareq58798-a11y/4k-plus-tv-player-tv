/**
 * The vertical slice: sign in, load the catalogue, browse Live TV by category, play a channel.
 *
 * Deliberately the narrow path rather than the whole app. It exercises every layer that the rest
 * depends on - the provider client, the focus engine, the key map, storage and AVPlay - so that
 * anything structurally wrong shows up on a real television before the remaining screens are
 * built on top of it.
 */
import { detectPlatform, keyOf, registerPlatformKeys, type RemoteKey } from './platform/keys';
import { createPlayer, type MediaPlayer } from './platform/video';
import { readJson, writeJson } from './platform/storage';
import { setLocale, isRtl, t } from './shared/i18n';
import { loadProvider } from './shared/xtream';
import type { LoadedPlaylist, PlaylistItem, ProviderLogin } from './shared/models';
import { itemKey } from './shared/models';
import { focus, handleKey, pushKeyHandler, setFocusDirection } from './ui/focus';

const platform = detectPlatform();
const app = document.getElementById('app') as HTMLElement;
const video = document.getElementById('video') as HTMLVideoElement;
let player: MediaPlayer;

const SAVED_LOGIN = 'login';

/* ------------------------------------------------------------------ utils */

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
  app.textContent = '';
}

/* ------------------------------------------------------------------ login */

function loginScreen(message = ''): void {
  clear();
  document.body.classList.remove('playing');

  const name = el('input', { type: 'text', value: '4K Plus TV', 'data-focus': '', 'data-focus-id': 'name' });
  const address = el('input', { type: 'text', placeholder: 'http://example.com:80', 'data-focus': '', 'data-focus-id': 'address' });
  const username = el('input', { type: 'text', 'data-focus': '', 'data-focus-id': 'username' });
  // A password field, so the characters are masked on a screen other people can see. Nothing
  // typed here is logged anywhere.
  const password = el('input', { type: 'password', 'data-focus': '', 'data-focus-id': 'password' });
  const status = el('div', { class: 'message' }, message);

  const connect = el('button', { class: 'button', 'data-focus': '', 'data-focus-id': 'connect' }, t('connect'));
  connect.addEventListener('click', () => {
    const login: ProviderLogin = {
      name: name.value.trim() || '4K Plus TV',
      address: address.value.trim(),
      username: username.value.trim(),
      password: password.value,
    };
    if (!login.address || !login.username || !login.password) {
      status.textContent = t('enter_all_fields');
      return;
    }
    void connectAndLoad(login, status);
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

async function connectAndLoad(login: ProviderLogin, status: HTMLElement): Promise<void> {
  status.textContent = t('loading_your_playlist');
  try {
    const playlist = await loadProvider(login, {
      liveContainer: player.liveContainer,
      // Live TV is usable before the on-demand catalogues arrive, so it is shown the moment it
      // lands rather than made to wait for tens of thousands of films and series.
      onPartial: (partial) => {
        status.textContent = t('loading_playlist_from_network') + ` (${partial.items.length})`;
      },
    });
    writeJson(SAVED_LOGIN, login);
    browseScreen(login, playlist);
  } catch (error) {
    status.textContent = error instanceof Error ? error.message : String(error);
  }
}

/* ----------------------------------------------------------------- browse */

function browseScreen(login: ProviderLogin, playlist: LoadedPlaylist): void {
  clear();
  document.body.classList.remove('playing');

  const live = playlist.items.filter((item) => item.kind === 'live');
  const groups = [...new Set(live.map((item) => item.group))].sort((a, b) => a.localeCompare(b));
  let selected = groups[0] ?? '';

  const grid = el('div', { class: 'grid', 'data-focus-group': 'channels' });
  const sidebar = el('div', { class: 'sidebar', 'data-focus-group': 'categories' });

  function renderGrid(): void {
    grid.textContent = '';
    const channels = live.filter((item) => item.group === selected).slice(0, 300);
    for (const channel of channels) {
      grid.append(card(channel, () => playScreen(login, playlist, channel)));
    }
  }

  function renderSidebar(): void {
    sidebar.textContent = '';
    for (const group of groups) {
      const row = el(
        'div',
        {
          class: 'category',
          'data-focus': '',
          'data-focus-id': group,
          'aria-selected': String(group === selected),
        },
        group,
      );
      // Focus selects, the way the television app does: moving the highlight down the list
      // changes what the grid shows, without needing a press for each one.
      row.addEventListener('focus', () => {
        selected = group;
        for (const other of sidebar.children) {
          other.setAttribute('aria-selected', String(other.getAttribute('data-focus-id') === group));
        }
        renderGrid();
      });
      sidebar.append(row);
    }
  }

  app.append(topBar(playlist), el('div', { class: 'browser' }, sidebar, grid));
  renderSidebar();
  renderGrid();
  focus(sidebar.querySelector<HTMLElement>('[data-focus]'));
}

function card(item: PlaylistItem, onSelect: () => void): HTMLElement {
  const node = el('div', {
    class: 'card',
    tabindex: '-1',
    'data-focus': '',
    'data-focus-id': itemKey(item),
  });
  const art = el('img', { class: 'art', alt: '', loading: 'lazy' }) as HTMLImageElement;
  if (item.logoUrl) art.src = item.logoUrl;
  // A provider's artwork host is not always reachable; a broken image icon in a grid of four
  // hundred is worse than a plain tile.
  art.addEventListener('error', () => art.removeAttribute('src'));
  node.append(art, el('div', { class: 'label' }, item.name));
  node.addEventListener('click', onSelect);
  return node;
}

function topBar(playlist: LoadedPlaylist): HTMLElement {
  return el(
    'div',
    { class: 'top-bar' },
    el('div', { class: 'brand' }, '4K', el('span', {}, ' PLUS TV')),
    el('div', { class: 'tab', 'aria-selected': 'true' }, t('nav_live_tv')),
    el('div', { class: 'spacer' }),
    el('div', { class: 'clock' }, `${playlist.items.length} ${t('items_label')}`),
  );
}

/* ------------------------------------------------------------------- play */

function playScreen(login: ProviderLogin, playlist: LoadedPlaylist, channel: PlaylistItem): void {
  clear();
  document.body.classList.add('playing');

  const bar = el(
    'div',
    { class: 'player-bar' },
    channel.name,
    el('div', { class: 'hint' }, t('press_back_to_return')),
  );
  app.append(bar);

  const rect = new DOMRect(0, 0, window.innerWidth, window.innerHeight);
  player.on((event) => {
    if (event.type === 'error') {
      bar.textContent = event.message;
    }
  });
  void player.play(channel.streamUrl, rect).catch((error: unknown) => {
    bar.textContent = error instanceof Error ? error.message : String(error);
  });

  const release = pushKeyHandler((key: RemoteKey) => {
    if (key === 'back') {
      player.stop();
      release();
      browseScreen(login, playlist);
      return true;
    }
    if (key === 'playpause' || key === 'pause') {
      player.pause();
      return true;
    }
    if (key === 'play') {
      player.resume();
      return true;
    }
    if (key === 'rewind') {
      player.seekBy(-10_000);
      return true;
    }
    if (key === 'forward') {
      player.seekBy(10_000);
      return true;
    }
    // Nothing else reaches the page while playing: an arrow key must not move a highlight that
    // is no longer on screen.
    return true;
  });
}

/* ------------------------------------------------------------------- boot */

function boot(): void {
  registerPlatformKeys(platform);
  setLocale(navigator.language?.slice(0, 2) ?? 'en');
  setFocusDirection(isRtl());
  player = createPlayer(platform, video);

  window.addEventListener('keydown', (event) => {
    const key = keyOf(event, platform);
    if (!key) return;
    // Back is the one key a television acts on itself when the app ignores it - Samsung closes
    // the app outright - so it is always consumed here and answered by the screen on top.
    event.preventDefault();
    handleKey(key);
  });

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
