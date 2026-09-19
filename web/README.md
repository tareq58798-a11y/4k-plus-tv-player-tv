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
* **Live stream container** — AVPlay decodes the MPEG-TS that Xtream serves directly; a browser
  has no demuxer for it outside HLS, so the browser build asks the same panel for `.m3u8`
  instead. This is the one place the two request different URLs, and it is a fact about browsers
  rather than a preference.

hls.js is a separate chunk on purpose. It exists only so this can be developed without a
television, and folding it into the main bundle would have a Samsung set parse 595 kB of code,
on a slow engine, before the first frame, for a player it will never construct.

## State

Working: the shared extraction, the Xtream client, the D-pad engine, the key map, storage, the
AVPlay and browser players, and a vertical slice — sign in, load, browse Live TV by category,
play a channel.

Also working: Home, Live TV, Movies and Series as landing pages, the category browser, the series
page with seasons and episodes, search across the whole playlist, settings with all eight
languages, now-and-next listings in Live TV, the layered backdrop, favourites, resume points, and
a catalogue cache that opens the app on what is already on the set.

Not built: parental controls. Everything else the television app does that is missing here -
subtitle appearance, aspect ratio, buffering modes - belongs with playback, which cannot be tried
until this runs on a set.

Nothing has run on Samsung hardware or the emulator yet: see the certificate above. In particular
nothing that depends on the browser delivering real focus events has been seen working, because
the pane used for development runs hidden and Chrome suppresses those while a document is hidden.

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

### The remaining blocker: a Samsung certificate

A Tizen author certificate is **not** enough, even on the emulator. Signed with one, the install
gets as far as 27% and then:

```
install failed[118, -12], reason: Check certificate error :
Invalid certificate chain with certificate in signature.
```

Tried with both distributor certificates the SDK ships (`tizen-distributor-signer.p12` and
`tizen-distributor-signer-new.p12`); same result. A Samsung set will only accept a **Samsung**
certificate, which Certificate Manager creates after signing in with a Samsung account.

That sign-in is the one step that cannot be automated here. Once the profile exists, sign with it
and the two commands above should complete.
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