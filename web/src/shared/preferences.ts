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
