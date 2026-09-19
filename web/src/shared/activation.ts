/**
 * Activation by MAC, ported from DeviceActivationClient.kt.
 *
 * The reseller assigns a playlist to a device in their dashboard and the device fetches it here -
 * the Stalker/Ministra pattern, and the reason a viewer never has to type a server address,
 * username and password with a remote control.
 *
 * Deliberately separate from the Xtream client: this speaks a first-party API, not the Xtream
 * Codes protocol, and the two have no reason to share anything but the result.
 */
import type { ProviderLogin } from './models';

const ACTIVATION_URL = 'https://fourk-plus-tv-player.onrender.com/api/activate';

/** Nothing has been assigned to this device yet. Expected, and not an error to shout about. */
export class ActivationPending extends Error {
  constructor() {
    super('No playlist has been assigned to this device yet.');
    this.name = 'ActivationPending';
  }
}

export type Activated =
  | { kind: 'xtream'; login: ProviderLogin }
  | { kind: 'm3u'; name: string; url: string };

/**
 * A television cannot be inspected the way a browser can - there is no console to open, and on a
 * customer's set no way to attach one. What this writes reaches the system log, which is readable
 * over sdb, and is the only account of why activation failed that anybody will ever get.
 *
 * Never the response body. It carries the provider's username and password, and a log is the one
 * place credentials must not end up.
 */
function note(outcome: string): void {
  try {
    console.info(`[activation] ${outcome}`);
  } catch {
    /* No console on this set. */
  }
}

/**
 * `userInitiated` says whether somebody asked for this check or the app is polling on its own.
 *
 * Only a check somebody asked for may put this device onto the reseller's dashboard. A device
 * deleted there has to stay deleted, and it cannot if the five-second poll behind this screen
 * re-creates it a moment later. Opening the activation screen, or pressing Refresh, counts as
 * asking; the repeats after that do not.
 */
export async function activate(
  mac: string,
  deviceKey: string,
  userInitiated = true,
  url = ACTIVATION_URL,
): Promise<Activated> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mac, deviceKey, userInitiated }),
    });
  } catch (error) {
    // Almost always one of two things: no network, or the browser refusing a cross-origin request
    // because the service sends no CORS headers. A Tizen widget declaring <access origin="*"> is
    // not subject to the second, so this failing here and working on a set is expected.
    note(`unreachable: ${error instanceof Error ? error.name : 'error'}`);
    throw new Error('Could not reach the activation service. Check your connection.');
  }
  note(`http ${response.status}`);
  if (!response.ok) throw new Error('Could not reach the activation service. Try again shortly.');

  let json: Record<string, unknown>;
  try {
    json = (await response.json()) as Record<string, unknown>;
  } catch {
    throw new Error('The activation service returned an unexpected response.');
  }

  const status = String(json.status ?? '');
  note(`status ${status}`);
  if (status === 'pending') throw new ActivationPending();
  if (status !== 'assigned') throw new Error('The activation service returned an unexpected status.');

  const name = String(json.name ?? '').trim() || 'Activated playlist';
  const type = String(json.type ?? '');
  if (type === 'm3u') {
    return { kind: 'm3u', name, url: String(json.url ?? '') };
  }
  if (type === 'xtream') {
    return {
      kind: 'xtream',
      login: {
        name,
        address: String(json.server ?? ''),
        username: String(json.username ?? ''),
        password: String(json.password ?? ''),
      },
    };
  }
  throw new Error('The activation service returned an unsupported playlist type.');
}
