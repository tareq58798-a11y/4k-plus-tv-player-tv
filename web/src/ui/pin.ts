/**
 * The PIN prompt.
 *
 * A keypad rather than a text field. A remote has no digits on it that a web page can reach, and
 * an on-screen keyboard for four numbers is three presses of overhead before the first one - so
 * the digits are on screen where the D-pad can walk to them.
 *
 * Shown as a layer over whatever is underneath, and it takes every key while it is up: a prompt
 * you can arrow out of is not a prompt.
 */
import { t } from '../shared/i18n';
import { checkPin, isValidPin } from '../shared/parental';
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

export interface PinOptions {
  title: string;
  /** 'verify' checks against the stored PIN; 'set' just collects a valid one. */
  mode: 'verify' | 'set';
  onDone: (pin: string) => void;
  onCancel: () => void;
}

export function askPin(options: PinOptions): void {
  let entered = '';

  const dots = el('div', { class: 'pin-dots' });
  const message = el('div', { class: 'pin-message' });
  const overlay = el('div', { class: 'pin-overlay' });

  function redraw(): void {
    dots.textContent = '';
    for (let i = 0; i < Math.max(6, entered.length); i++) {
      dots.append(el('span', { class: i < entered.length ? 'dot filled' : 'dot' }));
    }
  }

  async function submit(): Promise<void> {
    if (!isValidPin(entered)) {
      message.textContent = t('pin_length_error');
      return;
    }
    if (options.mode === 'set') {
      close();
      options.onDone(entered);
      return;
    }
    if (await checkPin(entered)) {
      close();
      options.onDone(entered);
      return;
    }
    // Wrong. Clear rather than let them keep adding digits onto a failed attempt.
    entered = '';
    redraw();
    message.textContent = t('incorrect_pin');
  }

  function press(digit: string): void {
    if (entered.length >= 6) return;
    entered += digit;
    message.textContent = '';
    redraw();
    // Nothing submits by itself. A four-digit PIN that fires on the fourth digit cannot be
    // corrected, and a six-digit one would never be reachable.
  }

  const pad = el('div', { class: 'pin-pad', 'data-focus-group': 'pin-pad' });
  for (const digit of ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0']) {
    const key = el('div', { class: 'pin-key', tabindex: '-1', 'data-focus': '', 'data-focus-id': `pin-${digit}` }, digit);
    key.addEventListener('click', () => press(digit));
    pad.append(key);
  }

  const back = el('div', { class: 'pin-key wide', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'pin-back' }, '⌫');
  back.addEventListener('click', () => {
    entered = entered.slice(0, -1);
    message.textContent = '';
    redraw();
  });
  const ok = el('div', { class: 'pin-key wide go', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'pin-ok' }, t('unlock_action'));
  ok.addEventListener('click', () => void submit());
  pad.append(back, ok);

  const panel = el(
    'div',
    { class: 'pin-panel' },
    el('h2', {}, options.title),
    el('div', { class: 'pin-hint' }, t('pin_digit_hint')),
    dots,
    pad,
    message,
  );
  overlay.append(panel);
  document.body.append(overlay);
  redraw();
  focus(pad.querySelector<HTMLElement>('[data-focus]'));

  let release: () => void = () => undefined;

  function close(): void {
    release();
    overlay.remove();
  }

  release = pushKeyHandler((key) => {
    if (key === 'back') {
      close();
      options.onCancel();
      return true;
    }
    // Everything else is handled by the pad, or by the focus engine moving between its keys - but
    // nothing below this layer may see any of it.
    return false;
  });
}
