/**
 * The page the television app plays a trailer in, at /trailer?v=<YouTube video id>.
 *
 * A trailer is played inside the app rather than by handing it to the YouTube app, so Back returns
 * straight to the film's page. YouTube only allows its videos to be shown through its own embedded
 * player, and that player now refuses to play in a page that has no web address of its own to
 * report (error 153) - which is exactly what a Samsung app's pages are. So the app puts this page
 * in a frame and this page holds the player: it has an address, and it is ours.
 *
 * It is given nothing but the video id, which the provider supplied as the title's trailer. No
 * login, no stream, nothing about the viewer. It tells the app what the player is doing, and takes
 * play, pause and seek from it, because the app has to keep the remote's keys for itself - a frame
 * holding the keys could not be left with Back. The Samsung app frames it and talks by postMessage;
 * the Android app (TrailerActivity.kt) opens it in a WebView and hears it through the
 * FourKPlusTrailer bridge, sending its commands as postMessage to the page itself.
 */
const VIDEO_ID = /^[\w-]{11}$/;

function trailerPage(videoId) {
  // Validated before this is called; embedded as JSON so nothing in it can break out of the script.
  const id = JSON.stringify(videoId);
  return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<title>Trailer</title>
<style>
  html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
  #player { position: fixed; inset: 0; width: 100%; height: 100%; }
</style>
</head>
<body>
<div id="player"></div>
<script>
  var player = null;
  function tell(message) {
    var full = Object.assign({ source: 'fourkplus-trailer' }, message);
    // Framed by the Samsung app, which listens for postMessage.
    if (window.parent !== window) {
      try { window.parent.postMessage(full, '*'); } catch (e) {}
    }
    // Opened directly by the Android app, which gives the page this bridge instead.
    try { if (window.FourKPlusTrailer) window.FourKPlusTrailer.post(JSON.stringify(full)); } catch (e) {}
  }
  function onYouTubeIframeAPIReady() {
    player = new YT.Player('player', {
      videoId: ${id},
      playerVars: { autoplay: 1, controls: 0, rel: 0, playsinline: 1, iv_load_policy: 3, modestbranding: 1, fs: 0, disablekb: 1, origin: location.origin },
      events: {
        onReady: function (event) { event.target.playVideo(); tell({ type: 'ready' }); },
        onStateChange: function (event) {
          var states = { '-1': 'unstarted', 0: 'ended', 1: 'playing', 2: 'paused', 3: 'buffering', 5: 'cued' };
          tell({ type: 'state', state: states[event.data] || String(event.data) });
        },
        onError: function (event) { tell({ type: 'error', code: event.data }); }
      }
    });
  }
  window.addEventListener('message', function (event) {
    var command = event.data || {};
    if (!player || command.target !== 'fourkplus-trailer') return;
    try {
      if (command.action === 'toggle') {
        if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo();
      } else if (command.action === 'seek') {
        player.seekTo(Math.max(0, player.getCurrentTime() + Number(command.seconds || 0)), true);
      }
    } catch (e) {}
  });
  setInterval(function () {
    try {
      if (player && player.getDuration) tell({ type: 'time', position: player.getCurrentTime(), duration: player.getDuration() });
    } catch (e) {}
  }, 1000);
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body>
</html>`;
}

module.exports = { VIDEO_ID, trailerPage };
