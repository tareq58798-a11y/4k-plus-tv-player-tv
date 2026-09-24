/**
 * Resume or start again, asked before a film or an episode with a saved position plays.
 *
 * The television resumes without asking - MoviePlayer starts at `progress[episode.id]` - and has
 * no way to watch something again from the start short of scrubbing back. Asked for here, so this
 * adds the question rather than porting one. It is drawn as the category menu is, an AlertDialog:
 * the title's name, the two choices with their icons, Cancel at the foot. Resume is where the
 * highlight starts, because it is what somebody coming back to a film usually wants.
 */
import { t } from '../shared/i18n';
import { focus, pushKeyHandler } from './focus';
import { iconElement, type IconName } from './icons';

export interface ResumeChoiceOptions {
  title: string;
  positionMs: number;
  onResume: () => void;
  onStartOver: () => void;
  onCancel: () => void;
}

/** "12:34", or "1:02:03" once there are hours - the same shape the player's clocks use. */
function clock(ms: number): string {
  const total = Math.floor(ms / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number): string => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

export function askResume(options: ResumeChoiceOptions): void {
  const backdrop = document.createElement('div');
  backdrop.className = 'dialog-backdrop';
  const dialog = document.createElement('div');
  dialog.className = 'dialog';
  dialog.setAttribute('data-focus-group', 'resume-choice');

  const title = document.createElement('div');
  title.className = 'dialog-title';
  title.textContent = options.title;
  dialog.append(title);

  const returnTo = document.activeElement as HTMLElement | null;

  function close(): void {
    release();
    backdrop.remove();
  }

  function action(id: string, icon: IconName, label: string, run: () => void): void {
    const row = document.createElement('div');
    row.className = 'dialog-action';
    row.tabIndex = -1;
    row.setAttribute('data-focus', '');
    row.setAttribute('data-focus-id', `resume-${id}`);
    const text = document.createElement('span');
    text.textContent = label;
    row.append(iconElement(icon, 'dialog-action-icon'), text);
    row.addEventListener('click', () => {
      // Closed first, so what it starts is drawn on a page with no dialog left over it.
      close();
      run();
    });
    dialog.append(row);
  }

  action('resume', 'playArrow', t('resume_time', clock(options.positionMs)), options.onResume);
  action('start', 'replay', t('play_from_beginning'), options.onStartOver);

  const buttons = document.createElement('div');
  buttons.className = 'dialog-buttons';
  const cancel = document.createElement('div');
  cancel.className = 'dialog-text-button';
  cancel.tabIndex = -1;
  cancel.textContent = t('action_cancel');
  cancel.setAttribute('data-focus', '');
  cancel.setAttribute('data-focus-id', 'resume-cancel');
  const dismiss = (): void => {
    close();
    if (returnTo?.isConnected) focus(returnTo);
    options.onCancel();
  };
  cancel.addEventListener('click', dismiss);
  buttons.append(cancel);
  dialog.append(buttons);

  backdrop.append(dialog);
  document.body.append(backdrop);

  // The same key rules as the category menu: Back cancels, the arrows walk the dialog and never
  // leave it, OK falls through to press what is highlighted.
  const release = pushKeyHandler((key) => {
    if (key === 'back') {
      dismiss();
      return true;
    }
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
