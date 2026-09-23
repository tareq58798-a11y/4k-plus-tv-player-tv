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
import {
  SKIP_CHOICES,
  setSkipSeconds,
  setSubtitleBackground,
  setVideoScaling,
  skipSeconds,
  subtitleBackground,
  videoScaling,
  type VideoScalingPreference,
} from '../shared/preferences';
import type { MediaPlayer } from '../platform/video';
import type { RemoteKey } from '../platform/keys';

/** How long the controls stay up after the last press. Matches CONTROLS_TIMEOUT_MS on Android. */
const CONTROLS_TIMEOUT_MS = 5000;

/** Offered rates. 1 first so the resting state is the one most people want back. */
const SPEEDS = [1, 0.5, 0.75, 1.25, 1.5, 2];

/** One entry in the strip: enough to draw it and to play it. */
export interface StripEpisode {
  id: string;
  label: string;
  thumbnailUrl: string | null;
  streamUrl: string;
}

export interface PlayerOverlayOptions {
  title: string;
  player: MediaPlayer;
  /**
   * A channel rather than a recording, which changes what the controls are.
   *
   * There is nothing to seek through and no end to run towards, so the timeline, its two clocks
   * and the skip buttons are all answering questions a live stream cannot be asked. The
   * television app settles this by turning media3's controller off entirely for Live TV.
   */
  live?: boolean;
  /**
   * The rest of the season, empty for a film.
   *
   * Its presence is what makes Down mean "show me the episodes" rather than "walk the controls" -
   * see stepDown. A film has nothing under the controls worth reaching, so there the walk is
   * right; a series has the rest of the season, and making somebody press three times to see it
   * puts the commonest thing they want furthest away.
   */
  episodes?: StripEpisode[];
  /** Which of [episodes] is playing, so the strip can mark it. */
  currentEpisodeId?: string | null;
  onEpisode?: (episode: StripEpisode) => void;
  /** Called when the viewer leaves, with the position to remember. */
  onExit: (positionMs: number) => void;
}

