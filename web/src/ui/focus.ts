/**
 * The D-pad. This is the part of the Android app that transfers completely, because it is
 * behaviour rather than code, and it is the part that took longest to get right.
 *
 * A browser has no spatial navigation, so it is built here: candidates are chosen by where they
 * are on screen, not by their order in the document. That ordering is what lets a grid behave like
 * a grid - pressing Down from the third poster in a row reaches the third poster in the next row,
 * which tab order alone would never do.
 *
 * Rules carried over from the television app:
 *
 *  - **Right-to-left mirrors.** In Arabic the whole layout flips, so Left must mean "towards the
 *    start of the row", which is the right-hand side of the screen. Hard-coding the direction was
 *    the bug that stopped Arabic moving between posters at all.
 *  - **A row remembers where you were.** Leaving a row and coming back puts the highlight where it
 *    was, not at the beginning. Without it, stepping up to the tabs and back down loses your place
 *    in a list of four hundred channels.
 *  - **Explicit overrides beat geometry.** Some moves are a decision, not a direction: Up from
 *    anywhere in a page goes to that page's tab, whatever happens to be above. Those are declared
 *    on the element and checked first.
 */
import type { RemoteKey } from '../platform/keys';

export type Direction = 'up' | 'down' | 'left' | 'right';

const FOCUSABLE = '[data-focus]';

/** Where the highlight was when each group was last left, so returning restores it. */
const lastInGroup = new Map<string, string>();

let rtl = false;

export function setFocusDirection(isRtl: boolean): void {
  rtl = isRtl;
}

export function focused(): HTMLElement | null {
  const active = document.activeElement;
  return active instanceof HTMLElement && active.matches(FOCUSABLE) ? active : null;
}

function centre(element: Element): { x: number; y: number } {
  const rect = element.getBoundingClientRect();
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
}

function visible(element: Element): boolean {
  const rect = element.getBoundingClientRect();
  if (rect.width === 0 || rect.height === 0) return false;
  const style = getComputedStyle(element);
  return style.visibility !== 'hidden' && style.display !== 'none';
}

/**
 * The nearest focusable element in [direction].
 *
 * Distance is weighted: drift across the axis of travel counts four times as much as distance
 * along it. Pressing Down should reach the item below rather than one much further sideways that
 * happens to be marginally closer in a straight line, and an unweighted nearest-neighbour search
 * gets that wrong constantly in a grid.
 */
function nearest(from: HTMLElement, direction: Direction): HTMLElement | null {
  const origin = centre(from);
  let best: HTMLElement | null = null;
  let bestScore = Number.POSITIVE_INFINITY;

  const fromRect = from.getBoundingClientRect();

  for (const candidate of document.querySelectorAll<HTMLElement>(FOCUSABLE)) {
    if (candidate === from || candidate.hasAttribute('data-focus-skip') || !visible(candidate)) continue;
    const point = centre(candidate);
    const dx = point.x - origin.x;
    const dy = point.y - origin.y;

    let along: number;
    let across: number;
    if (direction === 'up') {
      if (dy >= -1) continue;
      along = -dy;
      across = Math.abs(dx);
    } else if (direction === 'down') {
      if (dy <= 1) continue;
      along = dy;
      across = Math.abs(dx);
    } else {
      if (direction === 'left' ? dx >= -1 : dx <= 1) continue;
      // Sideways moves stay in their band. Without this, Right from the only box in a row finds
      // the nearest thing to its right anywhere on screen - which is the navigation bar, two
      // hundred pixels up - and the highlight leaves the page sideways. A row that has run out
      // should simply not move; Up and Down are how you leave it.
      const rect = candidate.getBoundingClientRect();
      if (rect.bottom <= fromRect.top || rect.top >= fromRect.bottom) continue;
      along = direction === 'left' ? -dx : dx;
      across = Math.abs(dy);
    }
    const score = along + across * 4;
    if (score < bestScore) {
      bestScore = score;
      best = candidate;
    }
  }
  return best;
}

/** `data-focus-up="#channels"` and friends: a named destination that overrides geometry. */
function override(from: HTMLElement, direction: Direction): HTMLElement | null {
  const selector = from.getAttribute(`data-focus-${direction}`);
  if (!selector) return null;
  // "none" is a deliberate wall - the Android app uses it to stop Down from the tab strip
  // falling through to the play button when it should land on the first box of the page.
  if (selector === 'none') return from;
  const target = document.querySelector<HTMLElement>(selector);
  return target && visible(target) ? target : null;
}

