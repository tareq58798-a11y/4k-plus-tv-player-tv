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
    if (existing) { existing.last_seen = new Date().toISOString(); return; }
    rows.push({
      mac, device_key: deviceKey, status: 'pending', playlist_type: null, playlist_name: null,
      m3u_url: null, xtream_server: null, xtream_username: null, xtream_password: null,
      last_seen: new Date().toISOString()
    });
  },
  deleteProfile: async (mac) => {
    const existing = rows.find((r) => r.mac === mac);
    if (!existing) return false;
    Object.assign(existing, {
      status: 'pending', playlist_type: null, playlist_name: null, m3u_url: null,
      xtream_server: null, xtream_username: null, xtream_password: null
    });
    return true;
  },
  deleteDevice: async (mac) => {
    const before = rows.length;
    rows = rows.filter((r) => r.mac !== mac);
    return rows.length < before;
  },
  assignM3u: async () => {},
  assignXtream: async () => {}
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
