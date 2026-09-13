const { Pool } = require('pg');

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: { rejectUnauthorized: false }
});

async function getDevice(mac) {
  const result = await pool.query('select * from devices where mac = $1', [mac]);
  return result.rows[0] || null;
}

async function upsertPendingDevice(mac, deviceKey) {
  await pool.query(
    `insert into devices (mac, device_key)
     values ($1, $2)
     on conflict (mac) do update set last_seen = now()`,
    [mac, deviceKey]
  );
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

async function unassignDevice(mac) {
  await pool.query(
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
}

module.exports = {
  getDevice,
  upsertPendingDevice,
  listDevices,
  assignM3u,
  assignXtream,
  unassignDevice
};
