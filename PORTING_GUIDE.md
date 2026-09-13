# 4K Plus TV Player — Porting Guide

This document is the durable technical and product reference for adapting 4K Plus TV Player to another device class or platform.

## Product purpose

- Private media-player app for existing 4K Plus TV customers.
- The app does not provide content. Customers connect an authorized provider account or M3U/M3U8 URL.
- Android phone is the first platform. Portrait usability is the current priority, with safe spacing in portrait and landscape.
- Future targets may include Android TV, Fire TV, tablets, Google TV, and other living-room platforms.

## Brand and design direction

- Product name: **4K Plus TV Player**.
- Use the supplied 4K Plus TV logo without modifying its proportions or artwork.
- Core look: deep navy background, bright blue primary actions, cyan accents, orange highlights, white text, rounded cards, subtle gradients and glow.
- Keep layouts minimal but lively and premium.
- Respect system safe areas and keep controls away from screen edges in portrait and landscape.
- Every touch should provide clear visual feedback.
- LIVE badge: cyan label with a red dot. A gentle 1.2-second pulsing animation is reserved for the final design-polish stage.
- Do not include Contact Support or Check Activation buttons. Refresh owns activation rechecking.

## Current user journey

1. On first use, the customer opens the activation/manual-playlist screen.
2. Manual connection supports either:
   - M3U/M3U8 URL; or
   - Provider login with server address, username, and password.
3. Provider login uses the Xtream Codes-compatible API directly.
4. A successful connection opens the dashboard with Live TV, Movies, and Series counts.
5. The encrypted connection source is retained on-device.
6. The parsed media library is stored in a compressed app-private cache.
7. Later launches open the cached dashboard immediately. A full provider download is only required when no valid cache exists.

## Current dashboard

- Brand header with search, refresh, and settings actions.
- Continue Watching card.
- Live TV, Movies, and Series cards with real item counts.
- Quick access for Favorites, Recently watched, and Playlists.
- Live TV and Movies browsing/playback are implemented. Series, settings, parental controls, updates, and external-player fallback remain future milestones.

## Live TV behavior

### Browse view

- The first available live channel begins previewing when Live TV opens.
- A category-search field filters all provider categories.
- Recently watched and Favorites are the first shelves when category search is empty.
- They disappear temporarily while category search contains text, allowing matching provider categories to appear first.
- Provider categories follow in the same order returned by the server.
- Each category is a horizontal channel shelf with a **See all** action.
- Channel cards show the provider logo when available and allow favorite toggling.

### Category view

- Opening **See all** retains the video preview.
- The first channel in that category begins previewing automatically.
- Automatic previews do not enter viewing history.
- A channel-search field filters channels inside the open category.
- Channels appear in a portrait-friendly three-column grid; landscape can use more columns.

### Player view

- Deliberately selecting a channel opens the player and records it in Recently watched.
- The active channel continues playing in a 16:9 Media3/ExoPlayer surface.
- The list beneath the player defaults to channels from the active channel's category.
- The user can switch that list to Recently watched.
- Recently watched stores up to 20 deliberate selections, newest first.
- Favorites and recent history are stored locally.
- Reused or duplicated provider IDs must never be assumed unique UI keys.
- Invalid/unsupported streams show an error inside the preview and must not close the app.

## Data model

`PlaylistInput`

- `name`
- `kind`: `M3U_URL` or `PROVIDER_LOGIN`
- `address`
- `username`
- `password`

`PlaylistItem`

- `name`
- `streamUrl`
- `group`
- `logoUrl`
- `channelId`
- `kind`: `LIVE`, `MOVIE`, or `SERIES`

`LoadedPlaylist`

- playlist name
- complete item list
- distinct groups
- computed live, movie, and series counts

When porting, preserve category order from the provider rather than alphabetically sorting it.

## Provider integration

- Authentication endpoint: `player_api.php?username=...&password=...`.
- Category actions:
  - `get_live_categories`
  - `get_vod_categories`
  - `get_series_categories`
- Content actions:
  - `get_live_streams`
  - `get_vod_streams`
  - `get_series`
- Live stream pattern: `/live/{username}/{password}/{stream_id}.ts`.
- Movie stream pattern: `/movie/{username}/{password}/{stream_id}.{extension}`.
- Movie details are loaded on demand with `player_api.php?action=get_vod_info&vod_id={stream_id}` so large playlists still open quickly. Available plot, cast, director, genre, year, rating, duration, trailer, poster, and backdrop fields enrich the details screen.
- Trailer actions use a YouTube search for the movie title, year, and “official trailer”; provider-supplied video IDs are not trusted because they can be missing, stale, or mapped to unrelated videos.
- When the catalogue title is localized, the app prefers the provider's original Latin-script VOD title for display and trailer search, while retaining the localized title as secondary information.
- Series currently retain a logical `series://{id}` reference until episode browsing is implemented.
- Provider compatibility includes HTTP/HTTPS normalization, special handling for HTTPS on port 80, redirects, and IPTV/VLC-compatible user agents.
- M3U parsing recognizes live, movie, and series items from group/title/URL indicators.

