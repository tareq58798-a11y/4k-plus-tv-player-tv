/**
 * The controls drawn over a playing stream, laid out to match the television app's player.
 *
 * On Samsung the video is not in the page - it is on a hardware plane *behind* it, and the page is
 * transparent over the top. So this is not a `<video>` with controls; it is an overlay that knows
 * the position and duration only because the player reports them, and every drawing decision has
 * to assume there is a moving picture underneath.
 *
 * ## Where the layout comes from
 *
 * Not from a screenshot. The television app draws a recording with media3's own PlayerControlView
 * and a Compose options row on top of it, so the arrangement is fixed by
 * `exo_player_control_view.xml` plus `PlaybackOptionsOverlay` in PlayerComponents.kt, and both were
 * read rather than eyeballed. Every number below is that layout's dp doubled, because a television
 * runs at 960dp on a 1920px panel and 1dp is exactly 2px.
 *
 *   recording          live channel
 *   ---------------    ------------------------------------------------
 *   full scrim         no scrim at all - the television app turns media3's
 *   (#98000000)        controller off for Live TV (useController = false),
 *                      so there is nothing to dim the picture for
 *   title, top left    channel banner, bottom left: logo, name, resolution,
 *                      now, next - every line shadowed, no panel behind it
 *   options, top right options, top right
 *   rewind / play /    -
 *   forward, centred
 *   timeline, full     -
 *   width
 *   bottom bar:        -
 *   position/duration
 *   left, subtitles
 *   and settings right
 *
 * ## Two buttons the television app has that this cannot
 *
 * Mute is absent because Tizen offers no per-stream volume: `tizen.tvaudiocontrol` mutes the
 * *television*, which is a different thing from what the button says. Fullscreen is absent because
 * this player has no smaller state to return to. Both are recorded in web/README.md under the
 * things this port deliberately does not have; the remaining buttons keep the television app's
 * order rather than closing the gaps.
 *
 * A channel's row is the shape button alone. It used to carry subtitles and a settings gear for the
 * soundtrack as well - the gear an addition the television does not have - and both were removed
 * at the owner's request; see where optionsRow is filled.
 *
 * ## Per title
 *
 * Everything chosen in here lasts for the title it was chosen on: the shape, the soundtrack, the
 * subtitle track, subtitles on or off, their background and size, the skip length and the speed.
 * The next film, episode or channel starts from the Settings defaults again. The television app
 * does this for the shape and writes the subtitle background and skip length back to its settings;
 * the owner asked for all of it to reset, so nothing here writes to preferences. Settings is where
 * a lasting choice is made. Recorded in web/README.md.
 */
import { focus } from './focus';
import { lazyImage } from './images';
import { t } from '../shared/i18n';
import {
  SKIP_CHOICES,
  SUBTITLE_SIZES,
  subtitleSize,
  type SubtitleSize,
  skipSeconds,
  subtitleBackground,
  videoScaling,
  type VideoScalingPreference,
} from '../shared/preferences';
import type { MediaPlayer } from '../platform/video';
import type { RemoteKey } from '../platform/keys';

/** How long the controls stay up after the last press. Matches CONTROLS_TIMEOUT_MS on Android. */
const CONTROLS_TIMEOUT_MS = 5000;

/**
 * Longest the channel banner waits for a stream to report its size before giving up on it.
 *
 * RESOLUTION_WAIT_MS on Android, and there for the same reason: a stream that never reports a
 * size must not pin the banner up for ever.
 */
const RESOLUTION_WAIT_MS = 3500;

/** How long the banner stays once the resolution is actually on screen - long enough to read. */
const RESOLUTION_READ_MS = 3000;

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
  /** The channel's mark, for the banner. Live only; a recording's banner is its title. */
  logoUrl?: string | null;
  /**
   * The rest of the season, empty for a film.
   *
   * More than one is what makes Down from a bare picture open the strip - see handleKey. With the
   * controls up, Down walks them whatever this holds - see stepDown.
   */
  episodes?: StripEpisode[];
  /** Which of [episodes] is playing, so the strip can mark it. */
  currentEpisodeId?: string | null;
  onEpisode?: (episode: StripEpisode) => void;
  /**
   * Changes channel without leaving full screen. Returns false when there is nowhere to go.
   *
   * Live only, and only while the controls are down. A broadcast has no controls worth walking,
   * so the television app spends Up and Down on the thing a viewer of live television actually
   * does with them - Up for the next channel, Down for the one before. With the controls up they
   * go back to being navigation, because then there is something to navigate.
   */
  onZap?: (forward: boolean) => boolean;
  /** Called when the viewer leaves, with the position to remember. */
  onExit: (positionMs: number) => void;
}

