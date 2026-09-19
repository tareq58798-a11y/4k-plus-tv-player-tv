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

Not built yet: Home, Movies, Series, search, settings, favourites, resume, EPG, the backdrop, and
the catalogue cache. Not yet run on Samsung hardware or the emulator.

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