/**
 * Which saved playlist the dashboard gave this set, ported from PlaylistSourceStore.kt's
 * dashboardSourceId and PlaylistRepository.kt's saveFromDashboard / syncWithDashboard.
 *
 * Deleting a playlist in the dashboard used to wipe it there and nowhere else: the set had saved
 * its own copy as an ordinary login, Refresh playlist reloaded that copy from the provider without
 * asking the dashboard, and start-up asked the dashboard but ignored the answer - so a playlist the
 * reseller had deleted carried on, and "came back" on every refresh. The dashboard decides what it
 * hands out; this is where the set remembers what that was, and hears when it is taken back.
 *
 * Only the dashboard's own login is ever taken away. One the viewer typed in is theirs, and stays
 * theirs even when the dashboard later assigns the same account.
 */
import { readJson, remove, writeJson } from '../platform/storage';
import type { Activated } from './activation';
import type { ProviderLogin } from './models';
import { sameAccount, savedPlaylists } from './playlists';

/** The account the dashboard gave, by the identity sameAccount compares: address and username. */
const DASHBOARD = 'dashboard';

type Marker = { address: string; username: string };

function marker(): Marker | null {
  return readJson<Marker | null>(DASHBOARD, null);
}

function setMarker(login: ProviderLogin | null): void {
  if (login) writeJson(DASHBOARD, { address: login.address, username: login.username });
  else remove(DASHBOARD);
}

function asLogin(m: Marker): ProviderLogin {
  return { name: '', address: m.address, username: m.username, password: '' };
}

/**
 * What to open for a login the dashboard has just sent.
 *
 * The viewer's own saved entry when they already had that account - their name, their password,
 * not overwritten - and otherwise what was sent, now remembered as the dashboard's. [replaced] is
 * the dashboard login this one takes over from, for the caller to remove: the dashboard no longer
 * gives it out.
 */
export function fromDashboard(sent: ProviderLogin): { use: ProviderLogin; replaced: ProviderLogin | null } {
  const previous = marker();
  const isPrevious = previous !== null && sameAccount(asLogin(previous), sent);
  const own = savedPlaylists().find((entry) => sameAccount(entry, sent));
  if (own && !isPrevious) return { use: own, replaced: null };
  setMarker(sent);
  const replaced = previous && !isPrevious ? savedPlaylists().find((entry) => sameAccount(entry, asLogin(previous))) ?? null : null;
  return { use: sent, replaced };
}

/** The dashboard's login, if the viewer has removed it themselves: it is no longer anybody's. */
export function forgetDashboardLogin(login: ProviderLogin): void {
  const current = marker();
  if (current && sameAccount(asLogin(current), login)) setMarker(null);
}

/**
 * The saved login the dashboard has taken back, given what it now assigns ([assigned], or null for
 * nothing) - for the caller to remove. Null when nothing is to go.
 *
 * Saved by a build that did not keep the marker, the login matching what the dashboard assigns now
 * is evidently the one it gave, so it is adopted and a later deletion reaches it. One the dashboard
 * no longer assigns cannot be told apart from a login the viewer typed in, so it is left alone.
 */
export function takenBack(assigned: Activated | null): ProviderLogin | null {
  const current = marker();
  const now = assigned?.kind === 'xtream' ? assigned.login : null;
  if (!current) {
    const adopt = now ? savedPlaylists().find((entry) => sameAccount(entry, now)) : undefined;
    if (adopt) setMarker(adopt);
    return null;
  }
  if (now && sameAccount(asLogin(current), now)) return null;
  setMarker(null);
  return savedPlaylists().find((entry) => sameAccount(entry, asLogin(current))) ?? null;
}
