/**
 * The shared skeleton behind Home, Live TV, Movies and Series, ported from Landing.kt.
 *
 * All four are the same page with different contents: a navigation bar, one or two rows of
 * artwork, and a block of information about whatever is focused. Writing it once is what stops
 * the focus behaviour, spacing and timing drifting apart between sections - which is the drift
 * that makes an interface feel assembled rather than designed.
 *
 * The rules that matter, all carried from the television app:
 *
 *  - Every row sets a fixed number of places and pads with empty ones. A row that vanishes when
 *    empty teaches the viewer it does not exist; places at the table say "this is where things
 *    go" instead.
 *  - The box that opens the full list is the **first** place in its row, not the last.
 *  - Up from anywhere in the page goes to that page's own tab and stays there.
 *  - The information block belongs to whatever is focused, and disappears when focus leaves the
 *    artwork - it describes a selection, so with nothing selected it has nothing to say.
 *  - On Home the app keeps its own artwork until the viewer actually moves onto a title.
 */
import { t } from '../shared/i18n';
import type { PlaylistItem } from '../shared/models';
import { itemKey } from '../shared/models';
import { isFavorite, progressOf, toggleFavorite } from '../shared/library';
import type { Backdrop } from './backdrop';
import { focus } from './focus';

export interface LandingRow {
  id: string;
  title: string;
  items: PlaylistItem[];
  /** Places the row always shows, padded with empty slots after whatever it holds. */
  minSlots: number;
  /** Shown only while the row still has empty places; once full it explains itself. */
  hint?: string;
  /** The box parked in this row's first place. */
  tile?: { title: string; caption: string; onOpen: () => void };
}

export interface LandingOptions {
  rows: LandingRow[];
  backdrop: Backdrop;
  /** False on Home: the app keeps its own artwork until the viewer moves. */
  followBackdropImmediately: boolean;
  onPlay: (item: PlaylistItem) => void;
  /** Extra details for the focused item, when the catalogue listing does not carry them. */
  loadDetails?: (item: PlaylistItem) => Promise<{ description?: string | null; backdropUrl?: string | null } | null>;
}

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

const KIND_LABEL: Record<PlaylistItem['kind'], string> = {
  live: 'nav_live_tv',
  movie: 'nav_movies',
  series: 'nav_series',
} as unknown as Record<PlaylistItem['kind'], string>;

