/**
 * Playback, behind one interface, because this is where the platforms differ most.
 *
 * Samsung does not play video in an HTML element. AVPlay draws onto a hardware plane *behind* the
 * page, and the page shows it through a hole punched in its own background - so the element the
 * app positions is a placeholder, and what actually moves is a rectangle given to the decoder in
 * real device pixels. Get that wrong and the video is fine but invisible, or visible in the wrong
 * place, with nothing in the logs to say so.
 *
 * A browser is the opposite: an ordinary <video>, with hls.js for the streams it cannot open
 * itself. That is only for developing without a television - it is not a shipping target.
 */
import type { PlatformName } from './keys';

export type PlayerEvent =
  | { type: 'ready'; durationMs: number }
  | { type: 'playing' }
  | { type: 'paused' }
  | { type: 'buffering'; percent: number }
  | { type: 'progress'; positionMs: number }
  | { type: 'ended' }
  /**
   * A line of subtitle to put on screen, or an empty string to take the last one off.
   *
   * AVPlay does not draw subtitles. It decodes them and hands the text over, and the app paints
   * it - which is the opposite of a `<video>` element, where the browser draws them and the app
   * never sees the words. That difference is why this is an event rather than a setting.
   */
  | { type: 'subtitle'; text: string }
  | { type: 'error'; message: string };

/** Matches the television app's video_mode preference: fit, fill, stretch. */
/**
 * The seven shapes the television app's aspect-ratio menu offers, under its own keys.
 *
 * Three of them are display methods - show it all, crop to fill, stretch to fill - and four are
 * requests to force the picture into a named frame whatever shape it arrived in. `zoom` rather
 * than `fill` because that is the string the television writes into its own `video_mode`
 * preference, and having the two apps disagree about what to call the same choice is how a
 * setting ends up meaning different things on different screens.
 */
export type VideoScaling = 'fit' | 'stretch' | 'zoom' | '16:9' | '4:3' | '21:9' | '1:1';

/** The four named frames, as width over height. The other three modes reshape nothing. */
export const FIXED_RATIOS: Partial<Record<VideoScaling, number>> = {
  '16:9': 16 / 9,
  '4:3': 4 / 3,
  '21:9': 21 / 9,
  '1:1': 1,
};

/** One selectable soundtrack: a dub, a commentary, or the original. */
export interface AudioTrack {
  /** What the platform wants back to select it. Opaque - do not do arithmetic on it. */
  readonly id: number;
  /** What to show. A language name where the stream declares one, else something like "Audio 2". */
  readonly label: string;
}

export interface MediaPlayer {
  /** Begins [url]. The rectangle is in CSS pixels of the page, converted internally if it must be. */
  play(url: string, rect: DOMRect): Promise<void>;
  pause(): void;
  resume(): void;
  seekBy(deltaMs: number): void;
  /**
   * Jumps to an absolute position. Distinct from seekBy because a timeline the viewer drags along
   * knows where it wants to land, not how far that is from wherever playback has drifted to while
   * they were dragging.
   */
  seekTo(positionMs: number): void;
  /**
   * Playback rate, 1 being normal. Not every set honours every value - a rate a decoder refuses
   * simply does not take, which is why this reports nothing back and the caller reads the speed it
   * asked for rather than one it was promised.
   */
  setSpeed(rate: number): void;
  /**
   * The soundtracks this stream carries, or an empty list.
   *
   * Only meaningful once playback has started - a decoder cannot say what is in a stream it has
   * not opened. Empty is the normal answer for a single-language file, and the caller is expected
   * to offer nothing rather than an empty menu.
   */
  audioTracks(): AudioTrack[];
  selectAudioTrack(id: number): void;
  /**
   * How the picture fills the box: the whole frame with bars, cropped to fill, or stretched.
   *
   * The same three the television app offers, and they map onto AVPlay's display methods without
   * inventing anything - which is why there are three rather than two.
   */
  setScaling(mode: VideoScaling): void;
  /** The picture's size as `W x H`, or null while the stream has not reported one. */
  resolution(): string | null;
  /**
   * True when a picture is actually being decoded right now.
   *
   * Asked rather than announced, because AVPlay will accept a stream, report prepare success, and
   * then tear itself down without telling anybody - which is what a channel the provider still
   * lists but no longer carries does. A caller that has drawn something on the strength of
   * playback starting needs a way to find out that it stopped.
   */
  isPlaying(): boolean;
  /** The subtitle tracks the stream carries, or an empty list. Only valid once playing. */
  subtitleTracks(): AudioTrack[];
  /** Null turns subtitles off. */
  selectSubtitleTrack(id: number | null): void;
  stop(): void;
  /** Re-aims the picture, for a resize or a change between full screen and a preview pane. */
  setRect(rect: DOMRect): void;
  on(listener: (event: PlayerEvent) => void): void;
  /** The container extension this platform wants for a live stream - see LiveContainer. */
  readonly liveContainer: 'ts' | 'm3u8';
}

