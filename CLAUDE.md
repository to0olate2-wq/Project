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
- **The menu stays (8.8), `ui/Shell.kt`:** Sidebar look with a playlist open: `AppShell` (in `MainActivity`, around
  the pages) draws the rail (TV, ≥600 dp) or a bottom bar (phones upright: Home, Live, Films, Series, More) for
  `SHELL_SCREENS` (lists, More, Recordings, Downloads, What's new, Settings and its pages). `SidebarFrame` is only the
  top bar + page now (no drawer). `LocalShell` tells pages which; Back from those pages is `vm.closePage()` (player
  detour → player, opened from More → More, else the lists). TV Settings: list of pages left, page right
  (`twoPane`; focus moves on OK, the page follows the remote after 160 ms). Classic look: unchanged, full pages.
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
  normalising, lenient parsing, `AddonClient` (OkHttp). Only http(s) stream links play; torrents/magnets and other
  links are counted, never played (owner: no torrent engine). No add-on ships with the app. Links in
  `Settings.addons` (sync with Wi-Fi linking). `ui/AddonsModel.kt` (state, in `vm.addons`) + `ui/AddonsUi.kt`
  (`Screen.ADDONS`: rows per catalog, search, details, a series' episode links in a dialog). Entries: More (phones),
  rail (TV), Settings; hidden in Kids. Playing: `Channel` id `addon:…` = one-off (no Recent, no resume, no Watch Next).
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
- In `ScreenshotBase`, `app` is the FalakApp property: write imports, not `app.falak.player…` inside tests.

## Saving tokens
Read big files by section (Grep, then Read with offset/limit), not whole. Keep CI output short (tail, grep).
One feature or fix per chat; update this file when a rule or a design decision changes.
