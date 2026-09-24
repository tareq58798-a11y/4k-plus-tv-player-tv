/**
 * The menu a held OK opens on a category, ported from CategoryActionsDialog.
 *
 * Four actions, in the television app's order: hand-move, jump to top, jump to bottom, hide. The
 * two jumps exist because hand-moving is one place per press, and a playlist with four hundred
 * categories makes moving a favourite from the bottom to the top four hundred presses.
 *
 * Drawn as that AlertDialog draws it: the category as the title, each action a row with its icon
 * in Cyan - SwapVert, VerticalAlignTop, VerticalAlignBottom, VisibilityOff - and Cancel as a text
 * button at the foot. The hold that opens it is holdOk in main.ts.
 */
import { t } from '../shared/i18n';
import { focus, pushKeyHandler } from './focus';
import { iconElement, type IconName } from './icons';

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

  function action(id: string, icon: IconName, label: string, run: () => void): void {
    const row = document.createElement('div');
    row.className = 'dialog-action';
    row.tabIndex = -1;
    const text = document.createElement('span');
    text.textContent = label;
    row.append(iconElement(icon, 'dialog-action-icon'), text);
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

  action('manual', 'swapVert', t('move_manually'), options.onMoveManually);
  action('top', 'verticalAlignTop', t('move_to_top'), options.onMoveToTop);
  action('bottom', 'verticalAlignBottom', t('move_to_bottom'), options.onMoveToBottom);
  if (options.onHide) action('hide', 'visibilityOff', t('hide_category_action'), options.onHide);

  // The AlertDialog's confirmButton: a TextButton, at the end of the foot.
  const buttons = document.createElement('div');
  buttons.className = 'dialog-buttons';
  const cancel = document.createElement('div');
  cancel.className = 'dialog-text-button';
  cancel.tabIndex = -1;
  cancel.textContent = t('action_cancel');
  cancel.setAttribute('data-focus', '');
  cancel.setAttribute('data-focus-id', 'category-cancel');
  cancel.addEventListener('click', close);
  buttons.append(cancel);
  dialog.append(buttons);

  backdrop.append(dialog);
  host.append(backdrop);

  const release = pushKeyHandler((key) => {
    if (key === 'back') {
      close();
      if (returnTo?.isConnected) focus(returnTo);
      return true;
    }
    /*
     * The arrows stay inside the dialog: Up and Down walk its entries and stop at either end,
     * Left and Right do nothing. This said the same and returned false, which handed every arrow
     * to the page's own directional search - so Up from the first entry left the dialog open and
     * put the highlight on a channel behind it. OK still falls through, to press what is
     * highlighted.
     */
    if (key === 'up' || key === 'down') {
      const stops = Array.from(dialog.querySelectorAll<HTMLElement>('[data-focus]'));
      const next = stops[stops.indexOf(document.activeElement as HTMLElement) + (key === 'down' ? 1 : -1)];
      if (next) focus(next);
      return true;
    }
    return key === 'left' || key === 'right';
  });

  focus(dialog.querySelector<HTMLElement>('[data-focus]'));
}
