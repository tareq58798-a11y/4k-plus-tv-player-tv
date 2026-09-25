/**
 * An on-screen keyboard for the Search screen, drawn by the app itself.
 *
 * The television app has no counterpart: Android TV brings its own keyboard up over a text field,
 * and SearchScreen.kt just uses it. A Samsung set's system keyboard is a separate overlay the page
 * cannot style or position, which covers the results it is supposed to be narrowing, so here the
 * keys sit beside the results and every press updates them in place. The owner asked for it on the
 * Samsung app's Search screen only; the category and title boxes on the browse screens still use
 * the set's own keyboard. Recorded in web/README.md.
 *
 * Two layouts, because the catalogue is two scripts: Latin letters with digits, and Arabic. It
 * opens in Arabic when the app is in Arabic and in Latin otherwise, and the last key switches.
 *
 * The text box above stays focusable, so Up from the top row still reaches it and the set's own
 * keyboard - and its voice input - remain there for anybody who prefers them.
 */
import { isRtl, t } from '../shared/i18n';
import { iconElement } from './icons';

const LATIN = 'abcdefghijklmnopqrstuvwxyz0123456789'.split('');
/* The 28 letters in alphabetical order, then the forms a title is actually spelled with that are
   not among them: taa marbuta, alef maqsura, hamza and the hamza-carrying letters. Without those a
   viewer cannot type "مدرسة" or "أحمد" as the catalogue spells them. */
const ARABIC = 'ابتثجحخدذرزسشصضطظعغفقكلمنهوي'.split('').concat(['ة', 'ى', 'ء', 'أ', 'إ', 'آ', 'ؤ', 'ئ']);

type Layout = 'latin' | 'arabic';

export interface SearchKeyboard {
  element: HTMLElement;
  /** The key the highlight should start on. */
  firstKey(): HTMLElement;
}

/**
 * [field] is the search box. Keys edit its value and fire its `input` event, so the screen's
 * existing wait-for-typing-to-stop search runs exactly as it does for the set's own keyboard.
 */
export function createSearchKeyboard(field: HTMLInputElement): SearchKeyboard {
  let layout: Layout = isRtl() ? 'arabic' : 'latin';

  const element = document.createElement('div');
  element.className = 'search-keyboard';
  element.setAttribute('data-focus-group', 'search-keyboard');
  // The keys stay in reading order for their own script whatever the page direction is, the way a
  // real keyboard does not reverse itself.
  element.dir = 'ltr';

  const letters = document.createElement('div');
  letters.className = 'search-keys';
  const actions = document.createElement('div');
  actions.className = 'search-keys search-key-actions';
  element.append(letters, actions);

  function edit(next: string): void {
    field.value = next;
    field.dispatchEvent(new Event('input'));
  }

  function key(label: string | SVGSVGElement, id: string, onPress: () => void, extraClass = ''): HTMLButtonElement {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `search-key ${extraClass}`.trim();
    button.setAttribute('data-focus', '');
    button.setAttribute('data-focus-id', id);
    button.append(label);
    // Pressed from the remote through focus.ts, which clicks whatever holds the highlight. The
    // highlight stays on the key, so a word is typed without walking back to it each time.
    button.addEventListener('click', onPress);
    return button;
  }

  function drawLetters(): void {
    letters.textContent = '';
    letters.lang = layout === 'arabic' ? 'ar' : 'en';
    const set = layout === 'arabic' ? ARABIC : LATIN;
    set.forEach((ch, index) => {
      letters.append(key(ch, `key-${index}`, () => edit(field.value + ch)));
    });
  }

  const space = iconElement('spaceBar', 'search-key-icon');
  const backspace = iconElement('backspace', 'search-key-icon');
  const spaceKey = key(space, 'key-space', () => {
    // A leading or doubled space never helps a substring match, and on a remote it is almost
    // always a slip.
    if (field.value && !field.value.endsWith(' ')) edit(field.value + ' ');
  }, 'wide');
  spaceKey.setAttribute('aria-label', t('keyboard_space'));
  const deleteKey = key(backspace, 'key-delete', () => edit(field.value.slice(0, -1)));
  deleteKey.setAttribute('aria-label', t('keyboard_delete'));
  const clearKey = key(t('cd_clear'), 'key-clear', () => edit(''), 'wide');
  // Names the layout it switches *to*, like a phone's keyboard does.
  const switchKey = key('', 'key-layout', () => {
    layout = layout === 'arabic' ? 'latin' : 'arabic';
    drawLetters();
    labelSwitch();
  });
  function labelSwitch(): void {
    switchKey.textContent = layout === 'arabic' ? 'ABC' : 'عربي';
  }
  actions.append(spaceKey, deleteKey, clearKey, switchKey);

  drawLetters();
  labelSwitch();

  return {
    element,
    firstKey: () => letters.firstElementChild as HTMLElement,
  };
}
