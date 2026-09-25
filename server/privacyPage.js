/**
 * The privacy policy, at /privacy - the URL given to Samsung's Seller Office, which requires one for
 * an app that sends a device identifier anywhere.
 *
 * Every statement here was checked against the code rather than written from a template: what the
 * activation service stores is schema.sql and the /api/activate route; what the Samsung app sends
 * is web/src/platform/identity.ts and shared/activation.ts; the Android app's is
 * DeviceActivationClient.kt and DeviceIdentity.kt; what stays on the television is the apps' own
 * local storage. If any of that changes, this page has to change with it - a policy that says less
 * than the app does is worse than none.
 */
const SUPPORT_EMAIL = '4kplustv.support@gmail.com';
const UPDATED = '26 September 2026';

function privacyPage() {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>4K Plus TV Player - Privacy Policy</title>
<style>
  body { margin: 0; background: #041a2c; color: #e8f1f7; font: 17px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 760px; margin: 0 auto; padding: 40px 22px 64px; }
  h1 { font-size: 30px; margin: 0 0 4px; }
  h2 { font-size: 21px; margin: 34px 0 8px; color: #9fd4ef; }
  p, li { color: #d3e3ee; }
  ul { padding-left: 22px; }
  .updated { color: #8fb0c4; margin: 0 0 26px; }
  .summary { background: #0b2a43; border: 1px solid #1f4d6d; border-radius: 12px; padding: 16px 20px; }
  a { color: #7fd0ff; }
  code { background: #0b2a43; padding: 1px 6px; border-radius: 5px; }
</style>
</head>
<body>
<main>
<h1>Privacy Policy</h1>
<p class="updated">4K Plus TV Player &middot; Last updated ${UPDATED}</p>

<div class="summary">
<p><strong>In short:</strong> 4K Plus TV Player is a player for a TV service you already have. It
shows no ads, contains no analytics or tracking, and sells nothing about you. The only thing it
sends to us is an anonymous code for your television, so that a playlist can be assigned to it.
Your logins, favourites and viewing history stay on your television.</p>
</div>

<h2>Who we are</h2>
<p>This policy covers the 4K Plus TV Player apps for Samsung televisions and Android TV, and the
activation service they use. Questions and requests: <a href="mailto:${SUPPORT_EMAIL}">${SUPPORT_EMAIL}</a>.</p>

<h2>What leaves your television, and where it goes</h2>
<ul>
<li><strong>To us - a device code and a device key.</strong> The app shows a device code (written
like a network address, for example <code>26:B4:11:22:33:44</code>) and a six-digit device key, and
sends both to our activation service to ask whether a playlist has been assigned to this
television. On a Samsung television the code is made from the television's device ID (DUID) by a
one-way calculation, so the DUID itself cannot be recovered from it; if the television does not
provide a DUID, its network MAC address is used instead. On Android TV the code is made the same
way from the Android device ID. We store the code, the key, and the times the television was first
and last seen.</li>
<li><strong>To us - an assigned playlist, only if one is assigned.</strong> If you or your reseller
assign a playlist to your device code, we store that playlist's name and its address, or its server,
username and password, and send them to your television when it asks. They are entered by whoever
assigns the playlist; the app does not upload anything you type.</li>
<li><strong>To your TV provider.</strong> Channels, films and series come directly from the provider
you (or your reseller) set up. The app sends your provider login to that provider's server, as any
player for that service must. We do not receive it. The provider's own privacy policy applies to
what it does with your requests.</li>
<li><strong>To image servers.</strong> Posters and channel logos are loaded from the addresses your
provider's catalogue lists, often a film database such as TMDB. Those servers see your television's
IP address, as any website would.</li>
<li><strong>To YouTube, for trailers.</strong> When you press Trailer, the Samsung app plays it
through YouTube's embedded player on a page on our server, which is given only the YouTube video ID;
the Android app opens the YouTube app. Google's privacy policy applies to YouTube.</li>
<li><strong>Server logs.</strong> Our service runs on Render in the United States. Like any web
server it may keep short-lived technical logs of requests, including IP addresses, to operate and
secure the service. We do not use them to identify or profile you.</li>
</ul>

<h2>What stays on your television</h2>
<p>Your provider login if you type it in, your saved playlists, favourites, recently watched
channels and titles, playback positions, settings, and your parental-control PIN (stored only as a
one-way hash, never as the PIN itself). These are kept in the app's storage on the television and
are not sent to us. Uninstalling the app, or clearing its data, removes them.</p>

<h2>What we do not do</h2>
<ul>
<li>No advertising, and no advertising identifiers.</li>
<li>No analytics, crash-reporting or tracking services.</li>
<li>No selling, renting or sharing of any information with third parties for marketing.</li>
<li>No content of our own: the app plays only what your provider supplies.</li>
</ul>

<h2>Keeping and deleting</h2>
<p>A device code stays in the activation service until it is removed. To have yours, and any
playlist assigned to it, deleted, email <a href="mailto:${SUPPORT_EMAIL}">${SUPPORT_EMAIL}</a> with
the device code shown on the app's Home screen. We will delete it and confirm.</p>

<h2>Children</h2>
<p>The app is not directed at children under 13, and we do not knowingly collect information from
them. It includes parental controls: categories can be hidden or locked behind a PIN.</p>

<h2>Changes</h2>
<p>If what the app sends changes, this page will change first, and the date at the top will say
when.</p>
</main>
</body>
</html>`;
}

module.exports = { privacyPage, SUPPORT_EMAIL };
