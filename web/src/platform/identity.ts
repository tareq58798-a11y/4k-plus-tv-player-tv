/**
 * This device's identity, as shown on screen and sent to the activation service.
 *
 * A Samsung television can give its real network MAC, which is what "activate by MAC" means to
 * the reseller assigning the playlist - it is the number printed in the set's own network
 * settings, so a customer can read it out without the app being open. Android cannot do that, so
 * the television build synthesises one from ANDROID_ID instead; this build uses the real thing
 * where the set offers it.
 *
 * The device key is derived exactly as DeviceIdentity.kt derives it - SHA-256 of the identity,
 * first four bytes as a big-endian integer, modulo a million, padded to six digits - so both apps
 * produce the same shape of pair and one activation service can answer both.
 */
import { readJson, writeJson } from './storage';

const FALLBACK_ID = 'device-identity';

interface TizenNetworkApi {
  getMac(): string;
}

function hardwareMac(): string | null {
  try {
    const webapis = (window as unknown as { webapis?: { network?: TizenNetworkApi } }).webapis;
    const mac = webapis?.network?.getMac();
    if (!mac) return null;
    // Sets report it with or without separators, and in either case. One shape from here on.
    const hex = mac.replace(/[^0-9a-fA-F]/g, '').toUpperCase();
    if (hex.length !== 12) return null;
    return (hex.match(/.{2}/g) ?? []).join(':');
  } catch {
    return null;
  }
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
  // The locally-administered bit, so a generated address cannot collide with a real one.
  bytes[0] = (bytes[0]! & 0xfe) | 0x02;
  const mac = [...bytes].map((b) => b.toString(16).padStart(2, '0').toUpperCase()).join(':');
  writeJson(FALLBACK_ID, mac);
  return mac;
}

export function deviceMac(): string {
  return hardwareMac() ?? fallbackIdentity();
}

/** True when the number on screen is the set's own, rather than one this app invented. */
export function macIsHardware(): boolean {
  return hardwareMac() !== null;
}

async function sha256(value: string): Promise<Uint8Array> {
  const data = new TextEncoder().encode(value);
  return new Uint8Array(await crypto.subtle.digest('SHA-256', data));
}

export async function deviceKey(): Promise<string> {
  const digest = await sha256(deviceMac());
  // First four bytes, big-endian, exactly as the Kotlin does it.
  let value = 0;
  for (let i = 0; i < 4; i++) value = value * 256 + digest[i]!;
  return String(value % 1_000_000).padStart(6, '0');
}
