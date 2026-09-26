/**
 * A series and its episodes, ported from SeriesScreen.kt's landscape layout.
 *
 * A television keeps the poster, title, pills and credits fixed in a column and lets only the
 * episode list scroll, because a remote walks down into that list and the header can stay above
 * it. That is the opposite of what the phone build needed, and deliberately so - this is the
 * television's arrangement, and the reason the header here is written compactly is to leave the
 * list as much of the screen as it can.
 *
 * Seasons are chips rather than a menu. A viewer on a remote should be able to see how many there
 * are without opening anything, and moving along them changes the list underneath at once.
 */
import { t } from '../shared/i18n';
import type { PlaylistItem, SeriesDetails, SeriesEpisode } from '../shared/models';
import { isFavorite, toggleFavorite, watchState } from '../shared/library';
import type { Backdrop } from './backdrop';
import { focus, pushKeyHandler } from './focus';
import { lazyImage } from './images';
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
function readableDuration(raw: string | null): string | null {
  if (!raw) return null;
  if (raw.includes(':')) return raw;
  const seconds = Number(raw);
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/** "23:41", or "1:02:03" once there are hours - the same shape the player's clocks use. */
function clock(ms: number): string {
  const total = Math.floor(ms / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number): string => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

export interface SeriesOptions {
  series: PlaylistItem;
  details: SeriesDetails;
  backdrop: Backdrop;
  onEpisode: (episode: SeriesEpisode) => void;
  onBack: () => void;
}

export function renderSeries(host: HTMLElement, options: SeriesOptions): void {
  const { series, details, backdrop } = options;
  // The provider's background picture, never the poster; the app's own artwork when there is none.
  if (details.backdropUrl) backdrop.show(details.backdropUrl);
  else backdrop.reset();

  const seasons = [...new Set(details.episodes.map((episode) => episode.seasonNumber))].sort((a, b) => a - b);
  let season = seasons[0] ?? 1;

  const poster = el('div', { class: 'series-poster' });
  if (details.posterUrl ?? series.logoUrl) {
    const image = el('img', { alt: '' }) as HTMLImageElement;
    image.src = (details.posterUrl ?? series.logoUrl)!;
    image.addEventListener('error', () => image.remove());
    poster.append(image);
  }

  const pills = el('div', { class: 'pills' });
  if (details.rating) pills.append(el('span', { class: 'pill star' }, `★ ${details.rating}/10`));
  if (details.year) pills.append(el('span', { class: 'pill' }, details.year));
  if (details.genre) pills.append(el('span', { class: 'pill' }, details.genre));
  if (series.group) pills.append(el('span', { class: 'pill' }, series.group));

  const head = el('div', { class: 'series-head' });
  head.append(el('h1', {}, details.originalTitle ?? series.name));
  // The listing name as well, but only when it is genuinely a different name rather than the
  // same words in the same alphabet.
  if (details.originalTitle && details.originalTitle !== series.name) {
    head.append(el('div', { class: 'series-alt' }, series.name));
  }
  head.append(pills);

  /*
   * Favourite, as on a film's page and as the television's series page has it - its star button
   * beside the title. A series had no way to be starred here at all, so its Favorites row could
   * only ever be empty. Under the pills, above the seasons, where Up from the season chips lands.
   */
  let starred = isFavorite(series);
  const favourite = el(
    'div',
    {
      class: 'button ghost details-favourite series-favourite',
      tabindex: '-1',
      'data-focus': '',
      'data-focus-id': 'series-favourite',
      'aria-selected': String(starred),
    },
    `${starred ? '★' : '☆'}  ${t('action_favorite')}`,
  );
  favourite.addEventListener('click', () => {
    starred = toggleFavorite(series);
    favourite.setAttribute('aria-selected', String(starred));
    favourite.textContent = `${starred ? '★' : '☆'}  ${t('action_favorite')}`;
  });
  // Trailer beside it, when the provider sent one. The television reads a series' trailer too
  // (SeriesDetailsInfo.trailerUrl) but only shows the button for films; here both have it, at
  // the owner's request. Recorded in web/README.md.
  const trailer = trailerButton('series-trailer', details.trailerUrl, details.originalTitle ?? series.name);
  head.append(trailer ? el('div', { class: 'series-actions' }, favourite, trailer) : favourite);
  if (details.description) head.append(el('p', { class: 'series-plot' }, details.description));
  if (details.cast) head.append(el('div', { class: 'credit' }, el('span', {}, t('cast_label')), details.cast));
  if (details.director) head.append(el('div', { class: 'credit' }, el('span', {}, t('director_label')), details.director));

  const chips = el('div', { class: 'chips', 'data-focus-group': 'seasons' });
  const list = el('div', { class: 'episodes', 'data-focus-group': 'episodes' });

  function renderEpisodes(): void {
    list.textContent = '';
    const episodes = details.episodes.filter((episode) => episode.seasonNumber === season);
    if (!episodes.length) {
      list.append(el('div', { class: 'status' }, t('no_episodes_supplied')));
      return;
    }
    for (const episode of episodes) {
      const row = el('div', {
        class: 'episode',
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': `episode-${episode.id}`,
      });
      const thumb = el('div', { class: 'episode-thumb' });
      if (episode.thumbnailUrl) {
        const image = el('img', { alt: '' }) as HTMLImageElement;
        lazyImage(image, episode.thumbnailUrl);
        image.addEventListener('error', () => image.remove());
        thumb.append(image);
      }
      const duration = readableDuration(episode.duration);
      /*
       * Where this episode was left, as YouTube marks a video: a red strip along the foot of the
       * thumbnail as long as the part watched, and the exact time reached beside the length -
       * "Resume 23:41" - or "Watched" once it was seen to the end. Asked for by the owner; the
       * television shows a bare progress line and no time. Both labels are the Android app's own
       * translated strings. The episode's resume point is keyed by the episode, as the player
       * saves it (onEpisode in main.ts).
       */
      const state = watchState({ ...series, channelId: episode.id });
      const meta: string[] = [];
      if (duration) meta.push(duration);
      if (state?.kind === 'partial') meta.push(t('resume_time', clock(state.positionMs)));
      if (state?.kind === 'finished') meta.push(t('watched_label'));
      const text = el(
        'div',
        { class: 'episode-text' },
        el('div', { class: 'episode-title' }, `E${episode.episodeNumber} • ${episode.title}`),
        el('div', { class: `episode-meta${state ? ' watched' : ''}` }, meta.join('  •  ')),
      );
      if (state) {
        const fraction = state.kind === 'finished' ? 1 : Math.min(1, state.positionMs / state.durationMs);
        thumb.append(
          el('div', { class: 'episode-progress' }, el('div', { class: 'episode-progress-fill', style: `width:${(fraction * 100).toFixed(1)}%` })),
        );
        if (state.kind === 'finished') row.classList.add('finished');
      }
      row.append(thumb, text);
      row.addEventListener('click', () => options.onEpisode(episode));
      list.append(row);
    }
  }

  for (const number of seasons) {
    const chip = el(
      'div',
      {
        class: 'chip',
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': `season-${number}`,
        'aria-selected': String(number === season),
      },
      t('season_number', number),
    );
    // Moving along the chips changes the list at once, the same way moving down the category
    // list in the browser does.
    chip.addEventListener('focus', () => {
      // Coming back up onto the season already showing must not rebuild its list - that threw the
      // viewer's place away for nothing.
      if (number === season && list.childElementCount) return;
      season = number;
      for (const other of chips.children) {
        other.setAttribute('aria-selected', String(other.getAttribute('data-focus-id') === `season-${number}`));
      }
      renderEpisodes();
    });
    chips.append(chip);
  }

  const right = el('div', { class: 'series-right' }, head, chips, list);
  host.append(el('div', { class: 'series' }, poster, right));
  renderEpisodes();
  // Straight onto the first episode: it is what the page is for, and a remote should not have to
  // walk past the synopsis to reach it.
  focus(list.querySelector<HTMLElement>('[data-focus]') ?? chips.querySelector<HTMLElement>('[data-focus]'));

  /*
   * Up and Down in the episode list go to the episode before and after, by position in the list -
   * never by what happens to be nearest on screen.
   *
   * The page's geometric focus search did this, and it broke at the top edge of the list: once the
   * list had scrolled, the episode above was hidden above the list's visible area, and the season
   * chips were physically closer. Up jumped to a chip, and focusing a chip switches season - so the
   * list was replaced with another season's, and every Up and Down after that bounced between that
   * chip and its first episode. Measured on the set: Down ep1 to ep16 fine, Up ep16 to ep14 fine,
   * then "season-3" and back, indefinitely.
   *
   * Up from the first episode goes to the chip of the season being shown, and Down from any chip
   * to that season's first episode.
   */
  const release = pushKeyHandler((key) => {
    if (key === 'back') {
      release();
      options.onBack();
      return true;
    }
    if (key !== 'up' && key !== 'down') return false;
    const active = document.activeElement as HTMLElement | null;
    if (!active) return false;
    if (list.contains(active)) {
      const rows = [...list.querySelectorAll<HTMLElement>('.episode')];
      const at = rows.indexOf(active);
      if (key === 'down') {
        if (rows[at + 1]) focus(rows[at + 1]!);
        return true;
      }
      if (at > 0) {
        focus(rows[at - 1]!);
        return true;
      }
      const current = chips.querySelector<HTMLElement>(`[data-focus-id="season-${season}"]`);
      if (current) focus(current);
      return true;
    }
    if (chips.contains(active) && key === 'down') {
      const first = list.querySelector<HTMLElement>('.episode');
      if (first) {
        focus(first);
        return true;
      }
    }
    return false;
  });
}
