import * as core from './core.js';

// ---------- storage ----------
const LS = {
  get(k, d = null) { try { const v = localStorage.getItem('nu.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('nu.' + k, JSON.stringify(v)); } catch {} },
  del(k) { try { localStorage.removeItem('nu.' + k); } catch {} },
};

let clientId = LS.get('clientId');
if (!clientId) { clientId = crypto.randomUUID(); LS.set('clientId', clientId); }

const state = {
  token: LS.get('token'),
  servers: LS.get('servers', []),        // [{id,name,owned}]
  serverId: LS.get('serverId'),
  allLibs: LS.get('allLibs', []),        // [{key,title}]
  libs: LS.get('libs', null),            // chosen library keys (null = all)
  plexShows: LS.get('plexShows', null),  // {updated, serverId, shows:[...]}
  mazeIds: LS.get('mazeIds', {}),        // "server:ratingKey" -> TVmaze id (0 = no match)
  info: LS.get('info', {}),              // TVmaze id -> {info, fetched}
  showEnded: LS.get('showEnded', false),
  lastRefresh: LS.get('lastRefresh', 0),
  busy: false,
  open: new Set(),
  episodes: {},                          // TVmaze id -> episodes (in memory)
};

const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const mazeKey = (sid, s) => `${sid}:${s.ratingKey}`;
const setStatus = t => { $('#status').textContent = t || ''; };
const setBanner = t => { $('#banner').textContent = t || ''; };

// ---------- dates ----------
const pad = n => String(n).padStart(2, '0');
const dayKey = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const isTimed = ep => !!(ep.airtime && ep.airstamp);
const epDate = ep => isTimed(ep) ? new Date(ep.airstamp) : new Date(ep.airdate + 'T12:00:00');
const epDay = ep => isTimed(ep) ? dayKey(new Date(ep.airstamp)) : ep.airdate;
function daysFromToday(key) {
  const [y, m, d] = key.split('-').map(Number);
  const a = new Date(y, m - 1, d); const t = new Date(); t.setHours(0, 0, 0, 0);
  return Math.round((a - t) / 86400000);
}
const fmtTime = d => d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
const fmtShort = d => d.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
const fmtLong = d => d.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });

function whenText(ep) {
  const diff = daysFromToday(epDay(ep));
  const d = epDate(ep);
  const time = isTimed(ep) ? ' ' + fmtTime(d) : '';
  if (diff <= 0) return isTimed(ep) && d.getHours() >= 17 ? `Tonight${time}` : `Today${time}`;
  if (diff === 1) return `Tomorrow${time}`;
  if (diff < 7) return d.toLocaleDateString([], { weekday: 'long' }) + time;
  return `${fmtShort(d)} · in ${diff} days`;
}
function agoText(ts) {
  if (!ts) return '';
  const m = Math.round((Date.now() - ts) / 60000);
  if (m < 1) return 'Updated just now';
  if (m < 60) return `Updated ${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `Updated ${h} hr ago`;
  return `Updated ${Math.round(h / 24)} days ago`;
}

// ---------- sign in ----------
async function signIn() {
  try {
    const pin = await core.createPin(clientId);
    LS.set('pin', { id: pin.id, at: Date.now() });
    location.href = core.authUrl(clientId, pin.code, location.origin + location.pathname);
  } catch (e) {
    setBanner(`Couldn't start Plex sign-in: ${e.message}`);
  }
}

async function checkPendingPin() {
  const pin = LS.get('pin');
  if (!pin) return false;
  if (Date.now() - pin.at > 30 * 60000) { LS.del('pin'); return false; }
  try {
    const tok = await core.checkPin(clientId, pin.id);
    if (tok) { state.token = tok; LS.set('token', tok); LS.del('pin'); return true; }
  } catch {}
  return false;
}

function signOut() {
  for (const k of ['token', 'servers', 'serverId', 'allLibs', 'libs', 'plexShows', 'mazeIds', 'info', 'lastRefresh', 'pin']) LS.del(k);
  Object.assign(state, { token: null, servers: [], serverId: null, allLibs: [], libs: null, plexShows: null, mazeIds: {}, info: {}, lastRefresh: 0 });
  $('#settings').close();
  render();
}

// ---------- refresh ----------
async function loadPlexShows() {
  const servers = await core.getServers(clientId, state.token);
  if (!servers.length) throw new Error('No Plex servers found on this account');
  state.servers = servers.map(s => ({ id: s.id, name: s.name, owned: s.owned }));
  LS.set('servers', state.servers);

  const server = servers.find(s => s.id === state.serverId) || servers.find(s => s.owned) || servers[0];
  if (server.id !== state.serverId) { state.serverId = server.id; LS.set('serverId', server.id); }

  setStatus(`Connecting to ${server.name}…`);
  const base = await core.connectToServer(server, clientId);
  const libs = await core.getShowLibraries(base, clientId, server.token);
  state.allLibs = libs; LS.set('allLibs', libs);

  const chosen = libs.filter(l => !state.libs || state.libs.includes(l.key));
  const shows = [];
  for (const l of chosen) {
    setStatus(`Reading ${l.title}…`);
    shows.push(...await core.getShows(base, clientId, server.token, l.key));
  }
  state.plexShows = { updated: Date.now(), serverId: server.id, shows };
  LS.set('plexShows', state.plexShows);
}

