/**
 * A yes-or-no question, as the television's AlertDialog asks one: a title, a sentence, and two
 * text buttons at the foot - the dismiss button, then the confirm button.
 *
 * Used for "Exit app?" and for removing a playlist. The highlight starts on the dismiss button,
 * so an extra OK or Back keeps things as they are; with both of these, the thing that cannot be
 * undone is the one that has to be chosen deliberately. Back answers it as dismiss.
 */
import { focus, pushKeyHandler } from './focus';

export interface ConfirmOptions {
  /** Identifies the dialog, so a second request while it is open does not stack another. */
  id: string;
  title: string;
  message: string;
  confirmLabel: string;
  dismissLabel: string;
  /** Drawn in the error colour, as the television draws Remove. */
  destructive?: boolean;
  onConfirm: () => void;
  onDismiss?: () => void;
}

export function confirm(options: ConfirmOptions): void {
  if (document.querySelector(`.dialog[data-focus-group="${options.id}"]`)) return;

  const backdrop = document.createElement('div');
  backdrop.className = 'dialog-backdrop';
  const dialog = document.createElement('div');
  dialog.className = 'dialog';
  dialog.setAttribute('data-focus-group', options.id);

  const title = document.createElement('div');
  title.className = 'dialog-title';
  title.textContent = options.title;
  const message = document.createElement('div');
  message.className = 'dialog-message';
  message.textContent = options.message;
  dialog.append(title, message);

  const returnTo = document.activeElement as HTMLElement | null;

  function close(): void {
    release();
    backdrop.remove();
  }

  function dismiss(): void {
    close();
    if (returnTo?.isConnected) focus(returnTo);
    options.onDismiss?.();
  }

  const buttons = document.createElement('div');
  buttons.className = 'dialog-buttons';
  const button = (id: string, label: string, className: string, run: () => void): HTMLElement => {
    const element = document.createElement('div');
    element.className = className;
    element.tabIndex = -1;
    element.textContent = label;
    element.setAttribute('data-focus', '');
    element.setAttribute('data-focus-id', id);
    element.addEventListener('click', run);
    buttons.append(element);
    return element;
  };
  const stay = button(`${options.id}-dismiss`, options.dismissLabel, 'dialog-text-button', dismiss);
  button(
    `${options.id}-confirm`,
    options.confirmLabel,
    options.destructive ? 'dialog-text-button danger' : 'dialog-text-button',
    () => {
      // Closed first, so whatever it starts is drawn without the dialog over it.
      close();
      options.onConfirm();
    },
  );
  dialog.append(buttons);

  backdrop.append(dialog);
  document.body.append(backdrop);

  // Left and Right move between the two buttons and nowhere else; Up and Down have nowhere to go.
  // OK falls through to press whichever is highlighted.
  const release = pushKeyHandler((key) => {
    if (key === 'back') {
      dismiss();
      return true;
    }
    if (key === 'left' || key === 'right') {
      const stops = Array.from(buttons.querySelectorAll<HTMLElement>('[data-focus]'));
      const here = stops.indexOf(document.activeElement as HTMLElement);
      const step = key === 'right' ? 1 : -1;
      // Laid out right to left in Arabic, so Right moves towards the start of the row there.
      const rtl = getComputedStyle(buttons).direction === 'rtl';
      const next = stops[here + (rtl ? -step : step)];
      if (next) focus(next);
      return true;
    }
    return key === 'up' || key === 'down';
  });

  focus(stay);
}
