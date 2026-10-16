// Covers the two removal actions on the admin dashboard. Run with: node test/dashboard.test.js
//
// db.js opens a pg Pool the moment it is required, which needs a real DATABASE_URL, so the module
// loader is intercepted and the routes talk to an in-memory stand-in instead. Nothing here touches
// a real database or a real device.
const assert = require('assert');
const Module = require('module');

let rows = [];
const fakeDb = {
  listDevices: async () => rows,
  getDevice: async (mac) => rows.find((r) => r.mac === mac) || null,
  upsertPendingDevice: async (mac, deviceKey) => {
    const existing = rows.find((r) => r.mac === mac);
    if (existing) {
      existing.last_seen = new Date().toISOString();
      if (existing.device_key === '') existing.device_key = deviceKey;
      return;
    }
    rows.push({
      mac, device_key: deviceKey, status: 'pending', playlist_type: null, playlist_name: null,
      m3u_url: null, xtream_server: null, xtream_username: null, xtream_password: null,
      last_seen: new Date().toISOString()
    });
  },
  addDevice: async (mac, deviceKey, playlist = null) => {
    if (rows.find((r) => r.mac === mac)) return false;
    const p = playlist || {};
    rows.push({
      mac, device_key: deviceKey,
      status: playlist ? 'assigned' : 'pending',
      playlist_type: playlist ? p.type : null,
      playlist_name: playlist ? p.name : null,
      m3u_url: p.type === 'm3u' ? p.m3uUrl : null,
      xtream_server: p.type === 'xtream' ? p.server : null,
      xtream_username: p.type === 'xtream' ? p.username : null,
      xtream_password: p.type === 'xtream' ? p.password : null,
      last_seen: new Date(0).toISOString()
    });
    return true;
  },
  deleteProfile: async (mac) => {
    const existing = rows.find((r) => r.mac === mac);
    if (!existing) return false;
    Object.assign(existing, {
      status: existing.playlist2_type ? 'assigned' : 'pending', playlist_type: null, playlist_name: null, m3u_url: null,
      xtream_server: null, xtream_username: null, xtream_password: null
    });
    return true;
  },
  assignSecond: async (mac, p) => {
    const existing = rows.find((r) => r.mac === mac);
    if (!existing) return false;
    Object.assign(existing, {
      status: 'assigned', playlist2_type: p.type, playlist2_name: p.name,
      playlist2_m3u_url: p.type === 'm3u' ? p.m3uUrl : null,
      playlist2_xtream_server: p.type === 'xtream' ? p.server : null,
      playlist2_xtream_username: p.type === 'xtream' ? p.username : null,
      playlist2_xtream_password: p.type === 'xtream' ? p.password : null
    });
    return true;
  },
  deleteSecond: async (mac) => {
    const existing = rows.find((r) => r.mac === mac);
    if (!existing) return false;
    Object.assign(existing, {
      status: existing.playlist_type ? 'assigned' : 'pending', playlist2_type: null, playlist2_name: null,
      playlist2_m3u_url: null, playlist2_xtream_server: null, playlist2_xtream_username: null, playlist2_xtream_password: null
    });
    return true;
  },
  deleteDevice: async (mac) => {
    const before = rows.length;
    rows = rows.filter((r) => r.mac !== mac);
    return rows.length < before;
  },
  assignM3u: async () => {},
  assignXtream: async (mac, playlistName, server, username, password) => {
    const existing = rows.find((r) => r.mac === mac);
    if (!existing) return;
    Object.assign(existing, {
      status: 'assigned', playlist_type: 'xtream', playlist_name: playlistName,
      xtream_server: server, xtream_username: username, xtream_password: password, m3u_url: null
    });
  }
};

const originalLoad = Module._load;
Module._load = function (request) {
  if (request === './db') return fakeDb;
  return originalLoad.apply(this, arguments);
};

