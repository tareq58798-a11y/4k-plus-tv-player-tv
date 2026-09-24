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
  subtitles_label: 'Subtitles',
  subtitles_off: 'Off',
  // The two halves of the switch at the top of the player's subtitle menu. Worded the way the
  // television app words them in PlaybackOptionsOverlay - the label says what the press will do,
  // not what the state currently is, which is the difference between a button and a readout.
  subtitles_turn_on: 'Turn subtitles on',
  subtitles_turn_off: 'Turn subtitles off',
  /*
   * The player's aspect-ratio menu, worded as the television app words it.
   *
   * Its Settings screen has three of these as resources - Fit, Fill, Stretch - but the player's
   * own dropdown spells them out at more length and adds four named frames, all hardcoded English
   * inside the composable. So they start life here, like the rest of this file, and the Settings
   * screen keeps using the extracted resource strings for the three it offers.
   */
  scale_fit: 'Fit video',
  scale_stretch: 'Stretch to screen',
  scale_zoom: 'Fill and crop',
  scale_16_9: '16:9 Standard',
  scale_4_3: '4:3 Traditional',
  scale_21_9: '21:9 Ultrawide',
  scale_1_1: '1:1 Square',
  // Both of these are hardcoded English inside the television app's own composable rather than
  // resources, so there is nothing to extract and they start life here like the rest of this file.
  subtitle_background: 'Background',
  skip_seconds_format: '{1}s',
  current_resolution: 'Resolution',
  // Not "unknown". The stream has not said yet, which is a different thing from there being no
  // answer, and it stops being true a second later.
  resolution_unavailable: 'Not reported yet',
  // The second choice when a film or episode has a saved position. The television resumes without
  // asking, so it has no string for starting again; "Resume {1}" is its own and is used beside
  // this one.
  play_from_beginning: 'Play from beginning',
} as const;

export type LocalStringKey = keyof typeof LOCAL_EN;
