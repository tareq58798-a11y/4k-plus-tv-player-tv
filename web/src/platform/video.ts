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
  | { type: 'error'; message: string };

export interface MediaPlayer {
  /** Begins [url]. The rectangle is in CSS pixels of the page, converted internally if it must be. */
  play(url: string, rect: DOMRect): Promise<void>;
  pause(): void;
  resume(): void;
  seekBy(deltaMs: number): void;
  stop(): void;
  /** Re-aims the picture, for a resize or a change between full screen and a preview pane. */
  setRect(rect: DOMRect): void;
  on(listener: (event: PlayerEvent) => void): void;
  /** The container extension this platform wants for a live stream - see LiveContainer. */
  readonly liveContainer: 'ts' | 'm3u8';
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
}

class TizenPlayer implements MediaPlayer {
  /** AVPlay decodes MPEG-TS directly, which is what Xtream serves for live and is a shorter path
   *  to the first frame than asking the panel to wrap the same stream in HLS. */
  readonly liveContainer = 'ts' as const;

  private readonly av: AvPlay;
  private listener: ((event: PlayerEvent) => void) | null = null;
  private ticker: number | null = null;

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
    this.av.setDisplayRect(...this.device(rect));
    // FULL_SCREEN here means "fill the rectangle I gave you", not "fill the panel". Letter-boxing
    // inside that rectangle is AVPlay's own aspect handling and is what the television app does.
    try {
      this.av.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX');
    } catch {
      /* Older sets name this differently; the default is already letter-box. */
    }
    this.av.setListener({
      onbufferingprogress: (percent: number) => this.emit({ type: 'buffering', percent }),
      onbufferingcomplete: () => this.emit({ type: 'playing' }),
      onstreamcompleted: () => {
        this.stop();
        this.emit({ type: 'ended' });
      },
      onerror: (error: unknown) => this.emit({ type: 'error', message: String(error) }),
    });
    await new Promise<void>((resolve, reject) => {
      this.av.prepareAsync(
        () => resolve(),
        (error) => reject(new Error(String(error))),
      );
    });
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

  setRect(rect: DOMRect): void {
    try {
      this.av.setDisplayRect(...this.device(rect));
    } catch {
      /* Not prepared yet; the rectangle is set again when it is. */
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