const ADMIN_PASSWORD = 'not-a-real-password-just-for-this-test';
process.env.ADMIN_PASSWORD = ADMIN_PASSWORD;

const { renderDevices } = require('../adminPage');
const app = require('../index.js');

const MAC = '26:B4:11:22:33:44';
const AUTH = 'Basic ' + Buffer.from(`admin:${ADMIN_PASSWORD}`).toString('base64');

function seedAssigned() {
  rows = [{
    mac: MAC,
    device_key: '123456',
    status: 'assigned',
    // Deliberately hostile. An apostrophe ends the confirm() string early if the escaping is
    // wrong, and that fails open: the button would delete without asking.
    playlist_name: "Kid's <script>alert(1)</script> \"TV\"",
    playlist_type: 'xtream',
    xtream_server: 'http://example.invalid',
    xtream_username: 'someone',
    xtream_password: 'secret',
    m3u_url: null,
    last_seen: new Date().toISOString()
  }, {
    mac: 'AA:BB:CC:DD:EE:FF',
    device_key: '654321',
    status: 'pending',
    playlist_name: null, playlist_type: null, m3u_url: null,
    last_seen: new Date().toISOString()
  }];
}

let base;
const checks = [];
function check(name, fn) { checks.push([name, fn]); }

check('the page offers Delete profile only where there is a profile', () => {
  seedAssigned();
  const html = renderDevices(rows);
  assert.strictEqual((html.match(/>Delete profile</g) || []).length, 1,
    'the pending device has no profile, so no button');
  assert.strictEqual((html.match(/>Delete device</g) || []).length, 2);
  assert.ok(!html.includes('>Unassign<'), 'the old Unassign label is gone');
  assert.ok(html.includes('/delete-profile'));
});

check('a hostile playlist name cannot break out of the confirmation', () => {
  seedAssigned();
  const html = renderDevices(rows);
  assert.ok(!html.includes('<script>alert(1)</script>'), 'the script tag is never rendered raw');

  // Scoped to the confirm attribute itself. The same name appears elsewhere on the page, in the
  // assign form's value="", where &#39; is exactly right - it is only inside a JS string literal
  // that an entity is wrong, because the browser decodes it before the JS is parsed.
  const attrs = html.match(/onsubmit="return confirm\('[^"]*'\);"/g) || [];
  assert.strictEqual(attrs.length, 3, 'three confirmations, each one a well-formed call');
  const profileConfirm = attrs.find((a) => a.includes('Delete the playlist'));
  assert.ok(profileConfirm, 'the Delete profile confirmation is there');
  assert.ok(profileConfirm.includes("Kid\\'s"), 'the apostrophe is escaped for the JS string');
  assert.ok(!profileConfirm.includes('&#39;'),
    'an entity here would decode back to a bare quote and end the string early');
  assert.ok(!profileConfirm.includes('<script'), 'and no raw tag inside the attribute');
});

check('Delete profile clears the playlist and leaves the device pending', async () => {
  seedAssigned();
  const res = await fetch(`${base}/admin/devices/26:b4:11:22:33:44/delete-profile`, {
    method: 'POST', headers: { Authorization: AUTH }, redirect: 'manual'
  });
  assert.strictEqual(res.status, 302);
  const device = rows.find((r) => r.mac === MAC);
  assert.ok(device, 'the device itself stays on the dashboard');
  assert.strictEqual(device.status, 'pending');
  assert.strictEqual(device.playlist_name, null);
  assert.strictEqual(device.xtream_password, null, 'the stored credentials are erased, not kept');
});

check('Delete device removes the row outright', async () => {
  seedAssigned();
  const res = await fetch(`${base}/admin/devices/${MAC}/delete`, {
    method: 'POST', headers: { Authorization: AUTH }, redirect: 'manual'
  });
  assert.strictEqual(res.status, 302);
  assert.strictEqual(rows.find((r) => r.mac === MAC), undefined);
});

