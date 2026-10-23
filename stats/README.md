# The Player: user counter

A tiny Cloudflare Worker (`src/index.js`) that counts the devices using The Player: the app sends a
random number made up on the device, the app version and TV or phone when it opens and every
10 minutes while it's on screen (see `app/.../Stats.kt` and PRIVACY.md). The page at the Worker's
address shows how many use it now, today, this week, this month and ever.

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

## Testing it on a computer

    cd stats && npm install wrangler@4 && echo "STATS_PASSWORD=test" > .dev.vars && npx wrangler dev
