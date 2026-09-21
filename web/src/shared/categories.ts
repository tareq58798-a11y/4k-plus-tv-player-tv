/**
 * Which categories a viewer has hidden, and the order they have put them in.
 *
 * Ported from the television app's applyCategoryOrder / moveCategory / moveCategoryToEnd. The
 * semantics are copied deliberately, including the ones that look like details:
 *
 *  - A stored order is a *preference*, not a schema. Categories the provider has since added are
 *    appended rather than dropped, and categories that have gone are ignored rather than leaving a
 *    hole. A playlist changes under the viewer all the time and the order has to survive that.
 *  - Hiding is per kind. Hiding "Adults" in Movies must not hide a channel category of the same
 *    name, because they are different lists that happen to share a word.
 */
import { readJson, writeJson } from '../platform/storage';

export type CategoryKind = 'live' | 'movie' | 'series';

function orderKey(kind: CategoryKind): string {
  return `category_order_${kind}`;
}

function hiddenKey(kind: CategoryKind): string {
  return `hidden_${kind}_categories`;
}

export function hiddenCategories(kind: CategoryKind): string[] {
  const stored = readJson<unknown>(hiddenKey(kind), []);
  return Array.isArray(stored) ? stored.filter((entry): entry is string => typeof entry === 'string') : [];
}

export function hideCategory(kind: CategoryKind, category: string): void {
  const hidden = hiddenCategories(kind);
  if (!hidden.includes(category)) writeJson(hiddenKey(kind), [...hidden, category]);
}

export function unhideCategory(kind: CategoryKind, category: string): void {
  writeJson(hiddenKey(kind), hiddenCategories(kind).filter((entry) => entry !== category));
}

function storedOrder(kind: CategoryKind): string[] {
  const stored = readJson<unknown>(orderKey(kind), []);
  return Array.isArray(stored) ? stored.filter((entry): entry is string => typeof entry === 'string') : [];
}

/**
 * Puts [categories] into the viewer's order.
 *
 * Anything in the stored order comes first, in that order, but only if it still exists; anything
 * the provider has added since keeps its natural position at the end. That way adding a category
 * never silently reorders the ones already placed.
 */
export function applyCategoryOrder(kind: CategoryKind, categories: string[]): string[] {
  const order = storedOrder(kind);
  if (!order.length) return categories;
  const present = new Set(categories);
  const placed = order.filter((entry) => present.has(entry));
  const placedSet = new Set(placed);
  return [...placed, ...categories.filter((entry) => !placedSet.has(entry))];
}

function persistOrder(kind: CategoryKind, ordered: string[]): void {
  writeJson(orderKey(kind), ordered);
}

/** Nudges [category] one place, which is what Up and Down do while a row is being hand-moved. */
export function moveCategory(kind: CategoryKind, categories: string[], category: string, up: boolean): void {
  const current = applyCategoryOrder(kind, categories);
  const index = current.indexOf(category);
  if (index < 0) return;
  const target = up ? index - 1 : index + 1;
  if (target < 0 || target >= current.length) return;
  const next = [...current];
  const moved = next[index];
  const displaced = next[target];
  if (moved === undefined || displaced === undefined) return;
  next[index] = displaced;
  next[target] = moved;
  persistOrder(kind, next);
}

/**
 * Lifts [category] to the very start or end.
 *
 * Worth having beside the nudge: a playlist with four hundred categories makes moving a favourite
 * from the bottom to the top four hundred presses, which is not a feature.
 */
export function moveCategoryToEnd(kind: CategoryKind, categories: string[], category: string, toTop: boolean): void {
  const current = applyCategoryOrder(kind, categories);
  const index = current.indexOf(category);
  if (index < 0) return;
  const next = current.filter((entry) => entry !== category);
  if (toTop) next.unshift(category);
  else next.push(category);
  persistOrder(kind, next);
}
