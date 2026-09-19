/**
 * One name for every button, whatever set it came from.
 *
 * The three television platforms disagree about key codes, and none of them matches a browser.
 * Samsung sends 412 for Rewind; LG sends 412 too but 461 for Back where Samsung sends 10009; a
 * desktop browser sends none of them and uses named keys instead. Rather than let those numbers
 * spread through the app, every screen deals in the names below and this file is the only place
 * that knows what a set actually transmits.
 *
 * Note Back. It is the one key a television will act on itself if the app ignores it - Samsung
 * closes the app - so the navigation code must always consume it, which is why it is named here
 * rather than left to fall through as an unknown.
 */
export type RemoteKey =
  | 'up'
  | 'down'
  | 'left'
  | 'right'
  | 'enter'
  | 'back'
  | 'play'
  | 'pause'
  | 'playpause'
  | 'stop'
  | 'rewind'
  | 'forward'
  | 'channelUp'
  | 'channelDown'
  | 'red'
  | 'green'
  | 'yellow'
  | 'blue'
  | 'exit';

/** Codes every platform agrees on, because they come from the standard keyboard set. */
const COMMON: Record<number, RemoteKey> = {
  37: 'left',
  38: 'up',
  39: 'right',
  40: 'down',
  13: 'enter',
  32: 'playpause',
  8: 'back',
  27: 'back',
};

/** Samsung Tizen. 10009 is Samsung's Return; 10182 is Exit, which must not be treated as Back. */
const TIZEN: Record<number, RemoteKey> = {
  10009: 'back',
  10182: 'exit',
  415: 'play',
  19: 'pause',
  10252: 'playpause',
  413: 'stop',
  412: 'rewind',
  417: 'forward',
  427: 'channelUp',
  428: 'channelDown',
  403: 'red',
  404: 'green',
  405: 'yellow',
  406: 'blue',
};

/** LG webOS. Kept here from the start: this same core ships to LG next, and leaving it out would
 *  mean coming back to rewrite the one file everything else routes through. */
const WEBOS: Record<number, RemoteKey> = {
  461: 'back',
  415: 'play',
  19: 'pause',
  413: 'stop',
  412: 'rewind',
  417: 'forward',
  33: 'channelUp',
  34: 'channelDown',
  403: 'red',
  404: 'green',
  405: 'yellow',
  406: 'blue',
};

/** A desktop browser, for developing without a television. Named keys, not codes. */
const BROWSER_BY_KEY: Record<string, RemoteKey> = {
  ArrowUp: 'up',
  ArrowDown: 'down',
  ArrowLeft: 'left',
  ArrowRight: 'right',
  Enter: 'enter',
  ' ': 'playpause',
  Backspace: 'back',
  Escape: 'back',
  MediaPlayPause: 'playpause',
  MediaStop: 'stop',
  MediaTrackNext: 'forward',
  MediaTrackPrevious: 'rewind',
};

export type PlatformName = 'tizen' | 'webos' | 'browser';

export function detectPlatform(): PlatformName {
  const anyWindow = window as unknown as Record<string, unknown>;
  if (anyWindow.tizen) return 'tizen';
  if (anyWindow.webOS || anyWindow.PalmSystem) return 'webos';
  return 'browser';
}

/**
 * Asks the set to deliver the buttons the app actually handles.
 *
 * Samsung sends only the four arrows and Enter unless an app registers for the rest, and a key
 * that is not registered does not arrive at all - the media keys simply never fire. Registering
 * names that a given set does not know throws, so each is registered on its own and a failure is
 * allowed to pass: a set without a colour button is not an error, it just has fewer buttons.
 */
export function registerPlatformKeys(platform: PlatformName): void {
  if (platform !== 'tizen') return;
  const tizen = (window as unknown as { tizen?: { tvinputdevice?: { registerKey(name: string): void } } }).tizen;
  const input = tizen?.tvinputdevice;
  if (!input) return;
  const wanted = [
    'MediaPlay',
    'MediaPause',
    'MediaPlayPause',
    'MediaStop',
    'MediaRewind',
    'MediaFastForward',
    'ChannelUp',
    'ChannelDown',
    'ColorF0Red',
    'ColorF1Green',
    'ColorF2Yellow',
    'ColorF3Blue',
  ];
  for (const name of wanted) {
    try {
      input.registerKey(name);
    } catch {
      /* This set does not have that button. Not a failure. */
    }
  }
}

export function keyOf(event: KeyboardEvent, platform: PlatformName): RemoteKey | null {
  const byName = BROWSER_BY_KEY[event.key];
  if (byName) return byName;
  const code = event.keyCode;
  const table = platform === 'tizen' ? TIZEN : platform === 'webos' ? WEBOS : {};
  return table[code] ?? COMMON[code] ?? null;
}