/** Shown when a stream declares a soundtrack but not what language it is. */
const AUDIO_FALLBACK_LABEL = 'Audio';

/** The same, for a subtitle track with no declared language. */
const SUBTITLE_FALLBACK_LABEL = 'Subtitles';

/** How long a cue stays up when the stream does not say. About the length of a spoken line. */
const SUBTITLE_FALLBACK_MS = 4000;

/**
 * Digs a language out of AVPlay's `extra_info`, or gives up quietly.
 *
 * Every step is defended because none of it is guaranteed: the field may be absent, may not be
 * JSON, and may use any of several names for the same thing depending on the container. A menu
 * entry reading "Audio 2" is a perfectly good outcome; an exception thrown out of a getter while a
 * film is playing is not.
 */
function languageOf(extraInfo: string | undefined): string | null {
  if (!extraInfo) return null;
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(extraInfo) as Record<string, unknown>;
  } catch {
    return null;
  }
  for (const key of ['language', 'track_lang', 'lang']) {
    const value = parsed[key];
    if (typeof value === 'string') {
      const trimmed = value.trim();
      // Streams commonly fill this with "und" for undetermined, which is worse than saying
      // nothing - it looks like a language nobody has heard of.
      if (trimmed && trimmed.toLowerCase() !== 'und') return describeLanguage(trimmed);
    }
  }
  return null;
}

/**
 * Turns a language tag into something a viewer reads, using the set's own language data.
 *
 * Intl.DisplayNames is in Chromium from 81 and these sets run 69, so this is expected to be absent
 * rather than unlucky - the tag itself is the fallback, and "ara" on a menu is still more use than
 * "Audio 2".
 */
function describeLanguage(tag: string): string {
  // Reached for rather than called directly: the type is not in the ES2019 lib this project
  // targets, precisely because it arrived after the engine these televisions run.
  const ctor = (Intl as unknown as {
    DisplayNames?: new (locales: string[], options: { type: string }) => { of(code: string): string | undefined };
  }).DisplayNames;
  if (!ctor) return tag;
  try {
    return new ctor([document.documentElement.lang || 'en'], { type: 'language' }).of(tag) ?? tag;
  } catch {
    return tag;
  }
}

/* ------------------------------------------------------------------ Tizen */

interface AvPlay {
  open(url: string): void;
  close(): void;
  prepareAsync(onSuccess: () => void, onError: (e: unknown) => void): void;
  setDisplayRect(x: number, y: number, w: number, h: number): void;
  setDisplayMethod(method: string): void;
  play(): void;
  pause(): void;
  stop(): void;
  seekTo(ms: number): void;
  getCurrentTime(): number;
  getDuration(): number;
  getState(): string;
  setListener(listener: Record<string, unknown>): void;
  /** Present from Tizen 2.4, but a set is free to reject a rate it cannot decode. */
  setSpeed?(rate: number): void;
  /** Every track in the stream, audio and video and subtitle together. Only valid once prepared. */
  getTotalTrackInfo?(): AvTrack[];
  setSelectTrack?(type: 'AUDIO' | 'VIDEO' | 'TEXT', index: number): void;
  /** True stops the cue callbacks, which is how subtitles are turned off on AVPlay. */
  setSilentSubtitle?(silent: boolean): void;
}

/**
 * A track as AVPlay describes it.
 *
 * `extra_info` is a JSON *string*, not an object, and what it contains varies by container: an
 * MPEG-TS gives `language`, an MP4 often gives `track_lang`, and some streams give neither. It is
 * also free to be malformed, so every read of it is guarded - a soundtrack menu is not worth
 * throwing away a playing stream for.
 */
