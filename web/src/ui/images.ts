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
export function lazyImage(image: HTMLImageElement, url: string | null | undefined): void {
  image.decoding = 'async';
  image.addEventListener('error', () => image.removeAttribute('src'));
  if (!url) return;
  const io = watcher();
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
