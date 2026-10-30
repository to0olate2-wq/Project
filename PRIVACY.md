# The Player: Privacy Policy

_Last updated: 25 October 2026 (version 7.5)_

The Player is a video player for Android phones, tablets and Android TV, made by Ahmed. It plays
playlists that you add yourself. It does not come with any channels, films or other content.

## The short version

- The Player has **no account, no ads and no tracking**.
- Everything you enter stays **on your devices**. The developer never receives it.
- The only things sent to the developer are an **anonymous user count** (a random number made up
  on your device, the app version, and whether it's a TV or a phone) and, after the app stops
  unexpectedly, a **crash report** with personal details taken out (see both sections below).
- The app only connects to the servers **you** choose (your playlist provider, the TV guide
  sources you add) and, if you turn it on, to OpenSubtitles.

## What is stored on your device

The app keeps these in its private storage on your device:

- the playlists you add: their names, links, and Xtream server address, username and password;
- your settings, favourites, custom lists, recently watched channels and how far you got in films
  and episodes;
- the TV guide sources you add and a copy of the guide data;
- programme reminders and alert words you set, and the audio and subtitle tracks you picked for
  each channel, film or series (so they come back next time);
- which live channels you watched for at least two minutes, and when (the last 600, from the past
  two months at most), so Home can show *For this hour*: the channels you usually watch around
  this time of day;
- for Xtream logins, your subscription's status, end date and connections as your provider
  reports them (read from the same provider, with the same login), so Settings can show them, the
  app can remind you before the subscription ends, and a channel refused because every connection
  is in use can be explained;
- for the *Service report*: how live channels played on this device over the last two weeks
  (how long they took to start, how often they stopped to load, the names of channels that
  wouldn't play, and the hours of the day this happened), so Settings can show how your provider
  performs;
- with kids mode on, how long the Kids profile has watched today and any time a grown-up added,
  for the daily limit and bedtime;
- Ramadan dates you moved by hand, and your voice search language;
- the PIN for locked groups, if you set one;
- your OpenSubtitles API key, username and password, if you enter them;
- a short technical record if the app stops unexpectedly, so the next start doesn't reopen what
  was playing; it is sent as a crash report (see *Crash reports* below) and then deleted;
- the random number used for the anonymous user count.

None of this is sent to the developer or to anyone else, except the crash record as described
below. Removing a playlist removes it from the app; clearing the app's data or uninstalling the
app deletes everything.

## What the app connects to

The app only makes network connections needed for what you ask it to do:

- **Your playlist provider.** To load your playlist and play its streams, the app contacts the
  server in the playlist link or Xtream login you entered. That server receives what any video
  player sends: your IP address, the app's user agent and, for Xtream, the username and password
  you entered for it. How that provider handles data is governed by its own policy.
- **Pictures in your playlist.** Channel logos and posters are loaded from the addresses given in
  your playlist.
- **TV guide sources.** If you add a TV guide (XMLTV) source, or pick one of the suggested free
  sources (epgshare01.online), the app downloads the guide from that address.
- **OpenSubtitles** (optional). If you enter an OpenSubtitles API key and search for subtitles,
  the title of the film or episode and your OpenSubtitles login are sent to api.opensubtitles.com
  to find and download subtitles. See the OpenSubtitles privacy policy.
- **Trailers.** If you open a trailer, it opens in YouTube or your browser, under their own
  policies.
- **Auto captions** (off unless you turn them on for a channel). The channel's sound is turned
  into captions on your device, as it plays; nothing you watch or hear is sent anywhere. The first
  time, the app downloads the speech model it needs (about 71 MB, the Kroko-ASR model by Banafo)
  from Hugging Face (huggingface.co), which, like any website, receives your IP address. You can
  remove it in Settings → Sound & subtitles.
- **Your own devices** (only while you use it). When you open *Your devices* and start
  sharing, the app serves a page on your home network, protected by a 6-digit code shown on
  screen, so your phone or computer can manage playlists or copy them to another device running
  The Player. Your playlists and logins go directly between your devices on your own network;
  sharing stops when you leave that screen.
- **Sync with my devices** (off unless you link devices). Devices you link with the 6-digit
  code keep everything the same: playlists (with their logins, and combined playlists), your own
  order of groups and channels, favorites and lists, Continue
  watching and recently watched, profiles, settings (apart from ones that belong to one device,
  such as its screen and player), followed series, reminders and alert words, and the Kids
  profile's time watched today. While the app is open, they find each other on your home network
  and exchange that data directly, encrypted with a key only your linked devices have. Nothing goes
  through the Internet or to the developer. Unlinking stops it.
- **Phone remote** (only after you pair a phone). While the app is open, it answers paired phones
  on your home network so they can work as a remote and keyboard and start channels, films and
  series. Phones pair with a code shown on the TV; the TV keeps only a fingerprint of each
  phone's key. Nothing goes through the Internet or to the developer. Remove a phone in
  Settings → Phone remote.
- **Prayer times** (off by default) and **Ramadan mode** (during Ramadan, unless switched off).
  Worked out on the device from its time zone and Android's own Hijri calendar; nothing is
  looked up online.
- **Voice search** (only when you press the microphone). What you say is turned into text by your
  device's speech service (usually Google's, under its own privacy policy); The Player only
  receives the text and searches your playlist with it. The app itself never records sound.
- **Android TV home screen.** On Android TV, films and episodes you've started appear in the
  system's Continue watching / Play next row. That list stays on the TV; it never includes hidden,
  locked or adult groups, or anything while kids mode is on.

The app does not send your viewing history, your playlists, your logins or anything that
identifies you to the developer. The only messages to the developer are the anonymous user count
and crash reports, both described below.

## Anonymous user count

So the developer knows how many people use The Player, the app sends a small message when it
comes on screen and every 10 minutes while it stays there. Each message contains only:

- a random number made up on your device the first time (it is not linked to you, your account,
  your email or the device's own identifiers, and a reinstall makes a new one);
- the app version;
- whether the app is running on a TV or a phone.

It never contains your name, email, playlists, logins, what you watch or your location. The
messages go to the developer's own counter, a small program on Cloudflare (cloudflare.com), which
keeps for each random number only when it was first and last seen, the app version and TV or
phone. Like any server, Cloudflare receives your IP address to deliver the message; the counter
doesn't store it. The developer sees totals only: how many devices use the app now, today, this
week, this month and ever, by TV or phone and by version. A number not seen for 400 days is
deleted.

The count is part of how the app works and has no setting. It stops when the app is uninstalled.
Because the number isn't linked to you, the developer can't pick out one person's number; numbers
not seen for 400 days are deleted.

## Crash reports

If the app stops unexpectedly (a crash, a freeze, or Android closing it), the next time it starts
it sends the developer's counter one report so the fault can be found and fixed. Nothing is shown
on screen. The report contains:

- what kind of stop it was, the error, and where in the app's own code it happened;
- the app's last steps before it stopped, as the app writes them (for example "player opened" or
  "channel list opened (3 groups, 120 items)"), never names of channels, films or playlists;
- the app version, whether it's a TV or a phone, the device model (for example "Google Pixel 8")
  and the Android version;
- the same random number as the user count, so repeated reports from one device can be told apart.

Before the report leaves your device, the app removes web and stream addresses, IP and MAC
addresses, server names, usernames, passwords and tokens, email addresses, file names from your
storage and long pieces of quoted text. It never contains your playlists, logins, what you watch
or your location. Reports are kept by the counter for 90 days at most (the newest 500), are seen
only by the developer, and are used only to fix faults. If a report can't be sent, it waits on
the device for the next start, a week at most. Crash reports are part of how the app works and
have no setting.

## Children

The Player is not directed at children and does not knowingly collect any information from
anyone.

## Permissions

The app uses the Internet permission to load your playlists, guides and streams, and the network
state permission, which the video engine uses to adapt playback to your connection. On Android 13
and later it may ask to show notifications: they only tell you, on your device, when a playlist
refresh finds new channels, films or series, that a programme you asked to be reminded of is
about to start, (phones and tablets) that your subscription ends in a week or tomorrow, or,
during Ramadan with the Suhoor reminder on, that Imsak is near. Nothing is sent anywhere to do this; reminders are worked out on the device from
your TV guide. On Android TV it
uses the TV provider permission to put part-watched films and episodes in the home screen's Play next
row. On phones and tablets with *Listen mode* turned on (it's off unless you choose it), it uses the
foreground service (media playback) and wake lock permissions so the sound can carry on with the
screen off, shown by a notification with play and pause. The version downloaded from Google Play
asks for nothing else: no location, contacts, camera, microphone or storage access.

## Changes

If this policy changes, the new version will be posted at this address with a new date.

## Contact

Questions about this policy: use the developer email address shown on The Player's Google Play
page.
