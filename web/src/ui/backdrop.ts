/**
 * The app-wide background, ported from Backdrop.kt.
 *
 * Three layers: the app's own artwork underneath, the settled image over it, and the incoming one
 * fading in over both. The handover is ordered so every frame shows something - the settled layer
 * takes the finished image first, and only then is the incoming layer released. Done the other
 * way round, the background blinks through to the default between the two.
 *
 * An image that fails to load simply never fades in, leaving what was there. A backdrop is
 * decoration; a broken one must not become a visible event.
 *
 * Rules carried over:
 *
 *  - Only films and series drive it. Channels, search and settings leave it alone, and whatever
 *    was last shown stays up - that is what makes moving through the app feel like one room
 *    rather than a slideshow.
 *  - Requests are debounced. Holding the D-pad down through twenty posters should cost one image
 *    request, not twenty.
 *  - Home shows the app's own artwork until the viewer actually moves onto something, so opening
 *    the app does not immediately replace it with a title nobody chose.
 */
import { Motion } from '../shared/tokens.generated';

/** How long the highlight has to rest on a title before its background is fetched. */
const WARM_AFTER_MS = 300;

export class Backdrop {
  private readonly base: HTMLElement;
  private readonly settled: HTMLImageElement;
  private readonly incoming: HTMLImageElement;
  private settledUrl: string | null = null;
  private incomingUrl: string | null = null;
  private timer: number | null = null;
  private warmTimer: number | null = null;
  /**
   * Pictures fetched and decoded ahead of being shown, newest last. Held so the engine keeps them:
   * an Image nobody references is free to be dropped, decoded pixels and all.
   */
  private readonly warm = new Map<string, HTMLImageElement>();

  constructor(host: HTMLElement) {
    this.base = document.createElement('div');
    this.base.className = 'backdrop-base';
    // Relative to the page, so it resolves the same while developing and inside the widget.
    this.base.style.backgroundImage = "url('./bg_app_default.png')";
    this.settled = document.createElement('img');
    this.settled.className = 'backdrop-layer';
    this.incoming = document.createElement('img');
    this.incoming.className = 'backdrop-layer';
    const scrim = document.createElement('div');
    scrim.className = 'backdrop-scrim';
    host.append(this.base, this.settled, this.incoming, scrim);
  }

  /**
   * Whether the background follows what the viewer is looking at.
   *
   * False is Classic: the app's own artwork stays up everywhere and nothing replaces it. Some
   * people want the room to hold still - a picture that changes every time the highlight moves is
   * the busiest thing on screen, and on a television across a room it is movement in the corner of
   * the eye all evening.
   *
   * Gated here rather than at the callers, exactly as on Android. Every page asks for artwork in
   * the ordinary way and this decides whether the request is honoured, so Classic cannot be
   * defeated by a screen that forgot to check.
   */
  followsFocus = true;

  /**
   * Ask for [url] to become the background. A blank url is a no-op rather than a reset: a title
   * with no artwork keeps whatever is already there, instead of dropping the viewer onto the
   * default image, which reads as a flash every time an untagged title is focused.
   */
  show(url: string | null | undefined): void {
    if (!this.followsFocus) return;
    if (!url) return;
    if (url === this.settledUrl || url === this.incomingUrl) return;
    this.schedule(url);
  }

  /** Back to the app's own artwork, fading rather than cutting. */
  reset(): void {
    this.schedule(null);
  }

  /**
   * Fetches and decodes [urls] now, so that when one of them becomes the background it is already
   * there. PreloadBackdrops on Android, with its limit of twelve.
   *
   * On Android the three-second wait before the background changes is spent with the picture
   * already warm - the neighbours of the focused card are fetched and decoded ahead of time - so
   * when the wait ends the picture is simply shown. Here the wait ended and *then* the download
   * began, followed by a decode of a full-size still on a television's processor, which is the
   * slowness the owner compared against the Android build.
   */
  preload(urls: (string | null | undefined)[]): void {
    if (!this.followsFocus) return;
    for (const url of urls.filter((value): value is string => Boolean(value)).slice(0, 12)) {
      const known = this.warm.get(url);
      if (known) {
        // Most recently wanted goes to the back, so trimming drops what is furthest behind.
        this.warm.delete(url);
        this.warm.set(url, known);
        continue;
      }
      const image = new Image();
      image.decoding = 'async';
      image.onload = () => {
        if (typeof image.decode === 'function') image.decode().catch(() => undefined);
      };
      image.src = url;
      this.warm.set(url, image);
    }
    // Twice the batch, so the pictures just passed are still warm when the viewer steps back.
    while (this.warm.size > 24) this.warm.delete(this.warm.keys().next().value!);
  }

  private schedule(url: string | null): void {
    if (this.timer !== null) window.clearTimeout(this.timer);
    if (this.warmTimer !== null) window.clearTimeout(this.warmTimer);
    this.warmTimer = null;
    this.timer = window.setTimeout(() => this.apply(url), Motion.BackdropDebounceMs);
    /*
     * The picture itself is asked for much sooner than it is shown.
     *
     * The three seconds are Android's, and they are about when the background *changes* - for
     * somebody who has stopped to look, not somebody passing through. They were never meant to
     * be three seconds and then a download. A short dwell first still keeps a held key from
     * fetching every title it passes; after that the fetch runs during the wait instead of after.
     */
    if (url) this.warmTimer = window.setTimeout(() => this.preload([url]), WARM_AFTER_MS);
  }

  private apply(url: string | null): void {
    if (url === null) {
      this.settled.style.opacity = '0';
      this.incoming.style.opacity = '0';
      this.settledUrl = null;
      this.incomingUrl = null;
      return;
    }
    this.incomingUrl = url;
    const image = new Image();
    image.decoding = 'async';
    image.onload = async () => {
      /*
       * Decoded before it is shown, not while it fades.
       *
       * A backdrop is a full-screen photograph, and an image that has loaded has not been decoded:
       * that happens on first paint, on the main thread, in the middle of the fade - a visible
       * hitch in the animation and a key press that waits behind it. decode() does it beforehand,
       * off the main thread where the engine can. It is decoded here, on a loose Image, and shown
       * on the layer: Chromium keeps one decoded copy per loaded image, which the layer showing the
       * same URL reuses. Not on every engine this app runs on, and a failure only means the old
       * behaviour.
       */
      if (typeof image.decode === 'function') await image.decode().catch(() => undefined);
      // Overtaken while this was decoding - a faster neighbour won. Drop it rather than fading in
      // a picture the viewer has already moved past.
      if (this.incomingUrl !== url) return;
      this.incoming.src = url;
      this.incoming.style.opacity = '1';
      window.setTimeout(() => {
        if (this.incomingUrl !== url) return;
        // Settled first, then release the incoming layer: both show the same picture for a frame,
        // which is what stops the swap being visible.
        this.settled.src = url;
        this.settled.style.opacity = '1';
        this.settledUrl = url;
        this.incoming.style.opacity = '0';
        this.incomingUrl = null;
      }, Motion.BackdropMs);
    };
    image.onerror = () => {
      if (this.incomingUrl === url) this.incomingUrl = null;
    };
    image.src = url;
  }
}