/**
 * *Entering* a group lands on whichever of its children was last focused.
 *
 * Entering, and only entering. Applied to every move, it also fires when the highlight is already
 * inside the group and simply moving along it - Right picks the next card, the row answers "you
 * were on the one before", and the highlight snaps back. The effect is a row that cannot be moved
 * along at all, which is exactly what it did until this check was added.
 */
function resolveGroupEntry(from: HTMLElement, target: HTMLElement): HTMLElement {
  const group = target.closest<HTMLElement>('[data-focus-group]');
  if (!group) return target;
  // Already in this group: this is movement within it, not arrival at it.
  if (group.contains(from)) return target;
  const name = group.getAttribute('data-focus-group');
  if (!name) return target;
  const previous = lastInGroup.get(name);
  if (!previous) return target;
  const remembered = group.querySelector<HTMLElement>(`[data-focus-id="${CSS.escape(previous)}"]`);
  return remembered && visible(remembered) ? remembered : target;
}

function remember(element: HTMLElement): void {
  const group = element.closest<HTMLElement>('[data-focus-group]');
  const name = group?.getAttribute('data-focus-group');
  const id = element.getAttribute('data-focus-id');
  if (name && id) lastInGroup.set(name, id);
}

export function focus(element: HTMLElement | null): void {
  if (!element) return;
  ensureFocusable(element);
  remember(element);
  element.focus({ preventScroll: true });
  element.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
}

/**
 * Makes sure a div can actually take focus before we ask it to.
 *
 * `data-focus` is this app's way of saying "the highlight can land here", but the browser does not
 * read it: a `<div>` without a tabindex refuses focus and `element.focus()` returns quietly having
 * done nothing. The failure is silent at every level - no error, no event, and `focus()` looks like
 * it worked - so the symptom appears a long way from the cause, as a screen where the highlight is
 * simply absent and no key does anything.
 *
 * That is exactly what happened to the category lists in the browse screens, which were marked
 * focusable and were not. Fixed at the source too, but guaranteed here so the next element marked
 * `data-focus` cannot inherit the same fault.
 */
function ensureFocusable(element: HTMLElement): void {
  if (element.hasAttribute('tabindex')) return;
  // Buttons, inputs and links are focusable already; adding tabindex to them would only change
  // their order in the tab sequence for no gain.
  if (element.matches('button, input, select, textarea, a[href]')) return;
  element.tabIndex = -1;
}

/** Clears a group's memory, for when its contents have been replaced entirely. */
export function forgetGroup(name: string): void {
  lastInGroup.delete(name);
}

export function move(direction: Direction): boolean {
  const from = focused();
  if (!from) {
    const first = document.querySelector<HTMLElement>(FOCUSABLE);
    focus(first);
    return Boolean(first);
  }
  // In Arabic the layout mirrors, so Left means "towards the start of the row", which is on the
  // right of the screen. Mirroring here rather than at each call site is what keeps every grid,
  // row and list correct in both directions at once.
  const effective: Direction =
    rtl && direction === 'left' ? 'right' : rtl && direction === 'right' ? 'left' : direction;

  const declared = override(from, direction);
  if (declared === from) return true;
  const target = declared ?? nearest(from, effective);
  if (!target) return false;
  focus(resolveGroupEntry(from, target));
  return true;
}

export type KeyHandler = (key: RemoteKey) => boolean;

const handlers: KeyHandler[] = [];

/**
 * Screens push a handler and pop it when they close. The newest gets first refusal, which is what
 * makes Back close a dialog rather than leaving the page underneath it.
 */
export function pushKeyHandler(handler: KeyHandler): () => void {
  handlers.push(handler);
  return () => {
    const index = handlers.indexOf(handler);
    if (index >= 0) handlers.splice(index, 1);
  };
}

export function handleKey(key: RemoteKey): void {
  for (let index = handlers.length - 1; index >= 0; index--) {
    if (handlers[index]!(key)) return;
  }
  if (key === 'up' || key === 'down' || key === 'left' || key === 'right') {
    move(key);
    return;
  }
  if (key === 'enter') {
    focused()?.click();
  }
}
