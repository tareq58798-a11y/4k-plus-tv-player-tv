# 4K Plus TV Player — web core

One codebase for **Samsung Tizen**, and later LG webOS, Hisense VIDAA and Windows. This is
codebase B in [`../PORTING.md`](../PORTING.md).

Separate project, separate build, separate everything — nothing here can affect the Android
build, and Gradle does not know this folder exists.

## What is shared with the Android app, and how

Samsung televisions run web apps. There is no way to execute Kotlin or Compose on one, so the
Android *code* cannot be shared. What is shared is shared properly, by reading the Android
sources at build time rather than by copying:

| Shared | From | How |
|---|---|---|
| 310 strings × 8 languages | `app/src/main/res/values[-locale]/strings.xml` | `scripts/extract-strings.mjs` |
| Colours, spacing, radii, timings | `ui/design/Tokens.kt` | `scripts/extract-tokens.mjs` |
| The artwork | `res/drawable-nodpi` | `scripts/extract-assets.mjs` |

**dp is not a CSS pixel.** An Android television at 1080p runs at density 2, so its layout is
960dp across while this page is a real 1920. Copied one for one, every measurement comes out at
half the size it was designed at - the cards `Tokens.kt` says fit five across a panel fitted
eleven, which is how this was caught. `DP_TO_PX` in the extractor is that factor, and it has to
change if the canvas in `index.html` ever stops being 1920x1080.

Both run on every `npm run dev` and `npm run build`. Fix a translation or a colour on Android and
it lands here at the next build. The generated files are gitignored so there is only ever one
copy to edit, and `StringKey` is a union of the real keys — a name that does not exist in the
Android resources fails to compile rather than showing up blank on a television.

The rest carries over as behaviour, not code: the Xtream endpoints and their quirks
(`src/shared/xtream.ts`, ported from `XtreamProviderClient.kt` with its comments intact), and
every D-pad rule (`src/ui/focus.ts`).

## Running it

```
npm install
npm run dev
```

Then open <http://localhost:5178>. Arrow keys are the remote, Enter is OK, Backspace is Back.

## Packaging for a television

Needs Tizen Studio, which is not installed on this machine yet.

```
npm run build
```

`dist/` plus `tizen/config.xml` is the widget. Two things must be filled in first:

* The ten-character package prefix in `tizen/config.xml` is a placeholder. Samsung issues the
  real one when the app is registered in Seller Office, and a set will only install a widget whose
  signature matches it.
* An author certificate, created in Tizen Studio's Certificate Manager.

## Platform differences, and where they live

Everything a television does differently is behind `src/platform/`, so no screen has to know
which set it is running on.

* **`keys.ts`** — Samsung sends 10009 for Back, LG sends 461, a browser sends `Escape`. Samsung
  also delivers *only* the arrows and Enter until an app registers for the rest, which is why the
  media keys are asked for by name at startup.
* **`video.ts`** — Samsung does not play video in an HTML element. AVPlay draws on a hardware
  plane *behind* the page, and the page shows it through a transparent background, so the
  rectangle is handed to the decoder in real device pixels rather than CSS ones. A browser uses an
  ordinary `<video>`, with hls.js for streams it cannot open itself.