interface AvTrack {
  index: number;
  type: string;
  extra_info?: string;
}

class TizenPlayer implements MediaPlayer {
  /** AVPlay decodes MPEG-TS directly, which is what Xtream serves for live and is a shorter path
   *  to the first frame than asking the panel to wrap the same stream in HLS. */
  readonly liveContainer = 'ts' as const;

  private readonly av: AvPlay;
  private listener: ((event: PlayerEvent) => void) | null = null;
  private ticker: number | null = null;
  private subtitleTimer: number | null = null;
  /**
   * The box the player was asked for, before the current mode reshapes it.
   *
   * This replaced a `pendingRect` that meant "held until the stream is prepared, because AVPlay
   * will not accept it before then". Both jobs are done by keeping the request instead of the
   * delivery: applyDisplay recomputes from this every time it runs and simply fails quietly while
   * the decoder is not ready, so 'ready' re-running it is all the deferral that was needed. And a
   * named aspect ratio has to be worked out from the original box each time either the box or the
   * mode changes - a rectangle already narrowed to 4:3 is no use as the start of 21:9.
   */
  private baseRect: DOMRect | null = null;
  private scaling: VideoScaling = 'fit';

  constructor() {
    const webapis = (window as unknown as { webapis?: { avplay?: AvPlay } }).webapis;
    if (!webapis?.avplay) throw new Error('AVPlay is not available on this device.');
    this.av = webapis.avplay;
  }

  on(listener: (event: PlayerEvent) => void): void {
    this.listener = listener;
  }

  private emit(event: PlayerEvent): void {
    this.listener?.(event);
  }

  /**
   * CSS pixels to device pixels. A Tizen app's page is usually declared 1920x1080 and the set
   * renders it at its own resolution; AVPlay wants the real thing. Deriving the ratio from the
   * window rather than assuming 1:1 is what keeps this correct on a 4K panel, where a hard-coded
   * rectangle would place the picture in a quarter of the screen.
   */
  private device(rect: DOMRect): [number, number, number, number] {
    const scaleX = (window.screen?.width || window.innerWidth) / window.innerWidth;
    const scaleY = (window.screen?.height || window.innerHeight) / window.innerHeight;
    return [
      Math.round(rect.left * scaleX),
      Math.round(rect.top * scaleY),
      Math.round(rect.width * scaleX),
      Math.round(rect.height * scaleY),
    ];
  }

  async play(url: string, rect: DOMRect): Promise<void> {
    this.stop();
    this.av.open(url);
    // Remembered, not applied: AVPlay ignores a display rectangle on a stream it has not prepared
    // yet, and ignores it silently. Setting it here left every stream at the default, which is the
    // whole panel - so the Live TV preview played full screen behind the page instead of in its
    // box. Applied below, once prepareAsync has returned.
    this.baseRect = rect;
    this.av.setListener({
      onbufferingprogress: (percent: number) => this.emit({ type: 'buffering', percent }),
      onbufferingcomplete: () => this.emit({ type: 'playing' }),
      onstreamcompleted: () => {
        this.stop();
        this.emit({ type: 'ended' });
      },
      onerror: (error: unknown) => this.emit({ type: 'error', message: String(error) }),
      /**
       * A cue, with how long it should stay up.
       *
       * The duration is honoured rather than waiting for the next cue, because there may not be
       * one: a line spoken before a long silence would otherwise sit on screen through the whole
       * silence. A zero or missing duration falls back to a few seconds, which is roughly how
       * long a line of dialogue lasts and is better than leaving it up for ever.
       */
      onsubtitlechange: (durationMs: number, text: string) => {
        this.emit({ type: 'subtitle', text: String(text ?? '') });
        if (this.subtitleTimer !== null) window.clearTimeout(this.subtitleTimer);
        const holdFor = Number(durationMs) > 0 ? Number(durationMs) : SUBTITLE_FALLBACK_MS;
        this.subtitleTimer = window.setTimeout(() => {
          this.subtitleTimer = null;
          this.emit({ type: 'subtitle', text: '' });
        }, holdFor);
      },
    });
    await new Promise<void>((resolve, reject) => {
      this.av.prepareAsync(
        () => resolve(),
        (error) => reject(new Error(String(error))),
      );
    });
    // Now that it is prepared, the rectangle and the display method actually take. Through
    // applyDisplay rather than straight to setDisplayRect, so a mode chosen before the stream was
    // ready is honoured now instead of being flattened back to letter-box; the caller applies the
    // viewer's own choice on this same 'ready' event either way.
    this.applyDisplay();
    this.emit({ type: 'ready', durationMs: this.av.getDuration() });
    this.av.play();
    this.emit({ type: 'playing' });
    this.ticker = window.setInterval(() => {
      this.emit({ type: 'progress', positionMs: this.av.getCurrentTime() });
    }, 500);
  }

