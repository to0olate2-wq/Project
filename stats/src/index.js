// The Player's user counter.
//
// The app sends, when it opens and every 10 minutes while it's on screen: a random number made
// up on the device (no name, no email, nothing about what's watched), the app version, and
// whether it's a TV or a phone. Turning the count off in the app sends "forget", which deletes
// that number. The stats page (password: the STATS_PASSWORD secret) shows how many use the app
// now, today, this week, this month and ever.

import { DurableObject } from "cloudflare:workers";

const ID = /^[0-9a-f]{32}$/;
const MINUTE = 60 * 1000;
const DAY = 24 * 60 * MINUTE;
/** The app checks in every 10 minutes: seen within 11 minutes = using it now. */
const ONLINE_MS = 11 * MINUTE;
/** Days are counted in UAE time. */
const ZONE_MS = 4 * 60 * MINUTE;
/** A device not seen for this long is no longer kept (it still counts in "ever"). */
const KEEP_MS = 400 * DAY;

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const stats = env.STATS.get(env.STATS.idFromName("all"));

    if (url.pathname === "/v1/ping") {
      if (request.method !== "POST") return new Response("Use POST", { status: 405 });
      let body;
      try {
        body = await request.json();
      } catch {
        return new Response("Bad request", { status: 400 });
      }
      const id = String(body?.id ?? "");
      if (!ID.test(id)) return new Response("Bad request", { status: 400 });
      const event = ["open", "beat", "forget"].includes(body.event) ? body.event : "beat";
      const version = String(body.v ?? "").replace(/[^0-9A-Za-z._-]/g, "").slice(0, 16);
      const kind = body.kind === "tv" ? "tv" : "phone";
      await stats.ping(id, event, version, kind);
      return new Response(null, { status: 204 });
    }

    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/stats.json")) {
      if (!authorized(request, env)) {
        return new Response("The Player stats: password needed", {
          status: 401,
          headers: { "WWW-Authenticate": 'Basic realm="The Player stats", charset="UTF-8"', "cache-control": "no-store" },
        });
      }
      const data = await stats.summary();
      if (url.pathname === "/stats.json") return json(data);
      return new Response(page(data), {
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-robots-tag": "noindex" },
      });
    }

    return new Response("Not found", { status: 404 });
  },
};

/** HTTP basic sign-in: any user name, the STATS_PASSWORD secret as password. */
function authorized(request, env) {
  const pass = env.STATS_PASSWORD;
  if (!pass) return false;
  const header = request.headers.get("Authorization") || "";
  if (!header.startsWith("Basic ")) return false;
  let decoded;
  try {
    decoded = new TextDecoder().decode(Uint8Array.from(atob(header.slice(6)), (c) => c.charCodeAt(0)));
  } catch {
    return false;
  }
  return sameText(decoded.slice(decoded.indexOf(":") + 1), pass);
}

