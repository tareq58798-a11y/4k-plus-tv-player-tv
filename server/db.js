const { Pool } = require('pg');

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: { rejectUnauthorized: false }
});

/**
 * Adds the Playlist 2 columns when they are missing. Run once at start-up (index.js).
 *
 * Nothing created the table on deploy - schema.sql was run by hand, once - so a column added there
 * alone would never reach the live database. This is additive and safe to run any number of times:
 * ADD COLUMN IF NOT EXISTS leaves a column that is already there exactly as it is, and the new ones
 * start empty (null) on every existing row. Nothing is dropped, renamed or rewritten; every device
 * and Playlist 1 stay where they were.
 */
async function ensureSchema() {
  await pool.query(
    `alter table devices
       add column if not exists playlist2_type text,
       add column if not exists playlist2_name text,
       add column if not exists playlist2_m3u_url text,
       add column if not exists playlist2_xtream_server text,
       add column if not exists playlist2_xtream_username text,
       add column if not exists playlist2_xtream_password text`
  );
}

async function getDevice(mac) {
  const result = await pool.query('select * from devices where mac = $1', [mac]);
  return result.rows[0] || null;
}

/**
 * Records a check-in. A device added by hand without its key (see addDevice) has an empty one,
 * and takes the key from its first check-in; a key already stored is never replaced, so a second
 * set cannot take over a MAC that is already someone's by claiming a different key.
 */
async function upsertPendingDevice(mac, deviceKey) {
  await pool.query(
    `insert into devices (mac, device_key)
     values ($1, $2)
     on conflict (mac) do update set
       last_seen = now(),
       device_key = case when devices.device_key = '' then excluded.device_key else devices.device_key end`,
    [mac, deviceKey]
  );
}

/**
 * Adds a device from the dashboard, before it has ever checked in, so a playlist can be waiting
 * for it. [deviceKey] may be empty: the device then supplies it on its first check-in.
 *
 * last_seen is set to the epoch rather than now, because "last seen" is a claim about the device
 * and this one has not been seen; the page shows it as "Never". Returns false when the MAC is
 * already on the dashboard, which is left exactly as it was - its playlist included.
 *
 * [playlist], when given, is assigned in the same insert: { type: 'm3u', name, m3uUrl } or
 * { type: 'xtream', name, server, username, password }. One statement, so a MAC that is already
 * there can never have a playlist written over it by an Add that lost the race.
 */
async function addDevice(mac, deviceKey, playlist = null) {
  const p = playlist || {};
  const result = await pool.query(
    `insert into devices (mac, device_key, last_seen, status, playlist_type, playlist_name,
                          m3u_url, xtream_server, xtream_username, xtream_password)
     values ($1, $2, 'epoch', $3, $4, $5, $6, $7, $8, $9)
     on conflict (mac) do nothing`,
    [
      mac, deviceKey,
      playlist ? 'assigned' : 'pending',
      playlist ? p.type : null,
      playlist ? p.name : null,
      p.type === 'm3u' ? p.m3uUrl : null,
      p.type === 'xtream' ? p.server : null,
      p.type === 'xtream' ? p.username : null,
      p.type === 'xtream' ? p.password : null
    ]
  );
  return result.rowCount > 0;
}

async function listDevices() {
  const result = await pool.query('select * from devices order by last_seen desc');
  return result.rows;
}

async function assignM3u(mac, playlistName, m3uUrl) {
  await pool.query(
    `update devices set
       status = 'assigned',
       playlist_type = 'm3u',
       playlist_name = $2,
       m3u_url = $3,
       xtream_server = null,
       xtream_username = null,
       xtream_password = null
     where mac = $1`,
    [mac, playlistName, m3uUrl]
  );
}

async function assignXtream(mac, playlistName, server, username, password) {
  await pool.query(
    `update devices set
       status = 'assigned',
       playlist_type = 'xtream',
       playlist_name = $2,
       xtream_server = $3,
       xtream_username = $4,
       xtream_password = $5,
       m3u_url = null
     where mac = $1`,
    [mac, playlistName, server, username, password]
  );
}

/**
 * Wipes the playlist assigned to a device, leaving the device itself in the list as pending.
 *
 * Every playlist column is cleared, not just the ones the current type uses: a device that was on
 * an Xtream login and is later given an M3U URL leaves its old server, username and password
 * sitting in the row, and those are someone's real credentials. Deleting the profile has to mean
 * the credentials are gone, or the word is a lie.
 *
 * The device keeps polling and reappears as pending, which is what the dashboard should show - the
 * set is still switched on and still has no playlist.
 */
async function deleteProfile(mac) {
  const result = await pool.query(
    `update devices set
       status = case when playlist2_type is not null then 'assigned' else 'pending' end,
       playlist_type = null,
       playlist_name = null,
       m3u_url = null,
       xtream_server = null,
       xtream_username = null,
       xtream_password = null
     where mac = $1`,
    [mac]
  );
  return result.rowCount > 0;
}

/**
 * Sets a device's second playlist, alongside the first. [playlist] is shaped as for addDevice.
 * The columns of the other type are cleared, for the same reason assignM3u/assignXtream clear
 * theirs: a login left behind under an M3U is somebody's credentials sitting in the row.
 */
async function assignSecond(mac, playlist) {
  const result = await pool.query(
    `update devices set
       status = 'assigned',
       playlist2_type = $2,
       playlist2_name = $3,
       playlist2_m3u_url = $4,
       playlist2_xtream_server = $5,
       playlist2_xtream_username = $6,
       playlist2_xtream_password = $7
     where mac = $1`,
    [
      mac, playlist.type, playlist.name,
      playlist.type === 'm3u' ? playlist.m3uUrl : null,
      playlist.type === 'xtream' ? playlist.server : null,
      playlist.type === 'xtream' ? playlist.username : null,
      playlist.type === 'xtream' ? playlist.password : null
    ]
  );
  return result.rowCount > 0;
}

/** Wipes the second playlist, credentials and all. The device stays assigned if it has a first. */
async function deleteSecond(mac) {
  const result = await pool.query(
    `update devices set
       status = case when playlist_type is not null then 'assigned' else 'pending' end,
       playlist2_type = null,
       playlist2_name = null,
       playlist2_m3u_url = null,
       playlist2_xtream_server = null,
       playlist2_xtream_username = null,
       playlist2_xtream_password = null
     where mac = $1`,
    [mac]
  );
  return result.rowCount > 0;
}

/**
 * Removes the device row outright.
 *
 * Different from unassignDevice, which keeps the row and clears its playlist. Unassigning leaves
 * a device that will reappear in the list the next time it polls, still pending; deleting removes
 * it entirely, so a set that has been sold, returned or replaced stops occupying the dashboard.
 *
 * A device that is still switched on and still polling will insert itself again as pending, which
 * is correct: the record describes a device that exists, and that one does.
 */
async function deleteDevice(mac) {
  const result = await pool.query('delete from devices where mac = $1', [mac]);
  return result.rowCount > 0;
}

module.exports = {
  ensureSchema,
  getDevice,
  upsertPendingDevice,
  addDevice,
  listDevices,
  assignM3u,
  assignXtream,
  deleteProfile,
  assignSecond,
  deleteSecond,
  deleteDevice
};