async function updateTvInfo(force) {
  const { shows, serverId: sid } = state.plexShows;

  // 1) Match new shows to TVmaze (cached forever).
  const toMatch = shows.filter(s => !(mazeKey(sid, s) in state.mazeIds));
  let i = 0;
  for (const s of toMatch) {
    setStatus(`Matching shows… ${++i} of ${toMatch.length}`);
    try { state.mazeIds[mazeKey(sid, s)] = (await core.findMazeId(s)) || 0; } catch {}
    if (i % 10 === 0) LS.set('mazeIds', state.mazeIds);
  }
  LS.set('mazeIds', state.mazeIds);

  // 2) Decide which shows need fresh air dates.
  const ids = [...new Set(shows.map(s => state.mazeIds[mazeKey(sid, s)]).filter(Boolean))];
  const fetchedTimes = ids.map(id => state.info[id]?.fetched || 0);
  const oldest = fetchedTimes.length ? Math.min(...fetchedTimes) : 0;
  const age = Date.now() - oldest;
  let updates = null;
  if (!force && oldest && age < 6 * 86400000) {
    try { updates = await core.getUpdates(age < 20 * 3600000 ? 'day' : 'week'); } catch {}
  }
  const now = Date.now();
  const stale = ids.filter(id => {
    const c = state.info[id];
    if (!c || force || !updates) return true;
    if (updates[id] && updates[id] > (c.info.updated || 0)) return true;
    if (c.info.next && epDate(c.info.next).getTime() < now) return true; // next episode has aired
    return false;
  });

  // 3) Fetch them.
  i = 0;
  for (const id of stale) {
    setStatus(`Checking air dates… ${++i} of ${stale.length}`);
    try {
      const info = await core.getShowInfo(id);
      if (info) { state.info[id] = { info, fetched: Date.now() }; delete state.episodes[id]; }
    } catch {}
    if (i % 10 === 0) { LS.set('info', state.info); render(); }
  }
  // Drop shows no longer in the library.
  for (const id of Object.keys(state.info)) if (!ids.includes(Number(id))) delete state.info[id];
  LS.set('info', state.info);
}

async function refresh({ force = false } = {}) {
  if (state.busy || !state.token) return;
  state.busy = true;
  $('#refreshBtn').classList.add('spin');
  setBanner('');
  let plexError = null;
  try {
    try { await loadPlexShows(); } catch (e) {
      if (e.status === 401) { signOut(); return; }
      plexError = e;
    }
    if (!state.plexShows) {
      setBanner(`${plexError?.message || 'Could not load your shows'}. If you're away from home, make sure Remote Access is on in Plex.`);
      return;
    }
    await updateTvInfo(force);
    state.lastRefresh = Date.now(); LS.set('lastRefresh', state.lastRefresh);
    if (plexError) setBanner(`Couldn't reach Plex (${plexError.message}), so this uses your saved show list with fresh air dates.`);
  } finally {
    state.busy = false;
    $('#refreshBtn').classList.remove('spin');
    setStatus('');
    render();
  }
}

// ---------- render ----------
function buildItems() {
  if (!state.plexShows) return { items: [], unmatched: [] };
  const { shows, serverId: sid } = state.plexShows;
  const seen = new Map(); const unmatched = [];
  for (const s of shows) {
    const id = state.mazeIds[mazeKey(sid, s)];
    if (id === 0) { unmatched.push(s.title); continue; }
    const info = id && state.info[id]?.info;
    if (!info) continue;
    const prior = seen.get(id);
    if (prior) { prior.episodes += s.episodes; prior.watched += s.watched; continue; }
    seen.set(id, { ...s, info });
  }
  return { items: [...seen.values()], unmatched };
}

