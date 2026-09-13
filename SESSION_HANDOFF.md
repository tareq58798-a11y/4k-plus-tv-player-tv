# 4K Plus TV Player — Session Handoff

The full cross-device architecture and porting reference is maintained in `PORTING_GUIDE.md`.

## Current state

- Native Android project using Kotlin and Jetpack Compose.
- Repository: `tareq58798-a11y/4k-plus-tv-player` (private GitHub repository).
- GitHub Actions successfully builds a debug APK using Java 17.
- Latest local commit includes the supplied 4K Plus TV logo, safer screen-edge spacing, and touch feedback.
- The next functional milestone adds real M3U/provider connection testing, playlist parsing, encrypted source storage, loading/error handling, and real content counts on Home.
- Version 0.2.1 improves compatibility after a real test returned `Connection reset`: protocol-aware HTTP/HTTPS fallback, VLC-compatible request headers, clearer connection errors, and a working clipboard paste action.
- Version 0.3.0 replaces the provider-login-to-M3U shortcut with direct Xtream Codes API authentication and separate live, movie, series, and category loading. It also corrects HTTPS on port 80 and retries common compatible request signatures.
- Version 0.4.0 adds the first real content browser for Live TV: lazy large-list rendering, category chips, search, channel logos, persistent favorites, empty states, and corrected Series count wording.
- Version 0.4.1 removes All Channels, puts Recently watched and Favorites first, adds category search, records the 20 most recently selected channels in order, and changes the LIVE status dot to red.
- Version 0.4.2 opens Live TV on the provider's first category and makes channel and category searches global regardless of the selected category.
- Version 0.4.3 temporarily hides Recently watched and Favorites during category search so matching server categories appear first, then restores them when search is cleared.
- Version 0.5.0 redesigns Live TV for portrait browsing with a real Media3 stream preview, a vertical category rail on the left, and a compact channel list on the right. Landscape uses a shorter preview to preserve browsing space.
- Version 0.6.0 replaces the rail layout with a portrait-first streaming layout: auto-playing preview, category search, horizontal channel shelves with See all, a searchable category grid, and a player view with Recently watched below it.
- Version 0.6.1 fixes the category-grid ChannelPoster argument mapping that caused the v0.6.0 Kotlin compilation failure.
- Version 0.6.2 hardens Live TV against provider data and stream failures: list rendering no longer assumes channel IDs are unique, and preview setup/player errors are contained and shown inside the preview rather than closing the app.
- Version 0.7.0 securely restores the saved playlist at launch, keeps an auto-playing preview on category pages, starts each category with its first channel, resets inaccurate legacy history, records only deliberate selections, and lets the player switch between the current category and Recently watched.
- Version 0.7.1 adds a compressed on-device playlist cache. After the first successful load creates it, later launches open the dashboard from local data immediately instead of downloading the full provider library again.
- Version 0.7.2 fixes Recently watched using each provider channel's unique stream ID instead of a potentially blank or shared EPG ID. It resets the corrupted v2 history and rebuilds the playlist cache once with corrected channel identities.
- Version 0.8.0 adds the Movies experience: category shelves and See all grids, global movie search, provider metadata, poster/details pages, favorites, recently watched, Continue watching, full Media3 playback, saved resume positions, and system-back navigation. Cache v3 stores the new movie metadata.
- Version 0.8.1 keeps Favorites and Recently watched as permanent movie categories and loads rich movie information on demand from the provider's VOD-details endpoint. The redesigned portrait details page includes a cinematic backdrop, poster, plot, rating, year, genre, duration, cast, director, trailer action, Play/Resume, and favorite control while hiding unavailable or zero-value metadata.
- Version 0.8.2 makes the trailer action reliable by searching YouTube with the movie title, release year, and “official trailer” instead of blindly opening provider-supplied video IDs that may be stale or unrelated.
- Version 0.8.3 reads the original Latin-script movie title from provider VOD details when available. English movies can retain their Arabic catalogue title as a subtitle while using the original English title and year for accurate YouTube trailer searches.
- Version 0.8.4 adds fullscreen playback to the implemented Live TV and Movie players, embedded-subtitle on/off control, external SRT/VTT loading and removal, and a shared 5/10/15/30/60-second skip setting. The shared controls are ready to be reused when Series playback is implemented.

## Product direction

- Android phones first, with portrait and landscape support.
- Built for existing 4K Plus TV customers; comfort and simplicity are the priority.
- Users can connect through generated Device ID/Device Key or manually add an M3U URL/provider login.
- No local M3U file option, activation code entry, payment, subscription, or trial interface.
- Main areas: Live TV, Movies, Series, Continue Watching, Favorites, History, Playlists, Settings, parental controls, updates, and external-player fallback.

## Latest implemented feedback

- Added safe top, bottom, and side spacing, including wider landscape margins.
- Added the supplied 4K Plus TV logo to the UI and application icon.
- Removed Contact Support.
- Removed Check Activation.
- Refresh now represents the activation/playlist check.
- Added animated refresh, card press scaling, ripple feedback, haptics, clipboard actions, and snackbar confirmations.
- Added a premium visual pass to the activation and home screens: atmospheric brand glows, layered gradient surfaces, stronger typography, accent-icon containers, richer status treatment, rounded elevated cards, a branded Continue Watching banner, entry animation, and overflow-safe quick actions.

## Design direction

The current interface is clean but feels too plain. Keep it minimal and easy to navigate, while bringing it to life creatively through:

- stronger visual hierarchy and branded blue/cyan/orange accents;
- subtle gradients, layered cards, depth, and tasteful background treatments;
- polished focus, pressed, loading, success, and error states;
- purposeful micro-animations and transitions;
- richer media artwork where content is available;
- consistent spacing and large touch targets without crowding screen edges;
- an interface that feels premium and lively rather than sterile or overdecorated.

## Next step

Build and test version 0.8.4 through GitHub Actions. Verify fullscreen entry/exit, embedded and external subtitles, and every skip interval in Live TV and Movie playback. Series browsing/playback is the next functional milestone and should reuse the shared controls; visual refinements remain intentionally deferred.
