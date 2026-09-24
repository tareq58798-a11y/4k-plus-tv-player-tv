/**
 * The Xtream Codes client, ported from XtreamProviderClient.kt.
 *
 * Every quirk in here was found the hard way on the Android app and is carried over deliberately:
 *
 *  - `optString` on Android returns the *string* "null" when a panel sends a JSON null, which is
 *    how a missing poster became the four characters n-u-l-l. `text()` below is the same filter.
 *  - Panels disagree about where a field lives. A movie poster is on `stream_icon`, or `cover`, or
 *    `cover_big`, or `movie_image`, depending on the fork; an episode's container is on the
 *    episode or nested in its `info`. Both are read broadly rather than assumed.
 *  - An address may answer on http, https, or only one of them, so each candidate is tried in turn
 *    and only the last failure is reported.
 *  - Live, movies and series are fetched together rather than in sequence. These responses run to
 *    tens of thousands of entries and several megabytes, so in series the load takes their sum
 *    instead of roughly the slowest one.
 *
 * Credentials travel in the query string because that is the protocol Xtream defines. Nothing here
 * may log a URL.
 */
import type {
  EpgProgram,
  LoadedPlaylist,
  MovieDetails,
  PlaylistItem,
  ProviderLogin,
  SeriesDetails,
  SeriesEpisode,
} from './models';

type Json = Record<string, unknown>;

const MAX_RESPONSE_BYTES = 80_000_000;
const CALL_TIMEOUT_MS = 60_000;

/* ------------------------------------------------------------- primitives */

/** A panel's JSON null arrives as the string "null" often enough that it must be filtered. */
function text(source: unknown, ...keys: string[]): string | null {
  if (!source || typeof source !== 'object') return null;
  const object = source as Json;
  for (const key of keys) {
    const raw = object[key];
    if (raw === null || raw === undefined) continue;
    const value = String(raw).trim();
    if (value && value.toLowerCase() !== 'null') return value;
  }
  return null;
}

function containsLatin(value: string): boolean {
  return /[A-Za-z]/.test(value);
}

/**
 * A catalogue timestamp, in either shape panels use: epoch seconds, or occasionally milliseconds.
 * Anything outside a plausible range is discarded rather than trusted, so one panel's malformed
 * field cannot park a title permanently at the top of "recently added".
 */
function epochSeconds(item: unknown, ...keys: string[]): number | null {
  const raw = text(item, ...keys);
  if (raw === null) return null;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) return null;
  const seconds = parsed > 100_000_000_000 ? Math.floor(parsed / 1000) : parsed;
  return seconds >= 946_684_800 && seconds <= 4_102_444_800 ? seconds : null;
}

function encode(value: string): string {
  return encodeURIComponent(value);
}

/** Both schemes when none was given, and http when https was asked for on port 80. */
export function addressCandidates(value: string): string[] {
  const trimmed = value.trim();
  if (!trimmed) throw new Error('Enter a playlist or server address.');
  let candidates: string[];
  if (/^https:\/\//i.test(trimmed) && /:80(\/|$)/.test(trimmed)) {
    candidates = [trimmed.replace(/^https/i, 'http')];
  } else if (/^https?:\/\//i.test(trimmed)) {
    candidates = [trimmed];
  } else {
    candidates = [`http://${trimmed}`, `https://${trimmed}`];
  }
  for (const candidate of candidates) {
    try {
      if (!new URL(candidate).hostname) throw new Error('no host');
    } catch {
      throw new Error('Enter a valid server or playlist address.');
    }
  }
  return candidates;
}

export function normalizeServerBase(value: string): string {
  const url = new URL(value);
  const scheme = url.port === '80' && url.protocol === 'https:' ? 'http' : url.protocol.replace(':', '');
  const port = url.port ? `:${url.port}` : '';
  const path = url.pathname.replace(/\/+$/, '');
  return `${scheme}://${url.hostname}${port}${path === '/' ? '' : path}`;
}

/* -------------------------------------------------------------- transport */

