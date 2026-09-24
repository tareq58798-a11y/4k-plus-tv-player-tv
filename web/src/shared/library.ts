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
/** Channels and series the viewer has watched, newest first - see recordWatched. */
const RECENT_CHANNELS = 'recent_channels';
const RECENT_SERIES = 'recent_series';
/** When each channel or series was last watched, so Home can put them in order with films. */
const WATCHED_AT = 'watched_at';

export type ResumeMap = Record<string, { positionMs: number; durationMs: number; at: number }>;

/*
 * Read from storage once, then kept in memory and written through.
 *
 * Every poster and channel row asks whether it is a favourite and how far through it is, and each
 * of those used to be a localStorage read and a JSON.parse of the whole list - four hundred of each
 * for one category of posters, all over again every time the highlight moved to the next category.
 * Only this module writes these two keys, so the copy here cannot go stale behind its back.
 */
let favoriteKeys: Set<string> | null = null;
let resumeMap: ResumeMap | null = null;

function starred(): Set<string> {
  if (!favoriteKeys) favoriteKeys = new Set(readJson<string[]>(FAVORITES, []));
  return favoriteKeys;
}

function saveFavorites(set: Set<string>): void {
  favoriteKeys = set;
  writeJson(FAVORITES, [...set]);
}

function resumeStore(): ResumeMap {
  if (!resumeMap) resumeMap = readJson<ResumeMap>(RESUME, {});
  return resumeMap;
}

function saveResume(map: ResumeMap): void {
  resumeMap = map;
  writeJson(RESUME, map);
}

/** A copy, so a caller changing it cannot change what is stored. */
export function favorites(): Set<string> {
  return new Set(starred());
}

export function isFavorite(item: PlaylistItem): boolean {
  return starred().has(itemKey(item));
}

/** Returns the new state, so a caller can redraw without asking again. */
export function toggleFavorite(item: PlaylistItem): boolean {
  const set = favorites();
  const key = itemKey(item);
  const nowFavorite = !set.has(key);
  if (nowFavorite) set.add(key);
  else set.delete(key);
  saveFavorites(set);
  return nowFavorite;
}

/** A copy, for the same reason as favorites(). */
export function resumePoints(): ResumeMap {
  return { ...resumeStore() };
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

  const stars = favorites();
  let touchedFavorites = false;
  for (const key of keys) {
    if (stars.delete(key)) touchedFavorites = true;
  }
  if (touchedFavorites) saveFavorites(stars);

  const resume = resumePoints();
  let touchedResume = false;
  for (const key of keys) {
    if (key in resume) {
      delete resume[key];
      touchedResume = true;
    }
  }
  if (touchedResume) saveResume(resume);

  for (const store of [RECENT_CHANNELS, RECENT_SERIES]) {
    const recent = readJson<string[]>(store, []);
    const kept = recent.filter((key) => !keys.has(key));
    if (kept.length !== recent.length) writeJson(store, kept);
  }
}

/**
 * Notes that a channel or a series was watched, most recent first.
 *
 * Neither can use the resume points films use. A channel has no position, and rememberPosition
 * refuses live items outright - so Live TV's Recently watched was only ever empty. A series is
 * watched an episode at a time, and each episode's position is kept under the episode's own id,
 * which no series in the catalogue carries - so Series' was empty too.
 *
 * The television keeps a separate list for each, and this is those lists: a channel goes on
 * `recent_ids_v3` whenever it is played (rememberChannel, twenty kept), and a series goes on
 * `recent_v1` whenever one of its episodes is (recordRecent in SeriesScreen.kt, thirty kept).
 * Films are not recorded here - their row is Continue watching, and resume points are that.
 */
