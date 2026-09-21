# Porting 4K Plus TV Player to six platforms

Working plan. One platform at a time, in the order at the bottom. Update the status
table as each lands.

## The single most important decision

Six targets do **not** mean six codebases. They mean three:

| Codebase | Serves | Language |
|---|---|---|
| **A — this repo** | Android TV, Android phones | Kotlin + Compose |
| **B — new web app** | Samsung Tizen, LG webOS, Hisense VIDAA, Windows | TypeScript |
| **C — new iOS app** | iPhone, iPad, Apple TV | Swift |

Tizen, webOS and VIDAA are all HTML/CSS/JS platforms. Their apps differ only in
packaging, key codes, lifecycle hooks and the video API. Writing three separate TV
apps would be doing the same work three times. Windows rides along with the same web
core in a desktop shell.

That collapses the job from six ports to **two new builds plus a manifest change**.

## What carries over, and what does not

Carries over — worth extracting and writing down once, as a spec both new codebases
follow:

- The Xtream Codes client: endpoints, parameters, response shapes, the `added` /
  `last_modified` handling (`XtreamProviderClient.kt`, 455 lines)
- The catalogue model and cache format (`PlaylistModels.kt`, `PlaylistCacheStore.kt`)
- The design tokens: colours, spacing, radii, timings (`ui/design/Tokens.kt`)
- **Every D-pad rule worked out on the TV app.** Which key leaves a grid, what Up
  answers with, when the section tab is lit, where focus lands after a details page.
  This is the part that took the longest and it is pure behaviour, not code.
- The eight translations (`res/values-*/strings.xml`)

Does not carry over:

- All 13,574 lines of Compose UI
- Media3 / ExoPlayer integration, including the aspect-ratio and subtitle work
- Android storage (SharedPreferences, EncryptedSharedPreferences)

## Per platform

## Keeping the builds apart

Every target ships as its own app. Nothing done for one may change what another ships.

Within codebase A that is enforced by product flavours: `tv` and `phone` build separate
APKs with separate application ids (`…tvplayer.tv`, `…tvplayer.phone`), and each takes
its form-factor manifest from `src/tv` or `src/phone`. The TV app's manifest is not in
the shared file at all, so phone work cannot reach it.

The **source** is shared on purpose. The D-pad rules, the provider client and all eight
translations are one programme; keeping two copies would mean fixing everything twice.
What differs between a remote and a fingertip is decided at runtime.

Build and test them independently:

```
gradlew assembleTvDebug        -> app/build/outputs/apk/tv/debug/app-tv-debug.apk
gradlew assemblePhoneDebug     -> app/build/outputs/apk/phone/debug/app-phone-debug.apk
```

The released TV APK comes from the `tv` flavour only. Both can sit on one device at once,
which is what makes side-by-side testing possible.

### 1. Android phones — same codebase

The only target already sitting in this repo. Two manifest lines currently block it:

- `<uses-feature android:name="android.software.leanback" android:required="true" />`
  hides the app from every phone in the Play Store
- `android:screenOrientation="landscape"` locks out portrait

There is already meaningful groundwork: 99 places in the source branch on
`portraitLayout`, `landscape` or `isTvDevice`. So this is a fix-and-polish job, not a
port. The real work is touch targets, portrait layouts and testing on a phone — not
new architecture.

Distribution: one APK, one Play listing, both form factors.

### 2. LG webOS — start of codebase B

Web app. `<video>` plus hls.js for HLS. Needs an LG Seller Lounge account and app
certification. Good simulator and a mature SDK, which is why it is the right place to
build the web core first.

### 3. Samsung Tizen — codebase B

Same web core. Different shims: AVPlay for video rather than `<video>`, Tizen key
codes, Samsung's app lifecycle. Needs a Samsung Seller Office account and
certification.

### 4. Hisense VIDAA — codebase B

Same web core again. **Start the partner application before writing any code for
this one.** VIDAA's developer programme is the most closed of the three and access is
the long pole, not the engineering.

### 5. Windows — codebase B in a desktop shell

The web core packaged for desktop. Mouse and keyboard rather than a remote, so the
focus model needs a pointer path the TV builds do not use.

### 6. iOS — codebase C

Swift + AVPlayer. Least reuse of the three.

**Flagging a real risk before any work goes in:** Apple regularly rejects IPTV player
apps where the user supplies their own playlist or Xtream credentials, under App
Review Guidelines 5.2.2 and 1.2. Apps like this do exist on the App Store, and apps
like this also get pulled. There is no sideloading fallback on iOS the way there is on
Android, so a rejection means no distribution at all. Worth a pre-submission enquiry
to App Review before committing engineering time.