check('a deleted device does come back on its own, as it always has', async () => {
  // Recorded rather than desired. Nothing in the app tells this service whether a check was asked
  // for or automatic, so a deleted row is re-created by the next five-second poll from a set
  // sitting on the activation screen. Deleting a device that is switched on is therefore only
  // useful for clearing out one that is gone for good.
  rows = [];
  const res = await fetch(`${base}/api/activate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ mac: MAC, deviceKey: '123456' })
  });
  assert.deepStrictEqual(await res.json(), { status: 'pending' });
  assert.strictEqual(rows.length, 1, 'the poll re-registered it');
});

check('a poll against an assigned device still returns its playlist', async () => {
  seedAssigned();
  const res = await fetch(`${base}/api/activate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ mac: MAC, deviceKey: '123456' })
  });
  const body = await res.json();
  assert.strictEqual(body.status, 'assigned');
  assert.strictEqual(body.type, 'xtream');
});

check('Delete profile makes the next poll answer pending', async () => {
  seedAssigned();
  await fetch(`${base}/admin/devices/${MAC}/delete-profile`, {
    method: 'POST', headers: { Authorization: AUTH }, redirect: 'manual'
  });
  const res = await fetch(`${base}/api/activate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ mac: MAC, deviceKey: '123456' })
  });
  assert.deepStrictEqual(await res.json(), { status: 'pending' },
    'the device stops being handed the playlist it just lost');
});

check('a malformed MAC is rejected before it reaches the database', async () => {
  seedAssigned();
  const before = rows.length;
  const res = await fetch(`${base}/admin/devices/not-a-mac/delete-profile`, {
    method: 'POST', headers: { Authorization: AUTH }, redirect: 'manual'
  });
  assert.strictEqual(res.status, 400);
  assert.strictEqual(rows.length, before);
});

check('both removal routes are behind the admin password', async () => {
  seedAssigned();
  for (const path of [`/admin/devices/${MAC}/delete-profile`, `/admin/devices/${MAC}/delete`]) {
    const res = await fetch(`${base}${path}`, { method: 'POST', redirect: 'manual' });
    assert.strictEqual(res.status, 401, `${path} needs authentication`);
  }
  assert.ok(rows.find((r) => r.mac === MAC), 'and nothing happened');
});

function addForm(fields) {
  return fetch(`${base}/admin/devices/add`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields).toString(),
    redirect: 'manual'
  });
}

function poll(mac, deviceKey) {
  return fetch(`${base}/api/activate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ mac, deviceKey })
  }).then((res) => res.json());
}

check('the page has an Add device form', () => {
  seedAssigned();
  const html = renderDevices(rows);
  assert.ok(html.includes('action="/admin/devices/add"'));
});

check('a MAC typed in any common shape is added in the form the apps send', async () => {
  rows = [];
  const res = await addForm({ mac: ' 1a-2b-3c-4d-5e-6f ', deviceKey: '' });
  assert.strictEqual(res.status, 302);
  assert.ok(res.headers.get('location').includes('notice=added'));
  assert.strictEqual(rows.length, 1);
  assert.strictEqual(rows[0].mac, '1A:2B:3C:4D:5E:6F');
  assert.strictEqual(rows[0].status, 'pending');
  await addForm({ mac: 'aabbccddeeff' });
  assert.ok(rows.find((r) => r.mac === 'AA:BB:CC:DD:EE:FF'), 'no separators at all is accepted too');
});

check('a hand-added device is shown as never seen, with its key still to come', () => {
  rows = [];
  rows.push({ mac: '1A:2B:3C:4D:5E:6F', device_key: '', status: 'pending', last_seen: new Date(0).toISOString() });
  const html = renderDevices(rows, 'added');
  assert.ok(html.includes('Never'));
  assert.ok(html.includes('Set on first check-in'));
  assert.ok(html.includes('The device was added'));
  assert.ok(html.includes('id="device-1A2B3C4D5E6F"'), 'the row can be scrolled to after adding');
});

