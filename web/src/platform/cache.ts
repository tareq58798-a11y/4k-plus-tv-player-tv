/**
 * The catalogue, kept on the device so a restart is not a re-download.
 *
 * A full Xtream account is tens of thousands of entries and several megabytes of JSON. Fetching
 * it again on every launch is the difference between an app that opens and an app you wait for,
 * and on a television the wait is worse: slower processor, slower network, and a viewer holding a
 * remote with nothing to press.
 *
 * IndexedDB rather than localStorage. localStorage is a few megabytes at best on Tizen, it is
 * synchronous - so writing a large catalogue blocks the page and the remote stops responding -
 * and it only stores strings. IndexedDB takes structured values, is asynchronous, and has room.
 *
 * Every call resolves rather than rejects. A set with storage disabled, a full quota or a
 * corrupted database must cost the viewer a slower start, never a broken app.
 */
import type { LoadedPlaylist, ProviderLogin } from '../shared/models';

const DB_NAME = '4kplus';
const DB_VERSION = 1;
const STORE = 'catalogue';

interface CacheRecord {
  playlist: LoadedPlaylist;
  savedAt: number;
}

/**
 * Which account a cached catalogue belongs to.
 *
 * The password is deliberately not part of this. A key ends up in a database that can be listed,
 * and the host and username already identify the account uniquely - a changed password should not
 * throw away a catalogue that is still perfectly good.
 */
export function cacheKey(login: ProviderLogin): string {
  return `${login.address.trim().toLowerCase()}|${login.username.trim()}`;
}

function open(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    let settled = false;
    const done = (value: IDBDatabase | null) => {
      if (!settled) {
        settled = true;
        resolve(value);
      }
    };
    try {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
      };
      request.onsuccess = () => done(request.result);
      request.onerror = () => done(null);
      // A blocked upgrade never fires either handler - another tab or a previous run holding the
      // database open leaves this pending for ever, and the app would wait on it at startup.
      request.onblocked = () => done(null);
      setTimeout(() => done(null), 3000);
    } catch {
      done(null);
    }
  });
}

export async function readCatalogue(key: string): Promise<{ playlist: LoadedPlaylist; ageMs: number } | null> {
  const db = await open();
  if (!db) return null;
  return new Promise((resolve) => {
    try {
      const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(key);
      request.onsuccess = () => {
        const record = request.result as CacheRecord | undefined;
        resolve(record?.playlist ? { playlist: record.playlist, ageMs: Date.now() - record.savedAt } : null);
      };
      request.onerror = () => resolve(null);
    } catch {
      resolve(null);
    } finally {
      db.close();
    }
  });
}

export async function writeCatalogue(key: string, playlist: LoadedPlaylist): Promise<boolean> {
  const db = await open();
  if (!db) return false;
  return new Promise((resolve) => {
    try {
      const transaction = db.transaction(STORE, 'readwrite');
      transaction.objectStore(STORE).put({ playlist, savedAt: Date.now() } satisfies CacheRecord, key);
      transaction.oncomplete = () => resolve(true);
      // Almost always the quota. Not worth telling the viewer about: the catalogue they are
      // looking at is fine, it simply will not be there next time.
      transaction.onerror = () => resolve(false);
      transaction.onabort = () => resolve(false);
    } catch {
      resolve(false);
    } finally {
      db.close();
    }
  });
}

export async function clearCatalogue(key: string): Promise<void> {
  const db = await open();
  if (!db) return;
  try {
    db.transaction(STORE, 'readwrite').objectStore(STORE).delete(key);
  } catch {
    /* Nothing stored, or no storage at all. */
  } finally {
    db.close();
  }
}
