/**
 * The handful of app-wide choices that are not playlists, parental controls or language.
 *
 * Separate from storage.ts because that is a key-value box and this is a vocabulary: the names and
 * the defaults live in one place so a screen that reads a preference and a screen that writes it
 * cannot drift apart. Every value here has to survive the store being unavailable - a television
 * with storage disabled still has to show a background.
 */
import { readJson, writeJson } from '../platform/storage';

/**
 * Classic keeps the app's own artwork up everywhere; Modern lets the background follow whatever
 * the viewer is looking at.
 *
 * Modern is the default because it is what the app has always done, and a setting that changes
 * behaviour for people who never open settings is a setting that arrives as a fault report.
 */
export type BackgroundMode = 'modern' | 'classic';

const BACKGROUND_KEY = 'background_mode';

export function backgroundMode(): BackgroundMode {
  return readJson<BackgroundMode>(BACKGROUND_KEY, 'modern') === 'classic' ? 'classic' : 'modern';
}

export function setBackgroundMode(mode: BackgroundMode): void {
  writeJson(BACKGROUND_KEY, mode);
}

/**
 * How the picture fills the screen, remembered between sessions.
 *
 * Deliberately not reset per title, unlike on Android where aspect is a per-playback choice. On a
 * television the reason somebody reaches for this is usually their set or their provider rather
 * than the film - a channel that arrives pillarboxed does so every night - so making them choose
 * again each time would be answering a standing complaint with a temporary fix.
 */
const SCALING_KEY = 'video_scaling';

/**
 * All seven, under the television app's own keys.
 *
 * The first three are the ones its Settings screen offers and are what a viewer is most likely to
 * want standing; the four named frames come from the player's own menu. `zoom` is what the
 * television calls the crop-to-fill mode in its `video_mode` preference - this used to call it
 * `fill`, which is only the label, and two apps disagreeing about the key for the same choice is
 * how a setting ends up meaning different things on different screens. See MIGRATED below.
 */
const SCALING_VALUES = ['fit', 'stretch', 'zoom', '16:9', '4:3', '21:9', '1:1'] as const;
export type VideoScalingPreference = (typeof SCALING_VALUES)[number];

/** Anything written under the old name, read back under the new one. */
const MIGRATED: Record<string, VideoScalingPreference> = { fill: 'zoom' };

export function videoScaling(): VideoScalingPreference {
  const stored = readJson<unknown>(SCALING_KEY, 'fit');
  const renamed = typeof stored === 'string' ? MIGRATED[stored] : undefined;
  if (renamed) return renamed;
  return SCALING_VALUES.includes(stored as VideoScalingPreference)
    ? (stored as VideoScalingPreference)
    : 'fit';
}

export function setVideoScaling(mode: VideoScalingPreference): void {
  writeJson(SCALING_KEY, mode);
}

/**
 * Whether subtitles are drawn on a dark backing.
 *
 * On by default, which is the television app's default too. Subtitles are white text laid over
 * whatever the film is showing, and a bright scene takes them with it - so the readable case is
 * the one to start from, and turning it off is the choice somebody makes when they would rather
 * see the picture.
 *
 * The key matches Android's own `subtitle_background`, so the same setting means the same thing
 * in both apps even though neither can read the other's store.
 */
const SUBTITLE_BACKGROUND_KEY = 'subtitle_background';

export function subtitleBackground(): boolean {
  return readJson<boolean>(SUBTITLE_BACKGROUND_KEY, true) !== false;
}

export function setSubtitleBackground(on: boolean): void {
  writeJson(SUBTITLE_BACKGROUND_KEY, on);
}

/**
 * How far the skip buttons and Left/Right on the timeline move, in seconds.
 *
 * The same five choices the television app offers, and the same default. It was fixed at ten here,
 * which is right for an advert break and wrong for a title sequence.
 */
const SKIP_KEY = 'skip_seconds';
export const SKIP_CHOICES = [5, 10, 15, 30, 60] as const;
export type SkipSeconds = (typeof SKIP_CHOICES)[number];

export function skipSeconds(): SkipSeconds {
  const stored = readJson<unknown>(SKIP_KEY, 10);
  return SKIP_CHOICES.includes(stored as SkipSeconds) ? (stored as SkipSeconds) : 10;
}

export function setSkipSeconds(seconds: SkipSeconds): void {
  writeJson(SKIP_KEY, seconds);
}

/**
 * What order the channel list runs in.
 *
 * "Default" is the provider's own order and is the default here, because a provider generally has
 * a reason for it - the channels people watch most are near the top, and alphabetising throws that
 * away. It is a choice rather than a fix for the same reason it is one on Android: a list of two
 * thousand channels is easier to search alphabetically and easier to browse as sent, and which of
 * those somebody is doing is not something the app can know.
 *
 * Key and values match Android's `live_channel_sort`.
 */
const LIVE_SORT_KEY = 'live_channel_sort';
const LIVE_SORTS = ['default', 'az', 'za'] as const;
export type LiveChannelSort = (typeof LIVE_SORTS)[number];

export function liveChannelSort(): LiveChannelSort {
  const stored = readJson<unknown>(LIVE_SORT_KEY, 'default');
  return LIVE_SORTS.includes(stored as LiveChannelSort) ? (stored as LiveChannelSort) : 'default';
}

export function setLiveChannelSort(order: LiveChannelSort): void {
  writeJson(LIVE_SORT_KEY, order);
}