* **Stream quality** — PlayerEngine.kt's intent carried into AVPlay's streaming properties, set
  between `open()` and `prepareAsync()`: Android's `VLC/3.0.20` user agent on every stream, and,
  for adaptive (HLS/DASH) streams only, one `ADAPTIVE_INFO` of `STARTBITRATE=HIGHEST` (the
  counterpart of `setForceHighestSupportedBitrate` in the FAST mode that is this app's only mode)
  plus `FIXED_MAX_RESOLUTION=3840x2160` on panels productinfo reports as UHD. `SET_MODE_4K` is not
  used: Samsung deprecated it from Tizen 5.0. Direct `.ts` channels and film files get no adaptive
  settings, which can do nothing for a single rendition and have been reported to break playback
  on 2020 sets. Each call is guarded; a set that refuses one still plays.
* **Live stream container** — AVPlay decodes the MPEG-TS that Xtream serves directly; a browser
  has no demuxer for it outside HLS, so the browser build asks the same panel for `.m3u8`
  instead. This is the one place the two request different URLs, and it is a fact about browsers
  rather than a preference.

hls.js is a separate chunk on purpose. It exists only so this can be developed without a
television, and folding it into the main bundle would have a Samsung set parse 595 kB of code,
on a slow engine, before the first frame, for a player it will never construct.

### Things the television app has that this deliberately does not

These are decided, not outstanding. Each one was looked at against the Android app and left out
because the platform makes it a worse idea here, and writing that down is the only thing that
stops it being reopened every few months.

* **Frames from the channel on its card.** The television captures a still from each recently
  watched channel's stream (LiveSnapshotCapture.kt) by opening the stream in the background and
  decoding a frame. A Samsung web app cannot: AVPlay draws on a hardware plane the page cannot
  read, drawing a video into a canvas stopped working after Tizen 2, and Samsung's WebAssembly
  decoder is only on sets newer than this app's Tizen 5.5 floor. Channel cards show the channel's
  logo. Looked at and left out on 2026-09-24.
* **Mute.** The television app mutes its own ExoPlayer instance, which affects that stream and
  nothing else. Tizen offers no per-stream volume for AVPlay; the available API,
  `tizen.tvaudiocontrol`, mutes the *television*. An app that silences the whole set from its own
  player button, and leaves it silenced for whatever the viewer switches to next, is doing
  something materially different from what the button says. Left out on 2026-09-22.
* **The fullscreen button.** Last in the television app's options row, and meaningless here: this
  player has no smaller state to return to. Left out on 2026-09-24, when the row was rebuilt to
  match; the buttons that remain keep their original positions rather than closing the gap.
* **Player engine and connection mode.** Both are ExoPlayer settings - external players and
  buffering strategy - with no AVPlay counterpart. See the note on `playbackPage` in `ui/settings.ts`.
* **Posters decoded at the size they are drawn.** Coil decodes each poster at the size of its card,
  so Android throws away pixels it downloaded. A browser cannot decode at a smaller size, and on a
  television's processor decoding full-size posters is most of the wait for a grid's pictures. So
  where a poster link is TMDB's (`image.tmdb.org/t/p/<size>/...`), the web asks TMDB for `w342`
  instead, the smallest width at least as wide as the widest card. Backgrounds and every other
  host's links are left as the provider gave them. See `posterArtwork` in `ui/images.ts`.
* **Player choices are per title.** The television app writes the subtitle background and skip
  length chosen in the player back to its settings. Here nothing chosen in the player outlasts
  the title (shape, tracks, subtitle background and size, skip length, speed), at the owner's
  request; Settings is where lasting defaults are set. Decided on 2026-09-24.
* **How soon the background changes.** The television waits three seconds on a title before its
  artwork becomes the background (`Motion.BackdropDebounceMs`). Here it is 200ms, at the owner's
  request that it be instant; that is still longer than a held key's repeat, so running along a
  row does not fetch every title passed. See `SETTLE_MS` in `ui/backdrop.ts`. Decided on
  2026-09-24.
* **Skip buttons speed up.** The television's rewind and fast-forward move by the skip length
  however often they are pressed. Here presses in quick succession grow: after two, each is worth
  three skips, then six, then twelve. The timeline itself follows the television (a twentieth of
  the running time per press, sent to the decoder once the presses stop). Decided on 2026-09-24.
* **A category search on Movies and Series.** The television has a "Search categories" box only
  on Live TV, beside "Search channels". Here all three sections have both boxes - categories at
  the top of the category column, titles at the top of the posters or channels - at the owner's
  request. Decided on 2026-09-24.
* **Background artwork is shown whole.** The television crops a title's artwork to fill the
  screen. Here it is fitted inside the screen with dark space either side, at the owner's request,
  so a poster used as a background shows all of itself rather than a slice of its middle. A
  landscape backdrop of the screen's shape looks the same either way. Decided on 2026-09-24.
* **English digits in every language.** Dates and times are formatted with Latin digits
  (`-u-nu-latn`, see `latinDigits` in `shared/i18n.ts`), so Arabic shows 11:08 rather than ١١:٠٨,
  at the owner's request. Month and day names stay in the viewer's language. Decided on
  2026-09-24.
* **A Trailer button on a series' page.** The television reads a series' `youtube_trailer` but
  only shows the button on a film's page. Here both have it, at the owner's request - still only
  when the provider supplied a trailer, and it opens that video in the YouTube app, never a
  search. Decided on 2026-09-24.
* **Home's Continue Watching is ordered by time.** The television's row mixes films, series and
  channels but, keeping no times, orders them as the last title touched, then films, series,
  channels. Here each is timestamped when watched and the row is simply most recent first.
  Decided on 2026-09-24.
* **Back goes to Home.** The television steps back a level at a time (category browser to its
  section, section to Home, Home to "Exit app?"). Here Back from any page - a section, a category
  browser, a film's page, a series, search, Settings - goes straight to Home, and Home asks the
  television's "Exit app?", at the owner's request. Exceptions: a Settings sub-page goes up to the
  Settings menu first; a category browser goes to its own section's page (Live TV, Movies,
  Series); and the player, a film's page and a series' page return to where the title was chosen -
  its category in the library, with the highlight on it, or its row on a landing page. A title
  chosen from a Recently watched or Continue watching row returns to that category in its own
  library.
  Decided on 2026-09-24.
