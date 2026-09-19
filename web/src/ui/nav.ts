/**
 * The bar across the top, ported from TopNav.kt.
 *
 * Two behaviours from the television app that are easy to miss and obvious when absent:
 *
 *  - **Moving the highlight along the bar changes the page.** The tabs are not buttons you press;
 *    arriving on one is the choice. OK then enters the page that is already showing, landing on
 *    its first box.
 *  - **The tab is only lit while the highlight is on the bar.** Once focus moves down into the
 *    page, the indicator leaves the tab - otherwise two things on screen both look selected and
 *    neither is clearly where the next press will go.
 */
import { t } from '../shared/i18n';

export type Section = 'home' | 'live' | 'movies' | 'series';

export const SECTIONS: { id: Section; label: () => string }[] = [
  { id: 'home', label: () => t('nav_home') },
  { id: 'live', label: () => t('nav_live_tv') },
  { id: 'movies', label: () => t('nav_movies') },
  { id: 'series', label: () => t('nav_series') },
];

export interface NavOptions {
  current: Section;
  onSection: (section: Section) => void;
  /** OK on a tab: go into the page that is already showing. */
  onEnter: () => void;
  subtitle?: string;
}

export function renderNav(host: HTMLElement, options: NavOptions): HTMLElement {
  const bar = document.createElement('div');
  bar.className = 'top-bar';

  const brand = document.createElement('img');
  brand.className = 'brand-logo';
  brand.src = './brand_logo.png';
  brand.alt = '4K Plus TV';
  bar.append(brand);

  for (const section of SECTIONS) {
    const tab = document.createElement('div');
    tab.className = 'tab nav-tab';
    tab.tabIndex = -1;
    tab.textContent = section.label();
    tab.setAttribute('data-focus', '');
    tab.setAttribute('data-focus-id', `tab-${section.id}`);
    // Up from the bar goes nowhere. Without this the highlight escapes to whatever the browser
    // considers above it, which on these pages is nothing at all.
    tab.setAttribute('data-focus-up', 'none');
    tab.setAttribute('aria-selected', String(section.id === options.current));
    tab.addEventListener('focus', () => {
      for (const other of bar.querySelectorAll('.nav-tab')) {
        other.setAttribute('aria-selected', String(other === tab));
      }
      if (section.id !== options.current) options.onSection(section.id);
    });
    tab.addEventListener('click', options.onEnter);
    bar.append(tab);
  }

  const spacer = document.createElement('div');
  spacer.className = 'spacer';
  bar.append(spacer);

  if (options.subtitle) {
    const note = document.createElement('div');
    note.className = 'clock';
    note.textContent = options.subtitle;
    bar.append(note);
  }

  host.append(bar);
  return bar;
}

/**
 * Dims the tab once the highlight has left the bar, and lights it again when it returns.
 *
 * Watched on the document rather than wired into each tab, because the thing being tracked is
 * where focus *is*, not what any one element did.
 */
export function trackNavHighlight(bar: HTMLElement): () => void {
  const update = () => {
    const onBar = document.activeElement instanceof HTMLElement && bar.contains(document.activeElement);
    bar.classList.toggle('bar-focused', onBar);
  };
  document.addEventListener('focusin', update);
  update();
  return () => document.removeEventListener('focusin', update);
}