check('a playlist assigned before the first check-in reaches the device, which supplies its key', async () => {
  rows = [];
  await addForm({ mac: '1A:2B:3C:4D:5E:6F' });
  await fetch(`${base}/admin/devices/1A:2B:3C:4D:5E:6F/assign`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ playlistName: 'Home', type: 'xtream', xtreamServer: 'http://example.invalid', xtreamUsername: 'u', xtreamPassword: 'p' }).toString(),
    redirect: 'manual'
  });
  const first = await poll('1A:2B:3C:4D:5E:6F', '424242');
  assert.strictEqual(first.status, 'assigned', 'the first check-in gets the waiting playlist');
  assert.strictEqual(rows[0].device_key, '424242', 'and its key is kept');
  const other = await poll('1A:2B:3C:4D:5E:6F', '999999');
  assert.deepStrictEqual(other, { status: 'pending' }, 'a different key afterwards gets nothing');
});

check('a key given when adding must match the device', async () => {
  rows = [];
  await addForm({ mac: '1A:2B:3C:4D:5E:6F', deviceKey: '123456' });
  await fetch(`${base}/admin/devices/1A:2B:3C:4D:5E:6F/assign`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ playlistName: 'Home', type: 'xtream', xtreamServer: 'http://example.invalid', xtreamUsername: 'u', xtreamPassword: 'p' }).toString(),
    redirect: 'manual'
  });
  assert.deepStrictEqual(await poll('1A:2B:3C:4D:5E:6F', '654321'), { status: 'pending' });
  assert.strictEqual(rows[0].device_key, '123456', 'a wrong key does not replace the one typed in');
  assert.strictEqual((await poll('1A:2B:3C:4D:5E:6F', '123456')).status, 'assigned');
});

check('adding a MAC already on the dashboard changes nothing', async () => {
  seedAssigned();
  const before = JSON.stringify(rows);
  const res = await addForm({ mac: MAC, deviceKey: '000000' });
  assert.ok(res.headers.get('location').includes('notice=exists'));
  assert.strictEqual(JSON.stringify(rows), before);
});

check('Add device can bring an Xtream playlist with it, and the first check-in gets it', async () => {
  rows = [];
  const res = await addForm({
    mac: '1A:2B:3C:4D:5E:6F', playlistName: 'Home', type: 'xtream',
    xtreamServer: 'http://example.invalid', xtreamUsername: 'u', xtreamPassword: 'p'
  });
  assert.ok(res.headers.get('location').includes('notice=added'));
  assert.strictEqual(rows[0].status, 'assigned');
  const body = await poll('1A:2B:3C:4D:5E:6F', '424242');
  assert.strictEqual(body.status, 'assigned');
  assert.strictEqual(body.type, 'xtream');
  assert.strictEqual(body.name, 'Home');
});

check('Add device can bring an M3U playlist with it', async () => {
  rows = [];
  await addForm({ mac: '1A:2B:3C:4D:5E:6F', playlistName: 'List', type: 'm3u', m3uUrl: 'http://example.invalid/list.m3u' });
  const body = await poll('1A:2B:3C:4D:5E:6F', '424242');
  assert.strictEqual(body.type, 'm3u');
  assert.strictEqual(body.url, 'http://example.invalid/list.m3u');
});

check('adding a MAC already listed never overwrites its playlist', async () => {
  seedAssigned();
  const before = JSON.stringify(rows);
  const res = await addForm({
    mac: MAC, playlistName: 'Other', type: 'xtream',
    xtreamServer: 'http://other.invalid', xtreamUsername: 'x', xtreamPassword: 'y'
  });
  assert.ok(res.headers.get('location').includes('notice=exists'));
  assert.strictEqual(JSON.stringify(rows), before, 'the existing playlist is exactly as it was');
});