async function download(url: string): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), CALL_TIMEOUT_MS);
  try {
    const response = await fetch(url, { signal: controller.signal, redirect: 'follow' });
    if (!response.ok) {
      throw new Error(`The server returned HTTP ${response.status}. Please try again.`);
    }
    const body = await response.text();
    if (body.length > MAX_RESPONSE_BYTES) {
      throw new Error('The provider response is too large to load safely.');
    }
    return body;
  } catch (error) {
    // A transport error's message can contain the credential-bearing URL, so it is replaced
    // rather than passed on. An HTTP status we raised ourselves is safe and kept.
    if (error instanceof Error && error.message.startsWith('The server returned HTTP')) throw error;
    if (error instanceof Error && error.message.startsWith('The provider response')) throw error;
    throw new Error('Could not connect to the server. Check the address and your connection.');
  } finally {
    clearTimeout(timer);
  }
}

/** Panels occasionally prefix the body with a byte order mark, which breaks JSON.parse. */
async function downloadJson<T>(url: string): Promise<T> {
  return JSON.parse((await download(url)).replace(/^﻿/, '')) as T;
}

function apiUrl(server: string, login: ProviderLogin, action: string | null): string {
  const base = `${server}/player_api.php?username=${encode(login.username)}&password=${encode(login.password)}`;
  return action ? `${base}&action=${action}` : base;
}

/* ------------------------------------------------------------- catalogues */

async function categories(url: string): Promise<Map<string, string>> {
  const array = await downloadJson<Json[]>(url);
  const out = new Map<string, string>();
  for (const entry of array) {
    const id = text(entry, 'category_id');
    if (id) out.set(id, text(entry, 'category_name') ?? 'Other');
  }
  return out;
}

async function categoriesOrEmpty(url: string): Promise<Map<string, string>> {
  try {
    return await categories(url);
  } catch {
    return new Map();
  }
}

function liveItems(
  array: Json[],
  groups: Map<string, string>,
  server: string,
  login: ProviderLogin,
  container: 'ts' | 'm3u8',
): PlaylistItem[] {
  // Encoded once rather than twice per entry: this runs over every channel a panel returns, tens
  // of thousands on a full playlist, and the result is identical every time.
  const prefix = `${server}/live/${encode(login.username)}/${encode(login.password)}/`;
  const out: PlaylistItem[] = [];
  for (const item of array) {
    const id = text(item, 'stream_id');
    if (!id) continue;
    out.push({
      name: text(item, 'name') ?? 'Unnamed channel',
      streamUrl: `${prefix}${id}.${container}`,
      group: groups.get(text(item, 'category_id') ?? '') ?? 'Other',
      logoUrl: text(item, 'stream_icon'),
      // stream_id is the provider's unique channel identity. EPG ids may be blank or shared by
      // several streams and must not be used for favourites or history.
      channelId: id,
      kind: 'live',
    });
  }
  return out;
}

function movieItems(
  array: Json[],
  groups: Map<string, string>,
  server: string,
  login: ProviderLogin,
): PlaylistItem[] {
  const prefix = `${server}/movie/${encode(login.username)}/${encode(login.password)}/`;
  const out: PlaylistItem[] = [];
  for (const item of array) {
    const id = text(item, 'stream_id');
    if (!id) continue;
    const extension = text(item, 'container_extension') ?? 'mp4';
    out.push({
      name: text(item, 'name') ?? 'Unnamed movie',
      streamUrl: `${prefix}${id}.${extension}`,
      group: groups.get(text(item, 'category_id') ?? '') ?? 'Other',
      // The poster field varies between panel forks - some use stream_icon like live channels,
      // others only cover/cover_big/movie_image even at list level. Reading one leaves most
      // posters blank until a details page is opened.
      logoUrl: text(item, 'stream_icon', 'cover', 'cover_big', 'movie_image'),
      channelId: id,
      kind: 'movie',
      description: text(item, 'plot'),
      year: text(item, 'year') ?? text(item, 'releaseDate')?.slice(0, 4) ?? null,
      rating: text(item, 'rating'),
      duration: text(item, 'duration'),
      addedEpochSeconds: epochSeconds(item, 'added'),
    });
  }
  return out;
}

