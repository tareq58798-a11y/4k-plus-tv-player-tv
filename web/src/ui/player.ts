/**
 * The controls drawn over a playing stream, ported from the television app's player chrome.
 *
 * On Samsung the video is not in the page - it is on a hardware plane *behind* it, and the page is
 * transparent over the top. So this is not a `<video>` with controls; it is an overlay that knows
 * the position and duration only because the player reports them, and every drawing decision has
 * to assume there is a moving picture underneath. That is why nearly everything here sits on a
 * scrim and why focus is a fill rather than an outline: an outline has to win against whatever
 * frame happens to be behind it, and some frames are white.
 *
 * The stops, and the order Down walks them, are the ones the remote user learned on Android:
 * transport row, then the timeline, then settings. Down from the last stop does nothing rather
 * than wrapping, because a remote user cannot see that they have reached the end of a list except
 * by the highlight refusing to move.
 */
import { focus } from './focus';
import { t } from '../shared/i18n';
import type { MediaPlayer } from '../platform/video';
import type { RemoteKey } from '../platform/keys';

/** How long the controls stay up after the last press. Matches CONTROLS_TIMEOUT_MS on Android. */
const CONTROLS_TIMEOUT_MS = 5000;

/** Offered rates. 1 first so the resting state is the one most people want back. */
const SPEEDS = [1, 0.5, 0.75, 1.25, 1.5, 2];

export interface PlayerOverlayOptions {
  title: string;
  player: MediaPlayer;
  skipSeconds: number;
  /** Called when the viewer leaves, with the position to remember. */
  onExit: (positionMs: number) => void;
}

export interface PlayerOverlay {
  readonly element: HTMLElement;
  /** Feeds a remote press in. Returns true when the overlay consumed it. */
  handleKey(key: RemoteKey): boolean;
  setPosition(positionMs: number, durationMs: number): void;
  setPaused(paused: boolean): void;
  setMessage(message: string): void;
  destroy(): void;
}

function clockOf(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) ms = 0;
  const total = Math.floor(ms / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number): string => String(value).padStart(2, '0');
  // Hours only when there are any. A 22-minute episode reading 00:22:14 makes the viewer parse a
  // field that is always zero.
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

/**
 * The glyphs, as paths on a 24x24 grid.
 *
 * Drawn rather than typed. The obvious way to write a play button is the character U+25B6, and the
 * obvious way to write rewind is U+23EA - but those are emoji on most engines, so they arrive in
 * somebody else's colour, at somebody else's weight, and on an older WebKit they may not arrive at
 * all. A path renders identically on every set and takes `currentColor`, which is what lets the
 * focused state recolour the icon along with the button.
 */
const ICONS = {
  rewind: 'M11 7v10l-8-5 8-5zm10 0v10l-8-5 8-5z',
  forward: 'M13 7v10l8-5-8-5zM3 7v10l8-5-8-5z',
  play: 'M8 5v14l11-7L8 5z',
  pause: 'M6 5h4v14H6V5zm8 0h4v14h-4V5z',
  settings: 'M19.4 13a7.8 7.8 0 000-2l2.1-1.6-2-3.4-2.5 1a7.6 7.6 0 00-1.7-1L15 3H9l-.3 2.9a7.6 7.6 0 00-1.7 1l-2.5-1-2 3.4L4.6 11a7.8 7.8 0 000 2l-2.1 1.6 2 3.4 2.5-1c.5.4 1.1.8 1.7 1L9 21h6l.3-2.9c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.4L19.4 13zM12 15.5A3.5 3.5 0 1112 8.5a3.5 3.5 0 010 7z',
} as const;

function icon(path: string): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  const node = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  node.setAttribute('d', path);
  // currentColor, so the focused state recolours the icon with the button rather than needing a
  // second rule that has to be kept in step with the first.
  node.setAttribute('fill', 'currentColor');
  svg.append(node);
  return svg;
}

function button(id: string, label: string, path: string): HTMLElement {
  const node = document.createElement('div');
  node.className = 'pc-button';
  node.tabIndex = -1;
  node.append(icon(path));
  node.setAttribute('data-focus', '');
  node.setAttribute('data-focus-id', id);
  node.setAttribute('aria-label', label);
  return node;
}

/** Swaps the glyph inside an existing button, for play becoming pause and back. */
function setIcon(node: HTMLElement, path: string): void {
  node.replaceChildren(icon(path));
}