function render() {
  $('#updated').textContent = state.busy ? 'Updating…' : agoText(state.lastRefresh);
  const list = $('#list');

  if (!state.token) {
    list.innerHTML = `<div class="signin">
      <h2>Your shows, next episodes</h2>
      <p>Sign in with Plex so Next Up can read the TV shows on your server. Air dates come from TVmaze.</p>
      <button class="btn primary" id="signInBtn">Sign in with Plex</button></div>`;
    $('#signInBtn').onclick = signIn;
    return;
  }

  const { items, unmatched } = buildItems();
  if (!items.length) {
    list.innerHTML = `<div class="empty">${state.busy ? 'Loading your shows…' : 'No shows yet. Tap refresh.'}</div>`;
    return;
  }

  const groups = { today: [], week: [], later: [], waiting: [], ended: [] };
  for (const it of items) {
    const { next, status } = it.info;
    if (next) {
      const diff = daysFromToday(epDay(next));
      (diff <= 0 ? groups.today : diff <= 7 ? groups.week : groups.later).push(it);
    } else if (status === 'Ended') groups.ended.push(it);
    else groups.waiting.push(it);
  }
  const byNext = (a, b) => epDate(a.info.next) - epDate(b.info.next);
  const byName = (a, b) => a.info.name.localeCompare(b.info.name);
  groups.today.sort(byNext); groups.week.sort(byNext); groups.later.sort(byNext);
  groups.waiting.sort(byName); groups.ended.sort(byName);

  let html = '';
  const section = (title, arr, cls = '') => {
    if (!arr.length) return;
    html += `<h2 class="${cls}">${title} <span class="count">${arr.length}</span></h2>`;
    html += arr.map(card).join('');
  };
  section('Today', groups.today, 'today');
  section('Next 7 days', groups.week);
  section('Coming later', groups.later);
  section('Returning · no date yet', groups.waiting);
  if (state.showEnded) section('Ended', groups.ended);
  else if (groups.ended.length) html += `<button class="more" id="showEndedBtn">Show ${groups.ended.length} ended ${groups.ended.length === 1 ? 'show' : 'shows'}</button>`;
  if (unmatched.length) html += `<p class="unmatched">Couldn't find air dates for: ${esc(unmatched.join(', '))}</p>`;
  list.innerHTML = html;

  list.querySelectorAll('.card').forEach(el => el.onclick = () => toggle(Number(el.dataset.id)));
  const se = $('#showEndedBtn');
  if (se) se.onclick = () => { state.showEnded = true; LS.set('showEnded', true); render(); };
  for (const id of state.open) fillDetails(id);
}

function card(it) {
  const { info } = it;
  const id = info.mazeId;
  const unwatched = Math.max(0, it.episodes - it.watched);
  let epLine, whenLine;
  if (info.next) {
    epLine = `${core.epCode(info.next)}${info.next.name ? ' · ' + esc(info.next.name) : ''}`;
    whenLine = `<b>${esc(whenText(info.next))}</b>${info.network ? ' · ' + esc(info.network) : ''}`;
  } else {
    epLine = info.prev ? `Last: ${core.epCode(info.prev)} · ${esc(fmtLong(epDate(info.prev)))}` : esc(info.status);
    whenLine = esc(info.status === 'Ended' ? 'Series ended' : info.status === 'Running' ? 'Waiting on a new date' : info.status) +
      (info.network ? ' · ' + esc(info.network) : '');
  }
  const open = state.open.has(id);
  return `<button class="card" data-id="${id}" aria-expanded="${open}">
      ${info.image ? `<img class="poster" src="${esc(info.image)}" alt="" loading="lazy" onerror="this.removeAttribute('src')">` : '<div class="poster"></div>'}
      <div class="meta"><h3>${esc(info.name)}</h3><p>${epLine}</p><p class="when">${whenLine}</p></div>
      ${unwatched ? `<span class="badge">${unwatched} unwatched</span>` : ''}
    </button>${open ? `<div class="details" id="d-${id}">Loading…</div>` : ''}`;
}

function toggle(id) {
  if (state.open.has(id)) state.open.delete(id); else state.open.add(id);
  render();
}

async function fillDetails(id) {
  const el = document.getElementById('d-' + id);
  if (!el) return;
  const { items } = buildItems();
  const it = items.find(x => x.info.mazeId === id);
  if (!it) return;
  const plexUrl = `https://app.plex.tv/desktop/#!/server/${state.plexShows.serverId}/details?key=${encodeURIComponent('/library/metadata/' + it.ratingKey)}`;
  const actions = `<div class="actions">
      <a class="btn primary" href="${plexUrl}" target="_blank" rel="noopener">Open in Plex</a>
      <a class="btn" href="${esc(it.info.url)}" target="_blank" rel="noopener">TVmaze</a></div>`;

  if (!state.episodes[id]) {
    el.innerHTML = 'Loading episodes…' + actions;
    try { state.episodes[id] = await core.getEpisodes(id); } catch { state.episodes[id] = []; }
    if (!document.getElementById('d-' + id)) return;
  }
  const todayKey = dayKey(new Date());
  const upcoming = state.episodes[id].filter(e => epDay(e) >= todayKey).slice(0, 12);
  let html = '';
  if (it.info.next?.summary) html += `<p class="summary">${esc(it.info.next.summary)}</p>`;
  if (upcoming.length) {
    html += upcoming.map(e => `<div class="ep"><span>${core.epCode(e)}${e.name ? ' · ' + esc(e.name) : ''}</span>
      <span>${esc(fmtShort(epDate(e)))}${isTimed(e) ? ' ' + esc(fmtTime(epDate(e))) : ''}</span></div>`).join('');
  } else {
    html += `<p class="summary">No upcoming episodes announced yet.${it.info.prev ? ` Last aired ${esc(fmtLong(epDate(it.info.prev)))}.` : ''}</p>`;
  }
  el.innerHTML = html + actions;
}

