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
import { latinDigits, locale, t } from '../shared/i18n';

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
  onSearch: () => void;
  onLanguage: () => void;
  onSettings: () => void;
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
    // Down enters the page at its first box, not at whichever card happens to sit nearest below
    // the tab. Geometry gets this wrong: the Home tab is centred over the gap between the first
    // and second card, so nearest-neighbour picks the second and the page appears to start in the
    // middle of a row.
    tab.setAttribute('data-focus-down', '.row [data-focus]');
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

  // Search and settings, as glyphs rather than words: they sit beside four tabs that are words,
  // and at this size two more would read as another four sections rather than as tools.
  //
  // Drawn, not typed. These were text glyphs - the globe an emoji, the others arrows and gears
  // from whatever font the set happened to resolve. Three problems came with that, all visible on
  // the television: the emoji rendered in full colour, so the globe was blue while its neighbours
  // were grey; U+2325 came out markedly smaller than the gear beside it, because a font is under
  // no obligation to size unrelated symbols alike; and the gear itself was whichever one Tizen
  // had. These are the paths behind Icons.Default.Search, Language and Settings in the television
  // app, so all three are now the same icons at the same weight, taking their colour from the
  // button like everything else on the bar.
  const ICONS: Record<string, string> = {
    search:
      'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14',
    language:
      'M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2m6.93 6h-2.95a15.7 15.7 0 0 0-1.38-3.56A8.03 8.03 0 0 1 18.92 8M12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96M4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2s.06 1.34.14 2zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56A7.99 7.99 0 0 1 5.08 16m2.95-8H5.08a7.99 7.99 0 0 1 4.33-3.56A15.7 15.7 0 0 0 8.03 8M12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96M14.34 14H9.66c-.09-.66-.16-1.32-.16-2s.07-1.35.16-2h4.68c.09.65.16 1.32.16 2s-.07 1.34-.16 2m.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95a8.03 8.03 0 0 1-4.33 3.56M16.36 14c.08-.66.14-1.32.14-2s-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2z',
    settings:
      'M19.14 12.94c.04-.3.06-.61.06-.94s-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.48.48 0 0 0-.48.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.48-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61zM12 15.6A3.6 3.6 0 0 1 8.4 12c0-1.98 1.62-3.6 3.6-3.6s3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6',
  };
  const tools: [string, string, () => void][] = [
    ['search', t('cd_search'), options.onSearch],
    // Language sits between the two, as on the television app. It is a destination people look
    // for by eye rather than by name, so the globe earns its place beside the other two glyphs.
    ['language', t('cd_language'), options.onLanguage],
    ['settings', t('cd_settings'), options.onSettings],
  ];
  for (const [icon, label, action] of tools) {
    const button = document.createElement('div');
    button.className = 'tool';
    button.tabIndex = -1;
    // currentColor throughout, so a focused button lights its glyph with its text rather than
    // needing a second rule to keep them in step.
    button.innerHTML =
      `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">` +
      `<path fill="currentColor" d="${ICONS[icon]}"/></svg>`;
    button.title = label;
    button.setAttribute('aria-label', label);
    button.setAttribute('data-focus', '');
    button.setAttribute('data-focus-id', `tool-${label}`);
    button.setAttribute('data-focus-up', 'none');
    // Unlike a tab, these do nothing on arrival - moving past the search icon should not open
    // search. They need the press.
    button.addEventListener('click', action);
    bar.append(button);
  }

  /*
   * The date and time, where the television app puts them.
   *
   * This corner used to carry a count of items in the playlist, which is a number that answers a
   * question nobody has. The clock answers one people ask constantly, and a television is the
   * thing they ask it of - which is why the set's own menus put one here too.
   *
   * Ticks every fifteen seconds rather than every second: the display has no seconds in it, and
   * fifteen is close enough that the minute never looks stale while costing almost nothing on a
   * set that is also decoding video.
   */
  const clock = document.createElement('div');
  clock.className = 'clock';
  bar.append(clock);

  function paintClock(): void {
    const now = new Date();
    const date = now.toLocaleDateString(latinDigits(locale()), { weekday: 'short', day: 'numeric', month: 'short' });
    const time = now.toLocaleTimeString(latinDigits(locale()), { hour: 'numeric', minute: '2-digit' });
    clock.textContent = `${date}  ·  ${time}`;
  }
  paintClock();
  const ticking = window.setInterval(() => {
    // Stops itself once the bar has been replaced, so navigating away does not leave a timer
    // painting into an element nobody can see.
    if (!clock.isConnected) {
      window.clearInterval(ticking);
      return;
    }
    paintClock();
  }, 15000);

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