/** Compares without leaking how much matched. */
function sameText(a, b) {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

/** "2026-10-23" in UAE time. */
const dayOf = (ms) => new Date(ms + ZONE_MS).toISOString().slice(0, 10);

/** All counting happens in one place, with its own small SQLite database. */
export class Stats extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS installs (
      id TEXT PRIMARY KEY, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
      version TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL DEFAULT 'phone', opens INTEGER NOT NULL DEFAULT 0)`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS installs_last_seen ON installs(last_seen)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS daily (day TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (day, id))");
    this.sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
  }

  ping(id, event, version, kind) {
    const now = Date.now();
    if (event === "forget") {
      this.sql.exec("DELETE FROM installs WHERE id = ?", id);
      this.sql.exec("DELETE FROM daily WHERE id = ?", id);
      return;
    }
    const known = this.sql.exec("SELECT 1 FROM installs WHERE id = ?", id).toArray().length > 0;
    if (!known) {
      this.sql.exec("INSERT INTO meta (key, value) VALUES ('ever', 1) ON CONFLICT(key) DO UPDATE SET value = value + 1");
    }
    this.sql.exec(
      `INSERT INTO installs (id, first_seen, last_seen, version, kind, opens) VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET last_seen = excluded.last_seen, version = excluded.version,
         kind = excluded.kind, opens = installs.opens + excluded.opens`,
      id, now, now, version, kind, event === "open" ? 1 : 0,
    );
    this.sql.exec("INSERT OR IGNORE INTO daily (day, id) VALUES (?, ?)", dayOf(now), id);
  }

  summary() {
    const now = Date.now();
    // Keep only what the page needs.
    this.sql.exec("DELETE FROM installs WHERE last_seen < ?", now - KEEP_MS);
    this.sql.exec("DELETE FROM daily WHERE day < ?", dayOf(now - 90 * DAY));

    const seenSince = (since) => this.sql.exec("SELECT COUNT(*) AS n FROM installs WHERE last_seen >= ?", since).one().n;
    const ever = this.sql.exec("SELECT value FROM meta WHERE key = 'ever'").toArray()[0]?.value ?? 0;
    const today = dayOf(now);
    return {
      at: now,
      online: seenSince(now - ONLINE_MS),
      today: this.sql.exec("SELECT COUNT(*) AS n FROM daily WHERE day = ?", today).one().n,
      week: seenSince(now - 7 * DAY),
      month: seenSince(now - 28 * DAY),
      ever,
      newThisWeek: this.sql.exec("SELECT COUNT(*) AS n FROM installs WHERE first_seen >= ?", now - 7 * DAY).one().n,
      kinds: this.sql.exec("SELECT kind, COUNT(*) AS n FROM installs WHERE last_seen >= ? GROUP BY kind ORDER BY n DESC", now - 28 * DAY).toArray(),
      versions: this.sql
        .exec("SELECT version, COUNT(*) AS n FROM installs WHERE last_seen >= ? GROUP BY version ORDER BY n DESC LIMIT 8", now - 28 * DAY)
        .toArray(),
      daily: this.sql.exec("SELECT day, COUNT(*) AS n FROM daily WHERE day >= ? GROUP BY day ORDER BY day", dayOf(now - 29 * DAY)).toArray(),
    };
  }
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const num = (n) => Number(n).toLocaleString("en-US");

/** The stats page: big numbers, then who and which version, then the last 30 days. */
function page(d) {
  // Every one of the last 30 days, with none for days nobody used it.
  const byDay = new Map(d.daily.map((r) => [r.day, r.n]));
  const days = Array.from({ length: 30 }, (_, i) => {
    const day = dayOf(d.at - (29 - i) * DAY);
    return { day, n: byDay.get(day) ?? 0 };
  });
  const maxDay = Math.max(1, ...days.map((r) => r.n));
  const total = d.kinds.reduce((a, r) => a + r.n, 0) || 1;
  const card = (label, value, note) =>
    `<div class="card"><div class="value">${num(value)}</div><div class="label">${esc(label)}</div>${note ? `<div class="note">${esc(note)}</div>` : ""}</div>`;
  const bars = days
    .map(
      (r, i) =>
        `<div class="bar" title="${esc(r.day)}: ${num(r.n)}"><span style="height:${r.n ? Math.max(2, Math.round((r.n / maxDay) * 100)) : 0}%"></span>` +
        `${i % 5 === 4 || i === 29 ? `<em>${esc(r.day.slice(8))}</em>` : ""}</div>`,
    )
    .join("");
  const rows = (list, key, name) =>
    list.length
      ? list.map((r) => `<tr><td>${esc(name(r[key]))}</td><td>${num(r.n)}</td><td>${Math.round((r.n / total) * 100)}%</td></tr>`).join("")
      : `<tr><td colspan="3" class="muted">Nobody yet</td></tr>`;
  const when = new Date(d.at + ZONE_MS).toISOString().replace("T", " ").slice(0, 16);
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="60"><title>The Player · users</title>
<style>
:root{--bg:#141210;--panel:#1d1a17;--line:#2c2824;--text:#f7f3ec;--muted:#a59d93;--accent:#ff6a4d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:920px;margin:0 auto;padding:28px 16px 48px}h1{font:600 26px/1.2 Georgia,serif;margin:0 0 4px}
.sub{color:var(--muted);margin:0 0 22px;font-size:13px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px}.value{font:600 34px/1.1 system-ui,sans-serif;font-variant-numeric:tabular-nums}
.card:first-child .value{color:var(--accent)}.label{margin-top:6px;font-weight:600}.note{color:var(--muted);font-size:12px}
h2{font-size:13px;letter-spacing:.12em;text-transform:uppercase;color:var(--muted);margin:30px 0 10px}
.chart{display:flex;align-items:flex-end;gap:3px;height:150px;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:14px 12px 26px}
.bar{flex:1 1 0;min-width:0;height:100%;display:flex;flex-direction:column;justify-content:flex-end;position:relative}.bar span{display:block;background:var(--accent);border-radius:3px 3px 0 0}
.bar em{position:absolute;bottom:-20px;left:0;right:0;text-align:center;font-style:normal;font-size:10px;color:var(--muted)}
.two{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:14px;overflow:hidden}
td{padding:9px 14px;border-top:1px solid var(--line);font-variant-numeric:tabular-nums}tr:first-child td{border-top:0}td:nth-child(n+2){text-align:right;color:var(--muted)}
.muted{color:var(--muted)}footer{margin-top:28px;color:var(--muted);font-size:12px}
@media (max-width:520px){.card:first-child{grid-column:1/-1}.grid{grid-template-columns:1fr 1fr}}
</style></head><body><main>
<h1>The Player</h1><p class="sub">Updated ${esc(when)} (UAE time) · refreshes every minute</p>
<div class="grid">
${card("Using it now", d.online, "seen in the last 11 minutes")}
${card("Today", d.today, "since midnight, UAE time")}
${card("Last 7 days", d.week)}
${card("Last 28 days", d.month)}
${card("Ever", d.ever, `${num(d.newThisWeek)} new this week`)}
</div>
<h2>Daily users, last 30 days</h2><div class="chart">${bars}</div>
<div class="two">
<div><h2>TV or phone · last 28 days</h2><table>${rows(d.kinds, "kind", (k) => (k === "tv" ? "TV" : "Phone & tablet"))}</table></div>
<div><h2>App version · last 28 days</h2><table>${rows(d.versions, "version", (v) => (v ? "The Player " + v : "Unknown"))}</table></div>
</div>
<footer>Each count is one device that has The Player, by a random number it made up: no names, emails or what's watched. A reinstall counts as a new device.</footer>
</main></body></html>`;
}