function seriesItems(array: Json[], groups: Map<string, string>): PlaylistItem[] {
  const out: PlaylistItem[] = [];
  for (const item of array) {
    const id = text(item, 'series_id');
    if (!id) continue;
    out.push({
      name: text(item, 'name') ?? 'Unnamed series',
      streamUrl: `series://${id}`,
      group: groups.get(text(item, 'category_id') ?? '') ?? 'Other',
      logoUrl: text(item, 'cover', 'cover_big', 'movie_image', 'stream_icon'),
      channelId: id,
      kind: 'series',
      // Series carry no "added" of their own here; panels report when the run last gained an
      // episode, which is the same thing a viewer means by "new".
      addedEpochSeconds: epochSeconds(item, 'last_modified', 'added'),
    });
  }
  return out;
}

/* ------------------------------------------------------------------ login */

export interface LoadOptions {
  /** 'ts' on a television, 'm3u8' in a browser - see MediaPlayer.liveContainer. */
  liveContainer: 'ts' | 'm3u8';
  /** Called with Live TV as soon as it is usable, before the on-demand catalogues arrive. */
  onPartial?: (playlist: LoadedPlaylist) => void;
}

/**
 * Signs in and loads the catalogue, publishing Live TV as soon as it is usable.
 *
 * A failure after publishing is rethrown rather than retried against another host: the viewer
 * already has working live playback on this one, and starting again elsewhere would take it away.
 */
