/**
 * What the Android app keeps in SharedPreferences: the saved playlist, favourites, resume points,
 * settings.
 *
 * localStorage on a television is small - a few megabytes, and Samsung does not promise more - so
 * a full catalogue of tens of thousands of entries does not belong in it. Small records go here;
 * the catalogue cache gets its own store later, on a quota that can hold it.
 *
 * Every read and write is guarded. A set with storage disabled or full throws on access rather
 * than returning null, and an app that assumes otherwise dies at startup on exactly the devices
 * hardest to debug.
 */
const PREFIX = '4kplus.';

export function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(PREFIX + key);
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

export function writeJson(key: string, value: unknown): boolean {
  try {
    window.localStorage.setItem(PREFIX + key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function remove(key: string): void {
  try {
    window.localStorage.removeItem(PREFIX + key);
  } catch {
    /* Nothing to remove, or no storage at all. */
  }
}