## Local persistence

- Connection details use Android `EncryptedSharedPreferences` with an AES-256 master key.
- Parsed playlist data uses a compressed binary cache in app-private internal storage.
- Current cache filename: `playlist_cache_v1.bin.gz`.
- Cache format starts with a numeric version and stores playlist/item fields as length-prefixed UTF-8.
- Cache readers must reject unreasonable item counts and field lengths.
- A successful provider/M3U load updates both the encrypted source and compressed cache.
- Current recent-history key: `recent_ids_v2`. The older key is intentionally ignored because an early build could record an automatic preview incorrectly.
- Favorite IDs are stored in `favorite_channels` preferences under `ids`.

## Android implementation

- Language: Kotlin.
- UI: Jetpack Compose + Material 3.
- Minimum Android SDK: 26.
- Target/compile SDK: 35.
- Java/Kotlin toolchain: 17.
- Android Gradle Plugin: 8.7.3.
- Kotlin: 2.1.0.
- Gradle used by CI: 8.10.2.
- Images: Coil Compose 2.7.0.
- Playback: AndroidX Media3 ExoPlayer, HLS, and UI 1.5.1.
- Secure preferences: AndroidX Security Crypto 1.1.0-alpha06.
- Cleartext HTTP is enabled because many customer IPTV providers still require HTTP.

## Playback compatibility

- Media3 uses an HTTP data source with redirects enabled.
- Playback user-agent: `VLC/3.0.20 LibVLC/3.0.20`.
- HLS and progressive/transport-stream playback dependencies are included.
- Player setup is guarded. Synchronous setup problems and asynchronous Media3 errors remain inside the player UI.
- Live TV and Movie players share fullscreen, embedded-subtitle toggle, external SRT/VTT loading/removal, and configurable 5/10/15/30/60-second seek controls.
- Subtitle enablement and skip duration are global playback preferences. External subtitle files apply to the current item and are not stored as provider metadata.
- On TV ports, add D-pad focus states, focus restoration, remote playback controls, and a full-screen player optimized for 10-foot viewing.

## Build and delivery

- Repository: `tareq58798-a11y/4k-plus-tv-player` (private).
- Main branch: `main`.
- GitHub Actions workflow: `.github/workflows/android-debug-apk.yml`.
- Workflow name: **Build Android Test APK**.
- It runs on pushes to `main` and can also be started manually with **Run workflow**.
- Test artifact name: `4K-Plus-TV-Player-test-apk`.
- The current development process is: replace local project files, commit in GitHub Desktop, publish/push, wait for Actions, download the artifact, and install the APK.

## Porting checklist

### Android TV / Google TV / Fire TV

- Replace touch-first navigation with full D-pad navigation.
- Add strong selected/focused states and predictable focus movement.
- Restore focus when returning from player/category pages.
- Use wider category shelves and landscape-only layouts.
- Keep safe margins for television overscan.
- Add remote Back, Play/Pause, Seek, Menu, and channel-change handling.
- Increase typography and targets for viewing distance.
- Add Android TV launcher banner, Leanback/TV manifest declarations, and platform-appropriate icons.
- Verify cleartext HTTP policy and Media3 codecs on Fire OS separately.

### Tablet / foldable

- Use adaptive breakpoints instead of stretching the phone UI.
- Consider persistent category navigation beside channel shelves.
- Keep the player visible while browsing on larger widths.
- Handle fold/hinge areas and multi-window resizing.

### Non-Android platform

- Reimplement provider API and cache layers using the same data model and safety limits.
- Use the platform's protected credential store.
- Use a native player that supports HLS and MPEG transport streams plus custom headers/user agents.
- Preserve history semantics: automatic preview is not history; deliberate selection is history.
- Preserve provider category order and tolerate duplicated provider IDs.

## Known limitations and future milestones

- Movies browsing, details, playback, favorites, history, and resume progress are implemented; provider metadata may be incomplete.
- Series browsing/playback is not yet implemented.
- Series episode API/loading is not yet implemented.
- EPG is not yet implemented.
- Fullscreen playback is implemented for Live TV and Movies; orientation locking and TV-specific fullscreen behavior may still need refinement.
- Refresh should update provider data and replace the cache without blocking access to cached content.
- Cache expiration/background refresh policy is not yet defined.
- Parental controls, external-player fallback, update screen, activation backend, language selection, and final motion/design polish remain pending.
- The pulsing red LIVE indicator is deliberately postponed to final visual polish.

## Product rules to preserve

- Prioritize simplicity and customer comfort.
- Never imply that the app includes or sells content.
- Keep credentials and user-specific data on-device unless a future backend explicitly requires otherwise.
- Do not let one bad channel, malformed provider entry, duplicated ID, or unsupported stream crash browsing.
- Keep loading, empty, and error states understandable to nontechnical customers.
