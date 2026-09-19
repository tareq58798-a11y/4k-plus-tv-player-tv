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