export interface PlayerOverlay {
  readonly element: HTMLElement;
  /**
   * Puts the highlight on the first control - the play button, or the first option on a channel.
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
 *
 * The four in the options row are the Material icons the television app names in
 * PlaybackOptionsOverlay - Subtitles, MoreTime, HighQuality, AspectRatio - so the row reads the
 * same on both.
 */
const ICONS = {
  rewind: 'M11 7v10l-8-5 8-5zm10 0v10l-8-5 8-5z',
  forward: 'M13 7v10l8-5-8-5zM3 7v10l8-5-8-5z',
  play: 'M8 5v14l11-7L8 5z',
  pause: 'M6 5h4v14H6V5zm8 0h4v14h-4V5z',
  settings: 'M19.4 13a7.8 7.8 0 000-2l2.1-1.6-2-3.4-2.5 1a7.6 7.6 0 00-1.7-1L15 3H9l-.3 2.9a7.6 7.6 0 00-1.7 1l-2.5-1-2 3.4L4.6 11a7.8 7.8 0 000 2l-2.1 1.6 2 3.4 2.5-1c.5.4 1.1.8 1.7 1L9 21h6l.3-2.9c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.4L19.4 13zM12 15.5A3.5 3.5 0 1112 8.5a3.5 3.5 0 010 7z',
  subtitles: 'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z',
  skip: 'M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42A8.962 8.962 0 0012 4c-4.97 0-9 4.03-9 9s4.02 9 9 9a8.994 8.994 0 007.03-14.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z',
  quality: 'M19 4H5c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 11H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zm7-1c0 .55-.45 1-1 1h-.75v1.5h-1.5V15H14c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v4zm-3.5-.5h2v-3h-2v3z',
  aspect: 'M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z',
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

/** Whether a row's document order runs right to left on screen, from its computed direction. */
function runsRightToLeft(element: HTMLElement): boolean {
  return getComputedStyle(element).direction === 'rtl';
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
  // Read once here and updated by the menu below, so a change takes effect on the next press
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
  chrome.className = live ? 'player-chrome is-live' : 'player-chrome';
  root.append(chrome);

  /*
   * What is playing.
   *
   * A recording gets the television app's title: top left, two lines at most, no panel behind it
   * and a shadow on the letters instead. A channel gets its banner: bottom left, logo beside four
   * stacked lines. Both are built, and the CSS shows whichever this player is.
   */
  const title = document.createElement('div');
  title.className = 'pc-title';
  title.textContent = options.title;

  const banner = document.createElement('div');
  banner.className = 'pc-banner';
  const bannerLogo = document.createElement('div');
  bannerLogo.className = 'pc-banner-logo';
  if (options.logoUrl) {
    const image = document.createElement('img');
    image.src = options.logoUrl;
    image.alt = '';
    // A mark that will not load leaves the tile plain rather than showing a broken-image glyph.
    image.addEventListener('error', () => image.remove());
    bannerLogo.append(image);
  }
  const bannerLines = document.createElement('div');
  bannerLines.className = 'pc-banner-lines';
  const bannerName = document.createElement('div');
  bannerName.className = 'pc-banner-name';
  bannerName.textContent = options.title;
  const bannerResolution = document.createElement('div');
  bannerResolution.className = 'pc-banner-resolution';
  bannerResolution.hidden = true;
  const guideNow = document.createElement('div');
  guideNow.className = 'pc-guide-now';
  guideNow.hidden = true;
  /*
   * How far through the programme is.
   *
   * The one thing on this banner the television app does not draw. It is kept because it was
   * already built and says the single thing a listing cannot say in words; it is two pixels of
   * line under the title rather than a control, so it costs the match almost nothing.
   */
  const guideBar = document.createElement('div');
  guideBar.className = 'pc-guide-bar';
  guideBar.hidden = true;
  const guideFill = document.createElement('div');
  guideFill.className = 'pc-guide-fill';
  guideBar.append(guideFill);
  const guideNext = document.createElement('div');
  guideNext.className = 'pc-guide-next';
  guideNext.hidden = true;
  bannerLines.append(bannerName, bannerResolution, guideNow, guideBar, guideNext);
  banner.append(bannerLogo, bannerLines);
  /*
   * The banner is not part of the controls, and on a channel that matters.
   *
   * A recording's title belongs to the chrome: it comes up with the controls and goes with them,
   * because it is answering "what is this" for somebody who has just pressed something. A
   * channel's banner answers "what did I just tune to", which is a question nobody has to press
   * for - so on the television it appears the moment fullscreen opens and again on every channel
   * change, with the top bar still hidden, and takes itself away once it has been read.
   *
   * Which is why it goes in the outer layer rather than in `chrome`: anything inside the chrome
   * is on screen exactly when the controls are, and these two are on screen at different times.
   */
  if (live) root.append(banner);
  else chrome.append(title);

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
   */
  if (!live) {
    transport.append(rewind, playPause, forward);
    chrome.append(transport);
  }

  // ----------------------------------------------------------------- timeline
  /*
   * Full width, above the bottom bar, exactly where exo_progress_placeholder puts it: 52dp up
   * from the foot with a 48dp touch band, which is 104px and 96px here.
   */
  const bar = document.createElement('div');
  bar.className = 'pc-bar';

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
  bar.append(track);

  /*
   * The bottom bar: clocks at one end, icons at the other.
   *
   * exo_bottom_bar is 60dp of #b0000000 across the foot with exo_time pinned to the start and
   * exo_basic_controls to the end. The two clocks are one field there - position, a separator,
   * duration - not two fields at opposite edges, which is what this used to draw.
   */
  const bottom = document.createElement('div');
  bottom.className = 'pc-bottom';
  const clocks = document.createElement('div');
  clocks.className = 'pc-time';
  const elapsed = document.createElement('span');
  elapsed.textContent = '0:00';
  const separator = document.createElement('span');
  separator.className = 'pc-time-separator';
  separator.textContent = ' / ';
  const total = document.createElement('span');
  total.textContent = '0:00';
  clocks.append(elapsed, separator, total);

  const bottomIcons = document.createElement('div');
  bottomIcons.className = 'pc-bottom-icons';
  const settingsButton = button('pc-settings', t('settings_title'), ICONS.settings);
  bottom.append(clocks, bottomIcons);

  // Left out of the page rather than hidden, so neither is a focus stop that cannot be seen - see
  // stepDown, which walks what is actually there.
  if (!live) {
    chrome.append(bar, bottom);
    bottomIcons.append(settingsButton);
  }

  // ------------------------------------------------------------- options row
  /*
   * Top right, in the television app's order.
   *
   * PlaybackOptionsOverlay lays out mute, subtitles, skip interval, resolution, aspect ratio,
   * fullscreen - each a 38dp button on a 68%-black bar with a 13dp radius. Mute and fullscreen
   * cannot exist here (see the note at the top of this file), so what is left is subtitles, skip,
   * resolution, aspect, in those positions; skip and resolution belong to a recording and are not
   * built for a channel, which is what `showSkipInterval = false` does on the television.
   */
  const optionsRow = document.createElement('div');
  optionsRow.className = 'pc-options';
  optionsRow.setAttribute('data-focus-group', 'pc-options');

  const subtitlesButton = button('pc-opt-subtitles', t('subtitles_label'), ICONS.subtitles);
  const skipButton = button('pc-opt-skip', t('skip_interval'), ICONS.skip);
  const qualityButton = button('pc-opt-quality', t('current_resolution'), ICONS.quality);
  const aspectButton = button('pc-opt-aspect', t('video_scaling'), ICONS.aspect);
  // Always tinted, like Icons.Default.AspectRatio is on the television - it is the button people
  // go looking for when a broadcast arrives in the wrong shape, and a row of identical white
  // glyphs gives them nothing to aim at.
  aspectButton.classList.add('is-accent');

  /*
   * On a channel, the shape button alone.
   *
   * A channel's row had subtitles, the shape, and a settings gear this app added for choosing a
   * soundtrack. The owner asked for the gear and the subtitles button to go: broadcast channels
   * rarely carry either, and three buttons were three stops to get past on the way to the one that
   * is used. The television's own row for a channel has no gear either. A film or an episode keeps
   * all of its buttons.
   */
  if (!live) optionsRow.append(subtitlesButton, skipButton, qualityButton);
  optionsRow.append(aspectButton);
  chrome.append(optionsRow);

  /*
   * The controls read left to right in every language.
   *
   * In Arabic the page is dir=rtl, and these rows used to follow it: rewind on the right,
   * fast-forward on the left, the options in reverse. Reported as the controls being inverted, and
   * changed at the owner's request. Playback controls conventionally do not mirror - forward in
   * time is to the right on every player - so these four are pinned left to right while the words
   * around them stay Arabic. The television mirrors its media3 controller here; this deliberately
   * does not, and web/README.md records it.
   */
  for (const row of [transport, bar, bottom, optionsRow]) row.dir = 'ltr';

  /** Every option button, left to right, for the walk along the row. */
  const optionButtons = (): HTMLElement[] =>
    Array.from(optionsRow.querySelectorAll<HTMLElement>('.pc-button'));

  // ---------------------------------------------------------------- menus
  /*
   * One menu element, refilled per button.
   *
   * The television app gives each icon its own DropdownMenu anchored under it. A single element
   * that is moved and refilled behaves the same from the remote and means one set of focus rules
   * rather than four that have to agree.
   */
  const menu = document.createElement('div');
  menu.className = 'pc-menu';
  menu.hidden = true;
  chrome.append(menu);

  let menuOwner: HTMLElement | null = null;

  interface MenuEntry {
    label: string;
    id: string;
    selected?: boolean;
    /** A fact rather than a choice - drawn, but not a stop the highlight can land on. */
    inert?: boolean;
    onPick?: () => void;
  }

  /**
   * [fromTop] opens on the first entry rather than the ticked one. A list of choices opens on the
   * current choice; the subtitles menu is two switches, where the ticks are states rather than a
   * choice, and opening on whichever happened to be on put the highlight on Background right after
   * subtitles were turned off - so OK toggled the backing instead of turning subtitles back on.
   */
  function openMenu(owner: HTMLElement, entries: MenuEntry[], fromTop = false): void {
    menuOwner = owner;
    menu.textContent = '';
    menu.hidden = false;
    // Under its own button rather than under the row, so four menus do not all open in the same
    // place. Measured against the chrome, which is the positioned ancestor.
    const anchor = owner.getBoundingClientRect();
    const frame = chrome.getBoundingClientRect();
    menu.style.right = `${Math.max(0, frame.right - anchor.right)}px`;
    menu.style.top = `${anchor.bottom - frame.top + 8}px`;
    for (const entry of entries) {
      const option = document.createElement('div');
      option.className = entry.inert ? 'pc-menu-item is-fact' : 'pc-menu-item';
      option.textContent = entry.label;
      if (!entry.inert) {
        option.tabIndex = -1;
        option.setAttribute('data-focus', '');
        option.setAttribute('data-focus-id', entry.id);
        option.setAttribute('aria-selected', String(Boolean(entry.selected)));
        option.addEventListener('click', () => {
          entry.onPick?.();
          closeMenu();
          focus(owner);
        });
      }
      menu.append(option);
    }
    const first = (fromTop ? null : menu.querySelector<HTMLElement>('[aria-selected="true"]'))
      ?? menu.querySelector<HTMLElement>('[data-focus]');
    // A menu of nothing but facts keeps the highlight on the button that opened it, so Back and
    // Up still have somewhere to return from.
    focus(first ?? owner);
  }

  function closeMenu(): void {
    menu.hidden = true;
    menu.textContent = '';
    menuOwner = null;
  }

  const menuOpen = (): boolean => menuOwner !== null;

  /*
   * Walking a list inside the player, which nothing else will do for us.
   *
   * Everywhere else in the app an unhandled arrow falls through to focus.ts's directional search.
   * Not here: main.ts registers the player's key handler as `overlay.handleKey(key); return true`,
   * deliberately, because the browse screen is still in the document behind the video and a
   * directional search would happily move the highlight onto a poster nobody can see. The price is
   * that every list the player puts on screen has to walk itself, and for a while none of them
   * did - stepDown swallowed the press outright while a menu or the settings panel was open, so
   * both could be opened, read, and only ever answered with their first entry.
   */
  function stopsIn(container: HTMLElement): HTMLElement[] {
    // offsetParent is null for anything inside a hidden section, which is how the soundtrack and
    // subtitle blocks say they have nothing to offer.
    return Array.from(container.querySelectorAll<HTMLElement>('[data-focus]'))
      .filter((node) => node.offsetParent !== null);
  }

  /** Down and up the open menu. False when there is no further to go in that direction. */
  function stepMenu(delta: number): boolean {
    const items = stopsIn(menu);
    const at = items.indexOf(document.activeElement as HTMLElement);
    const next = items[at + delta];
    if (!next) return false;
    focus(next);
    return true;
  }

  /** The settings panel as rows of options: the soundtracks, the subtitle tracks, the speeds. */
  function panelRows(): HTMLElement[][] {
    return Array.from(panel.querySelectorAll<HTMLElement>('.pc-speeds'))
      .map((row) => stopsIn(row))
      .filter((row) => row.length > 0);
  }

  /**
   * Down and up between the panel's rows, keeping the column where it can.
   *
   * Clamped rather than wrapped, because the rows are different lengths - a stream with two
   * soundtracks and six speeds would otherwise drop the highlight off the end of the short row
   * and leave the press doing nothing with no way to tell why.
   */
  function stepPanel(delta: number): boolean {
    const rows = panelRows();
    const here = document.activeElement as HTMLElement | null;
    const row = rows.findIndex((entries) => here !== null && entries.includes(here));
    if (row < 0) { focus(rows[0]?.[0] ?? null); return rows.length > 0; }
    const target = rows[row + delta];
    if (!target) return false;
    const column = rows[row]!.indexOf(here!);
    focus(target[Math.min(column, target.length - 1)] ?? target[0]!);
    return true;
  }

  /** Left and right within whichever panel row holds the highlight. */
  function stepPanelAcross(rightward: boolean): boolean {
    const here = document.activeElement as HTMLElement | null;
    for (const row of panelRows()) {
      if (here === null || !row.includes(here)) continue;
      // The panel follows the page, so it runs right to left in Arabic; see stepAcross.
      const next = row[row.indexOf(here) + (rightward !== runsRightToLeft(panel) ? 1 : -1)];
      if (next) focus(next);
      return true;
    }
    return false;
  }

  /*
   * Subtitles: on or off, and whether the line carries a dark backing.
   *
   * The television app's dropdown has two more entries - loading an SRT or VTT from storage, and
   * removing one - which this port has no file picker for. They are left out rather than shown
   * doing nothing.
   */
  let captionBackground = subtitleBackground();
  captions.classList.toggle('boxed', captionBackground);

  /*
   * How large the line is drawn. The captions are this app's own text - AVPlay hands over the cue
   * rather than painting it - so the size is simply the font size of that box: 40px at Medium, which
   * is what it always was, scaled by the choice. Kept between titles, like the backing above.
   */
  let captionSize = subtitleSize();
  const applyCaptionSize = (): void => {
    captions.style.fontSize = `${Math.round(40 * SUBTITLE_SIZES[captionSize])}px`;
  };
  applyCaptionSize();
  const SIZE_LABELS: Record<SubtitleSize, () => string> = {
    small: () => t('subtitle_size_small'),
    medium: () => t('subtitle_size_medium'),
    large: () => t('subtitle_size_large'),
    xlarge: () => t('subtitle_size_xlarge'),
  };
  let subtitlesOn = true;

  function applySubtitlesOn(next: boolean): void {
    subtitlesOn = next;
    subtitlesButton.classList.toggle('is-accent', next);
    if (!next) {
      if (selectedSubtitle !== null) lastSubtitle = selectedSubtitle;
      player.selectSubtitleTrack(null);
      selectedSubtitle = null;
      // Cleared at once rather than waiting for the decoder to stop sending cues, so turning them
      // off takes the line on screen off with it.
      captionText.textContent = '';
      return;
    }
    /*
     * On again means telling the decoder so. Off silences AVPlay's cues, and this used to leave
     * them silenced - the button lit up and nothing came back. The track picked before, or the
     * stream's first if none was.
     */
    const track = lastSubtitle ?? player.subtitleTracks()[0]?.id ?? null;
    if (track !== null) {
      player.selectSubtitleTrack(track);
      selectedSubtitle = track;
    }
  }

  subtitlesButton.addEventListener('click', () => {
    openMenu(subtitlesButton, [
      {
        label: subtitlesOn ? t('subtitles_turn_off') : t('subtitles_turn_on'),
        id: 'pc-menu-subtitles-toggle',
        selected: subtitlesOn,
        onPick: () => applySubtitlesOn(!subtitlesOn),
      },
      {
        label: t('subtitle_background'),
        id: 'pc-menu-subtitle-backing',
        selected: captionBackground,
        onPick: () => {
          // For this title only - see "Per title" at the top of the file.
          captionBackground = !captionBackground;
          captions.classList.toggle('boxed', captionBackground);
        },
      },
      // Films and series only. A channel's subtitles are rare and its menu stays as it was.
      ...(live ? [] : [
        { label: t('subtitle_size'), id: 'pc-menu-subtitle-size', inert: true },
        ...(Object.keys(SUBTITLE_SIZES) as SubtitleSize[]).map((size) => ({
          label: SIZE_LABELS[size](),
          id: `pc-menu-subtitle-size-${size}`,
          selected: size === captionSize,
          onPick: () => {
            captionSize = size;
            applyCaptionSize();
          },
        })),
      ]),
    ], true);
  });

  /*
   * How far the skip buttons jump.
   *
   * Ten seconds is right for an advert break and wrong for a title sequence, which is why the
   * television app makes it a choice rather than a constant. The same five values, so somebody
   * who has settled on thirty on their Android box finds thirty here.
   */
  skipButton.addEventListener('click', () => {
    openMenu(skipButton, SKIP_CHOICES.map((seconds) => ({
      label: t('skip_seconds_format', String(seconds)),
      id: `pc-menu-skip-${seconds}`,
      selected: seconds === skip,
      onPick: () => { skip = seconds; },
    })));
  });

  /*
   * What the picture actually is.
   *
   * Read when the menu opens rather than kept up to date, because AVPlay has no video-size event
   * to subscribe to - Android gets one and this does not. Asking on open is enough: nobody wants
   * this number except at the moment they have gone looking for it. It is a fact, not a choice,
   * so like the television app's single-item dropdown it cannot be picked.
   */
  qualityButton.addEventListener('click', () => {
    openMenu(qualityButton, [{
      label: player.resolution() ?? t('resolution_unavailable'),
      id: 'pc-menu-quality',
      inert: true,
    }]);
  });

  /*
   * How the picture fills the screen, for what is playing now.
   *
   * Applied the moment it is chosen and not remembered, as the television does it: its player
   * menu sets `videoMode` and deliberately does not write it back ("aspect ratio is usually
   * specific to whatever's currently playing ... it should reset to the real default (set in
   * Settings) for the next thing watched"). This used to write it, so a 4:3 picked for one old
   * programme opened every later film squashed until somebody found the menu again. The standing
   * default is Settings > Playback, which main.ts applies as each stream arrives.
   *
   * All seven of the television app's shapes, in its order. Three are AVPlay display methods; the
   * four named frames are built out of the display rectangle instead - see applyDisplay in
   * platform/video.ts, which reproduces the surface scaling the television does rather than the
   * tidier thing it looks like it is doing.
   */
  let scaling = videoScaling();
  const SCALINGS: { mode: VideoScalingPreference; label: () => string }[] = [
    { mode: 'fit', label: () => t('scale_fit') },
    { mode: 'stretch', label: () => t('scale_stretch') },
    { mode: 'zoom', label: () => t('scale_zoom') },
    { mode: '16:9', label: () => t('scale_16_9') },
    { mode: '4:3', label: () => t('scale_4_3') },
    { mode: '21:9', label: () => t('scale_21_9') },
    { mode: '1:1', label: () => t('scale_1_1') },
  ];

  aspectButton.addEventListener('click', () => {
    openMenu(aspectButton, SCALINGS.map((entry) => ({
      label: entry.label(),
      id: `pc-menu-scale-${entry.mode}`,
      selected: entry.mode === scaling,
      onPick: () => { scaling = entry.mode; player.setScaling(entry.mode); },
    })));
  });

  // ----------------------------------------------------- the settings panel
  /*
   * What the gear opens, which is what media3's own settings sheet carries: how fast, which
   * soundtrack, which subtitle track. The on-and-off switch for subtitles is not here - it is in
   * the options row, where the television app puts it.
   */
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
   * Which subtitle track, for a stream that carries more than one.
   *
   * Built when the panel opens, like the soundtracks and for the same reason. "Off" is not an
   * entry here: that switch lives on the options row, and offering it in two places invites the
   * two to disagree.
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
  /** The track that was showing when subtitles were last turned off, to bring back. */
  let lastSubtitle: number | null = null;

  function buildSubtitleOptions(): void {
    const tracks = player.subtitleTracks();
    subtitleRow.textContent = '';
    subtitleSection.hidden = tracks.length === 0;
    if (!tracks.length) return;
    for (const track of tracks) {
      const option = document.createElement('div');
      option.className = 'pc-speed';
      option.tabIndex = -1;
      option.textContent = track.label;
      option.setAttribute('data-focus', '');
      option.setAttribute('data-focus-id', `pc-sub-${track.id}`);
      option.setAttribute('aria-selected', String(track.id === selectedSubtitle));
      option.addEventListener('click', () => {
        selectedSubtitle = track.id;
        player.selectSubtitleTrack(track.id);
        // Choosing a track is a way of turning them on, so the row's switch follows rather than
        // being left saying the opposite of what is on screen.
        subtitlesOn = true;
        subtitlesButton.classList.add('is-accent');
        for (const other of subtitleRow.querySelectorAll('.pc-speed')) {
          other.setAttribute('aria-selected', String(other === option));
        }
      });
      subtitleRow.append(option);
    }
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
  // More than one, as RelatedItemsStrip's `if (items.size <= 1) return` and the `> 1` at both of
  // its call sites. A strip holding only the episode already playing offers nothing to pick.
  const hasStrip = episodes.length > 1;
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
      lazyImage(image, episode.thumbnailUrl);
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
  // In the outer layer, not the chrome. The strip has to be able to be on screen while the
  // controls are not - see openStrip - and anything inside the chrome goes when the chrome goes.
  root.append(strip);

  let stripOpen = false;

  function openStrip(): void {
    if (!hasStrip) return;
    stripOpen = true;
    strip.hidden = false;
    /*
     * The strip replaces the controls rather than joining them.
     *
     * Two things on screen for one press is the visible half of the problem. The other half is
     * that the controls keep the highlight, so Up - which is the strip's way of closing itself -
     * never reaches the strip at all. Taking the controls down is what lets the strip hold focus,
     * and so what makes Up work. The same change was made in the television app.
     */
    chrome.classList.add('is-hidden');
    visible = false;
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
      // Never while a menu, the settings panel or the episode strip is open: the viewer is reading
      // one of them, not idle, and taking the controls away under a list somebody is choosing from
      // is the one moment it is least welcome.
      if (panelOpen || stripOpen || menuOpen()) { armHide(); return; }
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
      closeMenu();
      /*
       * Focus goes nowhere when the chrome is down.
       *
       * This used to say the same thing and call focus(null), which does nothing at all - the
       * first line of focus() is `if (!element) return`. So the highlight stayed on a control
       * that had just faded out. Harmless in practice, because the player consumes every key
       * itself and never consults what is focused while it is hidden, but the comment was
       * describing something that was not happening. blur() actually does it.
       */
      const held = document.activeElement;
      if (held instanceof HTMLElement) held.blur();
    }
  }

  /*
   * The resolution line, re-read on a tick rather than once.
   *
   * The television app is told: it subscribes to a video-size listener and repaints when one
   * arrives. AVPlay has no such event, and asking once is not enough - measured on the set after
   * a channel change, getCurrentStreamInfo reported 1920x1080 while this line was still blank,
   * because the overlay is built and shown before the decoder has opened the stream, and nothing
   * came back to ask again. A second is also often enough to catch a stream that changes size
   * partway through, which the listener would have caught for free.
   */
  function paintBannerResolution(): void {
    if (!live) return;
    const label = player.resolution();
    if (bannerResolution.textContent === (label ?? '')) return;
    bannerResolution.textContent = label ?? '';
    bannerResolution.hidden = !label;
  }

  /*
   * The banner's own life, which is not the controls'.
   *
   * It comes up when the channel does and takes itself away once it has been read, and "read" is
   * measured from the resolution arriving rather than from a fixed count - that line is the one
   * thing on the banner a viewer waits for, and a slow-opening channel would otherwise have the
   * banner gone before the number it was waiting for turned up. Capped, because a stream that
   * never reports a size must not pin it there. Both numbers are the television's.
   */
  let bannerShownAt = 0;
  let bannerReadableAt = 0;

  function showBanner(): void {
    if (!live) return;
    bannerShownAt = Date.now();
    bannerReadableAt = 0;
    banner.classList.remove('is-hidden');
  }

  function tickBanner(): void {
    if (!live || banner.classList.contains('is-hidden')) return;
    const now = Date.now();
    if (bannerResolution.hidden) {
      if (now - bannerShownAt >= RESOLUTION_WAIT_MS) banner.classList.add('is-hidden');
      return;
    }
    if (bannerReadableAt === 0) bannerReadableAt = now;
    if (now - bannerReadableAt >= RESOLUTION_READ_MS) banner.classList.add('is-hidden');
  }

  // Drives both, and not only while the controls are up: the banner is most often on screen
  // when they are not. Only while the banner is up, though. The resolution comes from AVPlay's
  // getTotalTrackInfo, a synchronous call into the decoder; asked twice a second for as long as a
  // channel was watched, it took the page's time away from the remote for nothing anyone could see.
  const resolutionTimer = live
    ? window.setInterval(() => {
        if (banner.classList.contains('is-hidden')) return;
        paintBannerResolution();
        tickBanner();
      }, 500)
    : null;

  function openPanel(): void {
    panelOpen = true;
    panel.hidden = false;
    // Rebuilt every time it opens: switching episode replaces the stream, and with it the
    // soundtracks. A list cached from the last thing played would offer choices that no longer
    // exist.
    buildAudioOptions();
    buildSubtitleOptions();
    // Lands on the first section that has anything in it, so the highlight never opens on a
    // heading with nothing under it.
    const firstOption = panel.querySelector<HTMLElement>('div:not([hidden]) > .pc-speeds > .pc-speed');
    focus(firstOption ?? speedRow.querySelector<HTMLElement>('.pc-speed') ?? settingsButton);
  }

  function closePanel(): void {
    panelOpen = false;
    panel.hidden = true;
  }

  /** Where the highlight goes when the controls come up. A channel has no play button. */
  const firstStop = (): HTMLElement => (live ? optionButtons()[0] ?? settingsButton : playPause);

  /**
   * The key that points at the options row, and its opposite.
   *
   * Right and Left in every language, because the row is pinned top right in every language
   * (`.pc-options { right: 16px }`). The television's `towardsBar` mirrors because its bar moves to
   * the top left in Arabic; this one never moved, so mirroring the key had Arabic fetching the row
   * with the arrow that points away from it. What stays true on both is that the key points at the
   * row.
   */
  const towardsControls = (): RemoteKey => 'right';
  const awayFromControls = (): RemoteKey => 'left';

  /*
   * The order Down walks with the controls up, which is the television app's walk for a film:
   *
   *   options row -> play/pause -> timeline -> settings gear
   *
   * onExitDown on the options row hands straight to play/pause there;
   * advanceDownThroughControls walks the two below it. Derived from what currently holds focus
   * rather than counted, for the reason given in that function: a counter drifts the moment
   * anything else moves the highlight, and the viewer has no way to get it back in step.
   */
  function stepDown(): boolean {
    // Down the open list, and nothing at the bottom of it - a remote user cannot see that they
    // have reached the end except by the highlight refusing to move.
    if (menuOpen()) { stepMenu(1); return true; }
    if (panelOpen) { stepPanel(1); return true; }
    if (stripOpen) return true;
    const here = document.activeElement;
    const inOptions = here instanceof HTMLElement && optionsRow.contains(here);
    /*
     * With the controls up, Down walks the controls and never opens the strip - a series gets
     * the same walk a film does. Deliberately unlike advanceDownThroughControls on the
     * television, which opens the strip from the first press when there is one; changed at the
     * owner's request, because a press meant for the timeline was swapping the controls for the
     * season. The strip opens from a bare picture instead - see handleKey. Recorded in
     * web/README.md.
     */
    // A live stream has no transport, no timeline and no gear in the bottom bar, so there is
    // nothing under the options row to step to.
    if (live) return true;
    if (inOptions) { focus(playPause); return true; }
    if (here === track) { focus(settingsButton); return true; }
    // The gear is the last stop. Down from it does nothing rather than wrapping or opening the
    // panel - advanceDownThroughControls returns false here, and a remote user cannot see they
    // have reached the end of a list except by the highlight refusing to move. OK on the gear is
    // what opens the panel, on both.
    if (here === settingsButton) return true;
    focus(track);
    return true;
  }

  function stepUp(): boolean {
    // Up walks the open list, and past the top of it closes it - which is the way it was opened
    // in reverse, and saves the viewer hunting for Back.
    if (menuOpen()) {
      if (stepMenu(-1)) return true;
      const owner = menuOwner;
      closeMenu();
      focus(owner);
      return true;
    }
    if (panelOpen) {
      if (stepPanel(-1)) return true;
      closePanel();
      focus(settingsButton);
      return true;
    }
    // Up is the strip's way out, mirroring the way it was opened - and it leaves the picture
    // bare rather than putting the controls back, because the press said 'not this' rather than
    // 'something else'. Any key brings them back.
    if (stripOpen) { closeStrip(); focus(null); return true; }
    const here = document.activeElement;
    // The options row is the top of the screen and the top of the walk; there is nothing above it.
    if (here instanceof HTMLElement && optionsRow.contains(here)) return true;
    if (here === settingsButton) { focus(track); return true; }
    if (here === track) { focus(playPause); return true; }
    // Up from the transport row reaches the options row, which is where it sits on screen and
    // where Compose's own focus search sends it on the television.
    focus(optionButtons()[0] ?? playPause);
    return true;
  }

  /**
   * Left and right along whichever row holds the highlight.
   *
   * There are three rows and the highlight is only ever in one of them, so which row to walk is
   * read off the element rather than tracked. Running off either end stops, because the rows do
   * not wrap and nothing sits beside them - with one exception, below.
   */
  // `rightward` rather than `forward`, which is the fast-forward button three lines down - the
  // two collided, and the row came out holding a boolean where a button should have been.
  function stepAcross(rightward: boolean): boolean {
    // A menu is one column, so sideways means nothing in it; the panel is rows of options, where
    // it means everything. Both are consumed either way rather than falling through to a
    // directional search that would leave the player entirely - see stopsIn.
    if (menuOpen()) return true;
    if (panelOpen) { stepPanelAcross(rightward); return true; }
    const here = document.activeElement;
    /*
     * Which way document order runs across the screen depends on the row: the control rows are
     * pinned left to right, and the strip and the panel follow the page, which is right to left in
     * Arabic. Stepping by document order alone once sent Right leftwards; this goes the way the
     * arrow points in any of them.
     */
    const step = (row: HTMLElement): number => (rightward !== runsRightToLeft(row) ? 1 : -1);
    /*
     * Along the strip, which is the reason it exists: RelatedItemsStrip is a LazyRow and Left and
     * Right move along it there. This walk was missing, so the strip opened on the episode playing
     * and no press could reach any other. Stops at either end.
     */
    if (stripOpen) {
      const cards = stopsIn(strip);
      const at = here instanceof HTMLElement ? cards.indexOf(here) : -1;
      const next = at < 0 ? cards[0] : cards[at + step(strip)];
      if (next) focus(next);
      return true;
    }
    if (!(here instanceof HTMLElement)) return false;
    const inOptions = optionsRow.contains(here);
    let row: HTMLElement[] | null = null;
    let owner: HTMLElement | null = null;
    if (inOptions) { row = optionButtons(); owner = optionsRow; }
    else if (transport.contains(here)) { row = [rewind, playPause, forward]; owner = transport; }
    else if (bottomIcons.contains(here)) { row = Array.from(bottomIcons.children) as HTMLElement[]; owner = bottomIcons; }
    if (!row || !owner) return false;
    const at = row.indexOf(here);
    const next = row[at + step(owner)];
    if (next) { focus(next); return true; }
    /*
     * On a channel, running off the *inner* end of the options row puts the controls away.
     *
     * That is onCollapseOnDpad on the television, fired from the first button by `awayFromBar`,
     * and it exists because on a channel this row is the whole of the chrome - there is nowhere
     * else for the highlight to go, so the key may as well mean "done". Only that end: the far
     * end is where the key that opened the row points, and having it close the row too would mean
     * the same press opened and shut it depending on where the highlight happened to be. A
     * recording has a transport row and a timeline below, so there the press simply stops.
     */
    const outward = (rightward ? 'right' : 'left') === awayFromControls();
    if (live && inOptions && outward) { setVisible(false); return true; }
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
  /*
   * Moving through a film: the timeline and the skip buttons.
   *
   * Both used to seek on every press, ten seconds at a time. A seek on AVPlay is a rebuffer, so
   * each press cost the decoder a restart for a position the viewer was only passing through, and
   * getting twenty minutes in was a hundred and twenty presses.
   *
   * Now presses move a target, which is drawn at once, and the decoder is sent there once when
   * the presses stop - which is how media3's DefaultTimeBar scrubs on the television (it commits a
   * second after the last key). The timeline also takes that bar's step: with no increment set,
   * as the television app sets none, it is a twentieth of the running time per press.
   *
   * The skip buttons and the remote's rewind and fast-forward keys keep the skip length for a
   * single press, and speed up while presses keep coming: after two in quick succession each
   * press is worth three, then six, then twelve. The television's buttons stay at the skip length
   * however often they are pressed; the owner asked for these to be faster. Recorded in
   * web/README.md.
   */
  /** Where the presses have got to and not yet been sent, or null when nothing is waiting. */
  let seekTarget: number | null = null;
  let seekTimer: number | null = null;
  let nudgeStreak = 0;
  let lastNudgeAt = 0;
  /** DefaultTimeBar's STOP_SCRUBBING_TIMEOUT_MS. */
  const TIMELINE_COMMIT_MS = 1000;
  /** Shorter for the buttons: a single press there should feel like it acted. */
  const SKIP_COMMIT_MS = 500;
  /**
   * The seek just sent, until the decoder's clock reaches it. AVPlay goes on reporting the old
   * position for a moment after a seek is asked for, and drawing that made the bar jump back.
   */
  let landing: { at: number; until: number } | null = null;
  const LANDING_MS = 4000;
  /** Presses closer together than this count as one run. */
  const STREAK_GAP_MS = 900;

  function drawPosition(at: number): void {
    elapsed.textContent = clockOf(at);
    if (durationMs > 0) played.style.width = `${Math.min(1, Math.max(0, at / durationMs)) * 100}%`;
  }

  function moveTarget(deltaMs: number, commitAfterMs: number): void {
    const limit = durationMs > 0 ? durationMs - 1000 : Number.MAX_SAFE_INTEGER;
    const from = seekTarget ?? positionMs;
    const target = Math.max(0, Math.min(limit, from + deltaMs));
    seekTarget = target;
    positionMs = target;
    drawPosition(target);
    if (seekTimer !== null) window.clearTimeout(seekTimer);
    seekTimer = window.setTimeout(() => {
      seekTimer = null;
      if (seekTarget === null) return;
      player.seekTo(seekTarget);
      landing = { at: seekTarget, until: Date.now() + LANDING_MS };
      seekTarget = null;
    }, commitAfterMs);
  }

  function scrub(direction: 1 | -1): void {
    const step = durationMs > 0 ? durationMs / 20 : skip * 1000;
    moveTarget(direction * step, TIMELINE_COMMIT_MS);
  }

  function nudge(direction: 1 | -1): void {
    const now = Date.now();
    nudgeStreak = now - lastNudgeAt < STREAK_GAP_MS ? nudgeStreak + 1 : 0;
    lastNudgeAt = now;
    const times = nudgeStreak < 2 ? 1 : nudgeStreak < 4 ? 3 : nudgeStreak < 8 ? 6 : 12;
    moveTarget(direction * skip * 1000 * times, SKIP_COMMIT_MS);
  }

  function togglePlayback(): void {
    if (paused) {
      player.resume();
    } else {
      player.pause();
    }
  }

  rewind.addEventListener('click', () => nudge(-1));
  forward.addEventListener('click', () => nudge(1));
  playPause.addEventListener('click', togglePlayback);
  settingsButton.addEventListener('click', openPanel);

  /** When OK last brought the controls up - see the guard in handleKey. */
  let okWokeAt = 0;
  const OK_REPEAT_GUARD_MS = 500;

  function handleKey(key: RemoteKey): boolean {
    // Any press brings the chrome back rather than acting, so nothing happens unseen. The one
    // exception is Back, which leaves whether or not the controls are up.
    if (key === 'back') {
      if (menuOpen()) { const owner = menuOwner; closeMenu(); focus(owner); return true; }
      if (panelOpen) { closePanel(); focus(settingsButton); return true; }
      if (stripOpen) { closeStrip(); focus(null); return true; }
      if (visible) { setVisible(false); return true; }
      options.onExit(positionMs);
      return true;
    }
    /*
     * The remote's media keys act at once, whether or not the controls are up.
     *
     * They used to fall into the branch below that spends a press on bringing the controls back,
     * so the first Play/Pause, Rewind or Fast-forward on a bare picture did nothing but show the
     * controls. media3's PlayerView does both at once - dispatchMediaKeyEvent acts on the key and
     * maybeShowController shows the controls - and so does this now.
     *
     * Play and Pause are also each one thing now. All three keys used to toggle, so Play pressed on
     * something already playing paused it.
     */
    if (key === 'play' || key === 'pause' || key === 'playpause' || key === 'rewind' || key === 'forward') {
      // Swallowed on a channel. Holding a broadcast still leaves it falling further behind for as
      // long as it is paused, and with no button on screen there is nothing to press to come back.
      if (live) return true;
      if (key === 'play') { if (paused) player.resume(); }
      else if (key === 'pause') { if (!paused) player.pause(); }
      else if (key === 'playpause') togglePlayback();
      else nudge(key === 'rewind' ? -1 : 1);
      if (!visible && !stripOpen) {
        setVisible(true);
        focus(firstStop());
      } else {
        armHide();
      }
      return true;
    }

    /*
     * The CH+ and CH- buttons change channel on a channel, from any state - they are registered
     * with the set for this app and were then answered with nothing. The television app has no
     * handler for them; they are the buttons a viewer of a Samsung set reaches for first, and
     * Up and Down already do the same job below. Recorded in web/README.md.
     */
    if (key === 'channelUp' || key === 'channelDown') {
      if (live && options.onZap) options.onZap(key === 'channelUp');
      return true;
    }

    /*
     * Channel change, before anything else gets a look at the press.
     *
     * This has to come before the branch below, which decides what wakes the controls. On a
     * channel with the controls down, Up and Down are not navigation - there is nothing to
     * navigate to - so they change channel, which is what Key.DirectionUp and Key.DirectionDown
     * do on the television and what those buttons mean on every set a viewer has used.
     *
     * Up briefly did something else - it was the key that fetched the controls - and that made
     * zapping one-directional. The controls moved to the outward key instead, below, which is
     * where the television has always had them and which leaves both of these free again.
     */
    if (live && options.onZap && !visible && !stripOpen && (key === 'up' || key === 'down')) {
      if (options.onZap(key === 'up')) return true;
    }

    /*
     * OK on a channel brings up the controls - it no longer leaves.
     *
     * The television app has OK leave a channel exactly as Back does, and this copied it. The
     * owner pressed OK expecting the menu, and instead the channel closed, the list came back and
     * the stream started over behind it - reported as the picture cutting out and loading for a
     * long time. Back still leaves. Recorded in web/README.md.
     */

    /*
     * A series with the controls down: Down opens the strip, not the controls.
     *
     * "One press of Down goes to the episodes" has to hold from the state a viewer is actually
     * in, and five seconds into an episode the controls have already withdrawn. Without this the
     * press was spent waking them and the strip needed a second one - which is the same two-press
     * walk the whole change was meant to remove, just moved somewhere less obvious.
     */
    if (hasStrip && !visible && !stripOpen && key === 'down') {
      openStrip();
      return true;
    }

    /*
     * Waking the controls on a channel: the key that points at them, and only that one.
     *
     * A channel opens with the picture clean, so something has to be the key that goes and fetches
     * the options row, and it is the one pointing towards the corner the row sits in, which is
     * Right in every language here (see towardsControls). That is `towardsBar` on the television,
     * and the opposite key closes the row from its inner end (see stepAcross), so the gesture is
     * symmetrical: out to the controls, back in to the picture.
     *
     * Any other key is swallowed rather than acting. On a bare picture there is nothing else for
     * a press to act on, and a key that silently does nothing is better than one that does
     * something the viewer cannot see. A recording keeps the old behaviour, where any key wakes
     * the controls: there the controls are the point, and Down has a timeline to reach.
     */
    if (!visible && !stripOpen) {
      if (live && key !== towardsControls() && key !== 'enter') return true;
      setVisible(true);
      focus(firstStop());
      if (key === 'enter') okWokeAt = Date.now();
      return true;
    }
    armHide();
    // The same OK, still held and repeating, must not go on to press what it has just highlighted.
    if (key === 'enter' && Date.now() - okWokeAt < OK_REPEAT_GUARD_MS) return true;

    switch (key) {
      case 'down':
        return stepDown();
      case 'up':
        return stepUp();
      case 'left':
        if (!live && document.activeElement === track) { scrub(-1); return true; }
        return stepAcross(false);
      case 'right':
        if (!live && document.activeElement === track) { scrub(1); return true; }
        return stepAcross(true);
      case 'enter':
        (document.activeElement as HTMLElement | null)?.click();
        return true;
      default:
        return false;
    }
  }

  /*
   * How a player opens.
   *
   * A recording opens with its controls up, because somebody who has just chosen a film is still
   * holding the remote and the title, the timeline and the play button are all worth a glance. A
   * channel opens with nothing but the picture and its banner - the television app does the same
   * (`if (hostedFullscreen) controllerVisible = false`), and the highlight sitting on a row of
   * icons the moment a channel opens was what got this looked at.
   */
  if (live) {
    setVisible(false);
    showBanner();
  } else {
    setVisible(true);
  }

  return {
    element: root,
    focusFirst(): void {
      // Nothing to focus on a channel: the controls are not on screen, and Up is what fetches
      // them. Focusing a hidden row would put the highlight somewhere the viewer cannot see it.
      if (live) return;
      focus(firstStop());
    },
    handleKey,
    setPosition(next: number, length: number): void {
      durationMs = length;
      total.textContent = clockOf(length);
      // While presses are still moving the target, the target is what is shown. The decoder's
      // clock has not gone there yet, and drawing it would pull the bar back under the viewer.
      if (seekTarget !== null) return;
      if (landing) {
        if (Date.now() < landing.until && Math.abs(next - landing.at) > 3000) return;
        landing = null;
      }
      positionMs = next;
      drawPosition(next);
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
      if (progress !== null) guideFill.style.width = `${Math.round(progress * 100)}%`;
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
      // A seek still waiting belongs to this title, not to whatever opens next.
      if (seekTimer !== null) window.clearTimeout(seekTimer);
      if (hideTimer !== null) window.clearTimeout(hideTimer);
      if (resolutionTimer !== null) window.clearInterval(resolutionTimer);
      root.remove();
    },
  };
}
