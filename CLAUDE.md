# The Player: notes for Claude

Android IPTV player for phones and Android TV. Package `app.falak.player`, Kotlin + Compose.
The owner is not a developer: reply briefly, in plain words, answer first. This repo is **public**.

## Owner's standing rules (do not break)
- **Releases carry only the APK.** No screenshots, Play Store graphics or listings unless asked.
  Screenshots from CI are for your own checking; never send them to the owner.
- **Secrets:** never commit the Play upload keystore; never ask for Cloudflare tokens in chat; never
  print secrets in logs; never put the user-counter (stats) URL in release notes or anywhere public.
- **User count** is always on. Never add a Settings option for it.
- **Sync is Wi-Fi linking only** (owner's choice, 8.2): no Google sign-in or cloud. Everything syncs;
  playlist passwords are copied over Wi-Fi too. The TV gets playlists sent from the phone.
- "Make no mistake": check the change, let CI's tests pass, confirm the release is published, and
  say plainly what has **not** been tried on a real phone or TV (nothing is device-tested here).

## Release flow
Builds and tests run only in CI (`.github/workflows/build.yml`); there is no Android SDK locally.
1. Work on `dev`. Bump `versionName` in `app/build.gradle.kts` (one step per release: 8.6 → 8.7).
   `versionCode` is CI's run number; releases are tagged `build-<run>`.
2. `tools/kcheck.sh $(find app/src -name "*.kt")` (syntax only), commit, push `dev`.
3. Wait for CI (background loop on `gh run list -R to0olate2-wq/Project --branch dev -L 1 --json status,conclusion,databaseId`).
   - `build` (assembleGithubRelease) must pass. Compile errors: `gh api repos/to0olate2-wq/Project/check-runs/<job id>/annotations`.
   - `screens` (`testPlayDebugUnitTest`, all unit + screen tests, 12–20 min) must pass. Results go to
     branch `screenshots-dev` (PNGs, `log.txt`, `hang-*.txt`, `TEST-*.xml`): shallow-clone it to check.
     Look at the PNGs only when the change touches what's on screen.
4. Release: `git checkout main && git pull && git merge --squash dev && git commit -F notes.txt && git push`,
   then `git checkout dev && git merge main && git push`.
   **The main commit message is the release notes** (trailer lines are stripped): first line
   `8.7: <what changed>`, then plain-language bullets for the owner. Pushing `main` publishes the release.
5. Confirm: `gh release view build-<run> -R to0olate2-wq/Project` shows only `ThePlayer-<ver>-build<run>.apk`.
   If only "Publish release" failed, `gh api -X POST repos/to0olate2-wq/Project/actions/runs/<id>/rerun-failed-jobs`.
- Commits that shouldn't build (docs) get `[skip ci]` in the message.

## Layout
- `app/src/main/java/app/falak/player/`
  - `data/` models (`Models.kt`), `Repository.kt`, Xtream/M3U, EPG, `LibraryIndex.kt` (groups, search,
    your own order), `Combined.kt` (combined playlists), `SyncMerge.kt` (Wi-Fi sync merge), `KidsTime.kt`.
  - `player/` engines (ExoPlayer/Media3 and libVLC), captions, `MatroskaSubtitles.kt`.
  - `sync/` Wi-Fi link, phone remote. `ui/` Compose screens; `PlayerActivity.kt` is the player (≈4,600 lines).
- `app/src/test/` Robolectric unit tests; `screens/Screens.kt` draws phone and TV screens.
  Subtitle fixtures in `app/src/test/resources/subs/`.
- `stats/` Cloudflare worker for the user count (deployed by CI from `main` with repository secrets).
- Flavors: `github` (sideload, one-tap updates, the released APK) and `play` (Google Play).

## How things work (short)
- **Sync:** per-playlist parts with time stamps (pl, fav, lists, hidden, guide, recents, locked, kidsg,
  followed, intros, arranged; fav/lists/recents per profile as `id@profile`) plus whole-app parts
  (`set|<field>`, `profiles|*`, `reminders|*`, `alerts|*`), always stamped. `DEVICE_SETTINGS` never sync.
  Tie rule: a never-stamped, empty part adopts the other device's.
- **Combined playlists:** `Playlist.sources`, type `M3U_FILE` with an empty url (older versions ignore it).
  Item ids are `id~tag`, tag = `stableId(type + username or url).take(5)`; see `Combined.sourceFor/untag/zoneFor`.
- **Your own order (Arrange):** `Arrangement.trimmed` keeps only the changed prefix;
  `LibraryIndex.rearranged()` applies an order instantly, without rebuilding the index.
- **D-pad wrap-around:** `ui/FocusLoop.kt` (`focusLoop` / `loopItem`).
- **One look, Spotlight (8.14; owner asked for a Netflix/Apple TV-like layout):** Classic and the Look setting are
  gone (`Settings.look` is left unread: older versions sync it; a second look gets a NEW field). Home and the Films
  and Series front pages are `MarqueePage` (MarqueeBrowse.kt) inside `MarqueeStage` (draws the picture behind the
  top bar; `ArtMode`). **TV:** `SpotlightPanel` (Spotlight.kt, fixed 138 dp, nothing to press) shows the card the
  remote is on: cards call `Modifier.spotlights(ch)` → `vm.spotlight[section]`; a channel shows now/next/time left,
  a film or series its rating, year and story (asked for after the remote rests 0.5 s, `loadHeroArt`). Before any
  card has the focus the featured titles take turns. Rows are a `LazyColumn` under it (`HomeScroller` pins rows).
  **Phone upright:** `PhoneHero` over the poster edge to edge (swipe or 7 s for the next). Tablets and phones on
  their side: `MarqueeHero` as before. Screen tests set `vm.spotlight[...]` and `railOpenForTest`.
  **Live TV in the same look (8.15; the owner asked to mix "Channels first" into Spotlight rather than keep it a
  separate look):** TV list view = three columns (`GroupColumn` | `ChannelList` | `LivePreviewPane`; grid view and
  phones keep the chips); Settings → Playlists → "Open on" Home / Live TV (`Settings.startOn`, this device's);
  phones upright get `ResumeBar` under the Live list (`state.lastLive`, hidden while the mini player is up).
  **A film's page and a series' page in the same style (8.19, `ui/TitlePage.kt`, both looks):** `TitleStage` draws
  the same picture as Home (`MarqueeBackdrop`: TV full screen and fixed, phone upright the poster edge to edge fading
  as the page scrolls, otherwise wide art or the poster standing on the right) with Back over the page; `TitleHead`
  = kicker, name, a line about it, story, buttons (TV: story before the buttons, six lines at most; phone: buttons
  first, the story whole). Both pages are a `LazyColumn`. Film (`DetailsScreen`): the old buttons, cast and
  director, then "More like this" (other films of its group; opening one stays on the page, back at the top).
  Series (`SeriesScreen`): Play / Resume / Next episode (the episode watched last by `Watch.at`, the one after it
  when that is 95% done; its name under the buttons and "Up next" on its row; the page opens on its season),
  Follow, My list, season chips, the season's downloads, episodes (600 dp wide on a TV, the picture beside them).
  Tests: `TitlePageScreens.kt`.
- **A second look, Cinema (8.18; the owner asked for an Apple TV-like one under our own name and OK'd the sketch):**
  Settings → Appearance → Look (`LookOption`), `Settings.appLook` "spotlight" / "cinema", this device's
  (`DEVICE_SETTINGS`); `LocalCinema` / `isCinema()` (FalakTheme). Only the menu and Home/Films/Series differ
  (`ui/Cinema.kt` has the list). **TV:** `ShellMode.TOP` = `TopMenu` over the page (no rail): sections as words,
  tools as icons that show their name on focus (Search, TV guide, Recordings, Add-ons, Downloads, What's new, Update
  playlist, Playlists, Settings, profile, clock). The lists leave `TOP_MENU_HEIGHT` themselves so the picture goes
  behind the menu; other pages are padded by `AppShell`. The page's own top bar is drawn only while searching
  (`cinemaSearchOpen`, or something typed). The picture fills the screen (`marqueeFraction` 1); `CinemaPanel`
  (Spotlight.kt, fixed 190 dp so a poster row still fits under it on a 540 dp screen) shows the card the remote is on
  with Play / Watch / Episodes, More info and My list (Up from the first row reaches them). **Everywhere:** Continue
  watching is "Up next" with bigger cards (`UP_NEXT`, `upNextWidth`), on Home with the live channel watched last as
  its third card. **Phone upright:** taller picture, a My list button; the bottom bar stays (thumb reach; the owner
  saw that in the sketch). Tablets and phones on their side keep the rail. Tests: `CinemaScreens.kt`.
  The sketch is a Design artifact ("The Player: Cinema look (sketch)").
- **The menu stays (8.8), `ui/Shell.kt`:** with a playlist open, `AppShell` (in `MainActivity`, around the pages)
  draws the rail (TV, ≥600 dp; on a TV it opens to 212 dp with names while the remote is on it, over the page, see
  `SideRail`/`RailIcon`: keep the button's modifiers the same kinds so it keeps the focus) or a bottom bar (phones
  upright: Home, Live, Films, Series, More) for `SHELL_SCREENS` (lists, More, Recordings, Downloads, What's new,
  Add-ons, Settings and its pages). `SidebarFrame` is the top bar + page. `LocalShell` tells pages which; Back from
  those pages is `vm.closePage()` (player detour → player, opened from More → More, else the lists). TV Settings:
  list of pages left, page right (`twoPane`; focus moves on OK, the page follows the remote after 160 ms).
- **Player controls (8.10; the owner didn't like 8.8/8.9's More, don't bring it back):** every button on screen.
  Bottom row with names (`pills()`): Back to live, Channels, Record, Two screens, Audio and subtitles (one button,
  `showTracksPanel`: both lists in one panel; the remote's Audio/Subtitles keys still open one list), Speed — each
  only when it applies. Setting icons (`settingIcons()`: Picture size, Sleep, Player): phones top right, TV at the end
  of the row after a thin line; no names except TV focus and Sleep's time left (`applyIconLabels`). Top bar
  (`buildTopBar`): ☆ Favorites, then the name and what's on now; the zap banner hides while the controls are up.
  Removed: Menu (Back does it; `showAppMenu` stays for the remote's Menu job), Rotation (player uses FULL_USER,
  `playerRotation` setting unused). Phone buttons are 48 dp. `LiveTimeline` sits where the seek bar is.
- **Start and updates (8.11):** `Theme.Falak.Launch` (MainActivity's manifest theme; `setTheme(Theme_Falak)` in
  onCreate) puts the logo on Android 7–11's launch screen (`launch_screen.xml`; 12+ uses its splash attributes). Then
  `StartScreen` (Brand.kt, logo + name) while `vm.startingUp` (app start with a saved copy, `repo.hasSavedCopy`); a
  download shows its in-page progress ("Downloading" vs `fromSaved` "Opening"). Above the lists (`UpdateLine` in
  LibraryScreen): "Updating playlist…" for asked-for and automatic updates (`refreshing`), then `vm.updateNote`
  ("Playlist updated · N new" 5 s, or a failed automatic update with the copy's age until the next one works).
  Logos: `LogoOr` (ChannelLogo.kt) shows `Initials` (Initials.kt, `initialsOf`) or the name when a logo is
  missing or its link fails.
- **Add-ons (8.12), GitHub version only:** Stremio-style add-ons (open format: manifest, catalog, meta, stream).
  `BuildConfig.ADDONS` (github true, play false; screen tests use `Addons.enabledForTest`). `data/Addons.kt`: link
  normalising, lenient parsing, `AddonClient` (OkHttp). http(s) stream links play; torrent links play too since
  8.16 (see Torrents below); other links are counted. No add-on ships with the app (Cinemeta, `STANDARD_LISTS`,
  is offered in one tap when the add-ons added are links-only, e.g. Torrentio: owner's OK, 8.16). Links in
  `Settings.addons` (sync with Wi-Fi linking). `ui/AddonsModel.kt` (state, in `vm.addons`; `known` = names read
  with `readNames`, manifests only) + `ui/AddonsUi.kt` (`Screen.ADDONS`: browse only — rows per catalog, search,
  details, a series' episode links in a dialog; `returnTo` = page to go Back to). Added and removed with the playlists
  (owner, 8.13): "Add-on" choice in the Add playlist window (`SourceKinds`, `AddAddonDialog`), "Your add-ons" on the
  Playlists page, an Add-ons part in Settings → Playlists. Browsing: More (phones), rail, Playlists page rows; hidden
  in Kids. Playing: `Channel` id `addon:…` = one-off (no Recent, no resume, no Watch Next).
- **Torrents (8.16), GitHub version only, `torrent/`:** the owner asked for it (Torrentio and the like must play;
  he will make the repo private later; before that the rule was "no torrent engine"). libtorrent4j 2.1.0-39 (MIT):
  `githubImplementation` + android-arm/arm64 natives, `playCompileOnly`, tests use the `-linux` native.
  `BuildConfig.TORRENTS` (`Torrents.available`; tests: `availableForTest`). `TorrentSource` (hash, fileIdx, trackers,
  file name; from an add-on's `infoHash`/`sources` or a magnet) → `TorrentEngine` (no Android in it: magnet →
  metadata → `pickFile`; polling, no alert listeners) → `TorrentFile` (`GrowingFile`) → `TorrentServer` (127.0.0.1,
  random port + token, Range requests wait for pieces, drops the connection rather than ending short) → the player
  opens `http://127.0.0.1:…/video.mkv` like any film. **A stream, not a download (8.17; the owner: "like Stremio",
  8.16 refused films bigger than the free space and didn't start on his phone):** every piece is priority 0 except
  a stretch ahead of the player (192 MB, `want()` moves it; first pieces get deadlines) and the last two pieces;
  at most `maxBytes` kept (free space − 300 MB, 6 GB at most): when that is used up and the player needs more,
  `restart()` re-adds the torrent in a new folder (`g<generation>`, old one deleted, peers passed on) and carries
  on from there; the server re-opens the file when `generation` changes. `close_redundant_connections` is off (or
  the seeds are dropped whenever the stretch is complete). Starts once the first piece (≥2 MB) is here, the end is
  waited for 6 s at most. Files in `filesDir/torrents` (not the shared storage), deleted on stop, on leaving the
  film or the Add-ons page, and at app start. `Torrents` (object) = one at a time: `prepare` / `state` / `stop`;
  `state` keeps counting while it plays (people found/connected, speed, kept, what the player took): shown in
  `TorrentWindow` and in a line on the film's page (`TorrentNow`) so the owner can say where a film is stuck.
  Still not seen working on a real device. UI: torrent rows in `linkItems`. Upload capped at 96 kB/s. Tests:
  `TorrentServerTest` (runs anywhere), `TorrentEngineTest` (two engines on the CI machine, real transfer, also
  through a 3 MB room with restarts, and a small file).
  Local type-check without the SDK: compile `torrent/*.kt` (not Torrents.kt) with Gradle's Kotlin compiler against
  the library's Java sources (clone aldenml/libtorrent4j); Maven Central is not reachable from the sandbox.
  **What the library does (all met in 8.17; its C++ is arvidn/libtorrent at the commit of libtorrent4j's
  `swig/deps/libtorrent`):** (1) `prioritizeFiles` takes effect later (after its disk thread answers) and then puts
  every piece of the file back on the list: `begin()` waits for it (download held at 1 B/s) before giving the piece
  list; a restart adds the torrent with file and piece priorities inside `add_torrent_params` (pieces win).
  (2) `handle.torrentFile()` is borrowed and dies with the torrent: keep a copy (`torrent_info(it.swig())`).
  (3) Memory-mapped disk I/O (its default on 64-bit) and its torrent maker (`TorrentBuilder`) install their own
  SIGSEGV/SIGBUS handler for the whole process; a JVM then dies now and then (exit 139, no hs_err, `si_code` −6):
  sessions start with `TorrentEngine.plainDisk` (posix I/O) and the test writes its .torrent by hand.
  (4) A piece is checked against the torrent's SHA-1 in the file before the player gets it (`inFile`); a piece
  shared with a neighbouring file waits 1.5 s instead. (5) Public trackers are added to every link
  (`PUBLIC_TRACKERS`) and passed on at a restart. (6) The Android natives are built for API 28: Android 7–8.1 may
  fail to load them (shown as "This device can't play torrent links").
  CI helpers: `[only app.falak.player.torrent.*]` in a commit's first line runs just those tests (2 min, the engine
  test six times over); a native crash leaves `crash-gdb.txt` on the screenshots branch. The same steps can be
  tried on the sandbox with `pip download libtorrent` (the same core, Python).
- **Recording & pause live (8.7), `record/`:** `Capture` = one provider connection (plain TS or HLS, AES-128,
  fMP4) feeding a disk `Ring` (cacheDir/timeshift, marks = time→offset at key frames) and recording `Sink`s.
  `Captures` shares one capture per URL between the player and recordings. `LocalStream` serves the ring on
  127.0.0.1 (random token, chunked: a failed capture drops the connection, never "ends"). The player plays
  live through it when `liveBuffer == "on"`; any error → `noShift` and the channel plays directly.
  `RecordCenter` (recordings.json, device-only, not synced) + `RecordingService` (dataSync FGS, wake lock) +
  `RecordReceiver` (exact alarm; boot/update re-plan). Exact alarms: github flavor `USE_EXACT_ALARM`, play asks
  for `SCHEDULE_EXACT_ALARM`. Series rules plan from the guide when it loads (`planRules`).
- **Channel edits:** `AppState.channelEdits` (sync part `edits`), applied in `LibraryIndex.build` after Tidy;
  `Channel.edited` keeps the guide key. Home rows: `HomeRows`; remote button jobs: `KeyActions`
  (`MEDIA_RECORD` = Record by default); "Open when the TV starts": `StartWithTv` (needs the overlay permission).
- **Media3 is pinned to 1.5.1** with the Jellyfin ffmpeg decoder `1.5.0+1`: bump both together or neither.
  Subtitles: legacy text decoding is turned on in the renderers factory; HLS uses
  `experimentalParseSubtitlesDuringExtraction(true)`; MKV `S_TEXT/SSA`/`S_TEXT/ASCII` are renamed on read.

## Pitfalls already met
- A `Runnable` lambda that posts itself → "recursive problem" compile error: use `object : Runnable`.
- Robolectric tests that don't need the app: `@Config(application = android.app.Application::class)`.
- `animateItem()` fades leave screenshots blank: `animateItem(fadeInSpec = null, fadeOutSpec = null)`.
- In touch mode rows can't take focus: focus tests need keyboard input mode.
- An endless animation on screen (an indeterminate progress indicator) hangs a screen test: use `BusyLine` /
  `stillForTest` (Brand.kt). The `screens` job is continue-on-error: green doesn't mean every test ran (even a
  test compile error shows green). Check `log.txt` (compile errors on top, last lines), `hang-*.txt` and that
  `TEST-*.xml` files are there.
- Screen tests: one `mainScreens` test per class. A second test in the same class stops redrawing (every picture
  is the first frame, no error, checks still pass).
- A picture asked for the first time is not in the frame drawn then (a page opened in a screen test shows
  without its artwork): `shoot` the page twice (`TitlePageScreens.kt`).
- `capture` draws an open dialog (`ShadowDialog.getLatestDialog()`) centred over the dimmed page.
- In `ScreenshotBase`, `app` is the FalakApp property: write imports, not `app.falak.player…` inside tests.

## Saving tokens
Read big files by section (Grep, then Read with offset/limit), not whole. Keep CI output short (tail, grep).
One feature or fix per chat; update this file when a rule or a design decision changes.
