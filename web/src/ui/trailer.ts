/**
 * The Trailer button on a film's page and a series' page: the television's OutlinedButton with
 * the SmartDisplay icon and "Trailer", shown only when the provider supplied a trailer.
 */
import { t } from '../shared/i18n';
import { trailerVideoId } from '../platform/youtube';
import { playTrailer } from './trailerPlayer';
import { iconElement } from './icons';

/** Null when there is no trailer, so the caller adds nothing. */
export function trailerButton(id: string, trailerUrl: string | null | undefined, title: string): HTMLElement | null {
  const videoId = trailerVideoId(trailerUrl);
  if (!videoId) return null;
  const button = document.createElement('div');
  button.className = 'button ghost details-trailer';
  button.tabIndex = -1;
  button.setAttribute('data-focus', '');
  button.setAttribute('data-focus-id', id);
  const draw = (label: string): void => {
    button.textContent = '';
    const text = document.createElement('span');
    text.textContent = label;
    button.append(iconElement('smartDisplay', 'details-trailer-icon'), text);
  };
  draw(t('trailer_label'));
  // Played in the app, over this page - see trailerPlayer.ts. The YouTube app is its fallback.
  button.addEventListener('click', () => playTrailer(videoId, title));
  return button;
}
