/**
 * What the viewer has done with the catalogue: what they starred, what they were part way
 * through, and what is new.
 *
 * Keyed on the provider's own id via itemKey, exactly as the Android app is, so the two agree on
 * what "this title" means. Nothing here reaches the network - it is all local state over the
 * catalogue that has already been loaded.
 */
import { readJson, writeJson } from '../platform/storage';
import { itemKey, type PlaylistItem } from './models';

const FAVORITES = 'favorites';
const RESUME = 'resume';

export type ResumeMap = Record<string, { positionMs: number; durationMs: number; at: number }>;

export function favorites(): Set<string> {
  return new Set(readJson<string[]>(FAVORITES, []));
}

export function isFavorite(item: PlaylistItem): boolean {
  return favorites().has(itemKey(item));
}

/** Returns the new state, so a caller can redraw without asking again. */
export function toggleFavorite(item: PlaylistItem): boolean {
  const set = favorites();
  const key = itemKey(item);
  const nowFavorite = !set.has(key);
  if (nowFavorite) set.add(key);
  else set.delete(key);
  writeJson(FAVORITES, [...set]);
  return nowFavorite;
}

export function resumePoints(): ResumeMap {
  return readJson<ResumeMap>(RESUME, {});
}

/**
 * Records where playback got to.
 *
 * The first and last minutes are deliberately not recorded. A title abandoned twenty seconds in
 * was never really started, and one watched to the end should not sit in Continue Watching
 * offering to replay its credits - both are how that row fills up with things nobody wants.
 */
export function rememberPosition(item: PlaylistItem, positionMs: number, durationMs: number): void {
  if (item.kind === 'live' || durationMs <= 0) return;
  const key = itemKey(item);
  const map = resumePoints();
  const nearStart = positionMs < 60_000;
  const nearEnd = positionMs > durationMs - 60_000;
  if (nearStart || nearEnd) {
    delete map[key];
  } else {
    map[key] = { positionMs, durationMs, at: Date.now() };
  }
  writeJson(RESUME, map);
}

export function progressOf(item: PlaylistItem): number | null {
  const entry = resumePoints()[itemKey(item)];
  if (!entry || entry.durationMs <= 0) return null;
  return Math.min(1, entry.positionMs / entry.durationMs);
}

/** Most recently left, first. */
export function continueWatching(items: PlaylistItem[], limit = 20): PlaylistItem[] {
  const map = resumePoints();
  return items
    .filter((item) => map[itemKey(item)] !== undefined)
    .sort((a, b) => (map[itemKey(b)]?.at ?? 0) - (map[itemKey(a)]?.at ?? 0))
    .slice(0, limit);
}

export function favoriteItems(items: PlaylistItem[], limit = 20): PlaylistItem[] {
  const set = favorites();
  return items.filter((item) => set.has(itemKey(item))).slice(0, limit);
}

/**
 * Newest first, and only titles that actually carry a date.
 *
 * Live channels are excluded: a channel is not "added" in any sense a viewer cares about, and
 * including them buries the films and series this row exists to surface.
 */
export function recentlyAdded(items: PlaylistItem[], limit = 20): PlaylistItem[] {
  return items
    .filter((item) => item.kind !== 'live' && (item.addedEpochSeconds ?? 0) > 0)
    .sort((a, b) => (b.addedEpochSeconds ?? 0) - (a.addedEpochSeconds ?? 0))
    .slice(0, limit);
}