export function recordWatched(item: PlaylistItem): void {
  if (item.kind === 'movie') return;
  const store = item.kind === 'live' ? RECENT_CHANNELS : RECENT_SERIES;
  const limit = item.kind === 'live' ? 20 : 30;
  const key = itemKey(item);
  const recent = readJson<string[]>(store, []);
  writeJson(store, [key, ...recent.filter((entry) => entry !== key)].slice(0, limit));
  const times = readJson<Record<string, number>>(WATCHED_AT, {});
  times[key] = Date.now();
  // Only what the two lists still hold, so the record cannot grow without end.
  const kept = new Set([...readJson<string[]>(RECENT_CHANNELS, []), ...readJson<string[]>(RECENT_SERIES, [])]);
  for (const stale of Object.keys(times)) if (!kept.has(stale)) delete times[stale];
  writeJson(WATCHED_AT, times);
}

/**
 * Home's Continue Watching: films part way through, channels and series recently watched, all in
 * one row, the most recent first - up to twenty-four, as the television's Home row takes.
 *
 * It was films only, because it was built from resume points, and a channel or a series never has
 * one of its own (see recordWatched). The television's row mixes all three (LandingScreens.kt,
 * orderedIds: the last title touched, then films, series and channels). This orders them by when
 * each was actually watched, which the television cannot do because it keeps no times; a channel
 * or series recorded before times were kept goes after everything that has one, in its list's own
 * order.
 */
export function recentlyWatchedAll(items: PlaylistItem[], limit = 24): PlaylistItem[] {
  const resume = resumeStore();
  const times = readJson<Record<string, number>>(WATCHED_AT, {});
  const order = new Map<string, number>();
  for (const store of [RECENT_CHANNELS, RECENT_SERIES]) {
    readJson<string[]>(store, []).forEach((key, index) => {
      if (!order.has(key)) order.set(key, index);
    });
  }
  const when = (item: PlaylistItem): number => {
    const key = itemKey(item);
    if (item.kind === 'movie') return resume[key]?.at ?? -1;
    if (!order.has(key)) return -1;
    // Untimed entries sort after timed ones, and by their place in their own list.
    return times[key] ?? -1 - order.get(key)!;
  };
  const seen = new Set<string>();
  return items
    .filter((item) => {
      const key = itemKey(item);
      if (seen.has(key)) return false;
      const present = item.kind === 'movie' ? resume[key] !== undefined : order.has(key);
      if (present) seen.add(key);
      return present;
    })
    .sort((a, b) => when(b) - when(a))
    .slice(0, limit);
}

/**
 * The row above a section's categories: recently watched channels or series, in the order they
 * were watched, and for films the ones part way through. [items] may be of any kinds; each is
 * answered from its own record.
 */
export function watchedLately(items: PlaylistItem[], limit = 20): PlaylistItem[] {
  const films = continueWatching(items.filter((item) => item.kind === 'movie'), limit);
  const order = new Map<string, number>();
  for (const store of [RECENT_CHANNELS, RECENT_SERIES]) {
    readJson<string[]>(store, []).forEach((key, index) => order.set(key, index));
  }
  // Once each: a provider lists the same channel under several categories, all with one id.
  const seen = new Set<string>();
  const others = items
    .filter((item) => {
      const key = itemKey(item);
      if (item.kind === 'movie' || !order.has(key) || seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((a, b) => order.get(itemKey(a))! - order.get(itemKey(b))!);
  return [...films, ...others].slice(0, limit);
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
  saveResume(map);
}

/** Where [item] was left, or null when there is nothing to go back to. */
export function resumePosition(item: PlaylistItem): number | null {
  const entry = resumeStore()[itemKey(item)];
  return entry && entry.positionMs > 0 ? entry.positionMs : null;
}

export function progressOf(item: PlaylistItem): number | null {
  const entry = resumeStore()[itemKey(item)];
  if (!entry || entry.durationMs <= 0) return null;
  return Math.min(1, entry.positionMs / entry.durationMs);
}

/** Most recently left, first. */
export function continueWatching(items: PlaylistItem[], limit = 20): PlaylistItem[] {
  const map = resumeStore();
  return items
    .filter((item) => map[itemKey(item)] !== undefined)
    .sort((a, b) => (map[itemKey(b)]?.at ?? 0) - (map[itemKey(a)]?.at ?? 0))
    .slice(0, limit);
}

export function favoriteItems(items: PlaylistItem[], limit = 20): PlaylistItem[] {
  const set = starred();
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