// ---------- settings ----------
function openSettings() {
  const body = $('#settingsBody');
  if (!state.token) return;
  const libs = state.allLibs;
  body.innerHTML = `
    <h3>Settings</h3>
    ${state.servers.length > 1 ? `<label class="row"><span class="label">Server</span>
      <select id="serverSel">${state.servers.map(s => `<option value="${esc(s.id)}" ${s.id === state.serverId ? 'selected' : ''}>${esc(s.name)}${s.owned ? '' : ' (shared)'}</option>`).join('')}</select></label>` : ''}
    ${libs.map(l => `<label class="row"><span class="label">${esc(l.title)}<div class="hint">TV library</div></span>
      <input type="checkbox" data-lib="${esc(l.key)}" ${!state.libs || state.libs.includes(l.key) ? 'checked' : ''}></label>`).join('')}
    <label class="row"><span class="label">Show ended series</span><input type="checkbox" id="endedChk" ${state.showEnded ? 'checked' : ''}></label>
    <div class="row"><span><span class="label">Calendar backup list</span>
      <div class="hint">Upload as shows.json to your repo if the calendar job can't reach Plex.</div></span>
      <button class="btn" id="exportBtn">Save</button></div>
    <div class="row"><span><span class="label">Plex token</span>
      <div class="hint">For the calendar job's PLEX_TOKEN secret. Keep it private.</div></span>
      <button class="btn" id="tokenBtn">Copy</button></div>
    <div class="btns">
      <button class="btn" id="fullBtn">Re-check every show</button>
      <button class="btn" id="signOutBtn">Sign out</button>
      <button class="btn primary" id="closeBtn">Done</button>
    </div>`;

  const sel = $('#serverSel');
  if (sel) sel.onchange = () => {
    state.serverId = sel.value; LS.set('serverId', sel.value);
    state.libs = null; LS.del('libs'); state.plexShows = null; LS.del('plexShows');
  };
  body.querySelectorAll('[data-lib]').forEach(cb => cb.onchange = () => {
    const keys = [...body.querySelectorAll('[data-lib]')].filter(x => x.checked).map(x => x.dataset.lib);
    state.libs = keys; LS.set('libs', keys);
  });
  $('#endedChk').onchange = e => { state.showEnded = e.target.checked; LS.set('showEnded', state.showEnded); render(); };
  $('#exportBtn').onclick = exportShows;
  $('#tokenBtn').onclick = async e => {
    try { await navigator.clipboard.writeText(state.token); e.target.textContent = 'Copied'; }
    catch { prompt('Copy your Plex token:', state.token); }
  };
  $('#fullBtn').onclick = () => { $('#settings').close(); refresh({ force: true }); };
  $('#signOutBtn').onclick = () => { if (confirm('Sign out of Plex on this device?')) signOut(); };
  $('#closeBtn').onclick = () => $('#settings').close();

  const before = JSON.stringify([state.serverId, state.libs]);
  $('#settings').onclose = () => { if (JSON.stringify([state.serverId, state.libs]) !== before) refresh(); };
  $('#settings').showModal();
}

function exportShows() {
  if (!state.plexShows) return;
  const sid = state.plexShows.serverId;
  const rows = state.plexShows.shows
    .map(s => ({ title: s.title, ratingKey: s.ratingKey, mazeId: state.mazeIds[mazeKey(sid, s)] || 0 }))
    .filter(r => r.mazeId);
  const blob = new Blob([JSON.stringify(rows, null, 1)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = 'shows.json'; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}

// ---------- start ----------
$('#refreshBtn').onclick = () => refresh();
$('#settingsBtn').onclick = openSettings;

async function start() {
  if (!state.token && await checkPendingPin()) { render(); return refresh(); }
  render();
  if (state.token && Date.now() - state.lastRefresh > 3 * 3600000) refresh();
}
document.addEventListener('visibilitychange', async () => {
  if (document.visibilityState !== 'visible') return;
  if (!state.token && LS.get('pin')) { if (await checkPendingPin()) refresh(); return; }
  if (state.token && Date.now() - state.lastRefresh > 3 * 3600000) refresh();
  else render();
});
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
start();
