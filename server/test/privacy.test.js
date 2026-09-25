// The privacy policy route that Samsung's Seller Office links to. Run with: node test/privacy.test.js
//
// db.js needs a real DATABASE_URL the moment it is required, so it is swapped for an empty stand-in
// exactly as dashboard.test.js does; this route never touches it.
const assert = require('assert');
const Module = require('module');

const originalLoad = Module._load;
Module._load = function (request) {
  if (request === './db') return {};
  return originalLoad.apply(this, arguments);
};

const app = require('../index.js');
const { SUPPORT_EMAIL } = require('../privacyPage');

(async () => {
  const server = app.listen(0);
  const { port } = server.address();
  try {
    const res = await fetch(`http://127.0.0.1:${port}/privacy`);
    assert.strictEqual(res.status, 200);
    assert.match(res.headers.get('content-type'), /text\/html/);
    const body = await res.text();
    assert.ok(body.includes('<title>4K Plus TV Player - Privacy Policy</title>'), 'has its title');
    assert.ok(body.includes(`mailto:${SUPPORT_EMAIL}`), 'names a contact address');
    // The things the policy promises must be the things the code does - spot-check the key ones.
    assert.ok(body.includes('device code') && body.includes('device key'), 'says what is sent');
    assert.ok(/No analytics/.test(body), 'says there is no tracking');
    console.log(`privacy page ok (${body.length} bytes)`);

    // The Korean translation Seller Office also requires, and the links between the two.
    assert.ok(body.includes('href="/privacy/ko"'), 'English page links the Korean one');
    const ko = await fetch(`http://127.0.0.1:${port}/privacy/ko`);
    assert.strictEqual(ko.status, 200);
    assert.match(ko.headers.get('content-type'), /charset=utf-8/);
    const koBody = await ko.text();
    assert.ok(koBody.includes('<html lang="ko">'), 'Korean page is marked Korean');
    assert.ok(koBody.includes('개인정보 처리방침'), 'Korean title arrives intact as UTF-8');
    assert.ok(koBody.includes(`mailto:${SUPPORT_EMAIL}`), 'Korean page has the same contact address');
    assert.ok(koBody.includes('href="/privacy"'), 'Korean page links back to English');
    // The same sections, in the same order, as the English page.
    assert.strictEqual((koBody.match(/<h2>/g) || []).length, (body.match(/<h2>/g) || []).length, 'same number of sections');
    console.log(`korean privacy page ok (${koBody.length} chars)`);

    // The homepage Seller Office links to, which is also Render's health check.
    const home = await fetch(`http://127.0.0.1:${port}/`);
    assert.strictEqual(home.status, 200);
    const homeBody = await home.text();
    assert.ok(homeBody.includes('<title>4K Plus TV Player</title>'), 'homepage has its title');
    assert.ok(homeBody.includes('href="/privacy"'), 'homepage links the privacy policy');
    assert.ok(homeBody.includes(`mailto:${SUPPORT_EMAIL}`), 'homepage uses the same contact address');
    assert.ok(/player only/.test(homeBody), 'homepage says the app provides no content');
    console.log(`homepage ok (${homeBody.length} bytes)`);
  } finally {
    server.close();
  }
})().catch((err) => { console.error(err); process.exit(1); });