* **Start muted, and embedded-subtitle handling.** Decisions the decoder makes for us on this
  platform. A switch that does nothing is worse than an absent one.
* **Down opening the episode strip while the controls are up.** On the television,
  `advanceDownThroughControls` opens the strip from the first Down whenever there is one, from
  wherever the highlight is. Here Down with the controls up walks play/pause, timeline and gear
  as it does for a film, and the strip opens only from a bare picture. Changed at the owner's
  request on 2026-09-24: a press meant for the timeline was swapping the controls for the season.
  The Android app is unchanged.
* **The television's two backdrop scrims on Home, Movies and Series.** The television darkens the
  artwork with a left-hand gradient (85% at the edge) and a top-to-bottom one (88% at the foot).
  Here every section gets the flat 30% black that Live TV already had, so the other three are as
  light as Live TV. Changed at the owner's request on 2026-09-24. The Android app is unchanged.
* **Mirrored player controls in Arabic.** The television lets media3's controller follow the
  language, so in Arabic rewind is on the right and fast-forward on the left, and its options bar
  moves to the top left. Here the transport, the timeline, the bottom bar and the options row are
  pinned left to right, top right, in every language, because a player's controls read the same
  way everywhere. The words stay Arabic, and the episode strip still reads right to left. Changed
  at the owner's request on 2026-09-24. The Android app is unchanged.
* **(The other way round) a count on every category.** The browse screens' category lists show how
  many channels, films or series each category holds, at the end of the row. The television's
  CategoryPill is the name alone. Added at the owner's request on 2026-09-24; the Android app is
  unchanged.
* **(The other way round) holding OK on a poster stars it.** The television's channel rows toggle a
  favourite on a held OK, and this does the same there. Its film and series posters carry a star
  that only touch can reach, so here the held OK works on posters too - the one gesture wherever a
  title is listed. Added at the owner's request on 2026-09-24; the Android app is unchanged.
* **(The other way round) CH+ and CH- change channel.** The set sends them to this app because it
  registers them, and the television app has no handler for them. In a channel they zap, as Up and
  Down do with the controls down. Added on 2026-09-24; the Android app is unchanged.
* **(The other way round) resume or start again.** The television resumes a film or episode from
  its saved position without asking, and has no way to start one over. Here a title with a saved
  position asks first - "Resume 12:34" or "Play from beginning" - and positions are also saved
  every fifteen seconds while playing, not only on leaving. "Play from beginning" has no Android
  string yet, so it is in strings.local.ts in English only. Added at the owner's request on
  2026-09-24; the Android app is unchanged.
* **(The other way round) a subtitle size.** Small, Medium, Large and Extra large in the player's
  subtitles menu on films and series, kept between titles. The television leaves captions to
  media3 and has no size setting. Medium is the 40px this player always drew; the labels are in
  strings.local.ts in English only. Added at the owner's request on 2026-09-24; the Android app is
  unchanged.
* **Named aspect frames that always change the picture.** See the table above. The television's
  16:9 and 4:3 do nothing to a stream already of a similar shape; here each gives its shape.
  Changed at the owner's request on 2026-09-24; the Android app is unchanged.

## State

Working: the shared extraction, the Xtream client, the D-pad engine, the key map, storage, the
AVPlay and browser players, and a vertical slice — sign in, load, browse Live TV by category,
play a channel.

