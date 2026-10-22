package app.falak.player.sync

/** The phone remote's page: pairing, the remote control, and the lists to start things on the TV. */
internal object RemotePage {
    fun html(device: String): String = PAGE.replace("{{DEVICE}}", escape(device))

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // Plain JavaScript without "$" (this is a Kotlin string) and every name set with textContent.
    private val PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#121110">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>Remote · {{DEVICE}}</title>
<style>
  :root { --ink:#121110; --ink2:#1b1918; --ink3:#282523; --line:rgba(255,255,255,.12); --paper:#f7f3ec; --muted:#d2cbc1; --faint:#a0988e; --signal:#ff5a3c; }
  * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  html, body { margin:0; background:var(--ink); color:var(--paper); font:16px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
  body { padding-bottom:env(safe-area-inset-bottom); -webkit-user-select:none; user-select:none; }
  header { position:sticky; top:0; z-index:2; background:var(--ink); padding:14px 16px 10px; border-bottom:1px solid var(--line); }
  .brand { display:flex; align-items:center; gap:10px; }
  .mark { width:32px; height:32px; border-radius:9px; background:var(--ink3); border:1px solid var(--line); display:grid; place-items:center; }
  h1 { font:600 20px/1.1 Georgia,"Times New Roman",serif; margin:0; flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .now { color:var(--muted); font-size:13px; margin-top:8px; min-height:18px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .now b { color:var(--paper); font-weight:600; }
  nav { display:flex; gap:4px; margin-top:12px; background:var(--ink2); border:1px solid var(--line); border-radius:999px; padding:4px; }
  nav button { flex:1; border:0; background:none; color:var(--muted); font:600 14px system-ui,sans-serif; padding:9px 0; border-radius:999px; }
  nav button.on { background:var(--paper); color:var(--ink); }
  main { padding:6px 16px 28px; max-width:560px; margin:0 auto; }
  .hidden { display:none !important; }
  /* remote */
  .pad { position:relative; width:min(76vw,300px); aspect-ratio:1/1; margin:22px auto 6px; border-radius:50%; background:var(--ink2); border:1px solid var(--line); }
  .pad button { position:absolute; border:0; background:none; color:var(--paper); width:34%; height:34%; display:grid; place-items:center; border-radius:50%; }
  .pad button:active { background:rgba(255,255,255,.08); }
  .pad svg { width:30px; height:30px; }
  .pad .up { top:0; left:33%; } .pad .down { bottom:0; left:33%; } .pad .left { left:0; top:33%; } .pad .right { right:0; top:33%; }
  .pad .ok { left:27%; top:27%; width:46%; height:46%; background:var(--ink3); border:1px solid var(--line); font:700 18px system-ui,sans-serif; }
  .pad .ok:active { background:var(--signal); }
  .keys { display:grid; grid-template-columns:repeat(3,1fr); gap:10px; margin-top:18px; }
  .key { height:58px; border-radius:16px; border:1px solid var(--line); background:var(--ink2); color:var(--paper); font:600 13px system-ui,sans-serif; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:3px; }
  .key:active { background:var(--ink3); }
  .key svg { width:22px; height:22px; }
  .type { display:flex; gap:8px; margin-top:22px; }
  input { flex:1; min-width:0; background:var(--ink2); border:1px solid var(--line); color:var(--paper); border-radius:12px; padding:12px 14px; font:16px system-ui,sans-serif; -webkit-user-select:text; user-select:text; }
  input::placeholder { color:var(--faint); }
  .btn { border:0; border-radius:12px; background:var(--paper); color:var(--ink); font:600 15px system-ui,sans-serif; padding:0 16px; }
  .hint { color:var(--faint); font-size:12px; margin-top:8px; }
  /* lists */
  .search { margin-top:12px; display:flex; }
  .chips { display:flex; gap:8px; overflow-x:auto; padding:12px 0 8px; scrollbar-width:none; }
  .chips::-webkit-scrollbar { display:none; }
  .chip { flex:none; border:1px solid var(--line); background:var(--ink2); color:var(--paper); border-radius:999px; padding:8px 13px; font:600 13px system-ui,sans-serif; max-width:70vw; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .chip.on { background:var(--paper); color:var(--ink); border-color:var(--paper); }
  .item { display:flex; align-items:center; gap:12px; padding:10px 2px; border-bottom:1px solid var(--line); }
  .item:active { background:var(--ink2); }
  .pic { width:56px; height:36px; border-radius:6px; background:var(--ink3); flex:none; object-fit:contain; }
  .tall .pic { width:40px; height:58px; object-fit:cover; }
  .txt { flex:1; min-width:0; }
  .t { font-weight:600; font-size:15px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .s { color:var(--faint); font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; margin-top:2px; }
  .play { flex:none; width:34px; height:34px; border-radius:50%; background:var(--ink3); display:grid; place-items:center; }
  .play svg { width:16px; height:16px; }
  .empty { color:var(--faint); text-align:center; padding:32px 0; }
  /* pairing */
  .pairing { text-align:center; padding:48px 8px; }
  .pairing h2 { font:600 24px/1.2 Georgia,serif; margin:0 0 10px; }
  .pairing p { color:var(--muted); margin:0 0 18px; }
  .pairing .type { max-width:320px; margin:0 auto; }
  .toast { position:fixed; left:50%; bottom:calc(22px + env(safe-area-inset-bottom)); transform:translateX(-50%); background:var(--paper); color:var(--ink); padding:10px 18px; border-radius:999px; font:600 14px system-ui,sans-serif; opacity:0; transition:opacity .2s; pointer-events:none; white-space:nowrap; }
  .toast.on { opacity:1; }
</style>
</head>
<body>
<header>
  <div class="brand">
    <div class="mark"><svg width="16" height="18" viewBox="0 0 16 18"><path d="M2 1h6.5a5.5 5.5 0 0 1 0 11H6v5H2z" fill="#f7f3ec"/><path d="M6 4.5v4l3.5-2z" fill="#ff5a3c"/></svg></div>
    <h1 id="title">{{DEVICE}}</h1>
  </div>
  <div class="now" id="now"></div>
  <nav id="tabs" class="hidden">
    <button data-tab="remote" class="on">Remote</button>
    <button data-tab="LIVE">Live</button>
    <button data-tab="MOVIE">Films</button>
    <button data-tab="SERIES">Series</button>
  </nav>
</header>
<main>
  <section id="pairing" class="pairing hidden">
    <h2>Pair this phone</h2>
    <p>On the TV, open Settings → Phone remote and scan the code, or type the 6 digits shown there.</p>
    <div class="type"><input id="code" inputmode="numeric" maxlength="6" placeholder="6-digit code" autocomplete="off"><button class="btn" id="pairBtn">Pair</button></div>
    <div class="hint" id="pairMsg"></div>
  </section>

  <section id="remote" class="hidden">
    <div class="pad">
      <button class="up" data-key="up" aria-label="Up"><svg viewBox="0 0 24 24"><path d="M7 14l5-5 5 5" stroke="currentColor" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
      <button class="left" data-key="left" aria-label="Left"><svg viewBox="0 0 24 24"><path d="M14 7l-5 5 5 5" stroke="currentColor" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
      <button class="ok" data-key="ok">OK</button>
      <button class="right" data-key="right" aria-label="Right"><svg viewBox="0 0 24 24"><path d="M10 7l5 5-5 5" stroke="currentColor" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
      <button class="down" data-key="down" aria-label="Down"><svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5" stroke="currentColor" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
    </div>
    <div class="keys">
      <button class="key" data-key="back"><svg viewBox="0 0 24 24"><path d="M10 6l-6 6 6 6M4 12h11a5 5 0 0 1 0 10h-2" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>Back</button>
      <button class="key" data-home="1"><svg viewBox="0 0 24 24"><path d="M4 11l8-7 8 7v9h-5v-6H9v6H4z" stroke="currentColor" stroke-width="2" fill="none" stroke-linejoin="round"/></svg>Home</button>
      <button class="key" data-key="playpause"><svg viewBox="0 0 24 24"><path d="M5 5l8 7-8 7z" fill="currentColor"/><path d="M15 5h2v14h-2zM19 5h2v14h-2z" fill="currentColor"/></svg>Play / pause</button>
      <button class="key" data-key="chup"><svg viewBox="0 0 24 24"><path d="M7 14l5-5 5 5" stroke="currentColor" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>Channel up</button>
      <button class="key" data-key="last"><svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>Last channel</button>
      <button class="key" data-key="chdown"><svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5" stroke="currentColor" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>Channel down</button>
    </div>
    <div class="type"><input id="text" placeholder="Search on the TV" enterkeyhint="search" autocomplete="off"><button class="btn" id="sendText">Search</button></div>
    <div class="hint">Shows the results on the TV, as if typed there.</div>
  </section>

  <section id="lists" class="hidden">
    <div class="search"><input id="q" placeholder="Search" enterkeyhint="search" autocomplete="off"></div>
    <div class="chips" id="chips"></div>
    <div id="items"></div>
  </section>
</main>
<div class="toast" id="toast"></div>
<script>
var token = '';
try { token = localStorage.getItem('tpr') || ''; } catch (e) {}
var tab = 'remote', group = '', timer = null, toastTimer = null;
function el(id) { return document.getElementById(id); }
function toast(msg) {
  var t = el('toast'); t.textContent = msg; t.classList.add('on');
  clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.classList.remove('on'); }, 1600);
}
function api(path, body) {
  return fetch(path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', 'X-Remote': token },
    body: body ? JSON.stringify(body) : undefined
  }).then(function (r) {
    return r.json().catch(function () { return {}; }).then(function (j) {
      if (r.status === 403 && path !== '/api/pair') { showPairing(); }
      if (!r.ok) { throw new Error(j.error || 'The TV said no'); }
      return j;
    });
  });
}
function showPairing() {
  el('pairing').classList.remove('hidden');
  el('remote').classList.add('hidden'); el('lists').classList.add('hidden'); el('tabs').classList.add('hidden');
}
function pair(code) {
  el('pairMsg').textContent = 'Pairing…';
  api('/api/pair', { code: code }).then(function (j) {
    token = j.token; try { localStorage.setItem('tpr', token); } catch (e) {}
    history.replaceState(null, '', '/');
    start();
  }).catch(function (e) { el('pairMsg').textContent = e.message; });
}
function start() {
  el('pairing').classList.add('hidden');
  el('tabs').classList.remove('hidden');
  show(tab);
  refresh(); clearInterval(timer); timer = setInterval(refresh, 3000);
}
function refresh() {
  api('/api/state').then(function (s) {
    el('title').textContent = s.device || 'The Player';
    var now = el('now'); now.textContent = '';
    if (s.playing) {
      var b = document.createElement('b'); b.textContent = s.playing; now.appendChild(b);
      if (s.sub) { now.appendChild(document.createTextNode('  ·  ' + s.sub)); }
    } else if (s.playlist) { now.textContent = s.playlist; }
  }).catch(function () {});
}
function show(t) {
  tab = t;
  var buttons = el('tabs').querySelectorAll('button');
  for (var i = 0; i < buttons.length; i++) { buttons[i].classList.toggle('on', buttons[i].getAttribute('data-tab') === t); }
  el('remote').classList.toggle('hidden', t !== 'remote');
  el('lists').classList.toggle('hidden', t === 'remote');
  if (t !== 'remote') { group = ''; el('q').value = ''; loadGroups(); loadItems(); }
}
function loadGroups() {
  var chips = el('chips'); chips.textContent = '';
  api('/api/groups?kind=' + tab).then(function (list) {
    var all = chip('All', ''); all.classList.add('on'); chips.appendChild(all);
    list.forEach(function (g) { chips.appendChild(chip(g.name, g.name)); });
  }).catch(function (e) { chips.textContent = ''; });
}
function chip(label, value) {
  var c = document.createElement('button'); c.className = 'chip'; c.textContent = label;
  c.onclick = function () {
    group = value;
    var cs = el('chips').children; for (var i = 0; i < cs.length; i++) { cs[i].classList.remove('on'); }
    c.classList.add('on'); loadItems();
  };
  return c;
}
var itemsSeq = 0;
function loadItems() {
  var seq = ++itemsSeq, box = el('items');
  var url = '/api/items?kind=' + tab + '&group=' + encodeURIComponent(group) + '&q=' + encodeURIComponent(el('q').value);
  api(url).then(function (list) {
    if (seq !== itemsSeq) { return; }
    box.textContent = '';
    if (!list.length) { var e = document.createElement('div'); e.className = 'empty'; e.textContent = 'Nothing here'; box.appendChild(e); return; }
    list.forEach(function (it) { box.appendChild(row(it)); });
  }).catch(function (e) {
    if (seq !== itemsSeq) { return; }
    box.textContent = ''; var d = document.createElement('div'); d.className = 'empty'; d.textContent = e.message; box.appendChild(d);
  });
}
function row(it) {
  var r = document.createElement('div'); r.className = 'item' + (tab === 'LIVE' ? '' : ' tall');
  var img = document.createElement('img'); img.className = 'pic'; img.alt = ''; img.loading = 'lazy';
  if (it.logo) { img.src = it.logo; img.onerror = function () { img.removeAttribute('src'); }; }
  var txt = document.createElement('div'); txt.className = 'txt';
  var t = document.createElement('div'); t.className = 't'; t.textContent = it.name;
  var s = document.createElement('div'); s.className = 's'; s.textContent = it.sub || '';
  txt.appendChild(t); txt.appendChild(s);
  var p = document.createElement('div'); p.className = 'play';
  p.innerHTML = '<svg viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z" fill="currentColor"/></svg>';
  r.appendChild(img); r.appendChild(txt); r.appendChild(p);
  r.onclick = function () {
    api('/api/play', { id: it.id }).then(function (j) { toast(j.message || 'Playing on the TV'); refresh(); })
      .catch(function (e) { toast(e.message); });
  };
  return r;
}
function press(k) {
  if (navigator.vibrate) { navigator.vibrate(12); }
  api('/api/key', { key: k }).catch(function (e) { toast(e.message); });
}
document.addEventListener('click', function (ev) {
  var b = ev.target.closest('button'); if (!b) { return; }
  if (b.getAttribute('data-key')) { press(b.getAttribute('data-key')); }
  else if (b.getAttribute('data-home')) { api('/api/home', {}).catch(function (e) { toast(e.message); }); }
  else if (b.getAttribute('data-tab')) { show(b.getAttribute('data-tab')); }
});
function sendText() {
  var v = el('text').value.trim(); if (!v) { return; }
  api('/api/text', { text: v }).then(function () { toast('Searching on the TV'); el('text').blur(); })
    .catch(function (e) { toast(e.message); });
}
el('sendText').onclick = sendText;
el('text').addEventListener('keydown', function (e) { if (e.key === 'Enter') { sendText(); } });
var qTimer = null;
el('q').addEventListener('input', function () { clearTimeout(qTimer); qTimer = setTimeout(loadItems, 250); });
el('pairBtn').onclick = function () { pair(el('code').value.trim()); };
el('code').addEventListener('keydown', function (e) { if (e.key === 'Enter') { pair(el('code').value.trim()); } });

var code = new URLSearchParams(location.search).get('pair');
if (code) { pair(code); }
else if (token) { start(); }
else { showPairing(); }
</script>
</body>
</html>
"""
}
