# Nova TV — IPTV player for Android phones and Android TV

Stage 1 features
- Add playlists by **M3U link**, **M3U file**, or **Xtream Codes login** (server, username, password)
- Live TV and Movies, channel groups, search, favorites, recently watched
- TV guide (XMLTV EPG): "now / next" with progress bar, auto-detected from the playlist or Xtream server
- Player (Media3/ExoPlayer): HLS, MPEG-TS, DASH, MP4/MKV; auto-retry; Xtream live falls back from .ts to HLS
- Channel zapping: CH+/CH−, D-pad up/down, or the on-screen previous/next buttons
- Picture-in-picture on phones, full Android TV remote support, TV launcher banner
- Optional custom User-Agent and EPG link per playlist

Stage 2 (v1.3)
- **Series** (Xtream): seasons and episodes, next episode plays automatically
- **TV Guide grid**: timeline of channels and programmes; tap what's on to watch, tap later shows for details
- **Resume**: movies and episodes continue where you stopped, with progress bars
- **Settings**: picture size (Fit/Fill/Zoom), larger buffer, prefer HLS for Xtream live, open last playlist
- **Parental PIN**: lock adult groups automatically and/or pick groups to lock
- Player: subtitles button, picture-size button (MENU key on TV remotes), audio track and speed in the ⚙ menu

No channels are included: you add your own provider's playlist.

## Build the APK

### Option A — GitHub (no Android Studio needed)
1. Create a new repository on GitHub and upload this whole folder (including `.github/`).
2. Open the repo's **Actions** tab → the "Build APK" run starts on every push (about 5 minutes).
3. Open the finished run → **Artifacts** → download `NovaTV-apk` (a zip containing `app-debug.apk`).

### Option B — Android Studio on your Mac
1. Install Android Studio from developer.android.com/studio.
2. **File → Open** → select this `NovaTV` folder → wait for Gradle sync (first time downloads a few GB).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

If sync complains about the Java version: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** → pick a JDK 17 or 21.
If Android Studio offers to upgrade the Android Gradle Plugin, you can accept or skip; both work.

## Install
- **Phone:** open the APK, allow "Install unknown apps" for your browser/files app when asked.
- **Android TV / box:** copy the APK with a USB stick, or use the "Downloader" app / "Send Files to TV", then open it and allow unknown apps.

## Tips
- Xtream server format: `http://host:port` (no `/player_api.php` needed).
- A playlist is cached after the first load; use the refresh button to re-download it.
- Long lists: use the search button or pick a group first.

## Ideas for later
Catch-up / timeshift, multi-screen, recordings, cloud backup of favorites.
