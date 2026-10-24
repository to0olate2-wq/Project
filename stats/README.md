# The Player: user counter, crash reports and Match centre feed

A tiny Cloudflare Worker (`src/index.js`) that counts the devices using The Player: the app sends a
random number made up on the device, the app version and TV or phone when it opens and every
10 minutes while it's on screen (see `app/.../Stats.kt` and PRIVACY.md). The page at the Worker's
address shows how many use it now, today, this week, this month and ever.

After the app stops unexpectedly, its next start sends a crash report (`POST /v1/crash`, see
`app/.../CrashReports.kt`): the error and where in the app's code it happened, with web
addresses, IP addresses, logins and file names already taken out on the device. The page lists
them grouped by cause, newest first; open one to see its latest report. Kept 90 days, the newest
500 at most, 5 a day per device.

## Turning it on (once)

1. Create a free Cloudflare account at https://dash.cloudflare.com/sign-up.
2. Make a key: https://dash.cloudflare.com/profile/api-tokens → **Create Token** → template
   **Edit Cloudflare Workers** → **Use template** → under *Account Resources* pick your account
   (and *All zones* under *Zone Resources*) → **Continue to summary** → **Create Token** → copy it.
3. Copy your **Account ID** (Cloudflare dashboard → your account's menu → *Copy account ID*).
4. In GitHub: the repository → **Settings → Secrets and variables → Actions → New repository
   secret**, three times:
   - `CLOUDFLARE_API_TOKEN`: the key from step 2
   - `CLOUDFLARE_ACCOUNT_ID`: the ID from step 3
   - `STATS_PASSWORD`: a password you make up for the stats page
5. Run the build again (Actions → Build APK → Run workflow). The `stats` job deploys the counter,
   the app build after it points at it, and the run's summary shows the stats page address.

Without these secrets nothing is deployed and the app counts nothing.

## Match centre scores and the Premier League table (optional)

The app's Match centre finds matches in your TV guide by itself. For scores and the Premier
League table it asks this counter, which fetches them from football-data.org (shared by all
users: at most once a minute, so the free plan's 10 requests a minute are plenty).

1. Register at https://www.football-data.org/client/register (free). The key comes by email.
2. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**:
   `FOOTBALL_DATA_KEY` = that key.
3. Run the build again. The run's summary says "Match centre feed: on, …".

The free plan's scores can be a few minutes behind; their "Free w/ Livescores" plan (paid) makes
them live with no change here. The key stays in Cloudflare and is never put in the app.

## Testing it on a computer

    cd stats && npm install wrangler@4 && echo "STATS_PASSWORD=test" > .dev.vars && npx wrangler dev
