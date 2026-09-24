/**
 * Search across the whole playlist, ported from SearchScreen.kt.
 *
 * Deliberately over everything rather than the section the viewer happens to be in: somebody who
 * remembers a title does not also remember whether the provider filed it under films or series,
 * and making them guess is the difference between finding it and giving up.
 *
 * Typing on a television is slow - an on-screen keyboard and a remote - so every keystroke has to
 * earn its place. Results update as characters arrive, and matching is by substring rather than
 * anything cleverer, because a viewer typing three letters wants everything containing them.
 */
import { t } from '../shared/i18n';
import type { PlaylistItem } from '../shared/models';
import { itemKey } from '../shared/models';
import type { Backdrop } from './backdrop';
import { focus, pushKeyHandler } from './focus';
import { lazyImage, posterArtwork } from './images';

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

/**
 * Case-insensitive, and accent-insensitive where the browser can manage it.
 *
 * A catalogue is full of titles with diacritics the viewer will not type - "Bäckström" searched
 * as "backstrom" should still be found. NFD splits a letter from its accent so the accent can be
 * stripped; a set whose engine lacks normalize simply keeps the accents, which is no worse than
 * not trying.
 */
function fold(value: string): string {
  const lower = value.toLowerCase();
  try {
    return lower.normalize('NFD').replace(/[̀-ͯ]/g, '');
  } catch {
    return lower;
  }
}

export interface SearchOptions {
  items: PlaylistItem[];
  backdrop: Backdrop;
  onOpen: (item: PlaylistItem) => void;
  onBack: () => void;
}

export function renderSearch(host: HTMLElement, options: SearchOptions): void {
  // Folded once for the whole catalogue rather than per keystroke: on forty thousand titles the
  // difference is a search that keeps up with typing and one that does not.
  const index = options.items.map((item) => ({ item, haystack: fold(`${item.name} ${item.group}`) }));

  /** Matches the Android app's pause before a search runs. */
  const SEARCH_DELAY_MS = 320;

  const field = el('input', {
    type: 'text',
    class: 'search-field',
    placeholder: t('search_hint'),
    'data-focus': '',
    'data-focus-id': 'search-field',
  });
  const count = el('div', { class: 'search-count' });
  const results = el('div', { class: 'grid', 'data-focus-group': 'search-results' });

  function render(): void {
    const query = fold(field.value.trim());
    results.textContent = '';
    if (query.length < 2) {
      // One character matches most of a catalogue, which is not a result, it is a redraw of
      // everything. Saying so is more use than showing it.
      count.textContent = t('search_hint');
      return;
    }
    const matched = index.filter((entry) => entry.haystack.includes(query)).slice(0, 300);
    count.textContent = matched.length
      ? `${matched.length} ${t('items_label')}`
      : t('search_no_results', field.value.trim());
    matched.forEach(({ item }, index) => {
      const card = el('div', { class: 'card', tabindex: '-1', 'data-focus': '', 'data-focus-id': itemKey(item) });
      const art = el('img', { class: 'art', alt: '' }) as HTMLImageElement;
      // The first two rows at once; the rest as they near the screen.
      lazyImage(art, posterArtwork(item.logoUrl), index < 12);
      card.append(art, el('div', { class: 'card-foot' }, el('div', { class: 'label' }, item.name)));
      card.addEventListener('focus', () => {
        if (item.kind !== 'live') options.backdrop.show(item.logoUrl);
      });
      card.addEventListener('click', () => options.onOpen(item));
      results.append(card);
    });
  }

  /*
   * Searching waits for the typing to stop.
   *
   * The filter runs over every item in the catalogue - fifty thousand is ordinary - and each pass
   * rebuilds the results grid and starts fetching artwork for it. Per keystroke that makes a
   * six-letter word cost six full passes and six rounds of image loading, five of which nobody
   * asked for, and on a television the typing itself falls behind because the work is on the same
   * thread as the on-screen keyboard.
   *
   * Clearing is immediate: emptying the box means "start again", and making that wait reads as the
   * app having stuck.
   */
  let searchTimer: number | null = null;
  field.addEventListener('input', () => {
    if (searchTimer !== null) window.clearTimeout(searchTimer);
    if (!field.value.trim()) {
      render();
      return;
    }
    searchTimer = window.setTimeout(() => {
      searchTimer = null;
      render();
    }, SEARCH_DELAY_MS);
  });
  host.append(
    el('div', { class: 'browser-title' }, t('search_title')),
    el('div', { class: 'search' }, field, count, results),
  );
  render();
  focus(field);

  const release = pushKeyHandler((key) => {
    if (key !== 'back') return false;
    release();
    options.onBack();
    return true;
  });
}
