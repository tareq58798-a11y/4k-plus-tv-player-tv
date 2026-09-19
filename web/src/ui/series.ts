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
import { progressOf } from '../shared/library';
import type { Backdrop } from './backdrop';
import { focus, pushKeyHandler } from './focus';

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

export interface SeriesOptions {
  series: PlaylistItem;
  details: SeriesDetails;
  backdrop: Backdrop;
  onEpisode: (episode: SeriesEpisode) => void;
  onBack: () => void;
}

export function renderSeries(host: HTMLElement, options: SeriesOptions): void {
  const { series, details, backdrop } = options;
  backdrop.show(details.backdropUrl ?? details.posterUrl ?? series.logoUrl);

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
        image.src = episode.thumbnailUrl;
        image.addEventListener('error', () => image.remove());
        thumb.append(image);
      }
      const duration = readableDuration(episode.duration);
      const text = el(
        'div',
        { class: 'episode-text' },
        el('div', { class: 'episode-title' }, `E${episode.episodeNumber} • ${episode.title}`),
        el('div', { class: 'episode-meta' }, duration ?? ''),
      );
      row.append(thumb, text);
      const watched = progressOf({ ...series, channelId: episode.id });
      if (watched !== null) {
        row.append(el('div', { class: 'progress' }, el('div', { class: 'progress-fill', style: `width:${watched * 100}%` })));
      }
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

  const release = pushKeyHandler((key) => {
    if (key !== 'back') return false;
    release();
    options.onBack();
    return true;
  });
}
