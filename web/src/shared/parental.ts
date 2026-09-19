/**
 * Parental controls: a PIN, and the categories and channels it stands in front of.
 *
 * The PIN is stored as a salted SHA-256 hash rather than as itself. Be clear about what that is
 * worth: four to six digits is at most a million possibilities, so anyone who can read the set's
 * storage and wants the number can have it in seconds. What hashing prevents is the number being
 * legible to anyone who merely looks - a child browsing the device, a repair technician, a screen
 * left open. That is the threat a parental PIN actually faces, and plain text loses to it.
 *
 * crypto.subtle is available on Tizen 5.5 and every browser this app targets. If it is missing the
 * app refuses to set a PIN at all, rather than quietly falling back to storing the digits: a
 * control that silently offers less protection than it claims is worse than one that says no.
 */
import { readJson, writeJson, remove } from '../platform/storage';

const SETTINGS = 'parental';

export interface ParentalSettings {
  /** Null until a PIN has been set. Both are hex. */
  pinHash: string | null;
  pinSalt: string | null;
  enabled: boolean;
  askOnStartup: boolean;
  /** Category names, which is what the catalogue keys them by. */
  lockedCategories: string[];
  /** Item keys, for individual channels. */
  lockedChannels: string[];
}

const DEFAULTS: ParentalSettings = {
  pinHash: null,
  pinSalt: null,
  enabled: false,
  askOnStartup: false,
  lockedCategories: [],
  lockedChannels: [],
};

export function parental(): ParentalSettings {
  return { ...DEFAULTS, ...readJson<Partial<ParentalSettings>>(SETTINGS, {}) };
}

function save(next: ParentalSettings): void {
  writeJson(SETTINGS, next);
}

export function update(change: Partial<ParentalSettings>): ParentalSettings {
  const next = { ...parental(), ...change };
  save(next);
  return next;
}

function hex(buffer: ArrayBuffer): string {
  return [...new Uint8Array(buffer)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

export function cryptoAvailable(): boolean {
  return typeof crypto !== 'undefined' && !!crypto.subtle && !!crypto.getRandomValues;
}

async function hash(pin: string, salt: string): Promise<string> {
  const data = new TextEncoder().encode(`${salt}:${pin}`);
  return hex(await crypto.subtle.digest('SHA-256', data));
}

export function isValidPin(pin: string): boolean {
  return /^\d{4,6}$/.test(pin);
}

export async function setPin(pin: string): Promise<boolean> {
  if (!isValidPin(pin) || !cryptoAvailable()) return false;
  const salt = hex(crypto.getRandomValues(new Uint8Array(16)).buffer);
  update({ pinHash: await hash(pin, salt), pinSalt: salt, enabled: true });
  return true;
}

export async function checkPin(pin: string): Promise<boolean> {
  const current = parental();
  if (!current.pinHash || !current.pinSalt || !cryptoAvailable()) return false;
  return (await hash(pin, current.pinSalt)) === current.pinHash;
}

export function hasPin(): boolean {
  return parental().pinHash !== null;
}

export function removePin(): void {
  // The locks go with it. Leaving them behind would mean content stayed hidden with no PIN left
  // to reveal it - a lock with no key.
  remove(SETTINGS);
}

/** Locked only while there is a PIN and the control is on; otherwise nothing is held back. */
function active(): ParentalSettings | null {
  const current = parental();
  return current.enabled && current.pinHash ? current : null;
}

export function isCategoryLocked(name: string): boolean {
  return active()?.lockedCategories.includes(name) ?? false;
}

export function isChannelLocked(key: string): boolean {
  return active()?.lockedChannels.includes(key) ?? false;
}

export function toggleCategoryLock(name: string): boolean {
  const current = parental();
  const locked = current.lockedCategories.includes(name);
  update({
    lockedCategories: locked
      ? current.lockedCategories.filter((entry) => entry !== name)
      : [...current.lockedCategories, name],
  });
  return !locked;
}

export function toggleChannelLock(key: string): boolean {
  const current = parental();
  const locked = current.lockedChannels.includes(key);
  update({
    lockedChannels: locked
      ? current.lockedChannels.filter((entry) => entry !== key)
      : [...current.lockedChannels, key],
  });
  return !locked;
}

/**
 * Unlocked for the rest of this run once the PIN has been given.
 *
 * Asking again for every channel in a locked category would make the control unusable and teach
 * the viewer to turn it off. Session-scoped rather than persisted, so closing the app locks it
 * again - which is what "ask on startup" then governs.
 */
let unlockedThisSession = false;

export function isUnlocked(): boolean {
  return unlockedThisSession;
}

export function markUnlocked(): void {
  unlockedThisSession = true;
}

export function relock(): void {
  unlockedThisSession = false;
}
