/**
 * The app's homepage, at / - the "Seller Homepage" Samsung's Seller Office requires.
 *
 * It replaced a bare "activation server is running" line, which is what a reviewer following the
 * link would otherwise have found. Render's health check also requests /, and needs nothing from it
 * but a 200.
 *
 * Says what the app is without naming any provider or promising any content, the same way the store
 * description does: the app plays what the viewer's own service supplies and provides none itself.
 * The contact address is the privacy page's, so the two cannot disagree.
 */
const { SUPPORT_EMAIL } = require('./privacyPage');

function homePage() {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>4K Plus TV Player</title>
<style>
  body { margin: 0; background: #041a2c; color: #e8f1f7; font: 17px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 760px; margin: 0 auto; padding: 48px 22px 64px; }
  h1 { font-size: 36px; margin: 0 0 6px; }
  .tagline { color: #9fd4ef; font-size: 20px; margin: 0 0 30px; }
  h2 { font-size: 21px; margin: 32px 0 8px; color: #9fd4ef; }
  p, li { color: #d3e3ee; }
  ul { padding-left: 22px; }
  .note { background: #0b2a43; border: 1px solid #1f4d6d; border-radius: 12px; padding: 14px 20px; }
  a { color: #7fd0ff; }
  footer { margin-top: 44px; color: #8fb0c4; font-size: 15px; }
</style>
</head>
<body>
<main>
<h1>4K Plus TV Player</h1>
<p class="tagline">A clean, fast player for the TV service you already have.</p>

<p>4K Plus TV Player brings your live channels, films and series to the big screen in one simple
app, with a programme guide, favourites, continue watching and parental controls. It is available
for Samsung smart TVs and Android TV.</p>

<div class="note">
<p><strong>Please note:</strong> 4K Plus TV Player is a player only. It does not include, sell or
provide any channels or content. To use it you need an existing subscription from a TV service
provider.</p>
</div>

<h2>Features</h2>
<ul>
<li>Live TV with a now-and-next programme guide</li>
<li>Films and series with artwork, details and resume where you left off</li>
<li>Search across your whole library</li>
<li>Favourites and recently watched</li>
<li>Parental controls: hide or PIN-lock categories</li>
<li>Available in English, Arabic, French, Hindi, Russian, Spanish, Turkish and Urdu</li>
</ul>

<h2>Getting started</h2>
<p>Open the app and add your service's details, or give your provider the device code and device
key shown on the app's Home screen so they can set it up for you.</p>

<h2>Support</h2>
<p>Email <a href="mailto:${SUPPORT_EMAIL}">${SUPPORT_EMAIL}</a>. If you are writing about a
particular television, include the device code shown on the app's Home screen.</p>

<footer>
<a href="/privacy">Privacy Policy</a>
</footer>
</main>
</body>
</html>`;
}

module.exports = { homePage };
