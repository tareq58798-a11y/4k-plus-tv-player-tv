/**
 * The two provider servers a playlist can be added against, from ApprovedServers.kt.
 *
 * The television's Add Playlist screen has no address box: it offers "Server 1" and "Server 2",
 * labelled by position, so the order here is the order a viewer is told which number to pick and
 * must match the television's. A device-assigned playlist (Check for a playlist) brings its own
 * server and is not limited to these, as on Android.
 */
export const APPROVED_SERVERS: readonly string[] = [
  'http://bag41135.wd.4kplus-tv-za.xyz/',
  'http://dtamadeus.com:80',
];
