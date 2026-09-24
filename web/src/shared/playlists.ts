/**
 * The saved playlists, ported from PlaylistSourceStore.kt's savedSources.
 *
 * This app kept one login, under 'login', and replaced it whenever another was entered. The
 * television keeps every provider login it has been given and switches between them, which is
 * what its Settings > Playlists page is built around - "Manage saved playlists", "Add another
 * playlist" ("Keep the current playlist and connect another one"). So the list is kept here, and
 * 'login' goes on meaning the one in use.
 *
 * An account is its address and username; the name is only a label and the password can change.
 * That is the same identity the catalogue cache uses (cacheKey), so each saved playlist keeps its
 * own cached catalogue and switching back to one opens straight away.
 *
 * Nothing here logs or displays a password. The list stays on the device, next to the one login
 * that was already stored the same way.
 */
import { readJson, remove, writeJson } from '../platform/storage';
import type { ProviderLogin } from './models';

/** The playlist in use. The key the app has always used, so an existing set keeps its login. */
export const ACTIVE_LOGIN = 'login';
const SAVED = 'playlists';

export function sameAccount(a: ProviderLogin, b: ProviderLogin): boolean {
  return (
    a.address.trim().toLowerCase() === b.address.trim().toLowerCase() &&
    a.username.trim() === b.username.trim()
  );
}

export function activeLogin(): ProviderLogin | null {
  return readJson<ProviderLogin | null>(ACTIVE_LOGIN, null);
}

/**
 * Every saved playlist, oldest first. A set upgrading from a build that only kept 'login' finds
 * that login here as the first entry, so nothing it had disappears.
 */
export function savedPlaylists(): ProviderLogin[] {
  const list = readJson<ProviderLogin[]>(SAVED, []);
  const active = activeLogin();
  if (active && !list.some((entry) => sameAccount(entry, active))) {
    list.unshift(active);
    writeJson(SAVED, list);
  }
  return list;
}

/** Makes [login] the one in use, adding it to the list or updating the entry already there. */
export function useLogin(login: ProviderLogin): void {
  const list = savedPlaylists();
  const at = list.findIndex((entry) => sameAccount(entry, login));
  if (at >= 0) list[at] = login;
  else list.push(login);
  writeJson(SAVED, list);
  writeJson(ACTIVE_LOGIN, login);
}

/** Renames the saved entry for [login], and the active login if it is that one. */
export function renamePlaylist(login: ProviderLogin, name: string): ProviderLogin {
  const renamed = { ...login, name };
  const list = savedPlaylists().map((entry) => (sameAccount(entry, login) ? renamed : entry));
  writeJson(SAVED, list);
  const active = activeLogin();
  if (active && sameAccount(active, login)) writeJson(ACTIVE_LOGIN, renamed);
  return renamed;
}

/**
 * Forgets [login]. When it was the one in use, the next saved playlist becomes the one in use and
 * is returned, as the television moves to another saved source; null when there is none left.
 */
export function forgetPlaylist(login: ProviderLogin): ProviderLogin | null {
  const list = savedPlaylists().filter((entry) => !sameAccount(entry, login));
  writeJson(SAVED, list);
  const active = activeLogin();
  if (active && !sameAccount(active, login)) return active;
  const next = list[0] ?? null;
  if (next) writeJson(ACTIVE_LOGIN, next);
  else remove(ACTIVE_LOGIN);
  return next;
}