export async function loadProvider(login: ProviderLogin, options: LoadOptions): Promise<LoadedPlaylist> {
  let lastError: unknown = null;
  let published = false;

  for (const candidate of addressCandidates(login.address)) {
    const server = normalizeServerBase(candidate);
    try {
      const auth = await downloadJson<Json>(apiUrl(server, login, null));
      const user = auth.user_info as Json | undefined;
      if (!user) throw new Error('This server did not return a compatible provider login response.');
      const status = text(user, 'status') ?? '';
      const authenticated = Number(user.auth) === 1;
      if (!authenticated || /^(disabled|expired)$/i.test(status)) {
        throw new Error('The provider rejected this username or password, or the account is inactive.');
      }
      const expiry = epochSecondsRaw(text(user, 'exp_date'));

      const snapshot = (items: PlaylistItem[]): LoadedPlaylist => ({
        name: login.name.trim(),
        items,
        groups: [...new Set(items.map((item) => item.group))],
        accountStatus: status || null,
        expiryEpochSeconds: expiry,
      });

      const liveGroupsPromise = categoriesOrEmpty(apiUrl(server, login, 'get_live_categories'));
      const liveRaw = await downloadJson<Json[]>(apiUrl(server, login, 'get_live_streams'));
      const live = liveItems(liveRaw, await liveGroupsPromise, server, login, options.liveContainer);
      if (live.length) {
        published = true;
        options.onPartial?.(snapshot(live));
      }

      // Each catalogue keeps its own failure, so one bad response cannot cancel its sibling or
      // take away the live list that already works.
      const moviesPromise = (async () => {
        const groups = await categoriesOrEmpty(apiUrl(server, login, 'get_vod_categories'));
        const raw = await downloadJson<Json[]>(apiUrl(server, login, 'get_vod_streams'));
        return movieItems(raw, groups, server, login);
      })().then(
        (value) => ({ ok: true as const, value }),
        (error: unknown) => ({ ok: false as const, error }),
      );
      const seriesPromise = (async () => {
        const groups = await categoriesOrEmpty(apiUrl(server, login, 'get_series_categories'));
        const raw = await downloadJson<Json[]>(apiUrl(server, login, 'get_series'));
        return seriesItems(raw, groups);
      })().then(
        (value) => ({ ok: true as const, value }),
        (error: unknown) => ({ ok: false as const, error }),
      );

      const movies = await moviesPromise;
      const withMovies = movies.ok ? [...live, ...movies.value] : live;
      if (movies.ok && withMovies.length) {
        published = true;
        options.onPartial?.(snapshot(withMovies));
      }
      const series = await seriesPromise;
      const all = series.ok ? [...withMovies, ...series.value] : withMovies;
      if (all.length && (!movies.ok || !series.ok)) {
        published = true;
        options.onPartial?.(snapshot(all));
      }
      if (!movies.ok) throw movies.error;
      if (!series.ok) throw series.error;
      if (!all.length) throw new Error('This account contains no available content.');
      return snapshot(all);
    } catch (error) {
      if (published) throw error;
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('The provider could not be reached.');
}

function epochSecondsRaw(value: string | null): number | null {
  if (!value) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

/* ---------------------------------------------------------------- details */

/** The first non-empty entry of backdrop_path, which panels send as an array or a bare string. */
function backdropOf(info: Json): string | null {
  const array = info.backdrop_path;
  if (Array.isArray(array)) {
    for (const entry of array) {
      const value = String(entry ?? '').trim();
      if (value && value.toLowerCase() !== 'null') return value;
    }
  }
  const single = text(info, 'backdrop_path');
  return single && /^http/i.test(single) ? single : null;
}

function trailerOf(info: Json): string | null {
  const value = text(info, 'youtube_trailer');
  if (!value) return null;
  return /^http/i.test(value) ? value : `https://www.youtube.com/watch?v=${value}`;
}

/*
 * Film and series details, kept for the session once fetched, as PlaylistRepository keeps them on
 * Android (movieDetailsCache and seriesDetailsCache, keyed on the provider's id).
 *
 * Without it the same title was asked for over and over: once by the landing page to describe the
 * highlighted card, again when its page opened, and again every time the viewer came back to it -
 * a round trip to the provider in front of every film page and every season list, which is time
 * spent before Play can even be pressed. The promise is what is kept, so two screens asking at once
 * share one request; a failed one is forgotten, so the next attempt really asks again.
 *
 * Keyed on the account as well as the id, because two accounts on one panel number their titles
 * the same way and a sign-in to another one must not inherit these.
 */
const movieDetailsCache = new Map<string, Promise<MovieDetails>>();
const seriesDetailsCache = new Map<string, Promise<SeriesDetails>>();

function remembered<T>(cache: Map<string, Promise<T>>, key: string, fetch: () => Promise<T>): Promise<T> {
  const known = cache.get(key);
  if (known) return known;
  const request = fetch();
  cache.set(key, request);
  request.catch(() => cache.delete(key));
  return request;
}

function detailsKey(login: ProviderLogin, id: string): string {
  return `${login.address.trim().toLowerCase()}|${login.username.trim()}|${id}`;
}

export function movieDetails(login: ProviderLogin, movieId: string): Promise<MovieDetails> {
  return remembered(movieDetailsCache, detailsKey(login, movieId), () => fetchMovieDetails(login, movieId));
}

export function seriesDetails(login: ProviderLogin, seriesId: string, fallbackPoster: string | null): Promise<SeriesDetails> {
  return remembered(seriesDetailsCache, detailsKey(login, seriesId), () => fetchSeriesDetails(login, seriesId, fallbackPoster));
}

async function fetchMovieDetails(login: ProviderLogin, movieId: string): Promise<MovieDetails> {
  let lastError: unknown = null;
  for (const candidate of addressCandidates(login.address)) {
    const server = normalizeServerBase(candidate);
    try {
      const root = await downloadJson<Json>(`${apiUrl(server, login, 'get_vod_info')}&vod_id=${encode(movieId)}`);
      const info = (root.info as Json | undefined) ?? root;
      const movieData = root.movie_data as Json | undefined;
      const originalTitle =
        text(info, 'o_name', 'original_name', 'original_title', 'title', 'name') ??
        (movieData ? text(movieData, 'o_name', 'original_name', 'original_title', 'name') : null);
      return {
        // Only worth showing when it is a different alphabet from the listing name; otherwise it
        // is the same words twice.
        originalTitle: originalTitle && containsLatin(originalTitle) ? originalTitle : null,
        description: text(info, 'plot', 'description'),
        year: text(info, 'year', 'releasedate', 'releaseDate')?.slice(0, 4) ?? null,
        rating: (() => {
          const value = text(info, 'rating');
          return value && value !== '0' && value !== '0.0' ? value : null;
        })(),
        duration: text(info, 'duration', 'duration_secs'),
        genre: text(info, 'genre'),
        cast: text(info, 'cast', 'actors'),
        director: text(info, 'director'),
        backdropUrl: backdropOf(info),
        posterUrl: text(info, 'movie_image', 'cover_big', 'cover') ?? (movieData ? text(movieData, 'stream_icon') : null),
        trailerUrl: trailerOf(info),
      };
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('Movie information could not be loaded.');
}

async function fetchSeriesDetails(
  login: ProviderLogin,
  seriesId: string,
  fallbackPoster: string | null,
): Promise<SeriesDetails> {
  let lastError: unknown = null;
  for (const candidate of addressCandidates(login.address)) {
    const server = normalizeServerBase(candidate);
    try {
      const root = await downloadJson<Json>(
        `${apiUrl(server, login, 'get_series_info')}&series_id=${encode(seriesId)}`,
      );
      const info = (root.info as Json | undefined) ?? {};
      const seasons = (root.episodes as Record<string, Json[]> | undefined) ?? {};
      const episodes: SeriesEpisode[] = [];
      for (const [seasonKey, list] of Object.entries(seasons)) {
        const seasonNumber = Number(seasonKey);
        if (!Number.isFinite(seasonNumber) || !Array.isArray(list)) continue;
        list.forEach((episode, index) => {
          const id = text(episode, 'id');
          if (!id) return;
          const episodeInfo = (episode.info as Json | undefined) ?? {};
          // Panels report the container in one of two places: on the episode itself, or nested in
          // its info block. Reading only the first and defaulting to mp4 meant a panel that does
          // it the second way had every episode requested as the wrong file - the server answers
          // with an error page, and the player reports an unsupported container, because that is
          // what it was handed.
          const extension = text(episode, 'container_extension') ?? text(episodeInfo, 'container_extension') ?? 'mp4';
          const episodeNumber = Number(text(episode, 'episode_num') ?? index + 1);
          episodes.push({
            id,
            seasonNumber,
            episodeNumber: Number.isFinite(episodeNumber) ? episodeNumber : index + 1,
            title: text(episode, 'title', 'name') ?? `Episode ${index + 1}`,
            streamUrl: `${server}/series/${encode(login.username)}/${encode(login.password)}/${id}.${extension}`,
            thumbnailUrl: text(episodeInfo, 'movie_image', 'cover_big', 'cover'),
            duration: text(episodeInfo, 'duration', 'duration_secs'),
            description: text(episodeInfo, 'plot', 'description'),
          });
        });
      }
      episodes.sort((a, b) => a.seasonNumber - b.seasonNumber || a.episodeNumber - b.episodeNumber);
      const originalTitle = text(info, 'o_name', 'original_name', 'original_title', 'name');
      return {
        originalTitle: originalTitle && containsLatin(originalTitle) ? originalTitle : null,
        description: text(info, 'plot', 'description'),
        year: text(info, 'year', 'releaseDate', 'releasedate')?.slice(0, 4) ?? null,
        rating: (() => {
          const value = text(info, 'rating');
          return value && value !== '0' && value !== '0.0' ? value : null;
        })(),
        genre: text(info, 'genre'),
        cast: text(info, 'cast', 'actors'),
        director: text(info, 'director'),
        backdropUrl: backdropOf(info),
        posterUrl: text(info, 'cover_big', 'cover') ?? fallbackPoster,
        trailerUrl: trailerOf(info),
        episodes,
      };
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('Series information could not be loaded.');
}

/**
 * Xtream base64-encodes EPG text, but not every panel does - fall back to the raw value rather
 * than dropping the entry when it does not decode to anything.
 */
function decodeEpgText(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  try {
    const decoded = decodeURIComponent(escape(atob(trimmed))).trim();
    if (decoded) return decoded;
  } catch {
    /* Not base64. */
  }
  return trimmed;
}

export async function shortEpg(login: ProviderLogin, channelId: string): Promise<EpgProgram[]> {
  let lastError: unknown = null;
  for (const candidate of addressCandidates(login.address)) {
    const server = normalizeServerBase(candidate);
    try {
      const root = await downloadJson<Json>(
        `${apiUrl(server, login, 'get_short_epg')}&stream_id=${encode(channelId)}&limit=4`,
      );
      const listings = (root.epg_listings as Json[] | undefined) ?? [];
      const out: EpgProgram[] = [];
      for (const entry of listings) {
        const title = decodeEpgText(String(entry.title ?? ''));
        const start = Number(entry.start_timestamp);
        const end = Number(entry.stop_timestamp);
        if (!title || !Number.isFinite(start) || !Number.isFinite(end) || end <= start) continue;
        out.push({ title, startEpochSeconds: start, endEpochSeconds: end });
      }
      return out.sort((a, b) => a.startEpochSeconds - b.startEpochSeconds);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('Programme information could not be loaded.');
}