  pause(): void {
    this.av.pause();
    this.emit({ type: 'paused' });
  }

  resume(): void {
    this.av.play();
    this.emit({ type: 'playing' });
  }

  seekBy(deltaMs: number): void {
    const target = Math.max(0, this.av.getCurrentTime() + deltaMs);
    this.av.seekTo(target);
  }

  seekTo(positionMs: number): void {
    this.av.seekTo(Math.max(0, Math.round(positionMs)));
  }

  setSpeed(rate: number): void {
    try {
      this.av.setSpeed?.(rate);
    } catch {
      /* The set refused this rate. Playback carries on at whatever it was already doing, which is
         better than tearing down the stream over a speed control. */
    }
  }

  audioTracks(): AudioTrack[] {
    let tracks: AvTrack[];
    try {
      tracks = this.av.getTotalTrackInfo?.() ?? [];
    } catch {
      // Asked before the stream was prepared, or not supported on this set. Either way the answer
      // is "no choice to offer", which is also the answer for most files.
      return [];
    }
    const audio = tracks.filter((track) => String(track.type).toUpperCase() === 'AUDIO');
    // One soundtrack is not a choice. Offering a menu of one invites the viewer to open it, read
    // it, and close it again having learned nothing.
    if (audio.length < 2) return [];
    return audio.map((track, position) => ({
      id: track.index,
      label: languageOf(track.extra_info) ?? `${AUDIO_FALLBACK_LABEL} ${position + 1}`,
    }));
  }

  selectAudioTrack(id: number): void {
    try {
      this.av.setSelectTrack?.('AUDIO', id);
    } catch {
      /* Refused, for the same reason and with the same answer as a refused speed. */
    }
  }

  /**
   * What the picture actually is, as "1920 x 1080", or null while that is not yet knowable.
   *
   * Android reads this off a video-size callback; AVPlay has no such event, so it has to be asked
   * for - and it can only answer once the stream is prepared and decoding, which is why this
   * returns null rather than a placeholder. The panel calls it each time it opens, so a stream
   * that was not ready a moment ago reports properly the next time somebody looks.
   *
   * The numbers live in the video track's extra_info, which is the same untyped JSON blob the
   * language comes out of, under any of several names depending on the container. Every step is
   * defended for the reason given over languageOf: a missing field is a fine outcome, an exception
   * thrown while a film is playing is not.
   */
  isPlaying(): boolean {
    try {
      return String(this.av.getState()).toUpperCase() === 'PLAYING';
    } catch {
      return false;
    }
  }

