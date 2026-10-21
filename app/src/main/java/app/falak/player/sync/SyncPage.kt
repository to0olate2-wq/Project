package app.falak.player.sync

/** The page a phone or computer browser sees: manage this device's playlists, backup and restore. */
internal object SyncPage {
    fun html(device: String): String = PAGE.replace("{{DEVICE}}", escape(device))

    /** A problem report shown on the phone, ready to copy (the TV can't copy text out). */
    fun report(device: String, text: String): String = REPORT.replace("{{DEVICE}}", escape(device)).replace("{{TEXT}}", escape(text))

    private val REPORT = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Problem report · {{DEVICE}}</title>
<style>
  :root { --ink:#121110; --ink2:#1b1918; --line:rgba(255,255,255,.12); --paper:#f7f3ec; --muted:#d2cbc1; --signal:#ff5a3c; }
  @media (prefers-color-scheme: light) { :root { --ink:#f6f2ea; --ink2:#ffffff; --line:rgba(0,0,0,.12); --paper:#16130f; --muted:#4a423a; --signal:#dc3a1c; } }
  body { margin:0; background:var(--ink); color:var(--paper); font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
  main { max-width:760px; margin:0 auto; padding:24px 16px 40px; }
  h1 { font:600 26px/1.15 Georgia,"Times New Roman",serif; margin:0 0 6px; }
  p { color:var(--muted); margin:0 0 14px; }
  textarea { width:100%; box-sizing:border-box; height:62vh; background:var(--ink2); color:var(--paper); border:1px solid var(--line);
             border-radius:12px; padding:12px; font:12px/1.45 ui-monospace,Menlo,monospace; resize:vertical; }
  button { font:600 16px/1 system-ui,sans-serif; border-radius:999px; padding:14px 26px; border:0; background:var(--paper); color:var(--ink); margin-top:14px; cursor:pointer; }
  #m { margin-left:12px; color:var(--muted); }
</style>
</head>
<body>
<main>
  <h1>Problem report</h1>
  <p>From {{DEVICE}}. Copy it and paste it to Claude.</p>
  <textarea id="t" readonly>{{TEXT}}</textarea>
  <button onclick="copyIt()">Copy</button><span id="m"></span>
</main>
<script>
function copyIt() {
  var t = document.getElementById('t');
  t.focus(); t.select(); t.setSelectionRange(0, t.value.length);
  var ok = false;
  try { ok = document.execCommand('copy'); } catch (e) {}
  document.getElementById('m').textContent = ok ? 'Copied' : 'Press and hold the text, then Select all and Copy';
}
</script>
</body>
</html>
"""

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>The Player · {{DEVICE}}</title>
<style>
  :root { --ink:#121110; --ink2:#1b1918; --ink3:#282523; --line:rgba(255,255,255,.12); --paper:#f7f3ec; --muted:#d2cbc1; --faint:#a0988e; --signal:#ff5a3c; }
  @media (prefers-color-scheme: light) {
    :root { --ink:#f6f2ea; --ink2:#ffffff; --ink3:#ebe5da; --line:rgba(0,0,0,.12); --paper:#16130f; --muted:#4a423a; --faint:#756c62; --signal:#dc3a1c; }
  }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--ink); color:var(--paper); font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
  main { max-width:620px; margin:0 auto; padding:28px 18px 60px; }
  h1 { font:600 30px/1.1 Georgia,"Times New Roman",serif; margin:0; letter-spacing:-.01em; }
  h1 i { font-weight:400; color:var(--faint); }
  h1 b { color:var(--signal); }
  .sub { color:var(--muted); margin:6px 0 26px; }
  h2 { font:600 12px/1 system-ui,sans-serif; letter-spacing:.14em; text-transform:uppercase; color:var(--signal); margin:30px 0 10px; }
  .card { background:var(--ink2); border:1px solid var(--line); border-radius:14px; padding:16px; }
  .row { display:flex; align-items:center; gap:12px; padding:12px 0; border-bottom:1px solid var(--line); }
  .row:last-child { border-bottom:0; }
  .row .t { flex:1; min-width:0; }
  .name { font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .meta { color:var(--faint); font-size:13px; }
  input, select { width:100%; font:inherit; color:var(--paper); background:var(--ink); border:1px solid var(--line); border-radius:10px; padding:12px 14px; margin:6px 0; }
  input:focus { outline:2px solid var(--signal); border-color:transparent; }
  button, .btn { font:600 15px/1 system-ui,sans-serif; border-radius:999px; padding:12px 18px; border:1px solid var(--line); background:transparent; color:var(--paper); cursor:pointer; text-decoration:none; display:inline-block; }
  button.primary { background:var(--paper); color:var(--ink); border-color:var(--paper); }
  button.small { padding:8px 12px; font-size:13px; }
  .tabs { display:grid; grid-template-columns:repeat(3, 1fr); gap:6px; margin-bottom:10px; }
  .tabs button { padding:10px 4px; font-size:14px; white-space:nowrap; }
  button, .btn { white-space:nowrap; }
  .tabs button[aria-pressed="true"] { background:var(--paper); color:var(--ink); }
  .msg { margin-top:10px; color:var(--muted); }
  .msg:empty { display:none; }
  .msg.err { color:var(--signal); }
  .actions { display:flex; gap:10px; flex-wrap:wrap; margin-top:6px; }
  .actions .primary { flex:1; }
  .actions.two { display:grid; grid-template-columns:1fr 1fr; }
  .actions.two > * { text-align:center; }
  .hidden { display:none !important; }
  .empty { color:var(--faint); padding:8px 0; }
  .code { font:600 28px/1 ui-monospace,Menlo,monospace; letter-spacing:.3em; text-align:center; }
  footer { color:var(--faint); font-size:13px; margin-top:34px; }
  /* show & hide groups */
  select { appearance:none; -webkit-appearance:none; margin:0 0 12px; background-image:linear-gradient(45deg,transparent 50%,var(--faint) 50%),linear-gradient(135deg,var(--faint) 50%,transparent 50%); background-position:calc(100% - 20px) 52%,calc(100% - 14px) 52%; background-size:6px 6px; background-repeat:no-repeat; padding-right:40px; }
  .gctl { position:sticky; top:0; z-index:2; background:var(--ink2); margin:-16px -16px 0; padding:16px 16px 10px; border-radius:14px 14px 0 0; border-bottom:1px solid var(--line); }
  .tabs button .n { font-weight:500; opacity:.6; margin-left:4px; font-variant-numeric:tabular-nums; }
  .find { position:relative; }
  .find svg { position:absolute; left:14px; top:50%; transform:translateY(-50%); width:17px; height:17px; stroke:var(--faint); fill:none; stroke-width:2; pointer-events:none; }
  .find input { padding-left:40px; margin:0; }
  .find input::-webkit-search-cancel-button { filter:grayscale(1); }
  .gbar { display:flex; align-items:center; gap:8px; margin-top:12px; }
  .gbar .meta { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-variant-numeric:tabular-nums; }
  .gbar button:disabled { opacity:.35; cursor:default; }
  .grow { display:flex; align-items:center; gap:12px; padding:12px 2px; border-bottom:1px solid var(--line); cursor:pointer; -webkit-tap-highlight-color:transparent; }
  .grow:last-child { border-bottom:0; }
  .gname { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; transition:color .15s; }
  .gcount { color:var(--faint); font-size:13px; font-variant-numeric:tabular-nums; }
  .grow.off .gname { color:var(--faint); }
  .sw { appearance:none; -webkit-appearance:none; flex:none; width:46px; height:28px; margin:0; padding:0; border-radius:999px; border:1px solid var(--line); background:var(--ink3); position:relative; cursor:pointer; transition:background .18s,border-color .18s; }
  .sw::after { content:""; position:absolute; top:3px; left:3px; width:20px; height:20px; border-radius:50%; background:var(--faint); transition:transform .18s,background .18s; }
  .sw:checked { background:var(--signal); border-color:var(--signal); }
  .sw:checked::after { transform:translateX(18px); background:#fff; }
  .sw:focus-visible { outline:2px solid var(--signal); outline-offset:2px; }
  .gempty { color:var(--faint); padding:18px 2px 6px; }
</style>
</head>
<body>
<main>
  <h1><i>The</i> Player<b>.</b></h1>
  <div class="sub">Playlists on <strong>{{DEVICE}}</strong></div>

  <section id="login" class="card hidden">
    <div>Enter the 6-digit code shown on the TV.</div>
    <input id="code" class="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="••••••">
    <div class="actions"><button class="primary" onclick="enter()">Continue</button></div>
    <div id="loginMsg" class="msg"></div>
  </section>

  <div id="app" class="hidden">
    <h2>Playlists</h2>
    <div class="card"><div id="list"></div></div>

    <h2>Show &amp; hide groups</h2>
    <div class="card" id="groups">
      <div class="gctl">
        <select id="gpl" class="hidden" aria-label="Playlist" onchange="loadGroups(this.value)"></select>
        <div id="gbody">
          <div class="tabs" id="gtabs"></div>
          <div class="find">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
            <input id="gq" type="search" placeholder="Find a group" autocomplete="off" oninput="renderGroups()">
          </div>
          <div class="gbar">
            <span id="gsum" class="meta"></span>
            <button class="small" id="gshow" onclick="bulk(false)">Show all</button>
            <button class="small" id="ghide" onclick="bulk(true)">Hide all</button>
          </div>
        </div>
      </div>
      <div id="glist"></div>
      <div id="gmsg" class="msg"></div>
    </div>

    <h2>Add a playlist</h2>
    <div class="card">
      <div class="tabs">
        <button data-t="m3u" aria-pressed="true" onclick="tab('m3u')">M3U link</button>
        <button data-t="xtream" aria-pressed="false" onclick="tab('xtream')">Xtream</button>
        <button data-t="file" aria-pressed="false" onclick="tab('file')">M3U file</button>
      </div>
      <input id="name" placeholder="Name (optional)">
      <div data-f="m3u"><input id="url" type="url" placeholder="Playlist link (http://…)"></div>
      <div data-f="xtream" class="hidden">
        <input id="server" type="url" placeholder="Server (http://…)">
        <input id="user" placeholder="Username" autocapitalize="off">
        <input id="pass" type="password" placeholder="Password">
      </div>
      <div data-f="file" class="hidden"><input id="file" type="file" accept=".m3u,.m3u8,.txt,audio/x-mpegurl"></div>
      <input id="epg" type="url" placeholder="TV guide link (optional)">
      <div class="actions"><button class="primary" id="addBtn" onclick="add()">Add playlist</button></div>
      <div id="addMsg" class="msg"></div>
    </div>

    <h2>Backup</h2>
    <div class="card">
      <div class="meta">Playlists, favorites, your lists and hidden groups, in one file. Load it on any device running The Player.</div>
      <div class="actions two" style="margin-top:12px">
        <a class="btn" id="download" href="#">Download</a>
        <button onclick="document.getElementById('restore').click()">Load backup</button>
        <input id="restore" type="file" accept=".json,application/json" class="hidden" onchange="restore(this)">
      </div>
      <div id="backupMsg" class="msg"></div>
    </div>
    <footer>Changes appear on the TV straight away. Keep this page on the same Wi-Fi as the TV; it stops working when sharing is closed there.</footer>
  </div>
</main>
<script>
let code = new URLSearchParams(location.search).get('code') || '';
let mode = 'm3u';
const ${'$'} = id => document.getElementById(id);

async function api(path, body) {
  const opt = { headers: { 'X-Code': code } };
  if (body !== undefined) { opt.method = 'POST'; opt.headers['Content-Type'] = 'application/json'; opt.body = typeof body === 'string' ? body : JSON.stringify(body); }
  const r = await fetch(path, opt);
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || ('Error ' + r.status));
  return data;
}
function show(id, text, err) { const m = ${'$'}(id); m.textContent = text || ''; m.className = 'msg' + (err ? ' err' : ''); }
function esc(s) { return String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }

function render(list) {
  const el = ${'$'}('list');
  fillGroupPlaylists(list);
  if (!list.length) { el.innerHTML = '<div class="empty">No playlists yet. Add one below.</div>'; return; }
  el.innerHTML = list.map(p => '<div class="row"><div class="t"><div class="name">' + esc(p.name) + '</div><div class="meta">' +
    esc([p.type, p.server, p.user].filter(Boolean).join(' · ')) + '</div></div>' +
    '<button class="small" onclick="rename(\'' + p.id + '\', this)">Rename</button>' +
    '<button class="small" onclick="removeP(\'' + p.id + '\', this)">Remove</button></div>').join('');
  el.querySelectorAll('.row').forEach((row, i) => row.dataset.name = list[i].name);
}
async function load() {
  try {
    render(await api('/api/playlists'));
    ${'$'}('login').classList.add('hidden'); ${'$'}('app').classList.remove('hidden');
    ${'$'}('download').href = '/api/bundle?download=1&code=' + encodeURIComponent(code);
  } catch (e) {
    ${'$'}('app').classList.add('hidden'); ${'$'}('login').classList.remove('hidden');
    if (code) show('loginMsg', e.message, true);
  }
}
function enter() { code = ${'$'}('code').value.trim(); load(); }
${'$'}('code').addEventListener('keydown', e => { if (e.key === 'Enter') enter(); });
function tab(t) {
  mode = t;
  document.querySelectorAll('.tabs button').forEach(b => b.setAttribute('aria-pressed', b.dataset.t === t));
  document.querySelectorAll('[data-f]').forEach(f => f.classList.toggle('hidden', f.dataset.f !== t));
  show('addMsg', '');
}
async function add() {
  const b = { type: mode, name: ${'$'}('name').value, epgUrl: ${'$'}('epg').value };
  if (mode === 'm3u') b.url = ${'$'}('url').value;
  if (mode === 'xtream') { b.url = ${'$'}('server').value; b.username = ${'$'}('user').value; b.password = ${'$'}('pass').value; }
  if (mode === 'file') {
    const f = ${'$'}('file').files[0];
    if (!f) return show('addMsg', 'Choose an M3U file', true);
    b.m3u = await f.text();
  }
  ${'$'}('addBtn').disabled = true; show('addMsg', mode === 'xtream' ? 'Signing in…' : 'Adding…');
  try {
    render(await api('/api/playlists', b));
    show('addMsg', 'Added. It\'s on the TV now.');
    ['name','url','server','user','pass','epg'].forEach(id => ${'$'}(id).value = ''); ${'$'}('file').value = '';
  } catch (e) { show('addMsg', e.message, true); }
  ${'$'}('addBtn').disabled = false;
}
async function rename(id, btn) {
  const cur = btn.closest('.row').dataset.name;
  const name = prompt('New name', cur);
  if (!name || name === cur) return;
  try { render(await api('/api/playlists/rename', { id, name })); } catch (e) { alert(e.message); }
}
async function removeP(id, btn) {
  const cur = btn.closest('.row').dataset.name;
  if (!confirm('Remove “' + cur + '” from the TV? Its favourites and lists go too.')) return;
  try { render(await api('/api/playlists/delete', { id })); } catch (e) { alert(e.message); }
}
async function restore(input) {
  const f = input.files[0]; if (!f) return;
  show('backupMsg', 'Loading…');
  try {
    const r = await api('/api/bundle', await f.text());
    show('backupMsg', r.message + '.');
    render(await api('/api/playlists'));
  } catch (e) { show('backupMsg', e.message, true); }
  input.value = '';
}
// ---- show & hide groups ----
let gid = null, gData = null, gKind = null;
function fillGroupPlaylists(list) {
  const sel = ${'$'}('gpl');
  if (!list.length) { gid = null; gData = null; renderGroups(); return; }
  if (!list.some(p => p.id === gid)) gid = (list.find(p => p.last) || list[0]).id;
  sel.innerHTML = list.map(p => '<option value="' + esc(p.id) + '"' + (p.id === gid ? ' selected' : '') + '>' + esc(p.name) + '</option>').join('');
  sel.classList.toggle('hidden', list.length < 2);
  if (!gData || gData.id !== gid) loadGroups(gid);
}
async function loadGroups(id) {
  gid = id; show('gmsg', '');
  ${'$'}('glist').innerHTML = '<div class="gempty">Loading groups…</div>';
  try {
    gData = await api('/api/groups?id=' + encodeURIComponent(id));
    if (!gData.kinds.some(k => k.kind === gKind)) gKind = gData.kinds.length ? gData.kinds[0].kind : null;
  } catch (e) { gData = null; show('gmsg', e.message, true); }
  renderGroups();
}
function kindData() { return gData ? gData.kinds.find(k => k.kind === gKind) : null; }
function shown() {
  const k = kindData(); if (!k) return [];
  const q = ${'$'}('gq').value.trim().toLowerCase();
  const out = [];
  k.groups.forEach((g, i) => { if (!q || g.name.toLowerCase().includes(q)) out.push(i); });
  return out;
}
function gTab(kind) { gKind = kind; ${'$'}('gq').value = ''; renderGroups(); }
function gJump(kind) { gKind = kind; renderGroups(); }
function renderGroups() {
  const body = ${'$'}('gbody'), list = ${'$'}('glist');
  if (!gData) { body.classList.add('hidden'); list.innerHTML = gid ? '' : '<div class="gempty">Add a playlist first.</div>'; return; }
  if (!gData.ready || !gData.kinds.length) {
    body.classList.add('hidden');
    list.innerHTML = '<div class="gempty">Open “' + esc(gData.name) + '” on the TV once, and its groups appear here.</div>';
    return;
  }
  body.classList.remove('hidden');
  const tabs = ${'$'}('gtabs');
  tabs.style.gridTemplateColumns = 'repeat(' + gData.kinds.length + ', 1fr)';
  tabs.innerHTML = gData.kinds.map(k => '<button aria-pressed="' + (k.kind === gKind) + '" onclick="gTab(\'' + k.kind + '\')">' +
    esc(k.label) + '<span class="n">' + k.groups.length + '</span></button>').join('');
  const k = kindData(), idx = shown(), q = ${'$'}('gq').value.trim();
  const hiddenHere = idx.filter(i => k.groups[i].hidden).length;
  ${'$'}('gsum').textContent = hiddenHere + ' of ' + idx.length + ' hidden';
  // While searching, the buttons say how many groups they change.
  ${'$'}('gshow').textContent = q ? 'Show ' + idx.length : 'Show all';
  ${'$'}('ghide').textContent = q ? 'Hide ' + idx.length : 'Hide all';
  ${'$'}('gshow').disabled = hiddenHere === 0;
  ${'$'}('ghide').disabled = hiddenHere === idx.length;
  ${'$'}('gsum').parentNode.classList.toggle('hidden', !idx.length);
  if (!idx.length) {
    // Nothing on this tab: point to the tabs that do have it.
    const ql = q.toLowerCase();
    const elsewhere = gData.kinds.filter(o => o.kind !== gKind)
      .map(o => ({ o, n: o.groups.filter(g => g.name.toLowerCase().includes(ql)).length })).filter(x => x.n);
    list.innerHTML = '<div class="gempty">No ' + esc(k.label.toLowerCase()) + ' group matches “' + esc(q) + '”.' +
      (elsewhere.length ? '<div class="actions">' + elsewhere.map(x => '<button class="small" onclick="gJump(\'' + x.o.kind + '\')">' +
        x.n + ' in ' + esc(x.o.label) + '</button>').join('') + '</div>' : '') + '</div>';
    return;
  }
  list.innerHTML = idx.map(i => { const g = k.groups[i];
    return '<label class="grow' + (g.hidden ? ' off' : '') + '"><span class="gname">' + esc(g.name) + '</span>' +
      '<span class="gcount">' + g.count + '</span>' +
      '<input type="checkbox" class="sw" data-i="' + i + '"' + (g.hidden ? '' : ' checked') + ' aria-label="Show ' + esc(g.name) + '"></label>'; }).join('');
}
${'$'}('glist').addEventListener('change', e => {
  const i = e.target.dataset.i; if (i === undefined) return;
  setHidden([kindData().groups[+i].name], !e.target.checked);
});
function bulk(hide) {
  const k = kindData(); if (!k) return;
  const names = shown().map(i => k.groups[i]).filter(g => g.hidden !== hide).map(g => g.name);
  if (names.length) setHidden(names, hide);
}
function applyHidden(set) { gData.kinds.forEach(k => k.groups.forEach(g => { g.hidden = set.has(g.name); })); }
async function setHidden(names, hide) {
  // Hidden is by group name, as in the app: show it everywhere at once.
  const before = new Set(); gData.kinds.forEach(k => k.groups.forEach(g => { if (g.hidden) before.add(g.name); }));
  const next = new Set(before); names.forEach(n => hide ? next.add(n) : next.delete(n));
  applyHidden(next); renderGroups(); show('gmsg', '');
  try {
    const r = await api('/api/groups', { id: gid, groups: names, hidden: hide });
    applyHidden(new Set(r.hidden)); renderGroups();
  } catch (e) { applyHidden(before); renderGroups(); show('gmsg', e.message, true); }
}
load();
</script>
</body>
</html>
"""
}