Also working: Home, Live TV, Movies and Series as landing pages, the category browser, the series
page with seasons and episodes, search across the whole playlist, settings with all eight
languages, now-and-next listings in Live TV, the layered backdrop, favourites, resume points, and
a catalogue cache that opens the app on what is already on the set.

Playback carries playback speed, audio track selection, video scaling and subtitle track
selection. Parental controls are built - PIN, locked categories and locked channels, enforced
where the grid is drawn rather than on focus, so a locked category cannot show its contents even
when it is the one the page opens on.

Not built: the Playback, App info, and Privacy and history pages in settings, and the subtitle
background toggle.

This runs on real hardware. It is installed on a QN65QN800C and the layout work has been measured
there through the set's own web inspector rather than judged on the desktop - see
[TIZEN-RELEASE.md](TIZEN-RELEASE.md) for how to open that, and for what publishing this to anyone
other than ourselves actually involves.

Measured on that set, against a 50,299-item catalogue, at version 8.2.0 on Tizen 9.0:

| | |
| --- | --- |
| Live TV preview | starts on arrival; AVPlay `PLAYING`, clock advancing in real time |
| Page cleared for video | `body` background `none`, all three backdrop layers `none` |
| Resolution readout | `1920 x 1080`, from `{"fourCC":"video/x-h264","Width":"1920",…}` |
| Channel sort | default / A-Z / Z-A each produce a different first three |
| Browse grid | 400 posters, 7 across, 168x252, first row level with the pane, none zero-height |
| Focus ring, Home | card scales to 335, ring at 67.5, both clip edges at 64 |
| Focus ring, grid | exactly one ring; top 103.3 against a clip at 95 |
| App info | version and Tizen release read from the installed widget, not compiled in |

Several commit messages from the days before this carry a line saying their change was built but
not seen working, because the emulator would not hold an inspector session against a catalogue
this size. Those are now stale: everything in the table has been watched on the set. The commits
are left as they were written rather than rewritten, since what somebody knew at the time is part
of the record.

### The player's layout is copied, not approximated

The television app does not draw its own controls for a recording - it uses media3's
`PlayerControlView` with a Compose options row on top. So the arrangement is not a matter of taste
anywhere: it is fixed by `exo_player_control_view.xml` and its dimens inside `media3-ui-1.5.1.aar`,
plus `PlaybackOptionsOverlay` in `PlayerComponents.kt`. Both were read out of the AAR and the
source rather than judged from screenshots, and every number in `ui/player.ts` and the `.pc-*`
rules is that layout's dp doubled, because a television is 960dp wide on a 1920px panel.

What that fixes in place:

| | |
| --- | --- |
| Scrim | `exo_black_opacity_60`, flat across the whole picture - not the foot gradient this used to draw |
| Title | 18dp in, 14dp down, 20sp semi-bold, two lines, 55% width |
| Options row | top right, 68% black at a 13dp radius, 38dp buttons: subtitles, skip, resolution, aspect |
| Transport | `exo_center_controls`, rewind / play-pause / forward, centres 71dp apart |
| Timeline | full width, 2dp line, 10dp scrubber, 52dp up from the foot |
| Bottom bar | 60dp of `#b0000000`, `position / duration` at the start, the settings gear at the end |
| Focus | transparent at rest; focused is `#D9041C2B` under a 3dp `#23D7EE` ring, from `exo_control_focus_selector.xml` |
| Down walks | options -> play/pause -> timeline -> gear, then stops |

A channel gets none of it, which is also copied: `useController = false` in `LiveChannelPreview`
means Live TV on the television has no controller at all, so there is no scrim, no transport, no
timeline and no bottom bar - only the options row and a banner in the bottom-left corner carrying
the logo, the name, the resolution, and what is on now and next.

A channel also opens with its options row hidden and nothing highlighted, as the television does
(`if (hostedFullscreen) controllerVisible = false`). The banner is not part of that row and does
not hide with it: it appears the moment the channel opens and on every channel change, waits for
the resolution to arrive rather than running a fixed timer - that line is the one thing a viewer
is waiting for, and a slow channel would otherwise lose it - and then stays 3s longer, capped at
3.5s of waiting. Both numbers are `RESOLUTION_WAIT_MS` and `RESOLUTION_READ_MS`.

