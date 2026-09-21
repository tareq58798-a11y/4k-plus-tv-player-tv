/**
 * The few strings this app needs that the Android app has no equivalent for.
 *
 * Everything the two apps share lives in strings.generated.ts and is read straight out of the
 * Android resources - that is the point of the extraction, and adding to it here would defeat it.
 * This file is only for text that genuinely has no counterpart, such as the free-text server
 * address field, which the television app does not have because it offers a list of servers.
 *
 * English only for now, and deliberately visible as a gap: these want translating into the other
 * seven languages before this ships, and the right home for them is the Android resources once
 * the television app is unfrozen, so both sides keep one set.
 */
export const LOCAL_EN = {
  connect: 'Connect',
  enter_all_fields: 'Fill in the address, username and password.',
  server_address_label: 'Server address',
  press_back_to_return: 'Press Back to return',
  // The player's own chrome. The television app has no counterpart for these because it uses
  // media3's built-in controller, which brings its own labels - this app draws the controls
  // itself, so it has to name them.
  cd_rewind: 'Rewind',
  cd_forward: 'Fast forward',
  playback_speed: 'Playback speed',
  speed_normal: 'Normal',
  audio_track: 'Audio',
} as const;

export type LocalStringKey = keyof typeof LOCAL_EN;