## Recommended order

The order below is chosen so that each step unblocks or reuses the last.

1. **Android phones** — days, not weeks; the code is already here
2. **LG webOS** — builds the web core on the most open platform
3. **Samsung Tizen** — same core, new shims
4. **Hisense VIDAA** — same core again; apply for partner access at step 2
5. **Windows** — same core in a desktop shell
6. **iOS** — biggest risk, least reuse, so last

## Status

| # | Platform | Codebase | State |
|---|---|---|---|
| 1 | Android phones | A | In progress |
| 2 | LG webOS | B | Not started — key map already carried in `web/src/platform/keys.ts` |
| 3 | Samsung Tizen | B | **Started** — see `web/`. Shared layer, provider client, D-pad engine, activation by MAC and now the player chrome; nothing on hardware yet |
| 4 | Hisense VIDAA | B | Not started |
| 5 | Windows | B | Not started |
| 6 | iOS | C | Not started |

Tizen was taken before webOS at the owner's request. Nothing is lost by the swap: the work is
the shared core either way, and `keys.ts` already carries LG's codes.

Decisions taken for codebase B:

- Lives in `web/`, in this repo, so the strings and tokens can be read out of the Android
  sources at build time instead of copied. Gradle does not know the folder exists.
- Floor is **Tizen 5.5**, the 2020 sets. Older sets would mean an older engine and weaker CPUs.
- First milestone is a **vertical slice** — sign in, browse, play — so the provider client, the
  focus engine and AVPlay are proven together before the remaining screens are built on them.

### Tizen: what the player has, and what it still lacks

The controls over a playing stream are in `web/src/ui/player.ts`. They carry the parts of the
television app's chrome that are behaviour rather than Compose:

- Transport row, timeline and a settings panel, with **Down walking them in the order they are
  drawn** — transport, timeline, settings — worked out from where focus actually is rather than
  from a count of presses, for the reason given in `advanceDownThroughControls` on Android.
- The scrubber turns cyan and grows while the timeline holds focus, so a full-width line with a
  dot on it can say whether the next press will seek.
- Controls withdraw five seconds after the last press, and any key brings them back rather than
  acting, so nothing happens unseen.
- Seeking is absolute, not relative: held down, a relative seek asks a player that is still moving
  where it is between presses, and the steps come out uneven.

Still missing against the Android player, in rough order of how much they are missed:

- The subtitle background toggle. Subtitles themselves are in; the opaque backing behind them that
  the television app offers is not.
- Nothing has run on real hardware. The emulator is x86 and permissive; a 2020 set is ARM, slower,
  stricter and on an older WebKit.

**AVPlay decodes.** Proven on the emulator on 21 Sep 2026, end to end from a cold launch:
activation by MAC, catalogue load, a series' details and season list fetched live, OK on episode
one, and moving video. Successive captures a few seconds apart differ in size and hash, so it is
playing rather than holding a first frame. That was the single largest open risk in this codebase
and it is closed.

A note for anyone testing this way: capturing the emulator needs `PrintWindow` with
`PW_RENDERFULLCONTENT` (flag 2), not flag 1, and it fails intermittently - a failed capture comes
back as an identical ~1.3KB image every time. Retry until the size jumps rather than reading a
blank frame as a blank screen, which is a mistake that cost an hour here.

### Tizen: settings pages, ported and outstanding

Settings follows the television app's shape - a root menu, one page per row, Back stepping out a
level at a time. Ported: Playlists, Appearance, Language, Category visibility, Parental controls.
Outstanding: Playback, App info, Privacy & history.

Category visibility is not optional company for the category menu, it is its other half: a held OK
hides a category, and without this page hiding is a one-way door.

### Tizen: the old settings layout did not take a fifth column

`.settings` is a flex row of groups that shrink to share the width. Measured at 1920x1080:

| groups | column width | result |
|---|---|---|
| 4 (today) | 404px | rows 69-73px, nothing wraps |
| 5 | 314px | labels like "Enable parental control" wrap to two lines |

So the five settings pages still to be ported cannot simply be five more columns. They want either
a menu of pages, as on Android, or groups that wrap onto a second row. Worth deciding before the
first of them is written rather than after the fourth.

Anything written *inside* a row has far less room than the 560px the group asks for. A sentence in
a row wraps to four lines and turns a 69px row into a 205px one - which is why the background
mode's description sits once under the pair rather than inside each option.

## Carried over from the TV app

Still outstanding regardless of platform:

- The release keystore does not exist, so Android builds are debug-signed. Whatever
  key signs the first real release is the only key that can ever update it.
- `bg_app_default.png` is 1672x941; a 3840x2160 version would serve every platform.
