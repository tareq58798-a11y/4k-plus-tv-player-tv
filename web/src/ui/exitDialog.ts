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
import { confirm } from './confirmDialog';

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
  confirm({
    id: 'exit-app',
    title: t('exit_app_title'),
    message: t('exit_app_message'),
    dismissLabel: t('action_cancel'),
    confirmLabel: t('exit_app_confirm'),
    onConfirm: exitApp,
  });
}
