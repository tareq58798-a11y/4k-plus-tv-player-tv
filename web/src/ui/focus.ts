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
 *  - **An arrow goes where it points, in every language.** In Arabic the layout mirrors, and
 *    because candidates are found by where they are on screen, Left already reaches whatever is to
 *    the left. This used to swap Left and Right for Arabic as well, which on top of a mirrored
 *    layout sent the highlight the opposite way from the arrow pressed. The television's focus
 *    search is geometric the same way.
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

export function focused(): HTMLElement | null {
  const active = document.activeElement;
  return active instanceof HTMLElement && active.matches(FOCUSABLE) ? active : null;
}

function visible(element: Element): boolean {
  const rect = element.getBoundingClientRect();
  if (rect.width === 0 || rect.height === 0) return false;
  return shown(element);
}

/** The part of visible() that is not a measurement. */
function shown(element: Element): boolean {
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
/*
 * Every element is measured once per key press, and its computed style is only read when it
 * would actually win.
 *
 * This runs over everything focusable on the page - on a browse screen that is a few hundred
 * category rows and up to four hundred posters - and it measured each candidate three times and
 * asked for its computed style first, before knowing whether it was even in the right direction.
 * A computed style is one of the more expensive things a page can ask for, and on a television's
 * processor that was a noticeable part of the pause between a press and the highlight moving.
 * A zero-sized box is already ruled out by the measurement; the style only matters for the
 * rarer hidden-but-laid-out case, and only for the candidate about to be chosen.
 */
function nearest(from: HTMLElement, direction: Direction): HTMLElement | null {
  const fromRect = from.getBoundingClientRect();
  const origin = { x: fromRect.left + fromRect.width / 2, y: fromRect.top + fromRect.height / 2 };
  let best: HTMLElement | null = null;
  let bestScore = Number.POSITIVE_INFINITY;

  for (const candidate of document.querySelectorAll<HTMLElement>(FOCUSABLE)) {
    if (candidate === from || candidate.hasAttribute('data-focus-skip')) continue;
    const rect = candidate.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) continue;
    const dx = rect.left + rect.width / 2 - origin.x;
    const dy = rect.top + rect.height / 2 - origin.y;

    let along: number;
    let across: number;
    if (direction === 'up' || direction === 'down') {
      if (direction === 'up' ? dy >= -1 : dy <= 1) continue;
      /*
       * Vertical moves stay in their column, the same way sideways moves stay in their row below.
       *
       * Without this, Down from the only poster in a category found the nearest thing beneath it
       * anywhere on screen - the search box in the category pane, four hundred pixels to the left -
       * and the highlight left the grid sideways while the viewer was pressing Down. Android
       * cannot do that: its grid is a real grid widget and vertical movement is trapped inside it,
       * with Left and Right the only ways between the pane and the posters.
       *
       * Requiring the candidate to overlap the source horizontally is that rule, and it is the
       * mirror of the one already applied to Left and Right. A column that has run out simply does
       * not move, which is what the television app does and what tells a viewer they are at the
       * end of it.
       */
      if (rect.right <= fromRect.left || rect.left >= fromRect.right) continue;
      /*
       * Once they overlap, distance decides - centre offset only breaks ties.
       *
       * Weighting the horizontal offset heavily is right for deciding which column something is
       * in, and wrong once that is already settled. Down from a card used to reach the row below
       * rather than the Play button between them: the button is left-aligned with the card but
       * much narrower, so their centres differ by 84 pixels, and at four times weight that cost
       * it 336 points - more than the 220 pixels of extra distance down to the next row. Measured
       * on the set: Play scored 676 and the row below scored 560, so the row won while sitting
       * twice as far away.
       *
       * Centre-to-centre is simply the wrong measure for two things of different widths in the
       * same column. They overlap, which is what "same column" means, so the offset is demoted to
       * a tiebreak between candidates at the same height.
       */
      along = Math.abs(dy);
      across = Math.abs(dx) / 1000;
    } else {
      if (direction === 'left' ? dx >= -1 : dx <= 1) continue;
      // Sideways moves stay in their band. Without this, Right from the only box in a row finds
      // the nearest thing to its right anywhere on screen - which is the navigation bar, two
      // hundred pixels up - and the highlight leaves the page sideways. A row that has run out
      // should simply not move; Up and Down are how you leave it.
      if (rect.bottom <= fromRect.top || rect.top >= fromRect.bottom) continue;
      along = direction === 'left' ? -dx : dx;
      across = Math.abs(dy);
    }
    const score = along + across * 4;
    if (score < bestScore && shown(candidate)) {
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

/** When the highlight last moved, to tell a held key from a single press. */
let lastMove = 0;

/**
 * A move that comes this soon after the last one is a held key, or presses faster than a smooth
 * scroll can finish. A remote's auto-repeat is much faster than this; nobody taps that fast.
 */
const RAPID_MS = 180;

export function focus(element: HTMLElement | null): void {
  if (!element) return;
  ensureFocusable(element);
  remember(element);
  element.focus({ preventScroll: true });
  /*
   * Smooth for a single press, instant while the key is held.
   *
   * Each smooth scroll is an animation of a few hundred milliseconds, and a held key starts a new
   * one with every repeat, each interrupting the last - so the list trails the highlight, and the
   * next move is worked out from positions caught part way through an animation. Running down a
   * list of channels is the one time a viewer wants the list to keep up rather than glide.
   */
  const now = Date.now();
  const rapid = now - lastMove < RAPID_MS;
  lastMove = now;
  element.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: rapid ? 'auto' : 'smooth' });
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
  const declared = override(from, direction);
  if (declared === from) return true;
  const target = declared ?? nearest(from, direction);
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

/**
 * Forgets every screen's key handler, for when the screen itself is being replaced.
 *
 * Screens released their handler when Back was pressed on them and not when they were left any
 * other way - playing a film from its page, choosing a section from the bar - so the stack kept
 * handlers for pages that were gone. They answered a later Back that reached them and sent the
 * viewer to a page they were not on. Every screen puts its handler on after the page is cleared, so
 * clearing the page is when the old ones go.
 */
export function dropKeyHandlers(): void {
  handlers.length = 0;
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
