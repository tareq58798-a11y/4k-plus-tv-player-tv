/**
 * The catalogue model, matching PlaylistModels.kt field for field.
 *
 * Deliberately the same shape as the Android app's, so the cache written by one is legible to the
 * other and so a behaviour described for one is describable for the other without translation.
 */

export type MediaKind = 'live' | 'movie' | 'series';

export interface PlaylistItem {
  name: string;
  /** For a series this is `series://<id>`: a series is opened, not played. */
  streamUrl: string;
  group: string;
  logoUrl: string | null;
  /** The provider's own id. Favourites and history key on this, never on an EPG id. */
  channelId: string | null;
  kind: MediaKind;
  description?: string | null;
  year?: string | null;
  rating?: string | null;
  duration?: string | null;
  /** Seconds. What "recently added" sorts on. */
  addedEpochSeconds?: number | null;
}

export interface LoadedPlaylist {
  name: string;
  items: PlaylistItem[];
  groups: string[];
  accountStatus: string | null;
  expiryEpochSeconds: number | null;
}

export interface ProviderLogin {
  name: string;
  address: string;
  username: string;
  password: string;
}

export interface SeriesEpisode {
  id: string;
  seasonNumber: number;
  episodeNumber: number;
  title: string;
  streamUrl: string;
  thumbnailUrl: string | null;
  duration: string | null;
  description: string | null;
}

export interface MovieDetails {
  originalTitle: string | null;
  description: string | null;
  year: string | null;
  rating: string | null;
  duration: string | null;
  genre: string | null;
  cast: string | null;
  director: string | null;
  backdropUrl: string | null;
  posterUrl: string | null;
  trailerUrl: string | null;
}

export interface SeriesDetails extends Omit<MovieDetails, 'duration'> {
  episodes: SeriesEpisode[];
}

export interface EpgProgram {
  title: string;
  startEpochSeconds: number;
  endEpochSeconds: number;
}

/** The same identity rule as ItemKeys.kt: the provider id, or the group and name together. */
export function itemKey(item: PlaylistItem): string {
  return item.channelId ?? `${item.group}:${item.name}`;
}
