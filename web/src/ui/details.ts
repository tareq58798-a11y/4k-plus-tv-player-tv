/**
 * A film's page, ported from MovieDetails in MainActivity.kt's landscape branch.
 *
 * Until now, pressing OK on a poster in the Movies grid started playing it. Everything the
 * provider knows about a film - who is in it, who directed it, how long it runs, what it is about -
 * was being fetched and thrown away, and the only way to find out whether you wanted to watch
 * something was to start watching it. The landing page's information block covers part of this for
 * the row it sits under; the grid, which is where films are actually chosen, had nothing.
 *
 * The television app's arrangement, which this follows: a fixed left column carrying the poster and
 * the actions, and a right column that scrolls. A remote reads down the right-hand side and the
 * Play button stays where it was put - which is the opposite of the phone layout and the reason
 * that one is not what was ported.
 */
import { t } from '../shared/i18n';
import type { MovieDetails, PlaylistItem } from '../shared/models';
import { isFavorite, progressOf, toggleFavorite } from '../shared/library';
import type { Backdrop } from './backdrop';
import { pushKeyHandler } from './focus';
import { trailerButton } from './trailer';

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

/** 3600 or "3600" seconds, and "01:02:03", both of which panels send. */
function readableDuration(raw: string | null | undefined): string | null {
  if (!raw) return null;
  if (raw.includes(':')) return raw;
  const seconds = Number(raw);
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

export interface DetailsOptions {
  movie: PlaylistItem;
  /**
   * Null while the lookup is still running, so the page can open at once and fill in.
   *
   * Opening on a spinner would make every film feel slower than it is: the poster, the title, the
   * category and the Play button are all known from the catalogue before anything is asked for,
   * and they are most of what somebody needs to decide.
   */
  details: MovieDetails | null;
  /** True while that lookup is in flight; false once it has finished, succeeded or not. */
  loading: boolean;
  backdrop: Backdrop;
  onPlay: () => void;
  onBack: () => void;
}

export function renderDetails(host: HTMLElement, options: DetailsOptions): HTMLElement {
  const { movie, details, loading, backdrop } = options;
  // The provider's background picture, never the poster; the app's own artwork when there is none.
  // Left alone while the details are still loading, so the page does not flash the default first.
  if (details?.backdropUrl) backdrop.show(details.backdropUrl);
  else if (!loading) backdrop.reset();

  // The catalogue's value wins where it has one and the lookup fills the gaps, which is the rule
  // the television app follows. Neither invents anything: a field both leave empty is left out of
  // the page rather than shown as a blank row.
  const year = movie.year || details?.year || null;
  const rating = movie.rating || details?.rating || null;
  const duration = readableDuration(movie.duration) ?? readableDuration(details?.duration);
  const description = movie.description || details?.description || null;
  const title = details?.originalTitle || movie.name;

  const poster = el('div', { class: 'details-poster' });
  const posterUrl = details?.posterUrl ?? movie.logoUrl;
  if (posterUrl) {
    const image = el('img', { alt: '' }) as HTMLImageElement;
    image.src = posterUrl;
    image.addEventListener('error', () => image.remove());
    poster.append(image);
  }

  // Resume rather than Play once there is somewhere to resume to, so the button says what it will
  // actually do. The progress is the app's own record, not the provider's.
  const started = (progressOf(movie) ?? 0) > 0;
  const play = el(
    'div',
    { class: 'button details-play', tabindex: '-1', 'data-focus': '', 'data-focus-id': 'details-play' },
    started ? t('action_resume') : t('action_play'),
  );
  play.addEventListener('click', options.onPlay);

  let starred = isFavorite(movie);
  const favourite = el(
    'div',
    {
      class: 'button ghost details-favourite',
      tabindex: '-1',
      'data-focus': '',
      'data-focus-id': 'details-favourite',
      'aria-selected': String(starred),
    },
    `${starred ? '★' : '☆'}  ${t('action_favorite')}`,
  );
  favourite.addEventListener('click', () => {
    starred = toggleFavorite(movie);
    favourite.setAttribute('aria-selected', String(starred));
    favourite.textContent = `${starred ? '★' : '☆'}  ${t('action_favorite')}`;
  });

  const left = el('div', { class: 'details-side', 'data-focus-group': 'details-actions' });
  left.append(poster, play, favourite);
  // Trailer, when the provider sent one - see platform/youtube.ts. Absent otherwise, as on the
  // television, so it never leads anywhere but to the title's own trailer.
  const trailer = trailerButton('details-trailer', details?.trailerUrl);
  if (trailer) left.append(trailer);

  const pills = el('div', { class: 'pills' });
  if (rating) pills.append(el('span', { class: 'pill star' }, `★ ${rating}/10`));
  if (year) pills.append(el('span', { class: 'pill' }, year));
  if (duration) pills.append(el('span', { class: 'pill' }, duration));
  if (details?.genre) pills.append(el('span', { class: 'pill' }, details.genre));
  if (movie.group) pills.append(el('span', { class: 'pill' }, movie.group));

  const right = el('div', { class: 'details-main' });
  right.append(el('h1', {}, title));
  // The listing name as well, but only when it is genuinely a different name rather than the same
  // words in the same alphabet.
  if (details?.originalTitle && details.originalTitle !== movie.name) {
    right.append(el('div', { class: 'details-alt' }, movie.name));
  }
  right.append(pills);

  if (loading) {
    right.append(el('div', { class: 'details-loading' }, t('loading_movie_info')));
  } else {
    right.append(el('p', { class: 'details-plot' }, description || t('no_movie_details')));
    if (details?.cast) {
      right.append(el('div', { class: 'credit' }, el('span', {}, t('cast_label')), details.cast));
    }
    if (details?.director) {
      right.append(el('div', { class: 'credit' }, el('span', {}, t('director_label')), details.director));
    }
    // Said once, and only when there is genuinely nothing: a film with a plot but no cast list is
    // not missing its information, it is a film whose panel does not carry credits.
    if (!description && !details?.cast && !details?.director) {
      right.append(el('div', { class: 'details-note' }, t('no_additional_info')));
    }
  }

  const page = el('div', { class: 'details' }, left, right);
  host.append(page);

  const release = pushKeyHandler((key) => {
    if (key !== 'back') return false;
    release();
    options.onBack();
    return true;
  });

  // Only on the first draw. A redraw when the lookup lands must not move the highlight - by then
  // the viewer may have walked to the favourite button, and taking them back to Play would undo a
  // press they had already made.
  return play;
}