The keys are the television's, with one addition. Right fetches the options row and Left from its
first button puts it away - `towardsBar` and `awayFromBar`, which mirror with the language, so in
Arabic they swap. Running off the far end does nothing, because that is the direction the opening
key points and one press should not both open and shut the row. Up and Down change channel while
the row is down. The addition is a five-second inactivity timeout on the row, which the television
does not have: there it stays up until Left dismisses it.

Back closes the row when it is up, and leaves the channel when it is already down - the same two
stages as `BackHandler(enabled = controllerVisible)` on the television, where Back is only claimed
while the bar is visible and otherwise falls through to leaving fullscreen. OK also leaves a
channel, so there are two ways out and neither depends on the row's state.

The aspect-ratio menu carries all seven of the television's shapes. Three are AVPlay display
methods; the four named frames are built from the display *rectangle*, which is the only thing on
this platform that can be any shape at all - aim the decoder at a box of the right proportions and
tell it to fill that box exactly.

A named frame is the largest box of that shape that fits the screen, and the picture fills it.
That is deliberately not the television's arithmetic. `applyRequestedAspectRatio` scales a surface
`RESIZE_MODE_FIT` has already fitted, so what it squashes is the fitted picture rather than the
frame - and on the television 16:9 does nothing to a 16:9 stream, and 16:9 does nothing to a 4:3
one either. That was reproduced here faithfully, and reported as the dimension controls not
working. For a stream already the screen's shape the rectangles are unchanged:

| Mode | Rectangle handed to AVPlay |
| --- | --- |
| Fit video | `0,0,1920,1080` letter-box |
| Stretch to screen | `0,0,1920,1080` full-screen |
| Fill and crop | `0,0,1920,1080` cropped-full |
| 16:9 Standard | `0,0,1920,1080` full-screen |
| 4:3 Traditional | `240,0,1440,1080` |
| 21:9 Ultrawide | `0,129,1920,823` |
| 1:1 Square | `420,0,1080,1080` |

For a stream of another shape they now give that shape - a 4:3 stream asked for 16:9 is stretched
to the whole screen, where the television leaves it pillarboxed. Where a set refuses
`CROPPED_FULL`, Fill and crop builds the crop from the rectangle instead: the picture at its own
shape, scaled to cover the screen and centred, so a 4:3 stream is handed `0,-180,1920,1440`.

A shape picked in the player lasts for that title only, as on the television, whose menu sets
`videoMode` and deliberately does not store it. The next title starts from the Settings default.

Settings still offers three of these rather than seven, which is what the television's Settings
screen does: that is the standing default somebody sets once, and the named frames are answers to
what is on screen right now.

One thing is deliberately *not* copied. `exo_media_button` is 71dp by 52dp and the focus drawable
is an oval on that box, so a focused play button on the television is a stretched ellipse. It
looks like a mistake there and it looked like one here, so the transport buttons are square and
the gap between them puts back the width that loses - the three glyphs sit exactly where Android
has them, and the ring around them is a circle.

There is no subtitle button in the bottom bar because there is none on the television either:
media3 hides `exo_subtitle` unless `setShowSubtitleButton(true)` is called, and the television app
never calls it. The gear is the only icon down there on both.

### The listings work, and the commit that says otherwise is wrong

`e004c0f` ends with a line saying the now-and-next panel "could not be verified and probably never
will be on this playlist" because "this provider has no listings to put in it". That is not true,
and it was reached by trying a handful of channels in one sports category — several of which were
the separator rows that same commit was about, so they were never going to answer.

Counted properly, against the live catalogue: **10,942 channels, 1,827 of them carrying an EPG id**,
and of twelve sampled by `get_short_epg`, three returned listings. So roughly one channel in six has
a schedule, which is the provider's data rather than a fault here. Verified on the set on BBC 3, by
key presses, in both places the panel appears:

| | |
| --- | --- |
| Browse guide | populated — `Next: 02:00 AM TimeShift 20` |
| In-player panel | visible, `x: 80, y: 108` — below the title, which ends at 97, and left-aligned with it |
| Empty "now" line | hidden, and the progress bar with it, because no programme spans the clock |

That last row is the intended behaviour rather than a gap: `pick()` only fills the line it has a
programme for. A channel with no EPG id at all still shows nothing, and should.