export function renderLanding(host: HTMLElement, options: LandingOptions): void {
  const { rows, backdrop } = options;
  let viewerHasMoved = options.followBackdropImmediately;

  const info = el('div', { class: 'info' });
  const body = el('div', { class: 'landing' });

  // Details are fetched for whatever the viewer settles on, not for everything they pass. Keyed
  // on the focused item so moving on cancels the wait before it ever becomes a request.
  let pending: number | null = null;
  const details = new Map<string, { description?: string | null; backdropUrl?: string | null }>();

  function describe(item: PlaylistItem | null, anchor?: HTMLElement): void {
    info.textContent = '';
    if (!item) {
      info.classList.remove('visible');
      return;
    }
    info.classList.add('visible');
    // Moved to sit directly under the row the highlight is in, rather than pinned to the foot of
    // the screen. A block at the bottom describes something the viewer has to look away from to
    // read about; under the row it is beside what it is describing, which is how the television
    // app reads. It is one element that relocates rather than one per row, so only the row being
    // looked at ever carries it.
    //
    // The row comes from the card that reported the focus, not from document.activeElement. That
    // property is not reliable here - a window without focus updates it without firing the events
    // this depends on, and worse, the reverse also happens - so reading it turns a layout into a
    // guess about browser state.
    const track = anchor?.closest('.row') ?? null;
    if (track && info.previousElementSibling !== track) track.after(info);

    const extra = details.get(itemKey(item));
    const meta: string[] = [t(KIND_LABEL[item.kind] as never)];
    if (item.year) meta.push(item.year);
    if (item.rating && item.rating !== '0' && item.rating !== '0.0') meta.push(`★ ${item.rating}`);
    if (item.group) meta.push(item.group);

    const play = el('button', { class: 'button', 'data-focus': '', 'data-focus-id': 'play' }, t('cd_play'));
    play.addEventListener('click', () => options.onPlay(item));
    const fav = el(
      'button',
      { class: 'button ghost', 'data-focus': '', 'data-focus-id': 'favorite' },
      isFavorite(item) ? t('cd_favorite_remove') : t('cd_favorite_add'),
    );
    fav.addEventListener('click', () => {
      const now = toggleFavorite(item);
      fav.textContent = now ? t('cd_favorite_remove') : t('cd_favorite_add');
    });

    info.append(
      el('h2', {}, item.name),
      el('div', { class: 'meta' }, meta.join('  │  ')),
      el('p', {}, extra?.description ?? item.description ?? ''),
      el('div', { class: 'actions' }, play, fav),
    );
  }

  function onCardFocus(item: PlaylistItem, card: HTMLElement): void {
    viewerHasMoved = true;
    describe(item, card);
    // The poster now, the title's proper landscape artwork when the details arrive. Asking for
    // the better picture up front is what makes this one transition rather than a poster that is
    // replaced a moment later.
    if (item.kind !== 'live') backdrop.show(details.get(itemKey(item))?.backdropUrl ?? item.logoUrl);

    if (pending !== null) window.clearTimeout(pending);
    if (!options.loadDetails || details.has(itemKey(item))) return;
    pending = window.setTimeout(async () => {
      const loaded = await options.loadDetails!(item).catch(() => null);
      if (!loaded) return;
      details.set(itemKey(item), loaded);
      // Only if the viewer is still here - they may have moved on while this was in flight.
      if (document.activeElement?.getAttribute('data-focus-id') !== itemKey(item)) return;
      describe(item, card);
      if (viewerHasMoved && loaded.backdropUrl) backdrop.show(loaded.backdropUrl);
    }, 400);
  }

  rows.forEach((row, rowIndex) => {
    const track = el('div', { class: 'row', 'data-focus-group': row.id });

    if (row.tile) {
      // First place in the row, not last. A box that moves to the end whenever the row gains a
      // card is a box the viewer has to look for.
      const tile = el(
        'div',
        { class: 'card tile', tabindex: '-1', 'data-focus': '', 'data-focus-id': `${row.id}-tile`, 'data-focus-up': rowIndex === 0 ? '.nav-tab[aria-selected="true"]' : '' },
        el('div', { class: 'tile-title' }, row.tile.title),
        el('div', { class: 'tile-caption' }, row.tile.caption),
      );
      tile.addEventListener('click', row.tile.onOpen);
      // The tile is not a title, so it has nothing to describe and no artwork to raise.
      tile.addEventListener('focus', () => describe(null));
      track.append(tile);
    }

    for (const item of row.items) {
      const card = el('div', {
        class: 'card',
        tabindex: '-1',
        'data-focus': '',
        'data-focus-id': itemKey(item),
        ...(rowIndex === 0 ? { 'data-focus-up': '.nav-tab[aria-selected="true"]' } : {}),
      });
      const art = el('img', { class: 'art', alt: '' }) as HTMLImageElement;
      if (item.logoUrl) art.src = item.logoUrl;
      art.addEventListener('error', () => art.removeAttribute('src'));
      card.append(art);
      const progress = progressOf(item);
      if (progress !== null) {
        card.append(el('div', { class: 'progress' }, el('div', { class: 'progress-fill', style: `width:${progress * 100}%` })));
      }
      card.append(el('div', { class: 'label' }, item.name));
      card.addEventListener('focus', () => onCardFocus(item, card));
      card.addEventListener('click', () => options.onPlay(item));
      track.append(card);
    }

    // Empty places, so the row keeps its shape before anything has filled it.
    const placed = row.items.length + (row.tile ? 1 : 0);
    for (let i = placed; i < row.minSlots; i++) {
      track.append(el('div', { class: 'card empty' }, el('div', { class: 'empty-mark' }, '♡')));
    }

    body.append(el('h3', { class: 'section-title' }, row.title), track);
    if (row.hint && row.items.length < row.minSlots) {
      body.append(el('div', { class: 'row-hint' }, row.hint));
    }
  });

  // info is not appended here: it is inserted after whichever row holds the highlight, by
  // describe(). Appending it to the host as well would leave a second, empty copy at the foot of
  // the page.
  host.append(body);
  describe(null);

  // The block belongs to the artwork, so it goes when the highlight does - onto the navigation
  // bar, onto the box that opens the full list, anywhere that is not a title. Each card clearing
  // it for itself cannot work: the card that loses focus has no idea what gained it, and the one
  // case that matters is focus leaving the rows entirely.
  const onFocusIn = (event: FocusEvent) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    if (!target.classList.contains('card') || target.classList.contains('tile')) describe(null);
  };
  document.addEventListener('focusin', onFocusIn);
  disposers.push(() => document.removeEventListener('focusin', onFocusIn));

  // Marked on the host rather than left on #app, because only this page has an info block - the
  // browser, series, search and settings pages use the full height, and reserving 460px on every
  // one of them pushed their fixed-height panels off the bottom of the television.
}

/** Torn down when a page is replaced, so its listeners do not outlive it. */
const disposers: (() => void)[] = [];

export function disposeLanding(): void {
  while (disposers.length) disposers.pop()!();
}

/** Where a landing page starts: its first box, which is the tile when a row has one. */
export function focusPageStart(host: HTMLElement): void {
  focus(host.querySelector<HTMLElement>('.row [data-focus]'));
}
