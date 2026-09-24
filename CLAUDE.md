# 4K Plus TV Player

One product, two apps, one source of truth.

* `app/` — the Android app, Kotlin and Compose, built in two flavours from one tree: `tv` and
  `phone`. This is the **reference implementation**. It ships.
* `web/` — the Samsung Tizen port, TypeScript and Vite, no framework. A newer port of the same
  product to a platform where the video is not in the page.

## The Android app decides, and it is readable

Nothing in `web/` invents a value. Sizes, colours, wording, control order and key bindings are
read out of the Kotlin source and copied, and where the Android app uses a library's own layout
the library is unpacked and read too — `androidx.media3`'s `exo_player_control_view.xml` and its
dimens are in the Gradle cache and are what the player's chrome is built from.

**A television runs at 960dp on a 1920px panel, so 1dp is exactly 2px.** Every number in the web
player is an Android dp doubled. sp converts the same way at fontScale 1.

When the two must differ — a platform genuinely cannot do the thing — say so in a comment at the
point of difference and record it in `web/README.md` under "Things the television app has that
this deliberately does not". Do not quietly approximate.

## Generated files

`npm run shared` extracts the eight languages' strings, the design tokens and the image assets
straight out of the Android resources into `web/src/shared/*.generated.*`. Never hand-edit those;
change the Android resource and re-run. `web/src/shared/strings.local.ts` is the only place for
text the Android app genuinely has no counterpart for, and it says why for each entry.

## Versions move together

`versionName` in `app/build.gradle.kts` and `version` in `web/tizen/config.xml` are the same
number, because a viewer asking which build they have must not get a different answer depending on
which screen they are standing in front of. They drifted once and it was a bug.

Every change that alters what is on screen bumps both, and bumps `versionCode` (which must only
ever increase, and is never reset to match the name). Documentation-only changes do not.

## Building and checking

    cd web
    npm run typecheck          # extracts shared, then tsc --noEmit
    npm run build              # the above, then vite build (this empties dist/)
    npm run package:tizen      # build, then stage a widget in web/tizen-package/

`./gradlew :app:assembleTvRelease` for the Android side.

## Verifying on the television, and when you cannot

A change is not verified because it compiled. Nearly every real bug in this codebase was found by
measuring on the set over the web inspector, and several confident readings of the *code* were
wrong. Measure; then check the measurement is measuring what you think.

That loop needs all of: a Samsung set on the same network, `sdb` from Tizen Studio, the
`4kplus-samsung` signing profile and its certificate, and the inspector port from
`sdb shell 0 debug <appid>`. See `web/TIZEN-RELEASE.md`.

**A cloud session has none of that.** Write the code, run `npm run build` and the typecheck, and
then say plainly that it is unverified and name what needs watching on the set. Do not write "verified"
into a commit message for something no device has run. A commit in this history that overstated
what had been checked had to be corrected by a later one.

## Environment notes (the Windows machine)

* `git` is not on `PATH`. It ships with GitHub Desktop, at
  `%LOCALAPPDATA%\GitHubDesktop\app-*\resources\app\git\cmd`.
* `tizen` is not on `PATH` either: `C:\tizen-studio\tools\ide\bin`. `sdb` is `C:\tizen-studio\tools\sdb.exe`.
* PowerShell 5.1: no `&&`, no `||`, no ternary, no heredocs, and `String.Replace` has no three-argument
  overload. Write files with the editor rather than by shell interpolation — a backtick inside a
  template literal has been silently mangled that way before, and shipped.

## Commits

Prose, not bullet inventories. Say what was wrong, what the evidence was, what changed, and what
was actually tested — including the parts that were not. Several messages here carry a paragraph
admitting the author's own earlier mistake; that is the house style and it is worth keeping.

## Out of the repository, permanently

`*.jks`, `*.keystore`, `keystore.properties`, `dist/`, `*.apk` and the Tizen author and distributor
certificates are gitignored and stay that way — a signing key is what proves an update comes from
this developer. Never print the contents of a `.pwd` file, a provider login, or the viewer's
credentials into a transcript; metadata about them is fine, the values are not.

## Known outstanding

* The Android release build is still debug-signed (`CN=Android Debug`) and `debuggable`. The
  keystore has to be created by the repository's owner. This is the real risk on that side.
* Samsung TV publication goes through **`seller.samsungapps.com/tv`**, which is a different portal
  from the Galaxy Store one. `web/TIZEN-RELEASE.md` has the details.
