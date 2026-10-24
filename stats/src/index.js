// The Player's user counter and crash reports.
//
// The app sends, when it opens and every 10 minutes while it's on screen: a random number made
// up on the device (no name, no email, nothing about what's watched), the app version, and
// whether it's a TV or a phone. After the app stops unexpectedly, the next start sends a crash
// report: the error and where in the app's code it happened, with web addresses, IP addresses,
// logins and file names already taken out on the device, plus the device model and Android
// version. The stats page (password: the STATS_PASSWORD secret) shows how many use the app now,
// today, this week, this month and ever, and the crash reports grouped by cause.

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
/** Crash reports: kept 90 days, the newest 500 at most, 5 a day per device, 300 a day in all. */
const CRASH_KEEP_MS = 90 * DAY;
const CRASH_MAX = 500;
const CRASH_PER_DEVICE = 5;
const CRASH_PER_DAY = 300;
const CRASH_TYPES = ["crash", "anr", "native", "freeze", "memory", "other"];

/** Text from the app, without control characters (keeps line breaks and tabs), at most [max] long. */
const clean = (v, max) =>
  String(v ?? "")
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "")
    .slice(0, max);

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

    if (url.pathname === "/v1/crash") {
      if (request.method !== "POST") return new Response("Use POST", { status: 405 });
      const text = await request.text();
      if (text.length > 64000) return new Response("Too large", { status: 413 });
      let body;
      try {
        body = JSON.parse(text);
      } catch {
        return new Response("Bad request", { status: 400 });
      }
      const id = String(body?.id ?? "");
      const type = CRASH_TYPES.includes(body?.type) ? body.type : "";
      const report = clean(body?.report, 8000);
      if (!ID.test(id) || !type || !report.trim()) return new Response("Bad request", { status: 400 });
      await stats.crash({
        id,
        type,
        report,
        sig: clean(body.sig, 200).replace(/\s+/g, " ").trim() || type,
        version: String(body.v ?? "").replace(/[^0-9A-Za-z._-]/g, "").slice(0, 16),
        kind: body.kind === "tv" ? "tv" : "phone",
        device: clean(body.device, 80).replace(/\s+/g, " ").trim(),
        android: clean(body.android, 40).replace(/\s+/g, " ").trim(),
      });
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
    this.sql.exec(`CREATE TABLE IF NOT EXISTS crashes (
      n INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, day TEXT NOT NULL, install TEXT NOT NULL,
      version TEXT NOT NULL, kind TEXT NOT NULL, type TEXT NOT NULL, sig TEXT NOT NULL,
      device TEXT NOT NULL, android TEXT NOT NULL, report TEXT NOT NULL)`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS crashes_install_day ON crashes(install, day)");
    this.sql.exec("CREATE INDEX IF NOT EXISTS crashes_sig ON crashes(sig, type, at)");
  }

  /** Keeps a crash report (within the daily limits). */
  crash(r) {
    const now = Date.now();
    const day = dayOf(now);
    const mine = this.sql.exec("SELECT COUNT(*) AS n FROM crashes WHERE install = ? AND day = ?", r.id, day).one().n;
    const all = this.sql.exec("SELECT COUNT(*) AS n FROM crashes WHERE day = ?", day).one().n;
    if (mine >= CRASH_PER_DEVICE || all >= CRASH_PER_DAY) return false;
    this.sql.exec(
      `INSERT INTO crashes (at, day, install, version, kind, type, sig, device, android, report)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      now, day, r.id, r.version, r.kind, r.type, r.sig, r.device, r.android, r.report,
    );
    this.sql.exec("DELETE FROM crashes WHERE n <= (SELECT MAX(n) FROM crashes) - ?", CRASH_MAX);
    return true;
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
    this.sql.exec("DELETE FROM crashes WHERE at < ?", now - CRASH_KEEP_MS);

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
      crashes: this.crashSummary(now),
    };
  }

  /** Crash reports grouped by cause, newest first, each with its latest report. */
  crashSummary(now) {
    const week = this.sql.exec("SELECT COUNT(*) AS n, COUNT(DISTINCT install) AS devices FROM crashes WHERE at >= ?", now - 7 * DAY).one();
    const groups = this.sql
      .exec(
        `SELECT sig, type, COUNT(*) AS n, COUNT(DISTINCT install) AS devices, MIN(at) AS first, MAX(at) AS last,
           GROUP_CONCAT(DISTINCT version) AS versions
         FROM crashes GROUP BY sig, type ORDER BY last DESC LIMIT 30`,
      )
      .toArray()
      .map((g) => {
        const latest = this.sql
          .exec("SELECT version, kind, device, android, report FROM crashes WHERE sig = ? AND type = ? ORDER BY at DESC LIMIT 1", g.sig, g.type)
          .one();
        return { ...g, versions: String(g.versions ?? "").split(",").filter(Boolean), latest };
      });
    return { week: week.n, devicesWeek: week.devices, total: this.sql.exec("SELECT COUNT(*) AS n FROM crashes").one().n, groups };
  }
}

/** "3 h ago", "2 d ago". */
function ago(ms, now) {
  const m = Math.max(0, Math.round((now - ms) / MINUTE));
  if (m < 1) return "just now";
  if (m < 60) return `${m} min ago`;
  if (m < 48 * 60) return `${Math.round(m / 60)} h ago`;
  return `${Math.round(m / 1440)} d ago`;
}

const TYPE_NAMES = { crash: "Crash", anr: "Not responding", native: "Engine crash", freeze: "Froze", memory: "Out of memory", other: "Stopped" };

/** One line per cause (open it for the latest report). */
function crashList(groups, now) {
  if (!groups.length) return `<div class="crashes"><div class="empty">No crash reports.</div></div>`;
  return `<div class="crashes">${groups
    .map((g) => {
      const l = g.latest;
      const versions = g.versions.slice(-4).map((v) => esc(v)).join(", ");
      return `<details><summary><b><span class="tag">${esc(TYPE_NAMES[g.type] ?? g.type)}</span>${esc(g.sig)}</b>
<div class="info">${num(g.n)} ${g.n === 1 ? "time" : "times"} · ${num(g.devices)} ${g.devices === 1 ? "device" : "devices"} · version ${versions || "?"} · last seen ${esc(ago(g.last, now))}</div></summary>
<div class="meta">Latest: The Player ${esc(l.version || "?")} · ${esc(l.device || "unknown device")} · Android ${esc(l.android || "?")} · ${l.kind === "tv" ? "TV" : "phone"}</div>
<pre>${esc(l.report)}</pre></details>`;
    })
    .join("")}</div>`;
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
<meta http-equiv="refresh" content="300"><title>The Player · users</title>
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
.crashes{background:var(--panel);border:1px solid var(--line);border-radius:14px;overflow:hidden}
.crashes details{border-top:1px solid var(--line)}.crashes details:first-child{border-top:0}
.crashes summary{cursor:pointer;padding:12px 14px;list-style:none}.crashes summary::-webkit-details-marker{display:none}
.crashes summary b{display:block;font-weight:600;word-break:break-word}.crashes .info{color:var(--muted);font-size:12px;margin-top:3px}
.tag{display:inline-block;font-size:10px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--bg);background:var(--accent);border-radius:4px;padding:1px 6px;margin-right:6px}
.crashes .meta{padding:0 14px 8px;color:var(--muted);font-size:12px}
.crashes pre{margin:0 14px 14px;padding:12px;background:var(--bg);border:1px solid var(--line);border-radius:10px;white-space:pre-wrap;word-break:break-word;font:12px/1.45 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;max-height:420px;overflow:auto}
.empty{padding:14px;color:var(--muted)}
@media (max-width:520px){.card:first-child{grid-column:1/-1}.grid{grid-template-columns:1fr 1fr}}
</style></head><body><main>
<h1>The Player</h1><p class="sub">Updated ${esc(when)} (UAE time) · refreshes every 5 minutes</p>
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
<h2>Crash reports · by cause</h2>
<p class="sub">${num(d.crashes.week)} in the last 7 days, on ${num(d.crashes.devicesWeek)} ${d.crashes.devicesWeek === 1 ? "device" : "devices"} · open one for its latest report</p>
${crashList(d.crashes.groups, d.at)}
<footer>Each count is one device that has The Player, by a random number it made up: no names, emails or what's watched. A reinstall counts as a new device. Crash reports arrive with web addresses, IP addresses, logins and file names already taken out on the device.</footer>
</main></body></html>`;
}
