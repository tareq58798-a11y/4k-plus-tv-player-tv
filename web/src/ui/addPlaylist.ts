/**
 * Add Playlist, ported from ManualPlaylistScreen in MainActivity.kt.
 *
 * The title with its back arrow; the playlist's name; "Choose your server" with a chip for each
 * approved server, labelled by position; username; password, masked, with the eye that shows it;
 * the note that the details stay on the device; and "Test and Add Playlist", which is only
 * pressable once the name, username and password are all filled in.
 *
 * There is no address box. The television does not have one - a provider login can only be added
 * against the servers in ApprovedServers.kt - and the server chips are that, here.
 *
 * Nothing typed here is logged, and the password is never put anywhere but the field it was typed
 * into and the login that is saved.
 */
import { t } from '../shared/i18n';
import type { ProviderLogin } from '../shared/models';
import { APPROVED_SERVERS } from '../shared/servers';
import { focus, pushKeyHandler } from './focus';
import { iconElement } from './icons';

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
 * "Server 1", "Server 2": FilterChips, one selected at a time. Shared with the welcome page's
 * manual entry, which asks for the same thing.
 */
export function serverPicker(idPrefix: string): { element: HTMLElement; address: () => string } {
  let chosen = 0;
  const row = el('div', { class: 'server-chips', 'data-focus-group': `${idPrefix}-servers` });
  const chips = APPROVED_SERVERS.map((_, index) => {
    const chip = el(
      'div',
      { class: 'server-chip', tabindex: '-1', 'data-focus': '', 'data-focus-id': `${idPrefix}-server-${index + 1}` },
      t('server_index_label', String(index + 1)),
    );
    chip.addEventListener('click', () => {
      chosen = index;
      chips.forEach((other, at) => other.setAttribute('aria-selected', String(at === chosen)));
    });
    row.append(chip);
    return chip;
  });
  chips.forEach((chip, at) => chip.setAttribute('aria-selected', String(at === chosen)));
  return { element: row, address: () => APPROVED_SERVERS[chosen]! };
}

/** A password box with the eye at its end that shows or hides what was typed. */
export function passwordField(id: string, placeholder: string): { element: HTMLElement; input: HTMLInputElement } {
  const input = el('input', {
    class: 'outlined-field',
    type: 'password',
    placeholder,
    tabindex: '-1',
    'data-focus': '',
    'data-focus-id': id,
  }) as HTMLInputElement;
  const eye = el('div', {
    class: 'password-eye',
    tabindex: '-1',
    'data-focus': '',
    'data-focus-id': `${id}-reveal`,
    'aria-label': t('show_password'),
  });
  const drawEye = (): void => {
    eye.textContent = '';
    // Visibility while hidden - "show it" - and VisibilityOff while shown, as on the television.
    eye.append(iconElement(input.type === 'password' ? 'visibility' : 'visibilityOff', 'password-eye-icon'));
  };
  drawEye();
  eye.addEventListener('click', () => {
    input.type = input.type === 'password' ? 'text' : 'password';
    drawEye();
  });
  return { element: el('div', { class: 'password-field' }, input, eye), input };
}

export interface AddPlaylistOptions {
  onBack: () => void;
  /** Connects with [login]; writes progress and failures into [status]. */
  onSubmit: (login: ProviderLogin, status: HTMLElement) => Promise<void>;
}

export function renderAddPlaylist(host: HTMLElement, options: AddPlaylistOptions): void {
  const back = el('div', { class: 'settings-back', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'add-back', 'aria-label': t('cd_back') });
  back.append(iconElement('arrowBack', 'settings-back-icon'));
  back.addEventListener('click', () => leave());

  const field = (id: string, placeholder: string): HTMLInputElement =>
    el('input', { class: 'outlined-field', type: 'text', placeholder, tabindex: '-1', 'data-focus': '', 'data-focus-id': id }) as HTMLInputElement;
  const name = field('add-name', t('playlist_name_label'));
  const servers = serverPicker('add');
  const username = field('add-username', t('username_label'));
  // Into the chips at the first one, from above and below. Left to geometry, the middle of a
  // field as wide as the screen is nearer the second chip than the first.
  name.setAttribute('data-focus-down', '[data-focus-id="add-server-1"]');
  username.setAttribute('data-focus-up', '[data-focus-id="add-server-1"]');
  const password = passwordField('add-password', t('password_label'));
  const status = el('div', { class: 'message' });

  const submit = el('button', { class: 'button add-submit', 'data-focus': '', 'data-focus-id': 'add-submit' }, t('test_and_add_playlist')) as HTMLButtonElement;
  // Pressable once all three are filled in, as the television's button is enabled.
  const ready = (): boolean => Boolean(name.value.trim() && username.value.trim() && password.input.value);
  const refresh = (): void => {
    submit.classList.toggle('disabled', !ready());
  };
  for (const input of [name, username, password.input]) input.addEventListener('input', refresh);
  refresh();

  let busy = false;
  submit.addEventListener('click', () => {
    if (busy || !ready()) return;
    busy = true;
    status.textContent = t('connecting_playlist');
    void options
      .onSubmit(
        {
          name: name.value.trim(),
          address: servers.address(),
          username: username.value.trim(),
          password: password.input.value,
        },
        status,
      )
      .finally(() => {
        busy = false;
      });
  });

  host.append(
    el(
      'div',
      { class: 'add-playlist' },
      el('div', { class: 'settings-title-row' }, back, el('div', { class: 'browser-title settings-title' }, t('add_playlist_title'))),
      name,
      el('div', { class: 'add-label' }, t('choose_your_server')),
      servers.element,
      username,
      password.element,
      el('div', { class: 'add-note' }, t('credentials_stored_securely')),
      status,
      submit,
    ),
  );

  const release = pushKeyHandler((key) => {
    if (key !== 'back') return false;
    leave();
    return true;
  });

  function leave(): void {
    release();
    options.onBack();
  }

  focus(name);
}
