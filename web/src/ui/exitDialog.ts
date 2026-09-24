/**
 * "Exit app?" - asked when Back is pressed on Home, ported from the AlertDialog in MainActivity.kt
 * (showExitConfirm).
 *
 * Home is where Back runs out of places to go back to. Leaving without asking closes the app from
 * a press the viewer uses dozens of times an evening; doing nothing, which is what this did before,
 * leaves a Back that silently does not work on the one page it should mean something. The
 * television asks, with its own three strings, and so does this.
 *
 * Drawn as that AlertDialog is: title, message, and two text buttons at the foot - Cancel, then
 * Exit. The highlight starts on Cancel. A second press of OK, or a Back pressed once too often,
 * then keeps the viewer in the app rather than closing it, which is the cheaper mistake.
 */
import { t } from '../shared/i18n';
import { focus, pushKeyHandler } from './focus';

/**
 * Closes the app. On a Samsung set that is the application object's exit(); anywhere else there is
 * no app to close, and window.close() is the nearest thing a browser allows.
 */
function exitApp(): void {
  try {
    const tizen = (window as unknown as {
      tizen?: { application?: { getCurrentApplication(): { exit(): void } } };
    }).tizen;
    const app = tizen?.application?.getCurrentApplication();
    if (app) {
      app.exit();
      return;
    }
  } catch {
    /* Fall through to the browser's answer. */
  }
  window.close();
}

export function askExit(): void {
  // Asked once: a Back pressed again while the question is up answers it, below.
  if (document.querySelector('.dialog[data-focus-group="exit-app"]')) return;

  const backdrop = document.createElement('div');
  backdrop.className = 'dialog-backdrop';
  const dialog = document.createElement('div');
  dialog.className = 'dialog';
  dialog.setAttribute('data-focus-group', 'exit-app');

  const title = document.createElement('div');
  title.className = 'dialog-title';
  title.textContent = t('exit_app_title');
  const message = document.createElement('div');
  message.className = 'dialog-message';
  message.textContent = t('exit_app_message');
  dialog.append(title, message);

  const returnTo = document.activeElement as HTMLElement | null;

  function close(): void {
    release();
    backdrop.remove();
    if (returnTo?.isConnected) focus(returnTo);
  }

  const buttons = document.createElement('div');
  buttons.className = 'dialog-buttons';
  const button = (id: string, label: string, run: () => void): HTMLElement => {
    const element = document.createElement('div');
    element.className = 'dialog-text-button';
    element.tabIndex = -1;
    element.textContent = label;
    element.setAttribute('data-focus', '');
    element.setAttribute('data-focus-id', id);
    element.addEventListener('click', run);
    buttons.append(element);
    return element;
  };
  const stay = button('exit-cancel', t('action_cancel'), close);
  button('exit-confirm', t('exit_app_confirm'), exitApp);
  dialog.append(buttons);

  backdrop.append(dialog);
  document.body.append(backdrop);

  // Back is "no", as dismissing an AlertDialog is on the television. Left and Right move between
  // the two buttons and nowhere else; Up and Down have nowhere to go. OK falls through to press
  // whichever is highlighted.
  const release = pushKeyHandler((key) => {
    if (key === 'back') {
      close();
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
