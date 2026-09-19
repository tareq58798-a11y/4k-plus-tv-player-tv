const express = require('express');
const db = require('./db');
const { renderDevices } = require('./adminPage');

const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

const MAC_PATTERN = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/;

function normalizeMac(value) {
  return String(value || '').trim().toUpperCase();
}

app.post('/api/activate', async (req, res) => {
  const mac = normalizeMac(req.body.mac);
  const deviceKey = String(req.body.deviceKey || '').trim();
  if (!MAC_PATTERN.test(mac) || !deviceKey) {
    return res.status(400).json({ status: 'error', message: 'Invalid mac or deviceKey.' });
  }

  await db.upsertPendingDevice(mac, deviceKey);
  const device = await db.getDevice(mac);

  if (!device || device.status !== 'assigned' || device.device_key !== deviceKey) {
    return res.json({ status: 'pending' });
  }

  if (device.playlist_type === 'm3u' && device.m3u_url) {
    return res.json({ status: 'assigned', type: 'm3u', name: device.playlist_name, url: device.m3u_url });
  }
  if (device.playlist_type === 'xtream' && device.xtream_server) {
    return res.json({
      status: 'assigned',
      type: 'xtream',
      name: device.playlist_name,
      server: device.xtream_server,
      username: device.xtream_username,
      password: device.xtream_password
    });
  }
  return res.json({ status: 'pending' });
});

function requireAdmin(req, res, next) {
  const header = req.headers.authorization || '';
  const [scheme, encoded] = header.split(' ');
  if (scheme === 'Basic' && encoded) {
    const [user, pass] = Buffer.from(encoded, 'base64').toString().split(':');
    if (user === 'admin' && pass === process.env.ADMIN_PASSWORD) return next();
  }
  res.set('WWW-Authenticate', 'Basic realm="4K Plus TV Admin"');
  return res.status(401).send('Authentication required.');
}

app.get('/admin', requireAdmin, async (req, res) => {
  const devices = await db.listDevices();
  res.send(renderDevices(devices));
});

app.post('/admin/devices/:mac/assign', requireAdmin, async (req, res) => {
  const mac = normalizeMac(req.params.mac);
  const { playlistName, type, m3uUrl, xtreamServer, xtreamUsername, xtreamPassword } = req.body;
  if (type === 'm3u') {
    await db.assignM3u(mac, playlistName, m3uUrl);
  } else {
    await db.assignXtream(mac, playlistName, xtreamServer, xtreamUsername, xtreamPassword);
  }
  res.redirect('/admin');
});

/**
 * Clears the playlist assigned to a device, leaving the device in the list as pending.
 *
 * Validated and POST-only for the same reason the delete route below is: this throws away stored
 * credentials, and anything that can be triggered by following a link can be triggered by
 * something that follows links on its own.
 */
app.post('/admin/devices/:mac/delete-profile', requireAdmin, async (req, res) => {
  const mac = normalizeMac(req.params.mac);
  if (!MAC_PATTERN.test(mac)) return res.status(400).send('Invalid MAC address.');
  await db.deleteProfile(mac);
  res.redirect('/admin');
});

/**
 * Removes a device from the dashboard entirely.
 *
 * POST rather than GET, and validated like every other route: a link that deletes on being
 * followed is a link a browser's prefetch, or anything that crawls the page, can follow by
 * itself. The admin page asks for confirmation before it posts here.
 */
app.post('/admin/devices/:mac/delete', requireAdmin, async (req, res) => {
  const mac = normalizeMac(req.params.mac);
  if (!MAC_PATTERN.test(mac)) return res.status(400).send('Invalid MAC address.');
  await db.deleteDevice(mac);
  res.redirect('/admin');
});

app.get('/', (req, res) => res.send('4K Plus TV activation server is running.'));

// Started only when this file is run directly, so the test can require the app and listen on a
// port of its own instead of racing the real one.
if (require.main === module) {
  const port = process.env.PORT || 3000;
  app.listen(port, () => console.log(`Activation server listening on port ${port}`));
}

module.exports = app;