export function createPlayerOverlay(options: PlayerOverlayOptions): PlayerOverlay {
  const { player, skipSeconds } = options;

  const root = document.createElement('div');
  root.className = 'player-chrome';

  const title = document.createElement('div');
  title.className = 'pc-title';
  title.textContent = options.title;
  root.append(title);

  const message = document.createElement('div');
  message.className = 'pc-message';
  root.append(message);

  // ---------------------------------------------------------------- transport
  const transport = document.createElement('div');
  transport.className = 'pc-transport';
  const rewind = button('pc-rewind', t('cd_rewind'), ICONS.rewind);
  const playPause = button('pc-playpause', t('play_action'), ICONS.pause);
  const forward = button('pc-forward', t('cd_forward'), ICONS.forward);
  transport.append(rewind, playPause, forward);
  root.append(transport);

  // ----------------------------------------------------------------- timeline
  const bar = document.createElement('div');
  bar.className = 'pc-bar';

  const elapsed = document.createElement('span');
  elapsed.className = 'pc-time';
  elapsed.textContent = '0:00';

  const track = document.createElement('div');
  track.className = 'pc-track';
  track.tabIndex = -1;
  track.setAttribute('data-focus', '');
  track.setAttribute('data-focus-id', 'pc-track');

  const played = document.createElement('div');
  played.className = 'pc-played';
  // The thumb the viewer asked to be able to see. It is a child of the played portion so it moves
  // with it rather than needing its own arithmetic.
  const thumb = document.createElement('div');
  thumb.className = 'pc-thumb';
  played.append(thumb);
  track.append(played);

  const total = document.createElement('span');
  total.className = 'pc-time';
  total.textContent = '0:00';

  bar.append(elapsed, track, total);
  root.append(bar);

  // ----------------------------------------------------------------- settings
  const tools = document.createElement('div');
  tools.className = 'pc-tools';
  const settingsButton = button('pc-settings', t('settings_title'), ICONS.settings);
  tools.append(settingsButton);
  root.append(tools);

  const panel = document.createElement('div');
  panel.className = 'pc-panel';
  panel.hidden = true;

  /*
   * Soundtracks, built when the panel opens rather than now.
   *
   * A decoder cannot say what is in a stream it has not opened, so asking at construction time
   * reliably returns nothing. The panel is the first moment the answer is both available and
   * wanted. An empty list means a single-language file, and then this section is absent rather
   * than empty - a menu of one invites the viewer to open it, read it, and learn nothing.
   */
  const audioSection = document.createElement('div');
  audioSection.hidden = true;
  const audioTitle = document.createElement('div');
  audioTitle.className = 'pc-panel-title';
  audioTitle.textContent = t('audio_track');
  const audioRow = document.createElement('div');
  audioRow.className = 'pc-speeds';
  audioSection.append(audioTitle, audioRow);
  panel.append(audioSection);

  let selectedAudio: number | null = null;

  function buildAudioOptions(): void {
    const tracks = player.audioTracks();
    audioRow.textContent = '';
    audioSection.hidden = tracks.length === 0;
    if (!tracks.length) return;
    if (selectedAudio === null) selectedAudio = tracks[0]?.id ?? null;
    for (const track of tracks) {
      const option = document.createElement('div');
      option.className = 'pc-speed';
      option.tabIndex = -1;
      option.textContent = track.label;
      option.setAttribute('data-focus', '');
      option.setAttribute('data-focus-id', `pc-audio-${track.id}`);
      option.setAttribute('aria-selected', String(track.id === selectedAudio));
      option.addEventListener('click', () => {
        selectedAudio = track.id;
        player.selectAudioTrack(track.id);
        for (const other of audioRow.querySelectorAll('.pc-speed')) {
          other.setAttribute('aria-selected', String(other === option));
        }
      });
      audioRow.append(option);
    }
  }

  const panelTitle = document.createElement('div');
  panelTitle.className = 'pc-panel-title';
  panelTitle.textContent = t('playback_speed');
  panel.append(panelTitle);

  let speed = 1;
  const speedRow = document.createElement('div');
  speedRow.className = 'pc-speeds';
  for (const rate of SPEEDS) {
    const option = document.createElement('div');
    option.className = 'pc-speed';
    option.tabIndex = -1;
    option.textContent = rate === 1 ? t('speed_normal') : `${rate}x`;
    option.setAttribute('data-focus', '');
    option.setAttribute('data-focus-id', `pc-speed-${rate}`);
    option.setAttribute('aria-selected', String(rate === speed));
    option.addEventListener('click', () => {
      speed = rate;
      player.setSpeed(rate);
      for (const other of speedRow.querySelectorAll('.pc-speed')) {
        other.setAttribute('aria-selected', String(other === option));
      }
    });
    speedRow.append(option);
  }
  panel.append(speedRow);
  root.append(panel);

  // ------------------------------------------------------------------- state
  let visible = true;
  let paused = false;
  let positionMs = 0;
  let durationMs = 0;
  let hideTimer: number | null = null;
  let panelOpen = false;

  function armHide(): void {
    if (hideTimer !== null) window.clearTimeout(hideTimer);
    hideTimer = window.setTimeout(() => {
      // Never while the settings panel is open: the viewer is reading it, not idle.
      if (panelOpen) { armHide(); return; }
      setVisible(false);
    }, CONTROLS_TIMEOUT_MS);
  }

  function setVisible(next: boolean): void {
    visible = next;
    root.classList.toggle('is-hidden', !next);
    if (next) {
      armHide();
    } else {
      closePanel();
      // Focus goes nowhere when the chrome is down. Leaving it on a hidden control means the next
      // press acts on something invisible.
      focus(null);
    }
  }

  function openPanel(): void {
    panelOpen = true;
    panel.hidden = false;
    // Rebuilt every time it opens: switching episode replaces the stream, and with it the
    // soundtracks. A list cached from the last thing played would offer choices that no longer
    // exist.
    buildAudioOptions();
    focus(
      audioSection.hidden
        ? speedRow.querySelector<HTMLElement>('.pc-speed')
        : audioRow.querySelector<HTMLElement>('.pc-speed'),
    );
  }

  function closePanel(): void {
    panelOpen = false;
    panel.hidden = true;
  }

  // The order Down walks. Derived from what currently holds focus rather than counted, for the
  // reason given in advanceDownThroughControls on Android: a counter drifts the moment anything
  // else moves the highlight, and the viewer has no way to get it back in step.
  function stepDown(): boolean {
    const here = document.activeElement;
    if (panelOpen) return true;
    if (here === track) { focus(settingsButton); return true; }
    if (here === settingsButton) { openPanel(); return true; }
    focus(track);
    return true;
  }

  function stepUp(): boolean {
    if (panelOpen) { closePanel(); focus(settingsButton); return true; }
    const here = document.activeElement;
    if (here === settingsButton) { focus(track); return true; }
    if (here === track) { focus(playPause); return true; }
    return true;
  }

  /**
   * Moves along the timeline by [deltaMs], clamped to the recording.
   *
   * Absolute rather than relative on purpose. Held down, a relative seek asks the player where it
   * is between every press, and the player is still moving - so the jumps come out uneven and the
   * end of a long press lands somewhere nobody chose. Working from the position the bar is already
   * drawing keeps each press worth exactly one step, and clamping stops a run of presses at the
   * end of a film from asking for a position past it.
   */
  function scrub(deltaMs: number): void {
    const limit = durationMs > 0 ? durationMs : Number.MAX_SAFE_INTEGER;
    const target = Math.min(limit, Math.max(0, positionMs + deltaMs));
    positionMs = target;
    player.seekTo(target);
    // Redrawn now rather than waiting for the next progress tick, which can be a second away -
    // long enough for a press to feel like it did nothing.
    elapsed.textContent = clockOf(target);
    if (durationMs > 0) played.style.width = `${(target / durationMs) * 100}%`;
  }

  function togglePlayback(): void {
    if (paused) {
      player.resume();
    } else {
      player.pause();
    }
  }

  rewind.addEventListener('click', () => player.seekBy(-skipSeconds * 1000));
  forward.addEventListener('click', () => player.seekBy(skipSeconds * 1000));
  playPause.addEventListener('click', togglePlayback);
  settingsButton.addEventListener('click', openPanel);

  function handleKey(key: RemoteKey): boolean {
    // Any press brings the chrome back rather than acting, so nothing happens unseen. The one
    // exception is Back, which leaves whether or not the controls are up.
    if (key === 'back') {
      if (panelOpen) { closePanel(); focus(settingsButton); return true; }
      if (visible) { setVisible(false); return true; }
      options.onExit(positionMs);
      return true;
    }
    if (!visible) {
      setVisible(true);
      focus(playPause);
      return true;
    }
    armHide();

    switch (key) {
      case 'down':
        return stepDown();
      case 'up':
        return stepUp();
      case 'left':
        if (document.activeElement === track) { scrub(-skipSeconds * 1000); return true; }
        return false;
      case 'right':
        if (document.activeElement === track) { scrub(skipSeconds * 1000); return true; }
        return false;
      case 'enter':
        (document.activeElement as HTMLElement | null)?.click();
        return true;
      case 'playpause':
      case 'play':
      case 'pause':
        togglePlayback();
        return true;
      case 'rewind':
        player.seekBy(-skipSeconds * 1000);
        return true;
      case 'forward':
        player.seekBy(skipSeconds * 1000);
        return true;
      default:
        return false;
    }
  }

  setVisible(true);
  focus(playPause);

  return {
    element: root,
    handleKey,
    setPosition(next: number, length: number): void {
      positionMs = next;
      durationMs = length;
      elapsed.textContent = clockOf(next);
      total.textContent = clockOf(length);
      const fraction = length > 0 ? Math.min(1, Math.max(0, next / length)) : 0;
      played.style.width = `${fraction * 100}%`;
    },
    setPaused(next: boolean): void {
      paused = next;
      setIcon(playPause, next ? ICONS.play : ICONS.pause);
    },
    setMessage(text: string): void {
      message.textContent = text;
      // A message means something went wrong, and the viewer cannot read it if the chrome is on
      // its way out.
      if (text) setVisible(true);
    },
    destroy(): void {
      if (hideTimer !== null) window.clearTimeout(hideTimer);
      root.remove();
    },
  };
}