  resolution(): string | null {
    let tracks: AvTrack[];
    try {
      tracks = this.av.getTotalTrackInfo?.() ?? [];
    } catch {
      return null;
    }
    const video = tracks.find((track) => String(track.type).toUpperCase() === 'VIDEO');
    if (!video?.extra_info) return null;
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(video.extra_info) as Record<string, unknown>;
    } catch {
      return null;
    }
    const pick = (...names: string[]): number | null => {
      for (const name of names) {
        const value = parsed[name];
        const numeric = typeof value === 'string' ? Number(value) : value;
        if (typeof numeric === 'number' && Number.isFinite(numeric) && numeric > 0) return numeric;
      }
      return null;
    };
    const width = pick('Width', 'width', 'WIDTH');
    const height = pick('Height', 'height', 'HEIGHT');
    return width && height ? `${width} x ${height}` : null;
  }

  subtitleTracks(): AudioTrack[] {
    let tracks: AvTrack[];
    try {
      tracks = this.av.getTotalTrackInfo?.() ?? [];
    } catch {
      return [];
    }
    // Unlike soundtracks, a single subtitle track is worth offering: the choice being made is not
    // "which language" but "on or off", and one track is enough for that.
    return tracks
      .filter((track) => String(track.type).toUpperCase() === 'TEXT')
      .map((track, position) => ({
        id: track.index,
        label: languageOf(track.extra_info) ?? `${SUBTITLE_FALLBACK_LABEL} ${position + 1}`,
      }));
  }

  selectSubtitleTrack(id: number | null): void {
    try {
      if (id === null) {
        // There is no "deselect" on AVPlay. Silencing the stream of cues is what turns them off,
        // and the app simply stops being told about them.
        this.av.setSilentSubtitle?.(true);
        if (this.subtitleTimer !== null) {
          window.clearTimeout(this.subtitleTimer);
          this.subtitleTimer = null;
        }
        this.emit({ type: 'subtitle', text: '' });
        return;
      }
      this.av.setSilentSubtitle?.(false);
      this.av.setSelectTrack?.('TEXT', id);
    } catch {
      /* Refused. Subtitles stay as they were rather than taking the stream down with them. */
    }
  }

  setScaling(mode: VideoScaling): void {
    this.scaling = mode;
    this.applyDisplay();
  }

  setRect(rect: DOMRect): void {
    // Kept either way, so a rectangle handed over before the stream is ready is applied when it
    // is rather than lost - which is what "the rectangle is set again when it is" used to assume
    // without anything actually doing it.
    this.baseRect = rect;
    this.applyDisplay();
  }

  /**
   * The picture's own shape, or null while the stream has not said.
   *
   * Needed because the four named frames are not applied to the box - they are applied to the
   * picture already fitted inside it, which is what the television does and which gives a
   * different answer whenever the two shapes differ. See applyDisplay.
   */
  private videoRatio(): number | null {
    const label = this.resolution();
    if (!label) return null;
    const parts = label.split(' x ').map(Number);
    const width = parts[0] ?? 0;
    const height = parts[1] ?? 0;
    return width > 0 && height > 0 ? width / height : null;
  }

  /**
   * Puts the picture where the current mode says it goes.
   *
   * AVPlay has three display methods and the television app offers seven shapes, so four of them
   * have to be built rather than selected. They are built out of the display *rectangle*, which
   * is the one thing here that can be any shape at all: aim the decoder at a box of the right
   * proportions and tell it to fill that box exactly.
   *
   * Working out which box is the fiddly part, because the television's version is not "put the
   * picture in a 4:3 frame". applyRequestedAspectRatio scales the whole surface - a surface that
   * RESIZE_MODE_FIT has already sized to the fitted picture - so what gets squashed is whatever
   * was on screen a moment ago, not the raw video. Two consequences, and both are reproduced
   * here rather than tidied away:
   *
   *   - a 16:9 picture asked for 4:3 comes out genuinely squashed, not letterboxed into a 4:3 box
   *   - a 4:3 picture asked for 16:9 does not change at all, because the fitted picture is
   *     already pillarboxed and the scale factors both come out 1
   *
   * So: fit the picture into the box, then scale that result by how much the box would have to
   * shrink to become the named shape. Without a reported video size there is nothing to fit, and
   * the box's own ratio stands in - which degrades to plain reshaping rather than to nothing.
   */
  private applyDisplay(): void {
    const base = this.baseRect;
    if (!base) return;

    const ratio = FIXED_RATIOS[this.scaling] ?? null;
    let target = base;

    if (ratio !== null && base.width > 0 && base.height > 0) {
      const boxRatio = base.width / base.height;
      const video = this.videoRatio() ?? boxRatio;
      // The fitted picture, as the television's RESIZE_MODE_FIT would leave it.
      let width = video > boxRatio ? base.width : base.height * video;
      let height = video > boxRatio ? base.width / video : base.height;
      // Then the squash that turns the box into the named shape.
      if (ratio > boxRatio) height *= boxRatio / ratio;
      else width *= ratio / boxRatio;
      target = new DOMRect(
        base.left + (base.width - width) / 2,
        base.top + (base.height - height) / 2,
        width,
        height,
      );
    }

    try {
      this.av.setDisplayRect(...this.device(target));
    } catch {
      /* Not prepared yet. Applied on 'ready', which calls this again. */
      return;
    }

    // LETTER_BOX keeps the whole frame and adds bars; CROPPED_FULL keeps the shape and loses the
    // edges; FULL_SCREEN keeps neither and fills the box. The named frames use FULL_SCREEN
    // because the box has already been cut to the right shape above - the stretch into it is the
    // squash the television applies to its surface.
    const method = this.scaling === 'stretch' || ratio !== null
      ? 'PLAYER_DISPLAY_MODE_FULL_SCREEN'
      : this.scaling === 'zoom'
        ? 'PLAYER_DISPLAY_MODE_CROPPED_FULL'
        : 'PLAYER_DISPLAY_MODE_LETTER_BOX';
    try {
      this.av.setDisplayMethod(method);
    } catch {
      // Some sets reject a method before the stream is prepared, and some reject CROPPED_FULL
      // outright. Either way the picture stays as it was, which is better than losing it.
    }
  }

  stop(): void {
    if (this.ticker !== null) {
      window.clearInterval(this.ticker);
      this.ticker = null;
    }
    try {
      this.av.stop();
      this.av.close();
    } catch {
      /* Nothing was open. */
    }
  }
}

