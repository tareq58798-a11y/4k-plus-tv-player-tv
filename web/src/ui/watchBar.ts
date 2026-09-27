/**
 * Where a film or episode was stopped, drawn as a small timeline rather than a bare red line
 * (owner's request): a grey track for the whole length, red up to the stopping point, a marker
 * standing exactly on it, and the time reached over the whole length - "45:12 / 1:52:00".
 */
import { t } from '../shared/i18n';
import type { WatchPoint } from '../shared/library';

/** "23:41", or "1:02:03" once there are hours - the same shape the player's clocks use. */
export function clock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number): string => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

function div(className: string, text?: string): HTMLDivElement {
  const node = document.createElement('div');
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/** [withTime] false leaves out the time line, for a caller that prints it elsewhere. */
export function watchBar(point: WatchPoint, withTime = true): HTMLElement {
  const fraction = point.kind === 'finished' ? 1 : Math.min(1, Math.max(0, point.positionMs / point.durationMs));
  const percent = `${(fraction * 100).toFixed(2)}%`;
  const bar = div('watch-bar');
  const fill = div('watch-bar-fill');
  fill.style.width = percent;
  const mark = div('watch-bar-mark');
  mark.style.left = percent;
  bar.append(fill, mark);
  const wrap = div('watch');
  if (withTime) {
    wrap.append(
      div('watch-time', point.kind === 'finished' ? t('watched_label') : `${clock(point.positionMs)} / ${clock(point.durationMs)}`),
    );
  }
  wrap.append(bar);
  return wrap;
}
