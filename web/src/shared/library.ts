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
 * Forgets the favourites and resume points belonging to [items].
 *
 * Android keeps three separate stores - movie_library, series_library, favorite_channels - and
 * clears one per button. This app keeps one of each, keyed by itemKey, and itemKey carries no
 * notion of kind: it is the provider's id, or the group and name. So the kind has to come from
 * the catalogue, and the caller passes the items it means rather than a label.
 *
 * Anything not in [items] is left alone, so clearing films cannot take a series resume point with
 * it, and a title the provider has since dropped keeps its entry rather than being silently
 * collected - it will be cleared by the button that matches it once it returns.
 */
export function clearActivity(items: PlaylistItem[]): void {
  const keys = new Set(items.map(itemKey));
  if (!keys.size) return;

  const starred = favorites();
  let touchedFavorites = false;
  for (const key of keys) {
    if (starred.delete(key)) touchedFavorites = true;
  }
  if (touchedFavorites) writeJson(FAVORITES, [...starred]);

  const resume = resumePoints();
  let touchedResume = false;
  for (const key of keys) {
    if (key in resume) {
      delete resume[key];
      touchedResume = true;
    }
  }
  if (touchedResume) writeJson(RESUME, resume);
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

/** Where [item] was left, or null when there is nothing to go back to. */
export function resumePosition(item: PlaylistItem): number | null {
  const entry = resumePoints()[itemKey(item)];
  return entry && entry.positionMs > 0 ? entry.positionMs : null;
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
