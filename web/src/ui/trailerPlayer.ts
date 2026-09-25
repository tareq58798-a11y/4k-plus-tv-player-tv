/**
 * A trailer played inside the app.
 *
 * Handing the trailer to the YouTube app left Back to YouTube, which steps back through its own
 * screens before it gives the viewer back. Here the trailer is a layer over the film's or series'
 * page, and one press of Back takes it away.
 *
 * YouTube's embedded player is the only way its videos may be shown in another app, and it will
 * not play in a page with no web address of its own, which a Samsung app's page is. So the player
 * lives on a page the activation server serves (server/trailerPage.js), framed here. That page is
 * given the YouTube id and nothing else. It reports what the player is doing by postMessage, and
 * takes pause, play and seek the same way; the keys stay with this page throughout, because a
 * frame holding the remote could not be left with Back.
 *
 * If the trailer will not play here - YouTube refuses it, or nothing has started after a while,
 * which is also what a server still waking from sleep looks like - the viewer is told, and OK
 * opens it in the YouTube app instead, as before.
 */
import { t } from '../shared/i18n';
import { openTrailer } from '../platform/youtube';
import { focus, pushKeyHandler } from './focus';

/** Where the player page is: the activation server, which already answers the app. */
const TRAILER_PAGE = 'https://fourk-plus-tv-player.onrender.com/trailer';

/** How long to wait for the trailer to start before offering the YouTube app. */
const START_TIMEOUT_MS = 25_000;

const SEEK_SECONDS = 10;

export function playTrailer(videoId: string, title: string): void {
  const returnTo = document.activeElement as HTMLElement | null;

  const layer = document.createElement('div');
  layer.className = 'trailer-layer';
  // Holds the highlight so the remote's keys come to this page and never to the frame.
  layer.tabIndex = -1;
  layer.setAttribute('data-focus', '');
  layer.setAttribute('data-focus-id', 'trailer');

  const frame = document.createElement('iframe');
  frame.className = 'trailer-frame';
  frame.tabIndex = -1;
  frame.setAttribute('allow', 'autoplay; encrypted-media');
  frame.src = `${TRAILER_PAGE}?v=${encodeURIComponent(videoId)}`;

  const heading = document.createElement('div');
  heading.className = 'trailer-title';
  heading.textContent = `${t('trailer_label')} · ${title}`;
  const status = document.createElement('div');
  status.className = 'trailer-status';
  status.textContent = t('trailer_loading');

  layer.append(frame, heading, status);
  document.body.append(layer);
  focus(layer);

  let started = false;
  let failed = false;

  function fail(): void {
    if (started) return;
    failed = true;
    status.textContent = `${t('trailer_failed_here')} ${t('trailer_open_youtube_ok')}`;
    status.classList.add('visible');
  }

  const timeout = window.setTimeout(fail, START_TIMEOUT_MS);

  function send(action: string, extra: Record<string, unknown> = {}): void {
    frame.contentWindow?.postMessage({ target: 'fourkplus-trailer', action, ...extra }, '*');
  }

  const onMessage = (event: MessageEvent): void => {
    if (event.source !== frame.contentWindow) return;
    const data = event.data as { source?: string; type?: string; state?: string } | null;
    if (!data || data.source !== 'fourkplus-trailer') return;
    if (data.type === 'error') fail();
    if (data.type === 'state') {
      if (data.state === 'playing') {
        started = true;
        failed = false;
        status.textContent = '';
        status.classList.remove('visible');
        heading.classList.add('fading');
      }
      if (data.state === 'paused') heading.classList.remove('fading');
      if (data.state === 'ended') close();
    }
  };
  window.addEventListener('message', onMessage);

  // The frame taking focus would take the remote with it; take it straight back.
  const onBlur = (): void => {
    window.setTimeout(() => {
      if (layer.isConnected && document.activeElement !== layer) focus(layer);
    }, 0);
  };
  window.addEventListener('blur', onBlur);

  const release = pushKeyHandler((key) => {
    if (key === 'back' || key === 'stop') {
      close();
      return true;
    }
    if (key === 'enter' || key === 'playpause' || key === 'play' || key === 'pause') {
      if (failed) {
        close();
        void openTrailer(videoId);
      } else {
        send('toggle');
        heading.classList.remove('fading');
      }
      return true;
    }
    if (key === 'left' || key === 'rewind') send('seek', { seconds: -SEEK_SECONDS });
    if (key === 'right' || key === 'forward') send('seek', { seconds: SEEK_SECONDS });
    // Nothing else reaches the page underneath while the trailer is up.
    return true;
  });

  function close(): void {
    window.clearTimeout(timeout);
    window.removeEventListener('message', onMessage);
    window.removeEventListener('blur', onBlur);
    release();
    // Emptied first, so the frame's video stops at once rather than when it is collected.
    frame.src = 'about:blank';
    layer.remove();
    if (returnTo?.isConnected) focus(returnTo);
  }
}