export interface PlayerOverlay {
  readonly element: HTMLElement;
  /**
   * Puts the highlight on the first control - the play button, or the settings gear on a channel.
   *
   * Called by the caller after the overlay is in the page rather than done here, because an
   * element that is not in a document cannot take focus. Doing it in the constructor failed
   * silently, and the player opened with nothing highlighted until the first press woke it up.
   */
  focusFirst(): void;
  /** Feeds a remote press in. Returns true when the overlay consumed it. */
  handleKey(key: RemoteKey): boolean;
  setPosition(positionMs: number, durationMs: number): void;
  setPaused(paused: boolean): void;
  /** A line of subtitle, or an empty string to clear it. */
  setCaption(text: string): void;
  /**
   * What is on now and next, for a channel. Ignored for a recording.
   *
   * Null for either line leaves that line out rather than showing an empty one - a channel whose
   * panel carries no listings should look like a channel with no listings, not like one whose
   * listings failed to load.
   */
  setGuide(now: string | null, next: string | null, progress: number | null): void;
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

/**
 * Swaps the glyph inside an existing button, for play becoming pause and back.
 *
 * Not replaceChildren, which arrived in Chromium 86 and would throw on anything older than a 2023
 * set - taking the play button with it on the first press. The floor this app declares is Tizen
 * 5.5, which is Chromium 69.
 */
function setIcon(node: HTMLElement, path: string): void {
  while (node.firstChild) node.removeChild(node.firstChild);
  node.append(icon(path));
}

export function createPlayerOverlay(options: PlayerOverlayOptions): PlayerOverlay {
  const { player, live = false } = options;
  // Read once here and updated by the panel below, so a change takes effect on the next press
  // rather than on the next film.
  let skip = skipSeconds();

  /*
   * Two layers, not one.
   *
   * The controls fade out after five seconds, and a fade is an opacity on the element that holds
   * them - which every child inherits and none can opt out of, because opacity creates a group.
   * Subtitles must not fade with the controls, so they sit in this outer layer, which never fades,
   * and everything that does fade sits in `chrome` inside it.
   */
  const root = document.createElement('div');
  root.className = 'player-layer';

  const chrome = document.createElement('div');
  chrome.className = 'player-chrome';
  root.append(chrome);

  const title = document.createElement('div');
  title.className = 'pc-title';
  title.textContent = options.title;
  chrome.append(title);

  /*
   * What is on, for a channel.
   *
   * The listings sit beside the channel list right up until somebody commits to watching, and then
   * they disappear - which is the moment they are most wanted, because the name of the channel is
   * no longer the question. A recording has no counterpart to this, so it is built only for live.
   */
  const guide = document.createElement('div');
  guide.className = 'pc-guide';
  guide.hidden = true;
  const guideNow = document.createElement('div');
  guideNow.className = 'pc-guide-now';
  const guideBar = document.createElement('div');
  guideBar.className = 'pc-guide-bar';
  const guideFill = document.createElement('div');
  guideFill.className = 'pc-guide-fill';
  guideBar.append(guideFill);
  const guideNext = document.createElement('div');
  guideNext.className = 'pc-guide-next';
  guide.append(guideNow, guideBar, guideNext);
  if (live) chrome.append(guide);

  const message = document.createElement('div');
  message.className = 'pc-message';
  chrome.append(message);

  // Subtitles are painted here because AVPlay hands over the text rather than drawing it - see
  // the 'subtitle' event. Outside the chrome that hides itself: the controls withdraw after five
  // seconds and the subtitles must not go with them.
  const captions = document.createElement('div');
  captions.className = 'pc-captions';
  // The line goes in a span rather than straight on the box, so the optional dark backing can hug
  // the words instead of drawing a slab the full width of the screen - see .pc-caption-text.
  const captionText = document.createElement('span');
  captionText.className = 'pc-caption-text';
  captions.append(captionText);
  root.append(captions);

  // ---------------------------------------------------------------- transport
  const transport = document.createElement('div');
  transport.className = 'pc-transport';
  const rewind = button('pc-rewind', t('cd_rewind'), ICONS.rewind);
  const playPause = button('pc-playpause', t('play_action'), ICONS.pause);
  const forward = button('pc-forward', t('cd_forward'), ICONS.forward);
  /*
   * A channel has no transport row at all, which is what the television app means by turning
   * media3's controller off for Live TV.
   *
   * Skipping needs a position to skip from and pausing needs something to come back to, and a
   * broadcast has neither - a paused channel is a still frame that falls further behind for as
   * long as it is held, and the button that got you there is the only way back out of it.
   * Everything a viewer legitimately wants here - the soundtrack, the subtitles, how the picture
   * fills the screen - is in the settings panel, which stays.
   */
  if (!live) {
    transport.append(rewind, playPause, forward);
    chrome.append(transport);
  }

  /** Where the highlight goes when the controls come up. A channel has no play button. */
  const firstStop = (): HTMLElement => (live ? settingsButton : playPause);

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
  // Left out of the page rather than hidden, so the timeline is not a focus stop that cannot be
  // seen - see stepDown, which walks what is actually there.
  if (!live) chrome.append(bar);

  // ----------------------------------------------------------------- settings
  const tools = document.createElement('div');
  tools.className = 'pc-tools';
  const settingsButton = button('pc-settings', t('settings_title'), ICONS.settings);
  tools.append(settingsButton);
  chrome.append(tools);

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

  /**
   * Subtitles: Off, then whatever the stream carries.
   *
   * Built when the panel opens, like the soundtracks and for the same reason - a decoder cannot
   * list what is in a stream it has not opened. Unlike soundtracks, a single track is still worth
   * showing: the choice here is "on or off", and one track answers it.
   */
  const subtitleSection = document.createElement('div');
  subtitleSection.hidden = true;
  const subtitleTitle = document.createElement('div');
  subtitleTitle.className = 'pc-panel-title';
  subtitleTitle.textContent = t('subtitles_label');
  const subtitleRow = document.createElement('div');
  subtitleRow.className = 'pc-speeds';
  subtitleSection.append(subtitleTitle, subtitleRow);
  panel.append(subtitleSection);

  let selectedSubtitle: number | null = null;

  function buildSubtitleOptions(): void {
    const tracks = player.subtitleTracks();
    subtitleRow.textContent = '';
    subtitleSection.hidden = tracks.length === 0;
    if (!tracks.length) return;
    const choices: { id: number | null; label: string }[] = [
      { id: null, label: t('subtitles_off') },
      ...tracks.map((track) => ({ id: track.id, label: track.label })),
    ];
    for (const choice of choices) {
      const option = document.createElement('div');
      option.className = 'pc-speed';
      option.tabIndex = -1;
      option.textContent = choice.label;
      option.setAttribute('data-focus', '');
      option.setAttribute('data-focus-id', `pc-sub-${choice.id ?? 'off'}`);
      option.setAttribute('aria-selected', String(choice.id === selectedSubtitle));
      option.addEventListener('click', () => {
        selectedSubtitle = choice.id;
        player.selectSubtitleTrack(choice.id);
        // Cleared at once rather than waiting for the decoder to stop sending cues, so turning
        // them off takes the line on screen off with it.
        if (choice.id === null) captionText.textContent = '';
        for (const other of subtitleRow.querySelectorAll('.pc-speed')) {
          other.setAttribute('aria-selected', String(other === option));
        }
      });
      subtitleRow.append(option);
    }
  }

  /*
   * The dark backing behind the subtitle line.
   *
   * This app paints its own subtitles - the decoder hands over the text and nothing else - so the
   * backing is a class on the caption element rather than anything the player has to be told
   * about. It lives beside the track list because that is where somebody who is struggling to read
   * a line will look for it, which is where the television app puts it too.
   */
  let captionBackground = subtitleBackground();
  captions.classList.toggle('boxed', captionBackground);

  const backingRow = document.createElement('div');
  backingRow.className = 'pc-speeds';
  const backingOption = document.createElement('div');
  backingOption.className = 'pc-speed';
  backingOption.tabIndex = -1;
  backingOption.setAttribute('data-focus', '');
  backingOption.setAttribute('data-focus-id', 'pc-sub-backing');
  function paintBacking(): void {
    backingOption.textContent = t('subtitle_background');
    backingOption.setAttribute('aria-selected', String(captionBackground));
  }
  backingOption.addEventListener('click', () => {
    captionBackground = !captionBackground;
    setSubtitleBackground(captionBackground);
    captions.classList.toggle('boxed', captionBackground);
    paintBacking();
  });
  paintBacking();
  backingRow.append(backingOption);
  subtitleSection.append(backingRow);

  // Video scaling. Applied the moment it is chosen and remembered afterwards - see videoScaling.
  const scalingTitle = document.createElement('div');
  scalingTitle.className = 'pc-panel-title';
  scalingTitle.textContent = t('video_scaling');
  panel.append(scalingTitle);

  let scaling = videoScaling();
  const scalingRow = document.createElement('div');
  scalingRow.className = 'pc-speeds';
  const SCALINGS: { mode: VideoScalingPreference; label: () => string }[] = [
    { mode: 'fit', label: () => t('video_fit') },
    { mode: 'fill', label: () => t('video_fill') },
    { mode: 'stretch', label: () => t('video_stretch') },
  ];
  for (const entry of SCALINGS) {
    const option = document.createElement('div');
    option.className = 'pc-speed';
    option.tabIndex = -1;
    option.textContent = entry.label();
    option.setAttribute('data-focus', '');
    option.setAttribute('data-focus-id', `pc-scale-${entry.mode}`);
    option.setAttribute('aria-selected', String(entry.mode === scaling));
    option.addEventListener('click', () => {
      scaling = entry.mode;
      setVideoScaling(entry.mode);
      player.setScaling(entry.mode);
      for (const other of scalingRow.querySelectorAll('.pc-speed')) {
        other.setAttribute('aria-selected', String(other === option));
      }
    });
    scalingRow.append(option);
  }
  panel.append(scalingRow);

  /*
   * How far the skip buttons jump.
   *
   * Ten seconds is right for an advert break and wrong for a title sequence, which is why the
   * television app makes it a choice rather than a constant. The same five values, so somebody
   * who has settled on thirty on their Android box finds thirty here.
   */
  const skipTitle = document.createElement('div');
  skipTitle.className = 'pc-panel-title';
  skipTitle.textContent = t('skip_interval');
  if (!live) panel.append(skipTitle);

  const skipRow = document.createElement('div');
  skipRow.className = 'pc-speeds';
  for (const seconds of SKIP_CHOICES) {
    const option = document.createElement('div');
    option.className = 'pc-speed';
    option.tabIndex = -1;
    option.textContent = t('skip_seconds_format', String(seconds));
    option.setAttribute('data-focus', '');
    option.setAttribute('data-focus-id', `pc-skip-${seconds}`);
    option.setAttribute('aria-selected', String(seconds === skip));
    option.addEventListener('click', () => {
      skip = seconds;
      setSkipSeconds(seconds);
      for (const other of skipRow.querySelectorAll('.pc-speed')) {
        other.setAttribute('aria-selected', String(other === option));
      }
    });
    skipRow.append(option);
  }
  if (!live) panel.append(skipRow);

  /*
   * What the picture actually is.
   *
   * Read when the panel opens rather than kept up to date, because AVPlay has no video-size event
   * to subscribe to - Android gets one and this does not. Asking on open is enough: nobody wants
   * this number except at the moment they have gone looking for it, and a stream that had not
   * reported a size a moment ago will have by the next time the panel is opened.
   *
   * Not focusable. It is a fact, not a choice, and a stop on the walk that does nothing when
   * pressed is a stop that has to be explained.
   */
  const resolutionTitle = document.createElement('div');
  resolutionTitle.className = 'pc-panel-title';
  resolutionTitle.textContent = t('current_resolution');
  const resolutionValue = document.createElement('div');
  resolutionValue.className = 'pc-fact';
  panel.append(resolutionTitle, resolutionValue);

  function paintResolution(): void {
    resolutionValue.textContent = player.resolution() ?? t('resolution_unavailable');
  }

  const panelTitle = document.createElement('div');
  panelTitle.className = 'pc-panel-title';
  panelTitle.textContent = t('playback_speed');
  if (!live) panel.append(panelTitle);

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
  if (!live) panel.append(speedRow);
  chrome.append(panel);

  // ------------------------------------------------------------- episode strip
  const episodes = options.episodes ?? [];
  const strip = document.createElement('div');
  strip.className = 'pc-strip';
  strip.hidden = true;
  strip.setAttribute('data-focus-group', 'pc-strip');
  for (const episode of episodes) {
    const card = document.createElement('div');
    card.className = 'pc-episode';
    card.tabIndex = -1;
    card.setAttribute('data-focus', '');
    card.setAttribute('data-focus-id', `pc-ep-${episode.id}`);
    card.setAttribute('aria-selected', String(episode.id === options.currentEpisodeId));
    const thumb = document.createElement('div');
    thumb.className = 'pc-episode-thumb';
    if (episode.thumbnailUrl) {
      const image = document.createElement('img');
      image.src = episode.thumbnailUrl;
      image.alt = '';
      // A thumbnail that will not load leaves the tile as a plain box rather than a broken icon.
      image.addEventListener('error', () => image.remove());
      thumb.append(image);
    }
    const label = document.createElement('div');
    label.className = 'pc-episode-label';
    label.textContent = episode.label;
    card.append(thumb, label);
    card.addEventListener('click', () => {
      if (episode.id === options.currentEpisodeId) {
        // Already playing. Closing the strip is the useful answer; restarting the stream is not.
        closeStrip();
        return;
      }
      options.onEpisode?.(episode);
    });
    strip.append(card);
  }
  chrome.append(strip);

  let stripOpen = false;

  function openStrip(): void {
    if (!episodes.length) return;
    stripOpen = true;
    strip.hidden = false;
    // Opens on the episode that is playing rather than on the first, so the viewer starts where
    // they are and moves outwards from it.
    const current = strip.querySelector<HTMLElement>('[aria-selected="true"]');
    focus(current ?? strip.querySelector<HTMLElement>('[data-focus]'));
  }

  function closeStrip(): void {
    stripOpen = false;
    strip.hidden = true;
  }

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
      // Never while the settings panel or the episode strip is open: the viewer is reading one of
      // them, not idle, and taking the controls away under a list somebody is choosing from is
      // the one moment it is least welcome.
      if (panelOpen || stripOpen) { armHide(); return; }
      setVisible(false);
    }, CONTROLS_TIMEOUT_MS);
  }

  function setVisible(next: boolean): void {
    visible = next;
    chrome.classList.toggle('is-hidden', !next);
    if (next) {
      armHide();
    } else {
      closeStrip();
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
    buildSubtitleOptions();
    paintResolution();
    // Lands on the first section that has anything in it, so the highlight never opens on a
    // heading with nothing under it.
    const firstOption = panel.querySelector<HTMLElement>('div:not([hidden]) > .pc-speeds > .pc-speed');
    focus(firstOption ?? speedRow.querySelector<HTMLElement>('.pc-speed'));
  }

  function closePanel(): void {
    panelOpen = false;
    panel.hidden = true;
  }

  // The order Down walks. Derived from what currently holds focus rather than counted, for the
  // reason given in advanceDownThroughControls on Android: a counter drifts the moment anything
  // else moves the highlight, and the viewer has no way to get it back in step.
  function stepDown(): boolean {
    if (panelOpen) return true;
    if (stripOpen) return true;
    // An episode goes straight to the season. See PlayerOverlayOptions.episodes for why this is
    // not the same walk a film gets.
    if (episodes.length) { openStrip(); return true; }
    const here = document.activeElement;
    // A live stream has no timeline, so the walk is one stop shorter: transport, then settings.
    if (live) {
      if (here === settingsButton) { openPanel(); return true; }
      focus(settingsButton);
      return true;
    }
    if (here === track) { focus(settingsButton); return true; }
    if (here === settingsButton) { openPanel(); return true; }
    focus(track);
    return true;
  }

  function stepUp(): boolean {
    if (panelOpen) { closePanel(); focus(settingsButton); return true; }
    // Up is the strip's way out, mirroring the way it was opened.
    if (stripOpen) { closeStrip(); focus(firstStop()); return true; }
    const here = document.activeElement;
    if (here === settingsButton) { focus(live ? firstStop() : track); return true; }
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

  rewind.addEventListener('click', () => player.seekBy(-skip * 1000));
  forward.addEventListener('click', () => player.seekBy(skip * 1000));
  playPause.addEventListener('click', togglePlayback);
  settingsButton.addEventListener('click', openPanel);

  function handleKey(key: RemoteKey): boolean {
    // Any press brings the chrome back rather than acting, so nothing happens unseen. The one
    // exception is Back, which leaves whether or not the controls are up.
    if (key === 'back') {
      if (panelOpen) { closePanel(); focus(settingsButton); return true; }
      if (stripOpen) { closeStrip(); focus(firstStop()); return true; }
      if (visible) { setVisible(false); return true; }
      options.onExit(positionMs);
      return true;
    }
    if (!visible) {
      setVisible(true);
      focus(firstStop());
      return true;
    }
    armHide();

    switch (key) {
      case 'down':
        return stepDown();
      case 'up':
        return stepUp();
      case 'left':
        if (!live && document.activeElement === track) { scrub(-skip * 1000); return true; }
        return false;
      case 'right':
        if (!live && document.activeElement === track) { scrub(skip * 1000); return true; }
        return false;
      case 'enter':
        (document.activeElement as HTMLElement | null)?.click();
        return true;
      case 'playpause':
      case 'play':
      case 'pause':
        // Swallowed rather than obeyed on a channel. Holding a broadcast still leaves it falling
        // further behind for as long as it is paused, and with no button on screen there is
        // nothing to press to come back - so the key that got you there is the only way out, and
        // a viewer who pressed it by accident has no way of knowing that.
        if (!live) togglePlayback();
        return true;
      case 'rewind':
        if (live) return true;
        player.seekBy(-skip * 1000);
        return true;
      case 'forward':
        if (live) return true;
        player.seekBy(skip * 1000);
        return true;
      default:
        return false;
    }
  }

  setVisible(true);

  return {
    element: root,
    focusFirst(): void {
      focus(firstStop());
    },
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
    setGuide(now: string | null, next: string | null, progress: number | null): void {
      if (!live) return;
      guideNow.textContent = now ?? '';
      guideNow.hidden = !now;
      guideNext.textContent = next ?? '';
      guideNext.hidden = !next;
      guideBar.hidden = progress === null;
      if (progress !== null) guideFill.style.width = `\%`;
      guide.hidden = !now && !next;
    },

    setCaption(text: string): void {
      captionText.textContent = text;
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