/* ---------------------------------------------------------------- Browser */

class BrowserPlayer implements MediaPlayer {
  /**
   * A browser cannot decode the raw MPEG-TS that Xtream serves at `live/.../id.ts` - there is no
   * demuxer for it outside of HLS. Panels serve the same channel as HLS at `.m3u8`, which hls.js
   * can play, so the development target asks for that instead. This is the only place the two
   * differ, and it is a fact about browsers rather than a preference.
   */
  readonly liveContainer = 'm3u8' as const;

  private readonly video: HTMLVideoElement;
  private listener: ((event: PlayerEvent) => void) | null = null;
  private hls: { destroy(): void; loadSource(url: string): void; attachMedia(v: HTMLVideoElement): void } | null = null;

  constructor(video: HTMLVideoElement) {
    this.video = video;
    video.addEventListener('playing', () => this.emit({ type: 'playing' }));
    video.addEventListener('pause', () => this.emit({ type: 'paused' }));
    video.addEventListener('waiting', () => this.emit({ type: 'buffering', percent: 0 }));
    video.addEventListener('ended', () => this.emit({ type: 'ended' }));
    video.addEventListener('timeupdate', () =>
      this.emit({ type: 'progress', positionMs: video.currentTime * 1000 }),
    );
    video.addEventListener('loadedmetadata', () =>
      this.emit({ type: 'ready', durationMs: Number.isFinite(video.duration) ? video.duration * 1000 : 0 }),
    );
    video.addEventListener('error', () =>
      this.emit({ type: 'error', message: video.error?.message || 'Playback failed.' }),
    );
  }

  on(listener: (event: PlayerEvent) => void): void {
    this.listener = listener;
  }

  private emit(event: PlayerEvent): void {
    this.listener?.(event);
  }

  async play(url: string, rect: DOMRect): Promise<void> {
    this.stop();
    this.setRect(rect);
    this.video.style.display = 'block';
    const isHls = /\.m3u8(\?|$)/i.test(url);
    if (isHls && !this.video.canPlayType('application/vnd.apple.mpegurl')) {
      const { default: Hls } = await import('hls.js');
      if (Hls.isSupported()) {
        const hls = new Hls({ lowLatencyMode: false });
        hls.attachMedia(this.video);
        hls.loadSource(url);
        this.hls = hls;
        await this.video.play().catch(() => undefined);
        return;
      }
    }
    this.video.src = url;
    await this.video.play().catch((error: unknown) =>
      this.emit({ type: 'error', message: error instanceof Error ? error.message : String(error) }),
    );
  }

  pause(): void {
    this.video.pause();
  }

  resume(): void {
    void this.video.play().catch(() => undefined);
  }

  seekBy(deltaMs: number): void {
    this.video.currentTime = Math.max(0, this.video.currentTime + deltaMs / 1000);
  }

  seekTo(positionMs: number): void {
    this.video.currentTime = Math.max(0, positionMs / 1000);
  }

  setSpeed(rate: number): void {
    this.video.playbackRate = rate;
  }

