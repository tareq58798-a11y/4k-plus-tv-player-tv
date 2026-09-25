/**
 * The app's own on-screen keyboard, for every search box: the Search screen, and the category and
 * title boxes on Live TV, Movies and Series.
 *
 * The television app has no counterpart: Android TV brings its own keyboard up over a text field.
 * A Samsung set's system keyboard is an overlay the page cannot place or style, which covers the
 * very results it is meant to be narrowing, so the owner asked for the set's keyboard not to appear
 * at all and for this one instead. Every box it serves is made read-only to the set (see
 * [takeOverField]), which is what keeps the system keyboard away: it only opens for a box it could
 * type into. Recorded in web/README.md.
 *
 * Two layouts, because the catalogue is two scripts: Latin letters, and Arabic. Digits are a block
 * of their own under the letters in both, set apart so a number is not hunted for among letters.
 * It opens in Arabic when the app is in Arabic and in Latin otherwise; one key switches.
 *
 * On the Search screen it sits beside the results. On the browse screens there is no room for it
 * to stay up, so OK on a box opens it as a panel under that box ([attachKeyboardPanel]); it types
 * into the box as it goes, and Done or Back puts it away.
 */
import { isRtl, t } from '../shared/i18n';
import { focus, moveWithin, pushKeyHandler } from './focus';
import { iconElement } from './icons';

const LATIN = 'abcdefghijklmnopqrstuvwxyz'.split('');
/* The 28 letters in alphabetical order, then the forms a title is actually spelled with that are
   not among them: taa marbuta, alef maqsura, hamza and the hamza-carrying letters. Without those a
   viewer cannot type "مدرسة" or "أحمد" as the catalogue spells them. */
const ARABIC = 'ابتثجحخدذرزسشصضطظعغفقكلمنهوي'.split('').concat(['ة', 'ى', 'ء', 'أ', 'إ', 'آ', 'ؤ', 'ئ']);
/* In a keyboard's order, 0 last. Western digits in both layouts, because that is how the
   catalogue writes its years and numbers. */
const DIGITS = '1234567890'.split('');

type Layout = 'latin' | 'arabic';

export interface SearchKeyboard {
  element: HTMLElement;
  /** The key the highlight should start on. */
  firstKey(): HTMLElement;
}

/**
 * Stops the set's own keyboard from ever opening on [field].
 *
 * Read-only is what does it - the system keyboard is only offered for a box that can be typed into
 * - and inputmode="none" says the same to engines that read it. The box still shows its text and
 * placeholder, and this app's keys still change its value from script.
 */
export function takeOverField(field: HTMLInputElement): void {
  field.readOnly = true;
  field.setAttribute('inputmode', 'none');
  field.setAttribute('autocomplete', 'off');
}

/**
 * [field] is the box the keys type into. Keys edit its value and fire its `input` event, so each
 * screen's existing wait-for-typing-to-stop search runs exactly as it did for typed text.
 * [onDone], when given, adds a Done key that calls it.
 */
export function createSearchKeyboard(field: HTMLInputElement, onDone?: () => void): SearchKeyboard {
  takeOverField(field);
  let layout: Layout = isRtl() ? 'arabic' : 'latin';

  const element = document.createElement('div');
  element.className = 'search-keyboard';
  element.setAttribute('data-focus-group', 'search-keyboard');
  // The keys stay in reading order for their own script whatever the page direction is, the way a
  // real keyboard does not reverse itself.
  element.dir = 'ltr';

  const letters = document.createElement('div');
  letters.className = 'search-keys';
  const digits = document.createElement('div');
  digits.className = 'search-keys search-key-digits';
  const actions = document.createElement('div');
  actions.className = 'search-keys search-key-actions';
  element.append(letters, digits, actions);

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

  function type(ch: string): () => void {
    return () => edit(field.value + ch);
  }

  function drawLetters(): void {
    letters.textContent = '';
    letters.lang = layout === 'arabic' ? 'ar' : 'en';
    (layout === 'arabic' ? ARABIC : LATIN).forEach((ch, index) => letters.append(key(ch, `key-${index}`, type(ch))));
  }

  DIGITS.forEach((ch) => digits.append(key(ch, `key-digit-${ch}`, type(ch))));

  const spaceKey = key(iconElement('spaceBar', 'search-key-icon'), 'key-space', () => {
    // A leading or doubled space never helps a substring match, and on a remote it is almost
    // always a slip.
    if (field.value && !field.value.endsWith(' ')) edit(field.value + ' ');
  }, 'wide');
  spaceKey.setAttribute('aria-label', t('keyboard_space'));
  const deleteKey = key(iconElement('backspace', 'search-key-icon'), 'key-delete', () => edit(field.value.slice(0, -1)));
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

  if (onDone) {
    const done = key(iconElement('check', 'search-key-icon'), 'key-done', onDone, 'done');
    const label = document.createElement('span');
    label.textContent = t('keyboard_done');
    done.append(label);
    actions.append(done);
  }

  drawLetters();
  labelSwitch();

  return {
    element,
    firstKey: () => letters.firstElementChild as HTMLElement,
  };
}

/**
 * For the browse screens' boxes: OK on [field] opens the keyboard as a panel just under it.
 *
 * While it is up it owns the remote - the arrows walk its keys and never the page beneath, OK
 * presses a key, Back or Done put it away and hand the highlight back to the box - the same rules
 * as the app's dialogs. The box's results update underneath as each key is pressed.
 */
export function attachKeyboardPanel(field: HTMLInputElement): void {
  takeOverField(field);
  field.addEventListener('click', () => {
    if (document.querySelector('.keyboard-panel')) return;

    const panel = document.createElement('div');
    panel.className = 'keyboard-panel';
    const close = (): void => {
      release();
      panel.remove();
      focus(field);
    };
    const keyboard = createSearchKeyboard(field, close);
    panel.append(keyboard.element);
    document.body.append(panel);

    // Under the box, and kept on the screen: the category box is near the left edge, and in Arabic
    // the page is mirrored, so the panel lines up with whichever edge of the box starts the text.
    const box = field.getBoundingClientRect();
    const width = panel.offsetWidth;
    const height = panel.offsetHeight;
    const wanted = isRtl() ? box.right - width : box.left;
    panel.style.left = `${Math.max(16, Math.min(wanted, window.innerWidth - width - 16))}px`;
    panel.style.top = `${Math.max(16, Math.min(box.bottom + 12, window.innerHeight - height - 16))}px`;

    const release = pushKeyHandler((key) => {
      if (key === 'back') {
        close();
        return true;
      }
      if (key === 'up' || key === 'down' || key === 'left' || key === 'right') {
        moveWithin(panel, key);
        return true;
      }
      // OK falls through to press the highlighted key; nothing else reaches the page beneath.
      return key !== 'enter';
    });

    focus(keyboard.firstKey());
  });
}
