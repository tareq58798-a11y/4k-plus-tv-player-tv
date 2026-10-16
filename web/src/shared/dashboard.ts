/**
 * Which saved playlists the dashboard gave this set - up to two - ported from
 * PlaylistSourceStore.kt's dashboardSourceIds and PlaylistRepository.kt's saveFromDashboard /
 * syncWithDashboard.
 *
 * Deleting a playlist in the dashboard used to wipe it there and nowhere else: the set had saved
 * its own copy as an ordinary login, Refresh playlist reloaded that copy from the provider without
 * asking the dashboard, and start-up asked the dashboard but ignored the answer - so a playlist the
 * reseller had deleted carried on, and "came back" on every refresh. The dashboard decides what it
 * hands out; this is where the set remembers what that was, and hears when it is taken back.
 *
 * Only the dashboard's own logins are ever taken away. One the viewer typed in is theirs, and stays
 * theirs even when the dashboard later assigns the same account.
 *
 * Logins only. This app keeps one M3U at a time (SAVED_M3U in main.ts), which can only ever have
 * come from the dashboard, so main.ts handles it directly; a second M3U is not kept.
 */
import { readJson, remove, writeJson } from '../platform/storage';
import type { Activated } from './activation';
import type { ProviderLogin } from './models';
import { sameAccount, savedPlaylists } from './playlists';

/** The accounts the dashboard gave, by the identity sameAccount compares: address and username. */
const DASHBOARD = 'dashboard';

type Marker = { address: string; username: string };

/** The marked accounts. An earlier build kept a single one, not a list; that still reads. */
function markers(): Marker[] {
  const stored = readJson<Marker | Marker[] | null>(DASHBOARD, null);
  if (!stored) return [];
  return Array.isArray(stored) ? stored : [stored];
}

function setMarkers(logins: ProviderLogin[]): void {
  if (logins.length > 0) writeJson(DASHBOARD, logins.map(({ address, username }) => ({ address, username })));
  else remove(DASHBOARD);
}

function asLogin(m: Marker): ProviderLogin {
  return { name: '', address: m.address, username: m.username, password: '' };
}

function isMarked(login: ProviderLogin, list: Marker[]): boolean {
  return list.some((m) => sameAccount(asLogin(m), login));
}

function loginsOf(assigned: Activated[]): ProviderLogin[] {
  return assigned.flatMap((entry) => (entry.kind === 'xtream' ? [entry.login] : []));
}

/**
 * What to do with the playlists the dashboard has just sent.
 *
 * [first] is the login to open when the first playlist is a login (null for an M3U, which main.ts
 * opens itself): the viewer's own saved entry when they already had that account - their name,
 * their password, not overwritten - and otherwise what was sent, now remembered as the dashboard's.
 * [extras] are the other logins, for the caller to save beside it without opening them; [replaced]
 * are dashboard logins it no longer sends, for the caller to remove.
 */
export function fromDashboard(sent: Activated[]): {
  first: ProviderLogin | null;
  extras: ProviderLogin[];
  replaced: ProviderLogin[];
} {
  const previous = markers();
  const saved = savedPlaylists();
  const logins = loginsOf(sent);
  const replaced = saved.filter((entry) => isMarked(entry, previous) && !logins.some((l) => sameAccount(l, entry)));
  const marked: ProviderLogin[] = [];
  const resolved = logins.map((login) => {
    const own = saved.find((entry) => sameAccount(entry, login) && !isMarked(entry, previous));
    if (own) return own;
    marked.push(login);
    return login;
  });
  setMarkers(marked);
  const firstIsLogin = sent[0]?.kind === 'xtream';
  return {
    first: firstIsLogin ? resolved[0] ?? null : null,
    extras: firstIsLogin ? resolved.slice(1) : resolved,
    replaced,
  };
}

/** A dashboard login the viewer has removed themselves: it is no longer anybody's. */
export function forgetDashboardLogin(login: ProviderLogin): void {
  const current = markers();
  if (isMarked(login, current)) setMarkers(current.filter((m) => !sameAccount(asLogin(m), login)).map(asLogin));
}

/**
 * Given what the dashboard assigns now ([assigned], empty for nothing): the saved logins it has
 * taken back, for the caller to remove, and new logins it has given since - a Playlist 2 added later
 * - for the caller to save beside the others without opening them.
 *
 * Saved by a build that did not keep the marker, the logins matching what the dashboard assigns now
 * are evidently the ones it gave, so they are adopted and a later deletion reaches them. One the
 * dashboard no longer assigns cannot be told apart from a login the viewer typed in, so it is left
 * alone - and with nothing adopted, the set is not one the dashboard looks after, so nothing is
 * added to it either.
 */
export function takenBack(assigned: Activated[]): { removed: ProviderLogin[]; added: ProviderLogin[] } {
  const saved = savedPlaylists();
  const logins = loginsOf(assigned);
  let current = markers();
  if (current.length === 0) {
    const adopted = logins.filter((login) => saved.some((entry) => sameAccount(entry, login)));
    setMarkers(adopted);
    if (adopted.length === 0) return { removed: [], added: [] };
    current = adopted;
  }
  const removed = saved.filter((entry) => isMarked(entry, current) && !logins.some((l) => sameAccount(l, entry)));
  const added = logins.filter((login) => !isMarked(login, current) && !saved.some((entry) => sameAccount(entry, login)));
  const kept = current.map(asLogin).filter((m) => logins.some((l) => sameAccount(l, m)));
  setMarkers([...kept, ...added]);
  return { removed, added };
}
