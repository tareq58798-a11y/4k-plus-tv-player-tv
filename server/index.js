const express = require('express');
const db = require('./db');
const { renderDevices } = require('./adminPage');
const { VIDEO_ID, trailerPage } = require('./trailerPage');
const { privacyPage, privacyPageKo } = require('./privacyPage');
const { homePage } = require('./homePage');

const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

const MAC_PATTERN = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/;

function normalizeMac(value) {
  return String(value || '').trim().toUpperCase();
}

/**
 * A MAC as somebody types it into the dashboard: any case, with colons, dashes, dots, spaces or
 * nothing between the pairs. Returns the AA:BB:CC:DD:EE:FF form the apps send, or null.
 */
function parseTypedMac(value) {
  const hex = String(value || '').replace(/[\s:.-]/g, '').toUpperCase();
  if (!/^[0-9A-F]{12}$/.test(hex)) return null;
  return hex.match(/.{2}/g).join(':');
}

const DEVICE_KEY_PATTERN = /^[0-9]{6}$/;

/**
 * The optional playlist on the Add device form. null when none was typed (the device is added
 * pending, as before); undefined when one was started but is incomplete, which is refused rather
 * than saved half-filled - a device handed a playlist with no address would only show an error.
 */
function typedPlaylist(body) {
  const field = (name) => String(body[name] || '').trim();
  const name = field('playlistName');
  const type = field('type');
  const m3uUrl = field('m3uUrl');
  const server = field('xtreamServer');
  const username = field('xtreamUsername');
  const password = field('xtreamPassword');
  if (!name && !m3uUrl && !server && !username && !password) return null;
  if (!name) return undefined;
  if (type === 'm3u') return m3uUrl ? { type, name, m3uUrl } : undefined;
  if (type === 'xtream') return server && username && password ? { type, name, server, username, password } : undefined;
  return undefined;
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

  const playlists = [
    playlistOf(device.playlist_type, device.playlist_name, device.m3u_url,
      device.xtream_server, device.xtream_username, device.xtream_password),
    playlistOf(device.playlist2_type, device.playlist2_name, device.playlist2_m3u_url,
      device.playlist2_xtream_server, device.playlist2_xtream_username, device.playlist2_xtream_password)
  ].filter(Boolean);
  if (playlists.length === 0) return res.json({ status: 'pending' });

  /*
   * The first playlist goes out exactly as it always has, at the top level, so every app already
   * installed - which reads only those fields - carries on with one playlist and never knows there
   * is a second. `playlists` is new and optional: both of them, in order, for the apps that read it.
   * When Playlist 1 has been deleted and Playlist 2 kept, Playlist 2 is the first, so an older app
   * still gets something to play.
   */
  return res.json({ status: 'assigned', ...playlists[0], playlists });
});

/** One playlist as the apps receive it, or null when the slot is empty or incomplete. */
function playlistOf(type, name, m3uUrl, server, username, password) {
  if (type === 'm3u' && m3uUrl) return { type: 'm3u', name, url: m3uUrl };
  if (type === 'xtream' && server) return { type: 'xtream', name, server, username, password };
  return null;
}

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
  res.send(renderDevices(devices, String(req.query.notice || '')));
});

/**
 * Adds a device by hand, so a playlist can be assigned before the device first checks in.
 *
 * The device key is optional. The apps derive it in more than one way (Android from the device
 * id, Samsung from the MAC), so the dashboard cannot always work it out; left blank, the device
 * supplies it on its first check-in. Given, it must be the six digits the device shows, and the
 * device is only handed its playlist when its key matches.
 */
app.post('/admin/devices/add', requireAdmin, async (req, res) => {
  const mac = parseTypedMac(req.body.mac);
  const deviceKey = String(req.body.deviceKey || '').trim();
  if (!mac) return res.redirect('/admin?notice=bad-mac');
  if (deviceKey && !DEVICE_KEY_PATTERN.test(deviceKey)) return res.redirect('/admin?notice=bad-key');
  const playlist = typedPlaylist(req.body);
  if (playlist === undefined) return res.redirect('/admin?notice=bad-playlist');
  // Never an update: a MAC already on the dashboard keeps the playlist it has. Changing that is
  // what the row's own Save is for, where the existing playlist is in front of you.
  const added = await db.addDevice(mac, deviceKey, playlist);
  const notice = added ? 'added' : 'exists';
  res.redirect(`/admin?notice=${notice}&mac=${encodeURIComponent(mac)}#device-${mac.replace(/:/g, '')}`);
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
 * Sets a device's second playlist. Refused unless complete - name, and an M3U URL or a server,
 * username and password - so a device is never handed half a login.
 */
app.post('/admin/devices/:mac/assign2', requireAdmin, async (req, res) => {
  const mac = normalizeMac(req.params.mac);
  if (!MAC_PATTERN.test(mac)) return res.status(400).send('Invalid MAC address.');
  const playlist = typedPlaylist(req.body);
  if (!playlist) return res.redirect(`/admin?notice=bad-playlist2#device-${mac.replace(/:/g, '')}`);
  await db.assignSecond(mac, playlist);
  res.redirect(`/admin#device-${mac.replace(/:/g, '')}`);
});

/** Wipes a device's second playlist, credentials and all. POST-only and confirmed, like the others. */
app.post('/admin/devices/:mac/delete-playlist2', requireAdmin, async (req, res) => {
  const mac = normalizeMac(req.params.mac);
  if (!MAC_PATTERN.test(mac)) return res.status(400).send('Invalid MAC address.');
  await db.deleteSecond(mac);
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

// A trailer, inside the television app - see trailerPage.js. Only a YouTube video id is accepted.
app.get('/trailer', (req, res) => {
  const videoId = String(req.query.v || '');
  if (!VIDEO_ID.test(videoId)) return res.status(400).send('Invalid video.');
  res.set('Content-Type', 'text/html; charset=utf-8');
  // Framed by the app, which has no web origin of its own to list, so no frame-ancestors limit.
  res.set('Cache-Control', 'public, max-age=3600');
  res.send(trailerPage(videoId));
});

// The privacy policy Samsung's Seller Office links to - see privacyPage.js.
app.get('/privacy', (req, res) => {
  res.set('Content-Type', 'text/html; charset=utf-8');
  res.set('Cache-Control', 'public, max-age=3600');
  res.send(privacyPage());
});

// The same policy in Korean - Seller Office requires a Korean URL too.
app.get('/privacy/ko', (req, res) => {
  res.set('Content-Type', 'text/html; charset=utf-8');
  res.set('Cache-Control', 'public, max-age=3600');
  res.send(privacyPageKo());
});

// The app's homepage, which Seller Office requires - see homePage.js. Also the health check.
app.get('/', (req, res) => {
  res.set('Content-Type', 'text/html; charset=utf-8');
  res.set('Cache-Control', 'public, max-age=3600');
  res.send(homePage());
});

// Started only when this file is run directly, so the test can require the app and listen on a
// port of its own instead of racing the real one.
if (require.main === module) {
  const port = process.env.PORT || 3000;
  // The Playlist 2 columns, added if missing (see db.ensureSchema). The server listens either way:
  // devices must keep getting Playlist 1 even if this fails, and the reason is logged - the
  // message only, never the connection string.
  db.ensureSchema()
    .then(() => console.log('Database schema up to date (Playlist 2 columns present).'))
    .catch((error) => console.error(`Could not add the Playlist 2 columns: ${error.message}`))
    .finally(() => app.listen(port, () => console.log(`Activation server listening on port ${port}`)));
}

module.exports = app;
