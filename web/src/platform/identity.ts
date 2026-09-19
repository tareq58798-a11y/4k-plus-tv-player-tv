/**
 * This device's identity, as shown on screen and sent to the activation service.
 *
 * Derived from the set's DUID, exactly as the television app derives its own from ANDROID_ID:
 * hash it, take six bytes, format them as a MAC. A viewer reads the result off the screen and the
 * reseller assigns a playlist to it.
 *
 * The DUID rather than the real network MAC, deliberately, for two reasons. A television has more
 * than one network interface, and getMac() answers for whichever is in use - so a customer who
 * moves from wi-fi to a cable would come back with a different number and a lost activation. And
 * the DUID is what Samsung itself identifies a set by; it is on the device, in the set's own menus,
 * and does not change.
 *
 * A generated address is the last resort, and a poor one: it lives in the app's own storage, so it
 * does not survive the app being reinstalled. Anything relying on it must say so rather than let
 * somebody register a number that will not be there tomorrow.
 *
 * The device key is derived exactly as DeviceIdentity.kt derives it - SHA-256 of the identity,
 * first four bytes as a big-endian integer, modulo a million, padded to six digits - so both apps
 * produce the same shape of pair and one activation service can answer both.
 */
import { readJson, writeJson } from './storage';

const FALLBACK_ID = 'device-identity';

interface TizenWebApis {
  productinfo?: { getDuid(): string };
  network?: { getMac(): string };
}

function webapis(): TizenWebApis | undefined {
  return (window as unknown as { webapis?: TizenWebApis }).webapis;
}

/** The set's own unique id. Stable, and independent of which network interface is in use. */
function duid(): string | null {
  try {
    return webapis()?.productinfo?.getDuid() || null;
  } catch {
    return null;
  }
}

/** Only as a fallback to the DUID - see the note at the top about interfaces. */
function networkMac(): string | null {
  try {
    const mac = webapis()?.network?.getMac();
    if (!mac) return null;
    // Sets report it with or without separators, and in either case. One shape from here on.
    const hex = mac.replace(/[^0-9a-fA-F]/g, '').toUpperCase();
    if (hex.length !== 12) return null;
    return (hex.match(/.{2}/g) ?? []).join(':');
  } catch {
    return null;
  }
}

/** Six bytes of a hash, formatted as a MAC, with the locally-administered bit set so it cannot
 *  collide with a real address. The same shape DeviceIdentity.kt produces on Android. */
function macFrom(digest: Uint8Array): string {
  const bytes = [...digest.slice(0, 6)];
  bytes[0] = (bytes[0]! & 0xfe) | 0x02;
  return bytes.map((b) => b.toString(16).padStart(2, '0').toUpperCase()).join(':');
}

/**
 * A stable identity for anything that is not a Samsung set - the browser during development, or a
 * television whose network API is unavailable. Random once, then kept, so the pair a viewer reads
 * off the screen is the same pair tomorrow.
 */
function fallbackIdentity(): string {
  const existing = readJson<string | null>(FALLBACK_ID, null);
  if (existing) return existing;
  const bytes = new Uint8Array(6);
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  const mac = macFrom(bytes);
  writeJson(FALLBACK_ID, mac);
  return mac;
}

async function sha256(value: string): Promise<Uint8Array> {
  const data = new TextEncoder().encode(value);
  return new Uint8Array(await crypto.subtle.digest('SHA-256', data));
}

export type IdentitySource = 'duid' | 'network' | 'generated';

export interface DeviceIdentity {
  mac: string;
  key: string;
  source: IdentitySource;
  /** False when the number will not survive the app being reinstalled. */
  stable: boolean;
}

/**
 * Worked out once and remembered, because every caller wants the same answer and hashing is
 * asynchronous. Order matters: the DUID first, the network MAC only if the set will not give one,
 * and a generated address last of all.
 */
let resolved: DeviceIdentity | null = null;

export async function identity(): Promise<DeviceIdentity> {
  if (resolved) return resolved;

  const id = duid();
  if (id) {
    const mac = macFrom(await sha256(id));
    resolved = { mac, key: await keyFor(mac), source: 'duid', stable: true };
    return resolved;
  }

  const mac = networkMac();
  if (mac) {
    resolved = { mac, key: await keyFor(mac), source: 'network', stable: true };
    return resolved;
  }

  const generated = fallbackIdentity();
  resolved = { mac: generated, key: await keyFor(generated), source: 'generated', stable: false };
  return resolved;
}

async function keyFor(mac: string): Promise<string> {
  const digest = await sha256(mac);
  // First four bytes, big-endian, exactly as the Kotlin does it.
  let value = 0;
  for (let i = 0; i < 4; i++) value = value * 256 + digest[i]!;
  return String(value % 1_000_000).padStart(6, '0');
}
