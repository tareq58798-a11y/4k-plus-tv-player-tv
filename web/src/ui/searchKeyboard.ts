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
 * On the Search screen it sits beside the results, six keys wide. On the browse screens there is no
 * room for it to stay up, so OK on a box opens it as a wide panel along the bottom middle of the
 * screen ([attachKeyboardPanel]); it types into the box as it goes, and Done or Back puts it away.
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

/*
 * The wide panel's rows, in the order a Samsung set's own keyboard and a physical keyboard use:
 * QWERTY, and the standard Arabic (101) layout, ض on the key where Q is. The Arabic rows end with
 * ذ and the hamza-carrying alefs, which that layout reaches with Shift and a remote cannot. لا is
 * one key there and types both letters.
 */
const LATIN_ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'].map((row) => row.split(''));
const ARABIC_ROWS = [
  ['ض', 'ص', 'ث', 'ق', 'ف', 'غ', 'ع', 'ه', 'خ', 'ح', 'ج', 'د'],
  ['ش', 'س', 'ي', 'ب', 'ل', 'ا', 'ت', 'ن', 'م', 'ك', 'ط', 'ذ'],
  ['ئ', 'ء', 'ؤ', 'ر', 'لا', 'ى', 'ة', 'و', 'ز', 'ظ', 'أ', 'إ', 'آ'],
];

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
 * [onDone], when given, adds a Done key that calls it. [shape] is how the keys are laid out:
 *
 *  - `tall`: six keys wide, letters in alphabetical order above a block of digits, then Space,
 *    Delete, Clear and the layout switch - to stand beside the Search screen's results.
 *  - `wide`: laid out the way a Samsung set's own keyboard is, as the owner asked - QWERTY (or
 *    Arabic) rows with the layout switch, Space and Clear under them, a 1-9/0 number pad to their
 *    right, and Delete and Done beside that. For the browse screens' panel across the bottom.
 */
export function createSearchKeyboard(
  field: HTMLInputElement,
  onDone?: () => void,
  shape: 'tall' | 'wide' = 'tall',
): SearchKeyboard {
  takeOverField(field);
  let layout: Layout = isRtl() ? 'arabic' : 'latin';

  const element = document.createElement('div');
  element.className = `search-keyboard ${shape}`;
  element.setAttribute('data-focus-group', 'search-keyboard');
  // The keys stay in reading order for their own script whatever the page direction is, the way a
  // real keyboard does not reverse itself.
  element.dir = 'ltr';

  const wide = shape === 'wide';
  const div = (className: string): HTMLDivElement => {
    const node = document.createElement('div');
    node.className = className;
    return node;
  };
  const letters = div('search-keys search-key-letters');
  const digits = div('search-keys search-key-digits');
  const actions = div('search-keys search-key-actions');
  // Delete and Done, standing beside the number pad in the wide shape.
  const side = div('search-key-side');
  if (wide) {
    const lettersColumn = div('search-key-letter-column');
    lettersColumn.append(letters, actions);
    element.append(lettersColumn, digits, side);
  } else {
    element.append(letters, digits, actions);
  }

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
    if (wide) {
      // Row by row, each centred under the one above, as on a real keyboard.
      let index = 0;
      for (const row of layout === 'arabic' ? ARABIC_ROWS : LATIN_ROWS) {
        const line = div('search-key-row');
        for (const ch of row) line.append(key(ch, `key-${index++}`, type(ch)));
        letters.append(line);
      }
      return;
    }
    (layout === 'arabic' ? ARABIC : LATIN).forEach((ch, index) => letters.append(key(ch, `key-${index}`, type(ch))));
  }

  /** The first letter key, wherever the shape has put it. */
  function firstLetter(): HTMLElement {
    return letters.querySelector<HTMLElement>('.search-key')!;
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
  const doneKey = onDone ? key(iconElement('check', 'search-key-icon'), 'key-done', onDone, 'done') : null;
  if (doneKey) {
    const label = document.createElement('span');
    label.textContent = t('keyboard_done');
    doneKey.append(label);
  }

  if (wide) {
    // Under the letters, as on the set's keyboard: the language key, a long Space bar, Clear.
    actions.append(switchKey, spaceKey, clearKey);
    side.append(deleteKey);
    if (doneKey) side.append(doneKey);
  } else {
    actions.append(spaceKey, deleteKey, clearKey, switchKey);
    if (doneKey) actions.append(doneKey);
  }

  drawLetters();
  labelSwitch();

  return {
    element,
    firstKey: firstLetter,
  };
}

/**
 * For the browse screens' boxes: OK on [field] opens the keyboard as a wide panel at the bottom
 * middle of the screen, where it covers the least of the list the box is narrowing - the box itself
 * is at the top, and what it finds is in between.
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
    const keyboard = createSearchKeyboard(field, close, 'wide');
    panel.append(keyboard.element);
    document.body.append(panel);

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
