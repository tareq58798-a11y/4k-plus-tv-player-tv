# Getting the Samsung build to viewers

This is the distribution story for the Tizen port, written down because none of it works the way
the Android side does and the difference is easy to assume away.

## The headline

**Tizen has no sideloading.** On Android TV a viewer types a code into Downloader, fetches an APK
from a URL and installs it. There is no equivalent on a Samsung television: no file-manager
install, no third-party store, no "unknown sources". A `.wgt` reaches a consumer's set by exactly
two routes — Developer Mode, or the Samsung store — and that is the whole list.

So the GitHub release that serves the Android build has no counterpart here. Publishing the
Samsung version means Seller Office or nothing.

## What the certificate we have actually is

`~/SamsungCertificate/4kplus-samsung/device-profile.xml`:

```
DeveloperType : Individual
TestDevices   : 2 DUIDs
```

That is a **development** distributor certificate. It installs on those two devices — the
QN65QN800C and the emulator — and is refused by every other set, which surfaces as:

```
install failed[118, -12], reason: Check certificate error :
```

That same error appears if you sign with a profile name that does not exist, because the CLI
falls back to something the set will not accept rather than failing outright. The profiles that
exist are in `~/tizen-studio-data/profile/profiles.xml`; the one to use is `4kplus-samsung`.

A store build needs a different distributor certificate, issued for publication rather than
scoped to a device list. The author certificate carries over; the distributor one does not.

## Route 1: Developer Mode

What we do now, and the right tool for our own sets and for testers we can reach.

The viewer enables Developer Mode (Apps → `12345` on the remote) and enters the IP of a machine
running sdb. Their TV's DUID goes into the certificate profile, the widget is re-signed, and it is
pushed over **their** local network — which is the limitation: this cannot be done remotely.

A profile holds only a small number of test devices, on the order of ten. Confirm the current
figure in Certificate Manager before promising anyone a slot. Samsung firmware updates can also
remove Developer Mode apps, so an install is not necessarily permanent.

Useful for a handful of televisions. Not a distribution channel, and no amount of tooling makes it
one.

## Route 2: Samsung Apps TV Seller Office

The real answer for real users. The order matters, because most steps are blocked by an earlier
one.

1. **Register as a seller** at seller.samsungapps.com, TV section, with a Samsung account.
2. **Samsung issues a ten-character App ID prefix.** This replaces `4KPlusTVXX` in
   `web/tizen/config.xml`, in both the `id` and `package` attributes. Until then the packager
   warns on every build, and that warning is correct — it cannot be filled in by guessing.
3. **Create a publication distributor certificate** in Certificate Manager. Not the device-scoped
   one described above.
4. **Re-sign the widget** with it: `node scripts/package-tizen.mjs --sign <profile>`.
5. **Build the store listing** — icon, screenshots, description, category, age rating, countries,
   privacy-policy URL, at whatever sizes Samsung currently specifies.
6. **Review**, carried out on real hardware by Samsung.

Steps 2 and 3 both hang off step 1, and 4 hangs off both. Nothing below step 1 can be started
early, which is why step 1 is worth doing before any further packaging work.

## Two things to plan around

**Seller registration may require a company.** TV Seller Office has historically wanted a business
registration, unlike the mobile Galaxy Store which accepts individuals. The `Individual` in our
certificate is the Tizen developer type and is a separate thing — it says nothing about whether
the store will take us. This is the single cheapest question to answer and the one that most
changes the shape of the plan, so ask it first.

**An IPTV player draws the heaviest review scrutiny.** Samsung examines content rights, and an app
that plays viewer-supplied playlists sits in the category they push back on hardest. The honest
defence is one this app genuinely has: it bundles no content, lists no providers, and does nothing
at all until the viewer enters credentials they already hold. Players of this kind are on the
store, so the door is open — but budget for a rejection round rather than treating approval as
the default.

## Versioning

`config.xml` carries the same `version` as the Android `versionName`, because the two are one
product and a viewer should not get a different answer depending on which set they are standing in
front of. Tizen wants three parts, so 8.2 is written `8.2.0`.

## Build and install, end to end

```
cd web
npm run build
node scripts/package-tizen.mjs --sign 4kplus-samsung
sdb -s <tv-ip>:26101 push tizen-package/FourKPlusTVPlayer.wgt /opt/usr/home/owner/share/tmp/sdk_tools/FourKPlusTVPlayer.wgt
sdb -s <tv-ip>:26101 shell 0 vd_appinstall FourKPlusTVPlayer /opt/usr/home/owner/share/tmp/sdk_tools/FourKPlusTVPlayer.wgt
```

`tizen` and `sdb` are not on PATH by default; they live in `C:\tizen-studio\tools\ide\bin` and
`C:\tizen-studio\tools`. Why the install uses `vd_appinstall` rather than `sdb install`, why the
push destination is that exact path, and why the filename has no spaces are all recorded in
[README.md](README.md#installing-on-a-samsung-emulator-or-television) — each of those cost an hour
to find.

To inspect the running app on the set:

```
sdb -s <tv-ip>:26101 shell 0 debug FourKPlusTVPlayer
```

which prints a port for the web inspector. Driving that over the DevTools protocol is how the
layout work was measured on real hardware rather than guessed at from the desktop.