check('a half-filled playlist is refused and nothing is added', async () => {
  rows = [];
  for (const fields of [
    { mac: '1A:2B:3C:4D:5E:6F', playlistName: 'Home', type: 'xtream', xtreamServer: 'http://example.invalid' },
    { mac: '1A:2B:3C:4D:5E:6F', type: 'm3u', m3uUrl: 'http://example.invalid/list.m3u' },
    { mac: '1A:2B:3C:4D:5E:6F', playlistName: 'Home', type: 'm3u' }
  ]) {
    const res = await addForm(fields);
    assert.ok(res.headers.get('location').includes('notice=bad-playlist'), JSON.stringify(fields));
  }
  assert.strictEqual(rows.length, 0);
});

function post(pathname, fields) {
  return fetch(`${base}${pathname}`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields || {}).toString(),
    redirect: 'manual'
  });
}

const SECOND = { playlistName: 'Second', type: 'm3u', m3uUrl: 'http://example.invalid/second.m3u' };

check('a device with two playlists gets the first at the top level, as before, and both in playlists', async () => {
  seedAssigned();
  await post(`/admin/devices/${MAC}/assign2`, SECOND);
  const body = await poll(MAC, '123456');
  assert.strictEqual(body.status, 'assigned');
  assert.strictEqual(body.type, 'xtream', 'older apps read only these fields, and still get Playlist 1');
  assert.strictEqual(body.server, 'http://example.invalid');
  assert.strictEqual(body.playlists.length, 2);
  assert.deepStrictEqual(body.playlists[1], { type: 'm3u', name: 'Second', url: 'http://example.invalid/second.m3u' });
});

check('a device with only one playlist answers exactly as before, plus a one-entry playlists', async () => {
  seedAssigned();
  const body = await poll(MAC, '123456');
  const { playlists, ...old } = body;
  assert.deepStrictEqual(old, {
    status: 'assigned', type: 'xtream', name: rows[0].playlist_name,
    server: 'http://example.invalid', username: 'someone', password: 'secret'
  });
  assert.strictEqual(playlists.length, 1);
});

check('Delete profile with a Playlist 2 kept: the device stays assigned and Playlist 2 moves up', async () => {
  seedAssigned();
  await post(`/admin/devices/${MAC}/assign2`, SECOND);
  await post(`/admin/devices/${MAC}/delete-profile`);
  assert.strictEqual(rows[0].status, 'assigned');
  assert.strictEqual(rows[0].xtream_password, null, "Playlist 1's credentials are gone");
  const body = await poll(MAC, '123456');
  assert.strictEqual(body.type, 'm3u', 'an older app now gets Playlist 2 rather than nothing');
  assert.strictEqual(body.playlists.length, 1);
});

check('Delete playlist 2 erases only Playlist 2', async () => {
  seedAssigned();
  await post(`/admin/devices/${MAC}/assign2`, SECOND);
  const res = await post(`/admin/devices/${MAC}/delete-playlist2`);
  assert.strictEqual(res.status, 302);
  assert.strictEqual(rows[0].playlist2_type, null);
  assert.strictEqual(rows[0].playlist2_m3u_url, null);
  assert.strictEqual(rows[0].xtream_password, 'secret', 'Playlist 1 untouched');
  assert.strictEqual((await poll(MAC, '123456')).playlists.length, 1);
});

check('deleting both playlists makes the device pending again', async () => {
  seedAssigned();
  await post(`/admin/devices/${MAC}/assign2`, SECOND);
  await post(`/admin/devices/${MAC}/delete-profile`);
  await post(`/admin/devices/${MAC}/delete-playlist2`);
  assert.strictEqual(rows[0].status, 'pending');
  assert.deepStrictEqual(await poll(MAC, '123456'), { status: 'pending' });
});