  /*
   * The development path, where the picture is a real <video> and CSS can do the whole job.
   *
   * object-fit covers the three display methods and aspect-ratio covers the four named frames -
   * the element is reshaped and the picture fitted inside it, which is close enough for working
   * without a television in front of you. It is not the surface-scaling arithmetic the Tizen
   * player does, and it is not meant to be: that exists to reproduce the television app exactly,
   * on the platform that ships.
   */
  setScaling(mode: VideoScaling): void {
    const ratio = FIXED_RATIOS[mode] ?? null;
    this.video.style.objectFit = mode === 'stretch' ? 'fill' : mode === 'zoom' ? 'cover' : 'contain';
    this.video.style.aspectRatio = ratio === null ? '' : String(ratio);
    // Height rather than width, so a squarer frame shrinks inside the box instead of overflowing
    // it: with a fixed width an aspect-ratio below the box's own would push the picture off the
    // bottom of the screen.
    this.video.style.height = ratio === null ? '100%' : 'auto';
    this.video.style.maxHeight = '100%';
    this.video.style.margin = ratio === null ? '' : 'auto';
  }

  /**
   * The browser draws subtitles itself, so there is nothing here to enumerate or paint.
   *
   * Reporting none is honest rather than lazy: this path exists for developing without a
   * television, and pretending to offer a control that the shipping platform implements
   * completely differently would make the development build a worse guide, not a better one.
   */
  isPlaying(): boolean {
    return !this.video.paused && !this.video.ended && this.video.readyState >= 2;
  }

  /** The element knows its own picture size, unlike AVPlay, so this needs no digging. */
  resolution(): string | null {
    const { videoWidth, videoHeight } = this.video;
    return videoWidth > 0 && videoHeight > 0 ? `${videoWidth} x ${videoHeight}` : null;
  }

  subtitleTracks(): AudioTrack[] {
    return [];
  }

  selectSubtitleTrack(): void {
    /* See subtitleTracks. */
  }

  /**
   * hls.js knows the soundtracks in an HLS stream; a plain <video> mostly does not, because
   * HTMLMediaElement.audioTracks is unimplemented in Chrome. Both are read, and an empty list is
   * the honest answer when neither can say.
   */
  audioTracks(): AudioTrack[] {
    const fromHls = (this.hls as { audioTracks?: { id: number; name?: string; lang?: string }[] } | null)?.audioTracks;
    if (fromHls && fromHls.length > 1) {
      return fromHls.map((track, position) => ({
        id: track.id,
        label: track.name || track.lang || `${AUDIO_FALLBACK_LABEL} ${position + 1}`,
      }));
    }
    const native = (this.video as HTMLVideoElement & {
      audioTracks?: { length: number; [index: number]: { id: string; label?: string; language?: string } };
    }).audioTracks;
    if (!native || native.length < 2) return [];
    const tracks: AudioTrack[] = [];
    for (let i = 0; i < native.length; i++) {
      const track = native[i];
      if (!track) continue;
      tracks.push({ id: i, label: track.label || track.language || `${AUDIO_FALLBACK_LABEL} ${i + 1}` });
    }
    return tracks;
  }

  selectAudioTrack(id: number): void {
    const hls = this.hls as { audioTrack?: number } | null;
    if (hls && 'audioTrack' in hls) {
      hls.audioTrack = id;
      return;
    }
    const native = (this.video as HTMLVideoElement & {
      audioTracks?: { length: number; [index: number]: { enabled: boolean } };
    }).audioTracks;
    if (!native) return;
    for (let i = 0; i < native.length; i++) {
      const track = native[i];
      if (track) track.enabled = i === id;
    }
  }

  setRect(rect: DOMRect): void {
    const style = this.video.style;
    style.position = 'fixed';
    style.left = `${rect.left}px`;
    style.top = `${rect.top}px`;
    style.width = `${rect.width}px`;
    style.height = `${rect.height}px`;
  }

  stop(): void {
    this.hls?.destroy();
    this.hls = null;
    this.video.pause();
    this.video.removeAttribute('src');
    this.video.load();
    this.video.style.display = 'none';
  }
}

/**
 * The AVPlay picture is drawn behind the page, so the page has to be see-through where the video
 * belongs. A Tizen app whose body keeps an opaque background plays perfectly and shows nothing.
 */
export function createPlayer(platform: PlatformName, video: HTMLVideoElement): MediaPlayer {
  if (platform === 'tizen') {
    try {
      return new TizenPlayer();
    } catch {
      /* A Tizen build running somewhere without AVPlay - fall through to the element. */
    }
  }
  return new BrowserPlayer(video);
}
