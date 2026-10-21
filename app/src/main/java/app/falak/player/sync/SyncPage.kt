package app.falak.player.sync

/** The page a phone or computer browser sees: manage this device's playlists, backup and restore. */
internal object SyncPage {
    fun html(device: String): String = PAGE.replace("{{DEVICE}}", escape(device))

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
  .tabs button { padding:10px 6px; font-size:14px; }
  .tabs button[aria-pressed="true"] { background:var(--paper); color:var(--ink); }
  .msg { margin-top:10px; color:var(--muted); }
  .msg:empty { display:none; }
  .msg.err { color:var(--signal); }
  .actions { display:flex; gap:10px; flex-wrap:wrap; margin-top:6px; }
  .actions .primary { flex:1; }
  .actions.two { display:grid; grid-template-columns:1fr 1fr; }
  .actions.two > * { text-align:center; }
  .hidden { display:none; }
  .empty { color:var(--faint); padding:8px 0; }
  .code { font:600 28px/1 ui-monospace,Menlo,monospace; letter-spacing:.3em; text-align:center; }
  footer { color:var(--faint); font-size:13px; margin-top:34px; }
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

    <h2>Add a playlist</h2>
    <div class="card">
      <div class="tabs">
        <button data-t="m3u" aria-pressed="true" onclick="tab('m3u')">M3U link</button>
        <button data-t="xtream" aria-pressed="false" onclick="tab('xtream')">Xtream login</button>
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
      <div class="meta">Playlists, favourites, your lists and hidden groups, in one file. Load it on any device running The Player.</div>
      <div class="actions two" style="margin-top:12px">
        <a class="btn" id="download" href="#">Download backup</a>
        <button onclick="document.getElementById('restore').click()">Load a backup</button>
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
load();
</script>
</body>
</html>
"""
}
