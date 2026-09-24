/**
 * Artwork that loads when it is about to be seen, not when its element is made.
 *
 * A category of posters is up to four hundred images, and a channel list as many logos. Setting
 * `src` as each element was built asked the provider for all of them at once, the moment the
 * category was highlighted: the few on screen queued behind hundreds nobody had scrolled to, and
 * moving on to the next category left that whole queue running while the next one joined it. That
 * is most of why pictures appeared slowly and the remote felt heavy on a busy page.
 *
 * Android gets this for free - its grids are lazy and Coil only fetches what a visible cell asks
 * for. The `loading="lazy"` attribute would do it here, but it arrived in Chromium 76 and the
 * oldest sets this app installs on (Tizen 5.5) are Chromium 69, so an IntersectionObserver does
 * the same job. A margin of about a screen ahead means the next row down is already arriving
 * before the highlight gets there.
 *
 * An element that is thrown away before it comes near the screen never asks for anything.
 */

const AHEAD = '1080px 960px';

let observer: IntersectionObserver | null = null;
const pending = new Map<HTMLImageElement, string>();

function load(image: HTMLImageElement, url: string): void {
  pending.delete(image);
  image.src = url;
}

/**
 * Stops watching images whose page has been replaced. An observer keeps its targets alive after
 * they leave the document, so without this every category ever opened would stay in memory.
 */
function sweep(): void {
  for (const image of pending.keys()) {
    if (image.isConnected) continue;
    observer?.unobserve(image);
    pending.delete(image);
  }
}

function watcher(): IntersectionObserver | null {
  if (observer) return observer;
  if (typeof IntersectionObserver === 'undefined') return null;
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        const image = entry.target as HTMLImageElement;
        observer!.unobserve(image);
        const url = pending.get(image);
        if (url) load(image, url);
      }
    },
    { rootMargin: AHEAD },
  );
  return observer;
}

/**
 * Gives [image] its artwork once it is within about a screen of being visible.
 *
 * Decoding is marked asynchronous as well, so a large poster is decoded off the main thread
 * instead of stalling the key press that scrolled it into view. A failed image loses its `src`
 * rather than showing the broken-image mark, which is what every caller was already doing.
 */
export function lazyImage(image: HTMLImageElement, url: string | null | undefined, eager = false): void {
  image.decoding = 'async';
  image.addEventListener('error', () => image.removeAttribute('src'));
  if (!url) return;
  /*
   * The first screenful is asked for straight away, not through the observer.
   *
   * An observer reports at low priority - Chromium delivers its notifications when the page is
   * otherwise idle, with up to a tenth of a second's grace - and a television drawing a new page
   * is not idle. So the posters the viewer was already looking at waited behind the page that
   * contained them. Callers pass eager for the entries that are on screen when the page opens.
   */
  const io = eager ? null : watcher();
  if (!io) {
    load(image, url);
    return;
  }
  if (pending.size > 600) sweep();
  pending.set(image, url);
  io.observe(image);
}

/**
 * Starts the artwork of the [count] entries after [from], wherever they are.
 *
 * The margin above only reaches past the edge of the *screen*. A poster grid and a channel list
 * scroll inside their own boxes, and on the Chromium these sets carry an observer cannot look past
 * the edge of an inner scroller, so the row just below the fold would otherwise start loading only
 * as it scrolled in. Called as the highlight moves, this keeps the next couple of rows ahead of it.
 */
export function prefetchAfter(from: Element, count: number): void {
  let next = from.nextElementSibling;
  for (let i = 0; next && i < count; i++, next = next.nextElementSibling) {
    for (const image of next.querySelectorAll('img')) {
      const url = pending.get(image);
      if (!url) continue;
      observer?.unobserve(image);
      load(image, url);
    }
  }
}

/**
 * The size of a poster as drawn, for artwork TMDB serves.
 *
 * The Android app decodes every poster at the size of the card it lands in: Coil sizes a request
 * to its view, so a 2000-pixel poster costs a 316-pixel decode. A browser has no such thing - it
 * downloads the whole file and decodes all of it - and on a television's processor that is most
 * of the time between a grid appearing and its pictures appearing.
 *
 * Most Xtream panels hand out TMDB links for films and series, and TMDB serves any image at a set
 * of fixed widths chosen by one path segment. So where the link is TMDB's, the web asks for the
 * width the card actually needs - w342, the smallest at least as wide as the widest poster card,
 * 316px - which is the same pixels Android ends up with, fetched instead of thrown away. Any
 * other host is left exactly as the provider gave it. Recorded in web/README.md.
 */
const TMDB = /^(https?:\/\/image\.tmdb\.org\/t\/p\/)[^/]+(\/[^/]+)$/i;

export function posterArtwork(url: string | null | undefined): string | null {
  if (!url) return null;
  const match = TMDB.exec(url);
  return match ? `${match[1]}w342${match[2]}` : url;
}
