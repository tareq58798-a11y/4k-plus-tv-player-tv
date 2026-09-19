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

export class Backdrop {
  private readonly base: HTMLElement;
  private readonly settled: HTMLImageElement;
  private readonly incoming: HTMLImageElement;
  private settledUrl: string | null = null;
  private incomingUrl: string | null = null;
  private timer: number | null = null;

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
   * Ask for [url] to become the background. A blank url is a no-op rather than a reset: a title
   * with no artwork keeps whatever is already there, instead of dropping the viewer onto the
   * default image, which reads as a flash every time an untagged title is focused.
   */
  show(url: string | null | undefined): void {
    if (!url) return;
    if (url === this.settledUrl || url === this.incomingUrl) return;
    this.schedule(url);
  }

  /** Back to the app's own artwork, fading rather than cutting. */
  reset(): void {
    this.schedule(null);
  }

  private schedule(url: string | null): void {
    if (this.timer !== null) window.clearTimeout(this.timer);
    this.timer = window.setTimeout(() => this.apply(url), Motion.BackdropDebounceMs);
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
    image.onload = () => {
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
