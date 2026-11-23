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
  **Live TV for a big playlist (8.20; the owner saw three sketches and said "mix"), `ui/GroupsUi.kt`:**
  (1) Folders: `data/GroupFolders.kt` folds groups whose names start the same way before a mark ("UK | Sports",
  "AR: Kids", "[FR] Cinema"; never at & + . , or a dash without spaces) into a folder named as the provider wrote
  it (nothing guessed); only when that gives two folders or more; the groups' real names stay the keys
  everywhere. Off: Settings → Playlists → Group folders (`Settings.groupFolders`). TV: `GroupColumn` opens folders
  in place. (2) Find a group: `GroupFinderDialog` (`groupFinderOpen` / `groupFinderTyped`, any words of the name;
  nothing typed: the groups watched lately, then all, folders opening in place). With folders or more than
  `MANY_GROUPS` (12) groups the TV column gets "Find a group" and the chips row becomes `GroupBar` (Find a group,
  All, Favorites, the open group, the ones watched lately). (3) Your channels first: "All channels" in list view
  starts with `YourChannels` tiles (favorites, then `Habits.forNow`), the list's header item: `ChannelList(header)`,
  so `focusLoop(offset = 1)` and the scroll back to a channel count it. The heading names a group with its folder
  ("UK · Sports"). Films and Series keep their chips. Tests: `GroupFoldersTest` (runs on the sandbox with
  Gradle's Kotlin compiler), `LiveScreens.kt` (`groupPrefixes` gives the sample playlist such group names).
  The sketches are a Design artifact ("The Player: Live TV ideas for big playlists").
  **Live TV's front page is rows (8.21; the owner showed another app's page and asked for Live TV "matching movie
  and series way of showing content"), `ui/LiveRows.kt`:** Live now works like Films and Series: `vm.group == null`
  (nothing searched) = the front page, `ALL_FILTER` = "All channels" as the list (`selectGroup(null)` gives
  `ALL_FILTER`; Back from a group or the list returns to the rows; `liveFront` in LibraryScreen). The page is
  `MarqueePage(hero = false)`: chips ("All channels · N", "Find a group" when there are many, then the folders:
  one picked, `vm.liveFolder`, shows only its groups' rows, Back clears it), rows "Favorites" and "Often on at this
  time", then a row per group (the ones watched lately first; `Shelf(key, count)`, 24 channels, then a "See all N"
  tile; locked and adult groups show one tile, no logos). "See all" opens the group as the 8.20 list (three columns
  on a TV). `isDividerName` (GroupFolders.kt) keeps the provider's divider lines ("##### UK #####": the same sign
  three times at the start, or no letter or digit) out of the rows; the lists still have them. TV: the spotlight
  panel names the channel watched last, then favorites, until the remote is on a card. `YourChannels` tiles are
  now the header of the "All channels" list only. Tests: `LiveScreens.kt` (the sample groups start with a divider
  line when `groupPrefixes`), `GroupFoldersTest.tellsDividersFromChannels`.
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
  in Kids. Playing: `Channel` id `addon:<video id>`: not in the playlist's Recent or Watch Next (resume is the portal's own, below).
  **Like Stremio's Board and Discover (8.22; the owner: lists "stuck on same content", "make it like we are in
  the Stremio app"):** the lists were read once for as long as the app stayed open; now `load` reads them again
  when the page is opened or the app comes back and they are older than `FRESH_MS` (20 min), keeping what is
  shown until the answer is here. `AddonCatalog.genres` / `genreRequired` / `paged` (the "genre" and "skip"
  extras; years for Cinemeta's "New"): a list that needs a pick is a row with its first one ("New · Films · 2026");
  rows are named "Popular · Films" (`Row.name`). "See all" on a row = `AddonDiscover`: the picks as chips, a grid
  that asks for the next page (`skip` = titles so far) as its end comes near (`discover*` in the model).
  **A portal of its own (8.23; the owner: an add-on opens "in portal same as provider portal but of course no
  live"; only add-ons with links are clickable, the ones that only bring lists "only to be removed"):**
  `Screen.ADDONS` left `SHELL_SCREENS`: the page draws its own menu (`AddonsModel.Tab` Home / Films / Series /
  My list: `PortalBar` under the page on a phone upright, `PortalTabs` over it otherwise). Home = the first title
  large (`PortalHero`), Continue watching, My list, then the lists; Films and Series = the lists of that type.
  On the Playlists page an add-on with a "stream" resource (`Addons.hasLinks`) opens its portal (`m.only` = its
  link: links are asked from it alone; the menu's Add-ons entry opens all of them together); a lists-only add-on
  (Cinemeta) is a "LISTS" row that can't be clicked, only removed, and feeds every portal. **My list**
  (`Settings.addonSaved`) and **Continue watching** (`Settings.addonWatch`, one `AddonWatch` per title; both sync
  like any setting): `vm.noteAddonPlay` when a link starts, the player's `saveProgress` → `repo.noteAddonProgress`
  (`Addons.noteStart` / `noteProgress`: a finished film leaves, a series stays as "Watched S1 E2"), and the player
  resumes by the `addon:<video id>` id. The link itself is never kept (links change): a card opens the title's
  page (a series on its season) and a link is picked again. A long press on a card takes it out.
  **One tap, the next episode, subtitles (8.24, the owner picked them from a list of ideas):** `Addons.pickLink`
  chooses the link most like the kind picked last (`Settings.addonPick` = "add-on|quality|t or h", written by
  every pick): a "Play / Resume · 1080p · Torrentio" button over the links (`playItem`), and on a series' page
  "Play / Resume / Next episode · S1 E3" (`m.upNext(left)` → `m.playEpisode`: `playWhenReady`, the links are asked
  for and the best one plays). An add-on's film or episode that ends closes the player (its queue is one item),
  back on its page with that button; there is no in-player "Up next" for add-ons (a torrent can't be got ready
  ahead). Subtitles: the add-ons' "subtitles" resource (`AddonClient.subtitles`, asked with the links) and a
  link's own `subtitles` → `repo.addonSubs` (video id → list) → the player's subtitles list gets "Subtitles from
  your add-ons (N)…" (`pickAddonSubtitle`: your language first; the file is made UTF-8 by `Addons.subtitleText`,
  old Arabic/Russian… Windows files included, then `attachSubtitle`). Picked by hand, like "Find subtitles online".
- **Channels that don't work are hidden (8.24, the owner asked), `data/DeadChannels.kt`:** the player tells
  `repo.noteLiveFailed` at its last failure (with the internet there) and `noteLivePlayed` at `STATE_READY`. A
  failure counts once another channel plays within 10 min (or played in the 3 min before); more than five waiting
  at once = the provider, none count; a channel that played in the last 30 min only hiccupped. `AppState.dead`
  (playlist → channel → when; this device's, not synced): out of Live TV's rows (`LiveRows`) and lists
  (LibraryScreen's `result`) for 7 days, then shown again to be tried; playing one clears it. Favorites, Home and
  the search keep them. Under a list's heading "N that didn't work are hidden · Show" (`showDead`); Settings →
  Playlists → "Hide channels that don't work" (`Settings.hideDead`, on) with "Show them all again".
- **Skip buttons (8.25; the owner: "like Netflix, press to skip intro, press to skip outro, and if I didn't press
  the show will continue"):** one button (`SkipIntroButton(label)`, UpNext.kt; OK on a TV, a tap on a phone),
  never a skip by itself. **Add-on titles** (`data/SkipSegments.kt`): `loadSkips` asks introdb.app as the film or
  episode starts (`GET https://api.introdb.app/segments?imdb_id=tt…&season=…&episode=…`, or `&is_movie=true`; free,
  no key; answers `{"intro":{"start_ms","end_ms",…}|null,"recap":…,"outro":…}`; not reachable from the sandbox, but
  CI reaches it: `SkipSegmentsLiveTest` asks the real list and reads its answers, only in a `[only …]` run, which
  sets `LIVE_CHECKS`; checked 8.26, the answers were as expected. The list has slips, e.g. a film's "intro" inside
  its credits: `offer` takes intros and recaps in the first half only, credits in the second) and `addonSkipTick` shows "Skip intro / recap / credits" while that part plays
  (`offer`: from 1 s before to 4 s before its end; credits jump to the end, where the player closes and the
  series' page has "Next episode"). Settings → Playback → "Skip intro and credits buttons" (`Settings.skipLookup`,
  on; only the IMDb number, season and episode are sent). Tests: `previewSkip` runs the real check on a stand-in
  player (`PhoneSkipIntroScreens`, `TvSkipCreditsScreens`, `TvSkipNothingScreens`). Not in the list: the learned intro, now for add-on
  series too (`showKey` = "addon|tt…"). **The playlist's series** have no IMDb number: the intro is learned as
  before (a jump forward early in an episode, 20 s to 4 min, `AppState.intros`), and so is where the credits
  start (`learnOutro`: going on to the next episode yourself late in one, before the card; kept as the time left
  under the show's key + `OUTRO_KEY` in `intros`): the Up next card then comes at that point, offering only; its
  countdown (`autoNextEpisode`) still starts in the last 20 s. "Keep watching" on an early card forgets it.
  **The playlist's own films and series too (8.27, the owner asked):** introdb.app takes IMDb numbers only (a
  `tmdb_id` gets HTTP 400, checked) and providers send none, so `data/TitleMatch.kt` finds it: `clean` the name
  ("EN - The Matrix (1999) 4K" → "The Matrix", 1999), search Cinemeta's catalog (`searchUrl`, no key), `pick` only a
  sure match (same name once `norm`ed, year ±1; no year: the only one with that name, or for a series the search's
  first answer when it has that name). The year comes from the name or `repo.providerYears` (noted when the
  provider's film or series details are read). `repo.imdbFor` (cached for the session) → `providerVideoId` in the
  player. Films use the add-ons' check (`addonSkipTick`); a series' intro and recap get the button, its listed
  credits bring the Up next card at that point (or "Skip credits" after the last episode). Arabic or other
  non-Latin names find nothing sure on Cinemeta (its search answers with unrelated English titles): no button.
  `SkipSegmentsLiveTest.providerTitlesAreFound` checks real names against the real services.
- **Home button in the player (8.27, the owner asked; phones only since 8.28, the owner: "remove from TV"):**
  `homeButton` at the start of the player's top bar (a house), `goHome()`: the progress is kept, a live channel goes to the mini player as with
  Back, then MainActivity gets `Notifier.EXTRA_OPEN = "HOME"` → `vm.navigate("HOME")` (Library, Home, the add-on
  portal's page closed).
- **Welcome, the guide, what changed (8.27; the owner: "first time installing the app, a welcome screen and
  features on each button, like a guide, for the first time only"; after an update "show where the update is"),
  `ui/Guide.kt`, `data/AppChanges.kt`:** buttons register with `Modifier.guideAnchor(name)` (BarTab, RailIcon's
  square, the Cinema menu's words and tools, the search box, the top bar's TV guide button). `Guide.start(steps)`
  keeps the steps whose button is on screen (or that point at nothing); `GuideOverlay` (in MainActivity, over
  everything) dims the screen with a ring round the button and a card placed beside / under / over it (a `Layout`
  measures the card first). Tap or Next goes on, Skip or Back ends; on a TV Left/Right move between Skip and Next.
  A new install (no playlist, `Settings.seenVersion` "") shows `WelcomeScreen`; "Get started" sets `seenVersion`
  and `tourDue`, and `Guide.TOUR` runs when the lists are next on screen. After an update, `AppChanges.since(
  seenVersion or FIRST_NOTED, VERSION_NAME)` runs as a guide ("What's new in …" then each change, pointing at its
  button), then `seenVersion` = this version. **Add an `AppChanges.BIG` entry with a release only when a core
  feature changes how it works or where it is.** Settings → "Help and about": "Show the guide again" and "What
  changed in this version" (`vm.changesAgain`). `seenVersion` and `tourDue` are `DEVICE_SETTINGS`. Under
  Robolectric `Guide.auto` is false (nothing starts by itself); `GuideScreens.kt` turns it on.
- **8.28 (the owner picked five from a list):** (1) **Backup file**, `data/Backup.kt`: `BackupFile` = a
  `SyncSnapshot` of everything (stamped now, `deviceId` "" so no peer is added, no kids part) with app/kind/version;
  restore = `SyncMerge.merge` (what changed here since stays). Settings → Devices & phone remote: "Save a backup
  file" / "Restore from a backup file" (CreateDocument / OpenDocument; a TV without a file picker is told to link a
  phone). Passwords are in the file (the owner's sync rule: everything goes). (2) **Torrents on mobile data:**
  `onMobileData` (metered network) → asked first in AddonsUi's `play` unless `Settings.torrentOnData`; Settings →
  Playback "Ask before torrents use mobile data" (GitHub version). (3) **Stream info:** Player ("Play with") window
  → "Stream info": `player/StreamInfo.kt` lines, ticking each second; dropped frames from an `AnalyticsListener`.
  (4) **Screen lock (phones):** `lockButton` at the left edge, middle; `lockScreen()` puts a shield over everything
  (touches and Back only show "Tap to unlock" for 3 s). (5) **Chromecast (phones):** `cast/Cast.kt` (`CastSetup` in
  the manifest, kept by R8; `Cast.context` null without Google Play services, so no button), `CastLinks.castable`
  (no 127.0.0.1 links: torrents, live buffer; downloads stay; Xtream live .ts → .m3u8). Our own picker
  (`showCastPicker`, MediaRouter). While casting, `playCurrent` sends each next item to the Chromecast; ending the
  session plays here again. Provider headers/User-Agent are not sent: some providers refuse the Chromecast.
  Tests: `BackupTest`, `StreamInfoTest`, `CastLinksTest`, `PhoneLockScreens`.
- **8.29 (the owner picked them, plus actors):** (1) **Sound timing**, `player/SoundTiming.kt`: ExoPlayer's
  audio sink is wrapped so the clock the picture follows is moved (`Offset.us`, read live); VLC
  `setAudioDelay` (set again at each Playing). `Settings.audioDelayMs` (+ later, − earlier; this device's).
  Player ("Play with") window → "Sound timing" (−/+ 50 ms, heard at once), and Settings → Sound.
  (2) **Actors** from **Wikidata** (no key; the owner OK'd it after Trakt's change), `data/People.kt` (queries and
  reading, tested) + `data/PeopleClient.kt` (`repo.people`; User-Agent names the app, Wikimedia asks it; `testing`
  under Robolectric: nothing asked). Find = `wbsearchentities` (Arabic names asked in Arabic) + a SPARQL keeping
  humans who are cast (P161) or director (P57) of something, with a Commons photo (`Special:FilePath…?width=360`).
  Page = SPARQL (born, died, place, photo, en/ar Wikipedia) + Wikipedia's REST summary. Titles = SPARQL of what lists
  them with an IMDb number; names: en, else **mul** (since 2024 many items have no English label, only "mul"), Arabic
  as `altName`; series by kind (P31) or episode/season counts; posters `images.metahub.space/poster/small/tt…/img`.
  Checked live 8.29 (`PeopleLiveTest`, `[only …]`): Keanu Reeves 81 films, Adel Imam in Arabic, Nolan.
  **Quicker (8.30; the owner: "it took time to load"):** measured (`PeopleTimingLiveTest`): the network was 1.7–2.4 s
  from CI (5 requests, 3 in a row) and matching the playlist cleaned every film's name each time (1–2 s for 60,000
  on a desktop, more on a phone). Now: `TitleMatch.norm` does plain-English names by hand and `clean` checks a
  name ends with a tag before its slow pattern (same results as before on 300,000 made-up names, checked);
  `People.Names` = the playlist's cleaned names made once per `vm.index` (`repo.people.names`, ~0.3 s for 60,000
  on a desktop, then ~1 ms per actor); `PeopleClient.find` starts the likeliest one's films and page while the
  actor check runs (`once` keeps one request per thing); answers kept in `cacheDir/people` for 7 days (600 files;
  an old one is used when offline); `CastNames` → `warm` looks up a page's first 3 actors after 0.8 s (not on
  mobile data, not in kids mode); `PersonScreen` shows who first, then story and films, then "In your playlist".
  Live: first time ~0.8–1 s from CI, kept ~5 ms.
  `ui/PersonScreen.kt` (`Screen.PERSON`, `vm.openPerson/closePerson`, `PersonOpen.from`): `CastNames` (chips; text
  in kids mode); photo, line, born, story, "In your playlist" (`People.inLibrary`: cleaned name or Arabic name +
  year ±1), Films, Series (others open with add-ons that bring links, returnTo PERSON; Back from a title opened
  there comes back: `titleFromPerson` / `leaveTitle`). Search: "People" row (`rememberPeopleFound`, after the
  titles, names must contain every typed word). Add-on pages read cast from `cast`/`director` or Cinemeta's `links`.
  (3) **Ratings**: `rememberScores` → "IMDb 8.7" (Cinemeta, `repo.imdbRating`) on film/series pages (provider titles
  via `imdbFor`) and add-on pages. Nothing asked in screen tests.
  (4) **Trakt was removed in 8.31** (the owner: "I will not rely on it"): no code, settings, secrets or tests left.
  Don't bring it back unless he asks. Old `Settings.trakt*` fields in saved files are ignored (`ignoreUnknownKeys`).
  Tests: `PeopleTest` (runs on the sandbox), `SoundTimingTest`, `AddonsTest`, `PersonScreens.kt` (`PersonForTest`),
  `PeopleLiveTest`.
- **8.31 (the owner picked 1, 2, 11 + remove Trakt + tidy Settings + look for faults):**
  (1) **Settings in eight groups** (`SettingsPage`, hub in SettingsScreen.kt): Look & start (APPEARANCE, ends with
  "When the app opens": open last playlist, last channel, Open on, Open when the TV starts), Player (PLAYBACK:
  Watching, then Connection), Sound & subtitles (SOUND; Subtitle style is its sub-page), Playlists & TV guide,
  Recordings & reminders (RECORDINGS; Reminders & alerts is its sub-page, Prayer times a row there; `parent`),
  Profiles & parental control (FAMILY), Devices & backup (DEVICES), Help and about. Hints elsewhere ("Settings → …")
  use these names. (2) **Frozen streams restart**, `player/FreezeRule.kt` (pure, `FreezeRuleTest`): the player ticks
  every 2 s (`freezeTick`, `checkFrozen`; off under Robolectric, `FREEZE_OFF`); stuck while it should play = live
  12 s, a film 20 s, nothing shown yet 20 s/30 s → "Reconnecting…" (live: `playCurrent`; films: prepare again at the
  same place), at most 3 in 3 min, then "keeps freezing". Not for torrents, casting or two screens.
  (3) **Check the connection**, `data/ConnectionCheck.kt` (`ConnectionCheckTest`) + `ui/ConnectionUi.kt`: Settings →
  Player → Connection: the internet (Cloudflare's speed file, 25 MB, 5 MB on mobile data), the provider (Xtream
  `player_api.php` or the M3U link) and a channel (the last one watched), each time-limited; a plain verdict.
  (4) **Watched**: `Watch.done` (pos ≥ dur) = finished; finished titles are now KEPT in progress (were removed,
  so a series' page forgot them) and left out of Continue watching / resume. Series page: long press an episode
  → `vm.setWatched` (✓ Watched on the row, toast).
- **The mini player moves (8.32, the owner: "free to move anywhere in the app"), `ui/MiniPlayer.kt`:** drawn over
  every page (MainActivity) placed by its own `Layout` (its size known on the first frame); `MiniSpot` keeps its place as fractions of the free room
  (SharedPreferences "mini_player", this device's; bottom right at first). Phones: drag (a tap still opens it).
  TVs: hold OK on it (key repeat) → arrows move it 48 dp, OK or Back = done; a short OK opens it, Back closes it
  (all in `onPreviewKeyEvent`, so `clickable` never sees OK). Test: `MiniSpotTest`.
- **8.33 (the owner picked 1 and 3 from a list of what paid apps have):** (1) **MAC portals** (Stalker/Ministra),
  `data/Portal.kt` (`Portal` = pieces without network, `PortalApi` = session: handshake with our own token,
  get_profile with MAG ids made from the MAC (`identity`), "Authorization failed" → one new session, then
  `Portal.REFUSED`; lists: itv get_all_channels (else paged get_ordered_list), vod/series categories paged 4 at a
  time (`PARALLEL`, `MAX_TITLES`), series in the films list (`is_series`, "v" + id) or the series part ("x" + id);
  seasons via get_ordered_list movie_id/season_id; create_link per play; get_epg_info (24 h) as "Your portal's
  guide"; get_main_info → `Account`). A playlist with `Playlist.mac` (`isPortal`) is stored as M3U_URL (older
  versions just fail to download it); its UA defaults to `Portal.MAG_UA`. Links: `data/PortalLinks.kt`, a
  127.0.0.1 server (port + token kept in SharedPreferences "portal_links", started by Repository when a portal
  exists) that asks the portal for a fresh link and answers 302 (20 s cache; "still here" watchdog every 2 min for
  3 h after a play), so every player, recording and download works unchanged; `fix` mends saved copies after a
  port change. Details the list leaves out: `PortalExtra` in `<id>.portal.json` (`repo.portalExtra`). Add window:
  "MAC portal" chip (`SourceKinds(type, portal)`), `vm.addPlaylist(portal = true, mac)` checks it first
  (`repo.portalLogin`). Not done: catch-up, Chromecast for portal links (127.0.0.1), serial/device-id portals.
  Never tried against a real portal: `PortalTest` runs a made-up portal on the CI machine.
  (2) **Up to four screens** (PlayerActivity): `panes` (the other pictures; `dual` = the first, so the old
  `dual != null` checks mean "several screens"), `heard` (0 = main), `rects`, `dualLabels`; two side by side as
  before, three or four in a 2×2 grid (`layoutDual`). TV: two = ◀ ▶ sound, ▲ ▼ channel; three or four = arrows
  choose, CH± channel; OK = channel list, OK held = add a screen (`dualOkHeld`; the held key's repeats are
  swallowed), Back = one screen (the heard one). Phones: "Add a screen" in `dualBar`, tap = sound, double tap =
  full screen. Tests: `TvFourScreens`, `PhoneThreeScreens` (`previewScreens`).
- **8.34 (the owner picked 1, 2, 3, 5, 6 of a list of what paid apps show), `data/Discover.kt` (pure, `DiscoverTest`) +
  `ui/DiscoverUi.kt`:** (1) **Sports hub**: `Sports.find` reads the guide of sports channels (group or name words)
  for matches ("X v Y", Grand Prix, "LIVE:"…; not highlights/talk), on now and the next 18 h, one per match with all
  its channels; `SportsShelf` (`MatchCard` in Cards.kt) on Home ("sports" row) and Live TV's front page: on now
  plays, later sets a reminder, long press picks the channel, "See all" = the day's list. (2) **One poster per
  title** (`Versions`): copies share the cleaned name + year (`Versions.title` drops tags in front: "MULTI-SUB |",
  "4K-AR -"); `LibraryIndex.titleKeys`, the lists of everything (`movies`, `series`, new, trending) show the first
  copy, groups keep theirs; `VersionChips` on film and series pages. (3) **Pin to Home**: GroupMenu "Pin to Home",
  `Settings.homePins` ("playlist|KIND|group", syncs), Home row "pinned". (5) **Popular now**: Cinemeta's `top`
  catalogs (3 pages, kept a day, `PeopleClient.popular`) matched to the playlist by name and year
  (`repo.popularIn`, People.inLibrary; English names). (6) **Collections** (`Collections`: names — sequel marks,
  " - " and ":" subtitles; ≥2 films, one marked; `index.collections` lazy): Films page row of `CollectionCard`s
  opening `COLLECTION_FILTER + name` as a list, and "<name> collection" on a film's page. **Because you watched**
  (`repo.becauseYouWatched` → `PeopleClient.similar`, Wikidata in three short questions: the film's people
  (`similarPeopleQuery`: film series ×8, director ×3, writers ×2, its 8 best-known actors by sitelinks ×1), what
  they made (`similarQuery`, VALUES, score ≥ 2), then those works' names (`titlesQuery`); ~0.5 s from CI. One
  question with the names in it took 13–60 s or timed out: Wikidata then scans every title with an IMDb number)
  on Home and the Films/Series pages, from the last film or series watched. Checked live 8.34 (`DiscoverLiveTest`):
  The Matrix, Heat, Breaking Bad; Cinemeta's popular lists ~0.7 s, 147 films / 150 series.
  HomeRows got "sports", "pinned", "popular", "because". Tests: `DiscoverScreens.kt` (`discoverSample`,
  `discoverForTest` = names for popular/because).
- **8.35 (the owner picked 1 2 3 4 7 8 9 10 of an all-over check):** PRIVACY.md brought up to date (every online
  service the app asks; keep it so when one is added). Search shows one copy per title (`Versions.oneEach`);
  `rememberExcluded` (DiscoverUi) keeps hidden/locked/kids-hidden copies out of `VersionChips` and collections;
  "Because you watched" asks nothing in kids mode. **Another player app** already existed (`Engine.EXTERNAL`);
  now `player/ExternalPlayer.kt` sends the provider's User-Agent/Referer as `headers` (MX, Just Player) and no
  chooser (Android's own list with "Always"). **Android TV home row** `data/TvHomeRow.kt`: a preview channel
  "The Player" (`publishDefaultChannel`, id in SharedPreferences "tv_home_row") of favorite then recent live
  channels (`pick`: no excluded/adult groups, 12 at most; empty in kids mode), written by `vm.writeTvRow()` when
  MainActivity or the player stops (`repo.tvRow`); a pick → `TvHomeRow.EXTRA_PLAY` → `playFromAlert`. Google TV's
  home doesn't show app rows (only Play next). **Smooth interlaced channels** (`Settings.deinterlace`, this
  device's, Settings → Player): live channels play with VLC (`desiredEngine`), `--deinterlace=-1
  --deinterlace-mode=yadif2x`, and `:no-mediacodec-dr` so VLC's filter sees the pictures (more power).
  **Portal catch-up:** `Portal.archiveDays` (tv_archive/_duration, hours vs days rule as QiTV), `catchupType "stb"`,
  `Catchup.portalArchive` → link module `tv_archive`, cmd "channel@start s" → `PortalApi.archiveLink`
  (`get_simple_data_table` of the day ±1, `archiveEntry` within 90 s, `mark_archive` 0 = not recorded, then
  create_link tv_archive "auto /media/<id>.mpg"). Needs past programmes in the guide. **Portal → Chromecast:**
  `PortalLinks.ours/real` ask the portal's own link before casting (`castNow` async, `castBypass` = play here
  when it fails). The portal may refuse a Chromecast (another IP, no MAG User-Agent). Tests: `PortalTest`, `TvHomeRowTest`.
- **Save to gallery (8.36, phones only; the owner: "save only to gallery, no need for share"), `record/GallerySave.kt`:**
  long press a saved recording (phones) → Save to gallery / Delete. The .ts is put in an MP4 as it is by Media3's
  Transformer (`media3-transformer`, same 1.5.1; nothing re-encoded unless the sound can't go in MP4) in
  `getExternalFilesDir/gallery-<id>.mp4`, then copied to Movies/The Player (Android 10+: MediaStore, IS_PENDING;
  7–9: the folder itself + `WRITE_EXTERNAL_STORAGE` maxSdk 28, asked by the page). Transformer fails → the .ts is
  copied. Progress in `GallerySave.state` (`GalleryLine` on the Recordings page, a toast when done). Needs ~2× the
  file's size free for a moment. Tests: `GallerySaveTest`, `PhoneGallerySaveScreens`.
- **8.37 (the owner sent a native crash, SIGABRT, Nothing A001, Android 16, minutes after 8.36):** the report named
  no crashing thread (loose words of every thread). Now `data/Tombstone.kt` reads Android 12+'s tombstone protobuf
  (`ExitReasons`): signal, abort message, causes, the crashing thread's frames, the process's last W/E/F log lines
  (`TombstoneTest` runs on the sandbox with JUnit from Gradle's lib). `FreezeWatch` keeps its steps in
  `filesDir/steps.txt` too; `previousSteps` (read at start) go under a native crash or memory report. Likely cause:
  Save to gallery's Transformer used Android's MediaMuxer, which aborts the process on samples it refuses: now
  `InAppMuxer` (Media3's own writer; errors become `onError` → the .ts is copied). Not proven: the old report
  couldn't say.
- **8.38 (the owner: cartoon profile pictures; the side-on slideshow can't be moved; "make full use of the space on
  phones… push buttons… not necessarily the same way for both"; asked, he said both the playlist pages and the
  add-on portal):** (1) **Profile pictures** `data/Avatars.kt` + `res/drawable/avatar_*.xml` (13 original cartoons:
  fox, bear, cat, owl, frog, robot, alien, penguin, bunny, monster, lion, octopus, ghost; drawn from shapes by a
  script, never a known character). `Profile.avatar`, `Settings.mainAvatar` / `kidsAvatar` (Kids: octopus); a new
  profile gets a random unused one, an old one a steady one from its id (`Avatars.of`). `ProfileTile(avatar)`,
  `ProfileButton`, `AvatarDialog` (tap the picture in Settings → Profiles). (2) **Side-on slideshow:** `MarqueeHero`
  takes `onCurrent` (touch: swipe, and ‹ › `HeroArrow`s beside the dots) and `tight` (phones on their side: name
  and story one line); `marqueeFraction` 0.7 there (was 0.84). (3) **Room on phones** (`isPhone()` = not a TV,
  smallest width < 600): `ui/Chrome.kt` (`Chrome.hidden` from nested scrolling, 24 px of travel; `LocalChrome`):
  AppShell's bottom bar slides off and the page's top bar (SidebarFrame) folds away while scrolling down, back on
  the way up or while searching; reset on page/section/group change. Add-on portal on a phone: upright =
  `PortalTopBar` (back, name, search magnifier) + `PortalBar`, both hiding with the scroll, the hero `TALL` (poster
  across the width, 340 dp); on its side = `PortalRail` (back, the four pages, search) and the page beside it,
  hero `LOW` (156 dp). The search box shows only when asked for (`searchShown`). AppChanges entry "More room".
- **8.39 (the owner: "Froze in LibraryViewModel$watchAlerts", 9 times, 2 devices, 8.27–8.38, up to 221 s):**
  `watchAlerts`' "nothing changed" test read `old == new && it == 0`: the value test ran first for every part, so
  each new TV guide (or index, favorites…) was compared item by item on the main thread every few seconds. Now
  identity first, value only for the playlist id. **Never compare the guide by value on the main thread:** Compose
  keys use equals(), so keys holding the guide are `Same(epg)` (ui/Chrome.kt, identity). **And the report itself
  was mostly wrong:** one sample "after 219 s" (not at 4 s) means the watch thread didn't run either: Android's
  cached-apps freezer had paused the whole app in the background, and on waking the first main-thread job was
  watchAlerts' 3 s timer. `FreezeWatch` now skips a round whose own sleep overran by > `PAUSED_MS` (3 s).
- **8.40 (the owner picked 10 "app shortcuts" of a list, plus "change profile picture… best cartoon or fictional
  character or anything unique"):** known characters are someone's IP: never; instead 12 more originals (astronaut,
  pirate, wizard, ninja, viking, chef, dragon, unicorn, tiger, koala, shark, dino; the script is the one of 8.38) and
  **your own photo** (phones: `AvatarDialog` "Use a photo", PickVisualMedia → `saveAvatarPhoto`: EXIF turn, middle
  square, 320 px JPEG in filesDir/avatars, key `photo:<file>`; `Avatars.usable/photoFile`, `photoDir` set by FalakApp;
  the key syncs but the file doesn't: other devices show the cartoon). `AvatarImage` draws either. Hold a profile on
  "Who's watching?" to change it (`ProfileCard(onLongClick)`). **App shortcuts** `AppShortcuts.kt` (phones only,
  dynamic, set at start): Live TV / Last channel / Search → `EXTRA` → `vm.openShortcut` (waits in `shortcutWaiting`
  for the playlist or "Who's watching?"; Search bumps `vm.searchAsked`, which focuses the top bar's box).
  Icons `res/drawable/shortcut_*.xml`. **8.41:** 8.40 alone got Play Protect's "might be harmful" on the owner's
  phone (Thailand; 8.39 installed fine; same key, same permissions and components, checked with androguard). The
  only new behaviour Play Protect could weigh: shortcuts made by code at every start (adware trait) and the photo
  picker. Now the shortcuts are static (`res/xml/shortcuts.xml`, meta-data on MainActivity, same extra); no
  ShortcutManager code. **Confirmed by the owner: 8.41 installs without the warning.** Never create shortcuts (or
  icons) from code; keep them static in the manifest.
- **8.42 (the owner picked 2 3 4 5 7 of "things other apps don't have", plus: player buttons better organized
  especially on phones, TV menu on top, "just the logo" for the menu on TV and phone; he OK'd the sketch):**
  (1) **Menu as icons:** TV = `ShellMode.TOP` in both looks (`TopMenu`, sections are `MenuTool` icons with an
  underline when selected, the name shows on focus); phones' `BarTab` icons only (name in `contentDescription`).
  Tablets/phones on their side keep the rail. (2) **Player buttons:** phone top bar = home, the name with what's
  on under it (two lines, smaller, "N min left"), a plain star, then ONE rounded group (Cast, Picture size, Sleep,
  Lock, Player; lock left its spot at the left edge); the row under the seek bar = 40 dp pills (13 sp):
  `pills()` = Back to live, Channels, Audio and subtitles, Record, Two screens, Back in…, Speed, On TV. TV keeps
  actions left, icons right; `tvShortPills` (twoButton, handoffButton, hop when idle) go icon-only when crowded.
  (3) **Half-time hop** (`hop`, `Hop`, live only): "Back in…" → 5/10/15/20 min or when the next programme starts;
  the channel list opens; the button counts down; at the time `hopBack()` (rememberPrevious, then the hop's list);
  tap while running = Go back now / cancel; ends with the player (onStop). (4) **Still watching?**
  (`Settings.stillWatchingHours` 0/2/3/4, default 3, this device's; Settings → Player → Watching):
  `onUserInteraction` stamps `lastTouchMs`; paused/casting/PiP don't count; a 60 s dialog, then `stopForNow`
  (shared with the sleep timer: live lets go of the connection, OK/Play carries on). (5) **Hand-off and second
  screen** over Wi-Fi linking: `AutoSync` serves sealed `POST /link` (`data/Handoff.kt`: `LinkAsk` op now / play /
  key / stop, sent time ±5 min, `LinkAnswer`, `NowState`, `HandItem` = playlist SYNC id + the Channel + pos/dur +
  seriesId) answered by `sync/LinkCommands.kt`; `autoSync.nearby()` (names from `sync.peers` by id prefix),
  `ask()`. The player's "On TV" / "On phone" (`handoffButton`, only while a linked device is found; not for
  127.0.0.1 links except a MAC portal's) → the other side `RemoteHandler.handOff` → `vm.playHandedOff` (writes
  the progress 5 s back, then `autoPlay`); this side closes its player. `linkTick` keeps `repo.handNow`.
  **Second screen** (`ui/SecondScreen.kt`, phones: More → Second screen, only when linked): asks "now" every 3 s;
  live: now/next from the TV's guide; films/series: `repo.imdbMeta` (Cinemeta: rating, story, cast → actor page);
  `RemotePad` keys via `RemoteServer.pressKey` (now in its companion); "Play it on this phone" (asks "stop" there).
  Both apps must be open (sync runs only on screen). (6) **What can I finish?** (`data/Finish.kt`, `FinishTest`):
  last card of `ContinueRow` (2+ films/episodes) → `FinishDialog` (30 min–3 h): time left, next episodes guessed
  from the series' watched episodes; films not started have no length (said in the dialog).
  Tests: `HandoffScreens.kt` (`previewHop`, `askStillWatchingForTest`, `SecondScreenTest`). A Home screen test that opened the finish dialog by a
  test flag never saw ContinueRow compose (unexplained, 4 CI runs): the dialog has no picture test.
- **8.43 (the owner sent another app's picture, "Peek before you jump in", for the TV; phones unchanged):**
  **TV Live TV as a list** (`peek` in LibraryScreen: Live, TV, ≥720 dp, nothing searched, not grid) =
  `LivePeekHead` (LivePreview.kt, 170 dp: number + name, clock, programme, On now time + left, Up next, story; the
  picture on the right playing after `PREVIEW_DWELL_MS`) + `PeekBar` (the group · count → `GroupFinderDialog`;
  List | Grid | Guide; `SortMenu`; Search → `cinemaSearchOpen`) + `ChannelList(peek = true)` → `PeekRow` (40 dp:
  number, logo, name + quality badge, now + progress, next; under the remote white with "Full screen"; ☆ for
  favorites). No group column, chips or `YourChannels` there (the 8.20/8.21 front page rows still have them);
  search results keep the old list + `LivePreviewPane`. Test: `peekFocusedForTest`. **Player buttons are icons
  only** (`applyIconLabels`: a TV shows the name of the focused one; Sleep and "Back in…" show their time left;
  names are read out). **New icons** (our own line style, 24 vp, 1.8 stroke, round): `ic_pl_*`, `ic_nav_*`
  (incl. new home/favorites/search/guide), `ic_pl_live` (Back to live), and Media3's middle buttons overridden by
  name (`exo_styled_controls_play/pause/next/previous/rewind/fastforward`, Media3 1.5.1's names). Made by a
  script (kept in the 8.43 chat): keep new icons in the same style.
- **8.44 (the owner picked, of a list of what premium apps have: "Films" of "On now by type", the in-player list's
  next programme on TV only; and asked to check the ambient screen):** (1) **Films on now** (`data/Discover.kt`
  `FilmsOnNow`, `FilmsOnNowTest`): a programme is a film when the XMLTV `<category>` says so (`Program.film`, read in
  `Epg.parse`; saved as a NEGATIVE length in the guide cache, so older copies still read: `StoreTest`) or the channel
  is a film channel by group/name ("Movies", "Cinema", "أفلام"…); trailers/promos out, < 10 min left out; one card
  per title (`Sports.Match`, `MatchCard(label = "1 h 20 min left")`). Live TV's front page row after sports
  (`FilmsShelf`, TV and phone; not in Kids, not hidden/locked/adult groups). Xtream's and portals' own guides
  carry no types: only film channels count there. (2) **TV player's channel list**: a "Next 21:00 Title" line
  (ChannelBrowser, `tv` only). (3) **Ambient screen** (`ui/Ambient.kt`, Settings `ambient`, 5 min idle on the lists)
  existed; it now waits while a channel plays small on the page (`repo.previewOpen`): it used to cover Live TV's
  peek picture and the channel went on playing under it.
- **8.45 (the owner picked 2 and 3 of a list of what premium IPTV apps have):** (1) **Colour** (`Settings.accent`,
  syncs like `themeMode`; Settings → Look & start → Colour, `AccentDot` swatches): `Accents` in Theme.kt (orange =
  the app's own, red, pink, purple, blue, teal, green; a dark- and a light-page shade each, ARGB too);
  `FalakTheme(accent)` → `Palette.withAccent` (remembered: a new palette each time would redraw everything).
  The player's own views use `accentInt` (banner number, sound level bar, loading dots, a lit icon) and
  `LiveTimeline.accent`. Not changed: the launcher icon and the phone-remote web pages (the brand's orange).
  (2) **What the player shows** (`data/PlayerInfo.kt`, `Settings.playerInfo`, syncs; Settings → Player →
  "What the player shows" chips): clock, channel number, what's on now, time left, what's next, picture quality
  and speed, sound and subtitles; the name always. `DEFAULT` = everything that showed before except the new
  clock, so nothing changed for anyone. Read by `renderBanner` and `updateTopBar` (the clock redraws when the
  minute turns, `clockShownAt` in `linkTick`). Tests: `PlayerInfoTest`, `AccentScreens.kt` (`previewBanner`).
- **8.46 (the owner asked my opinion of his layout against TiviMate, iMPlayer, Smarters, Sparkle and OTT Navigator;
  he OK'd "a shorter Home"):** `HomeRows.DEFAULT_ORDER` (Continue watching, Favorite channels, Recently watched,
  pinned groups, Top 10, new films, new series, sports, because, then the rows only filled by what you made) and
  `DEFAULT_HIDDEN` (Popular now, For this hour) apply while `untouched` (homeRows and homeHidden both empty).
  Someone who only hid rows before keeps the old order (`ALL`'s); any change in the dialog writes both lists
  (`toggled` / `movedSettings`), so the defaults stop applying without moving anything else. "Default rows" empties
  both. Test: `HomeRowsTest`. AppChanges entry "A shorter Home".
- **8.47 (the owner: "line by line review… fix it all"):** an all-over check of the CI pictures, the code and
  Android lint (`[lint]` in a commit's first line runs `lintGithubDebug`; `lint-results-*.xml` go to the screenshots
  branch). Fixed: the 3–4 screens' names went under the hint bar (now at the top of a bottom pane); the tour named
  the player's house on a TV (phones only); alert windows ignored the colour; the second screen's "stop" could be
  cut off as its page closed; `/link` read an oversized body before refusing it (`MAX_LINK` first); divider lines in
  the TV peek list looked like channels (now a quiet heading). Lint's remaining errors were checked: Media3
  opt-ins, `dispatchKeyEvent`, tvprovider builders (guarded by `WatchNext.supported`, API 26), a `Map.get` read as
  a regex group (false), BOM literals on purpose. Screen tests count as a TV with no touchscreen (`detectTv`), so
  pictures of a "phone" in the default layout show the TV top menu: not a real-phone fault.
- CI: `[only A B C]` in a commit's first line runs several test patterns (separated by spaces).
- **Top 10 = the best of what is new (8.22; the owner: "based on recent movies, not all time high", his row never
  changed), `data/Trending.kt`:** rating × 0.5^(days since added / 30), nothing older than 120 days; a film whose
  name carries a year before last year is out; a day that stamped 15% of the library (200+) is no date; without a
  date the year in the name decides. The provider's "Trending/Popular/Top N" groups came first until then, in the
  provider's order (the same every day): now their titles count ×1.25 and the groups only fill a short row.
- **The loading page in the player (8.22):** `buildLoadingView` (PlayerActivity): the channel's logo and name and
  three dots in `playerView.overlayFrameLayout` (over the picture of both engines, under the controls), shown by
  `playCurrent`, hidden at `STATE_READY` / the first frame / the last failure. The dots move with a handler tick
  (no endless animator: screen tests).
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
