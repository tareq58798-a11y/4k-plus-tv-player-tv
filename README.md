# 4K Plus TV Player

Native Android source for the private, customer-focused 4K Plus TV Player.

## Milestone 1

- Branded light, dark and system themes
- Responsive portrait and landscape layouts
- Remote activation screen using Device ID and Device Key
- Manual M3U URL entry
- Manual provider login entry
- Branded home-screen shell
- Arabic/English and playback modules prepared for later milestones

The current buttons use prototype navigation. Real playlist networking, secure
device registration, Media3 playback, storage, Arabic localization and the admin
API will be connected in subsequent milestones.

## Open locally

Open this directory in Android Studio and allow it to synchronize dependencies.
The project targets Android 8 (API 26) and newer.

## Automatic test APK

The included GitHub Actions workflow builds a debug APK whenever code is pushed
to `main`. Open the completed **Build Android Test APK** run and download the
`4K-Plus-TV-Player-test-apk` artifact.

## Product rules

- The app is a media player and does not bundle content.
- Remote playlist credentials must never be exposed to customers.
- Manual credentials must be stored with Android-backed encryption.
- The built-in player is the default; external playback is an advanced fallback.
- All screens must remain usable in portrait, landscape, RTL and LTR layouts.