check('a half-filled Playlist 2 is refused and nothing changes', async () => {
  seedAssigned();
  const before = JSON.stringify(rows);
  const res = await post(`/admin/devices/${MAC}/assign2`, { playlistName: 'Half', type: 'xtream', xtreamServer: 'http://x.invalid' });
  assert.ok(res.headers.get('location').includes('notice=bad-playlist2'));
  assert.strictEqual(JSON.stringify(rows), before);
});

check('the Playlist 2 routes need the admin password and a real MAC', async () => {
  seedAssigned();
  for (const p of [`/admin/devices/${MAC}/assign2`, `/admin/devices/${MAC}/delete-playlist2`]) {
    const res = await fetch(`${base}${p}`, { method: 'POST', redirect: 'manual' });
    assert.strictEqual(res.status, 401, `${p} needs authentication`);
  }
  assert.strictEqual((await post('/admin/devices/not-a-mac/assign2', SECOND)).status, 400);
  assert.strictEqual((await post('/admin/devices/not-a-mac/delete-playlist2')).status, 400);
});

check('the page shows a Playlist 2 form on every row, and its delete only where there is one', async () => {
  seedAssigned();
  let html = renderDevices(rows);
  assert.strictEqual((html.match(/\/assign2"/g) || []).length, 2);
  assert.ok(!html.includes('>Delete playlist 2<'));
  await post(`/admin/devices/${MAC}/assign2`, { ...SECOND, playlistName: "Kid's \"2\"" });
  html = renderDevices(rows);
  assert.strictEqual((html.match(/>Delete playlist 2</g) || []).length, 1);
  const confirm2 = (html.match(/onsubmit="return confirm\('[^"]*'\);"/g) || []).find((a) => a.includes('Delete playlist 2'));
  assert.ok(confirm2 && confirm2.includes("Kid\\'s"), 'the name is escaped inside the confirmation');
});

check('Playlist 2 is folded behind "+ Add playlist" until the device has one', async () => {
  seedAssigned();
  let html = renderDevices(rows);
  assert.strictEqual((html.match(/<details class="playlist2">/g) || []).length, 2, 'both rows closed');
  assert.strictEqual((html.match(/<summary>\+ Add playlist<\/summary>/g) || []).length, 2);
  assert.ok(!html.includes('<details class="playlist2" open>'));
  await post(`/admin/devices/${MAC}/assign2`, SECOND);
  html = renderDevices(rows);
  assert.strictEqual((html.match(/<details class="playlist2" open>/g) || []).length, 1, 'the row with a Playlist 2 shows it');
  assert.strictEqual((html.match(/<summary>\+ Add playlist<\/summary>/g) || []).length, 1, 'the other is still folded');
});

check('a bad MAC or key is refused, and Add device needs the password', async () => {
  rows = [];
  assert.ok((await addForm({ mac: '1A:2B:3C' })).headers.get('location').includes('notice=bad-mac'));
  assert.ok((await addForm({ mac: 'ZZ:2B:3C:4D:5E:6F' })).headers.get('location').includes('notice=bad-mac'));
  assert.ok((await addForm({ mac: '1A:2B:3C:4D:5E:6F', deviceKey: '12ab' })).headers.get('location').includes('notice=bad-key'));
  assert.strictEqual(rows.length, 0);
  const res = await fetch(`${base}/admin/devices/add`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: 'mac=1A2B3C4D5E6F',
    redirect: 'manual'
  });
  assert.strictEqual(res.status, 401);
  assert.strictEqual(rows.length, 0);
});

(async () => {
  const server = app.listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}`;

  let failed = 0;
  for (const [name, fn] of checks) {
    try {
      await fn();
      console.log(`  ok   ${name}`);
    } catch (e) {
      failed++;
      console.log(`  FAIL ${name}\n       ${e.message}`);
    }
  }
  server.close();
  console.log(failed === 0 ? `\nAll ${checks.length} checks passed.` : `\n${failed} of ${checks.length} failed.`);
  process.exit(failed === 0 ? 0 : 1);
})();
