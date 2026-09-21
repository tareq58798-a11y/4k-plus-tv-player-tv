/**
 * The menu a held OK opens on a category, ported from CategoryActionsDialog.
 *
 * Four actions, in the television app's order: hand-move, jump to top, jump to bottom, hide. The
 * two jumps exist because hand-moving is one place per press, and a playlist with four hundred
 * categories makes moving a favourite from the bottom to the top four hundred presses.
 *
 * The gesture differs from Android by necessity rather than by choice. There is no long press on
 * the web: a remote held down produces repeated keydown events, so a hold is detected from the
 * repeat rather than from a timer, and the first press has already landed by then. On this screen
 * that costs nothing, because focusing a category is what selects it and OK does nothing else.
 */
import { t } from '../shared/i18n';
import { focus, pushKeyHandler } from './focus';

export interface CategoryMenuOptions {
  category: string;
  /** Absent where there is nothing behind the row to hide - the built-in views, as on Android. */
  onHide?: (() => void) | null;
  onMoveManually: () => void;
  onMoveToTop: () => void;
  onMoveToBottom: () => void;
  onDismiss: () => void;
}

export function openCategoryMenu(host: HTMLElement, options: CategoryMenuOptions): void {
  const backdrop = document.createElement('div');
  backdrop.className = 'dialog-backdrop';

  const dialog = document.createElement('div');
  dialog.className = 'dialog';
  dialog.setAttribute('data-focus-group', 'category-menu');

  const title = document.createElement('div');
  title.className = 'dialog-title';
  title.textContent = options.category;
  dialog.append(title);

  // Focus is returned to where it was, not left on a row that no longer exists. Hiding a category
  // removes the very row the menu was opened from, and a highlight on a removed element is a
  // highlight nobody can see.
  const returnTo = document.activeElement as HTMLElement | null;

  function close(): void {
    release();
    backdrop.remove();
    options.onDismiss();
  }

  function action(id: string, label: string, run: () => void): void {
    const row = document.createElement('div');
    row.className = 'dialog-action';
    row.tabIndex = -1;
    row.textContent = label;
    row.setAttribute('data-focus', '');
    row.setAttribute('data-focus-id', `category-${id}`);
    row.addEventListener('click', () => {
      // Closed first, so the action runs against a page with no dialog on it - a redraw underneath
      // an open dialog leaves the dialog orphaned over a page that has been rebuilt.
      close();
      run();
    });
    dialog.append(row);
  }

  action('manual', t('move_manually'), options.onMoveManually);
  action('top', t('move_to_top'), options.onMoveToTop);
  action('bottom', t('move_to_bottom'), options.onMoveToBottom);
  if (options.onHide) action('hide', t('hide_category_action'), options.onHide);

  const cancel = document.createElement('div');
  cancel.className = 'dialog-action muted';
  cancel.tabIndex = -1;
  cancel.textContent = t('action_cancel');
  cancel.setAttribute('data-focus', '');
  cancel.setAttribute('data-focus-id', 'category-cancel');
  cancel.addEventListener('click', close);
  dialog.append(cancel);

  backdrop.append(dialog);
  host.append(backdrop);

  const release = pushKeyHandler((key) => {
    if (key === 'back') {
      close();
      if (returnTo?.isConnected) focus(returnTo);
      return true;
    }
    // Everything else is swallowed while this is open. A dialog that lets arrow keys through moves
    // a highlight the viewer cannot see, on a page they are not looking at.
    return false;
  });

  focus(dialog.querySelector<HTMLElement>('[data-focus]'));
}
