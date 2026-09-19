const { Pool } = require('pg');

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: { rejectUnauthorized: false }
});

async function getDevice(mac) {
  const result = await pool.query('select * from devices where mac = $1', [mac]);
  return result.rows[0] || null;
}

/**
 * Puts a device on the dashboard, or refreshes the time it was last heard from.
 *
 * Only ever called for a request the viewer actually asked for - see the activate route. A device
 * that has been deleted must stay deleted, and it cannot if a background poll is allowed to put it
 * straight back.
 */
async function registerDevice(mac, deviceKey) {
  await pool.query(
    `insert into devices (mac, device_key)
     values ($1, $2)
     on conflict (mac) do update set last_seen = now()`,
    [mac, deviceKey]
  );
}

/**
 * Updates last_seen for a device that is already on the dashboard, and says whether it was there.
 *
 * This is what an automatic poll is allowed to do: keep an existing row's clock current, and
 * nothing else. False means the row is gone - either never registered or deleted - and the caller
 * answers "pending" without creating anything.
 */
async function touchDevice(mac) {
  const result = await pool.query('update devices set last_seen = now() where mac = $1', [mac]);
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
       status = 'pending',
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
  getDevice,
  registerDevice,
  touchDevice,
  listDevices,
  assignM3u,
  assignXtream,
  deleteProfile,
  deleteDevice
};