## Installing on a Samsung emulator or television

Three things here are not what the general Tizen documentation says, and each cost an hour:

1. **`sdb install` does not work on a Samsung TV image.** Its sdbd rejects the path outright
   (`is_pkg_file_path`) and the connection drops, which surfaces only as `closed`. A Samsung set
   installs through its own web app service: `sdb shell 0 vd_appinstall <name> <path>`.
2. **The push destination matters.** `sdb push` defaults to `/home/owner/share/tmp/sdk_tools`,
   which the installer cannot read - `org.tizen.webappservice has no permission to access this
   file`. The same place as `/opt/usr/home/owner/share/tmp/sdk_tools` is readable.
3. **No spaces in the package filename.** sdbd refuses a path containing any, again reported as
   the connection closing. `package-tizen.mjs` writes `FourKPlusTVPlayer.wgt` for this reason.

`npm run package:tizen` and then `node scripts/package-tizen.mjs --sign <profile>` prints the two
commands with the right paths already filled in.

### Signing: it has to be a Samsung certificate

A Tizen author certificate is **not** enough, even on the emulator. Signed with one, the install
gets as far as 27% and then:

```
install failed[118, -12], reason: Check certificate error :
Invalid certificate chain with certificate in signature.
```

Both distributor certificates the SDK ships (`tizen-distributor-signer.p12` and
`tizen-distributor-signer-new.p12`) give the same result. A Samsung set accepts only a **Samsung**
certificate, which Certificate Manager creates after signing in with a Samsung account. That
sign-in is the one step that cannot be automated here.

That profile now exists and is called `4kplus-samsung`. Pass exactly that to `--sign`: a profile
name that does not exist produces the same certificate error rather than an honest failure, which
is a confusing hour if you have not seen it before. The profiles that exist are listed in
`~/tizen-studio-data/profile/profiles.xml`.

The certificate is scoped to two devices, and what that means for shipping to anybody else is in
[TIZEN-RELEASE.md](TIZEN-RELEASE.md).
## A difference from Android worth knowing about

The Android app keeps provider credentials in `EncryptedSharedPreferences`, backed by the device
keystore. A Tizen web app has no equivalent: `localStorage` is plain text, and there is no key
store a page can reach. So the saved username and password sit unencrypted on the set.

This matches what the app needs to do - it has to replay the login to refresh the catalogue - but
it is genuinely weaker than the television build, and it is a decision rather than an oversight.
If that is not acceptable, the alternatives are to stop saving the password and ask on every
launch, or to save only long enough for one session. Both cost the viewer something, which is why
it is worth deciding deliberately rather than by default.

The catalogue cache is separate and carries no credentials: its key is the host and username
only, so a changed password does not throw away a catalogue that is still good.
## Activation by MAC

The way in that needs no typing: the set shows its MAC and a device key, the reseller assigns a
playlist to that pair in their dashboard, and the app collects it. Ported from
`DeviceActivationClient.kt` - same endpoint, same request shape, same pending/assigned states.

**This build uses the set's real MAC.** `webapis.network.getMac()` gives the number printed in the
television's own network settings, so a customer can read it out without the app being open.
Android cannot do that - it has no access to the hardware address - so the television build
synthesises one from `ANDROID_ID` instead. The device key is derived identically in both (SHA-256,
first four bytes big-endian, modulo a million, padded to six digits), so one activation service
answers both.

Off a Samsung set - in a browser during development - there is no hardware MAC, so a stable one is
generated once with the locally-administered bit set and kept, and the page says so rather than
sending somebody hunting through their TV settings for a number that is not there.

### The activation service sends no CORS headers

Verified directly: `POST /api/activate` with an unassigned MAC returns `{"status":"pending"}` and
**no** `Access-Control-Allow-` headers at all.

That means a **browser blocks the call** and activation cannot work in development. A Tizen widget
declares `<access origin="*" subdomains="true"/>` and is not subject to CORS, so it is expected to
work on a set - but that is the documented platform behaviour, not something observed here, because
this emulator image blocks shell, dlog and the web inspector alike and there is no way to read what
the app saw.

If the web core is ever to run as an ordinary web page - the Windows target in `PORTING.md` uses
the same code - the service will need to send `Access-Control-Allow-Origin`. One header, on their
side, and both cases work.