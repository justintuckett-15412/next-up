// core.js — shared by the web app (browser) and the calendar job (Node).
// Talks to Plex (your shows) and TVmaze (air dates). No API keys needed.

export const APP = { product: 'Next Up', version: '1.0' };

// ---------- small helpers ----------
const ACCEPT = { headers: { Accept: 'application/json' } };

function plexQS(clientId, token, extra = {}) {
  // Plex accepts its X-Plex-* settings as query params, which keeps
  // browser requests "simple" (no CORS preflight).
  const p = new URLSearchParams({
    'X-Plex-Product': APP.product,
    'X-Plex-Version': APP.version,
    'X-Plex-Client-Identifier': clientId,
    ...extra,
  });
  if (token) p.set('X-Plex-Token', token);
  return p.toString();
}

async function getJSON(url, opts = {}, timeoutMs = 15000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { ...opts, signal: ctrl.signal });
    if (!res.ok) {
      const err = new Error(`HTTP ${res.status} from ${new URL(url).host}`);
      err.status = res.status;
      throw err;
    }
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

export function stripHtml(s, max = 400) {
  if (!s) return '';
  const t = String(s).replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
  return t.length > max ? t.slice(0, max - 1) + '…' : t;
}

// ---------- Plex sign-in (PIN flow, same one other Plex apps use) ----------
export function createPin(clientId) {
  return getJSON(
    `https://plex.tv/api/v2/pins?${plexQS(clientId, null, { strong: 'true' })}`,
    { method: 'POST', ...ACCEPT },
  );
}

export function authUrl(clientId, code, forwardUrl) {
  const p = new URLSearchParams({
    clientID: clientId,
    code,
    forwardUrl,
    'context[device][product]': APP.product,
  });
  return `https://app.plex.tv/auth#?${p}`;
}

export async function checkPin(clientId, pinId) {
  const r = await getJSON(`https://plex.tv/api/v2/pins/${pinId}?${plexQS(clientId)}`, ACCEPT);
  return r.authToken || null;
}

// ---------- Plex servers, libraries, shows ----------
export async function getServers(clientId, token) {
  const list = await getJSON(
    `https://plex.tv/api/v2/resources?${plexQS(clientId, token, { includeHttps: '1', includeRelay: '1' })}`,
    ACCEPT,
  );
  return (list || [])
    .filter(r => String(r.provides || '').split(',').includes('server'))
    .map(r => ({
      id: r.clientIdentifier,
      name: r.name,
      owned: !!r.owned,
      token: r.accessToken || token,
      connections: (r.connections || []).filter(c => String(c.uri).startsWith('https://')),
    }));
}

// Tries every address Plex lists for the server at once and uses whichever answers first.
// preferLocal=false skips home-network addresses (used by the cloud calendar job).
export async function connectToServer(server, clientId, { preferLocal = true } = {}) {
  const candidates = server.connections.filter(c => preferLocal || !c.local);
  if (!candidates.length) {
    throw new Error(`${server.name} has no address reachable from here (is Remote Access on?)`);
  }
  try {
    return await Promise.any(candidates.map(async c => {
      await getJSON(`${c.uri}/identity?${plexQS(clientId, server.token)}`, ACCEPT, 8000);
      return c.uri;
    }));
  } catch {
    throw new Error(`Couldn't reach ${server.name}`);
  }
}

export async function getShowLibraries(base, clientId, token) {
  const r = await getJSON(`${base}/library/sections?${plexQS(clientId, token)}`, ACCEPT);
  return (r.MediaContainer?.Directory || [])
    .filter(d => d.type === 'show')
    .map(d => ({ key: String(d.key), title: d.title }));
}

export async function getShows(base, clientId, token, sectionKey) {
  const r = await getJSON(
    `${base}/library/sections/${sectionKey}/all?${plexQS(clientId, token, { type: '2', includeGuids: '1' })}`,
    ACCEPT,
    45000,
  );
  return (r.MediaContainer?.Metadata || []).map(m => ({
    ratingKey: String(m.ratingKey),
    title: m.title,
    year: m.year || null,
    ids: parseGuids(m),
    episodes: m.leafCount || 0,
    watched: m.viewedLeafCount || 0,
  }));
}

// Which episodes of a show are in the library, and which are watched.
// Returns Map "season-episode" -> { watched: bool }.
export async function getEpisodeStatus(base, clientId, token, ratingKey) {
  const r = await getJSON(`${base}/library/metadata/${ratingKey}/allLeaves?${plexQS(clientId, token)}`, ACCEPT, 30000);
  const map = new Map();
  for (const e of r.MediaContainer?.Metadata || []) {
    if (e.parentIndex == null || e.index == null) continue;
    map.set(`${e.parentIndex}-${e.index}`, { watched: (e.viewCount || 0) > 0 });
  }
  return map;
}

// Handles both the new Plex agent (Guid: [{id:"tvdb://123"}]) and the
// legacy one (guid: "com.plexapp.agents.thetvdb://123?lang=en").
export function parseGuids(m) {
  const ids = {};
  const all = [m.guid, ...(m.Guid || []).map(g => g.id)].filter(Boolean);
  for (const g of all) {
    const tvdb = g.match(/tvdb:\/\/(\d+)/);
    const imdb = g.match(/imdb:\/\/(tt\d+)/);
    if (tvdb && !ids.tvdb) ids.tvdb = tvdb[1];
    if (imdb && !ids.imdb) ids.imdb = imdb[1];
  }
  return ids;
}

// ---------- TVmaze (rate limit: 20 calls / 10 s) ----------
const TVMAZE = 'https://api.tvmaze.com';
const GAP_MS = 520;
let nextSlot = 0;

function waitForSlot() {
  const now = Date.now();
  const at = Math.max(now, nextSlot);
  nextSlot = at + GAP_MS;
  return new Promise(r => setTimeout(r, at - now));
}

async function maze(path) {
  for (let attempt = 0; attempt < 5; attempt++) {
    await waitForSlot();
    const res = await fetch(TVMAZE + path, ACCEPT);
    if (res.status === 429) {
      await new Promise(r => setTimeout(r, 2500 * (attempt + 1)));
      continue;
    }
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`TVmaze HTTP ${res.status}`);
    return res.json();
  }
  throw new Error('TVmaze is busy — try again in a minute');
}

export async function findMazeId(show) {
  if (show.ids?.tvdb) {
    const s = await maze(`/lookup/shows?thetvdb=${show.ids.tvdb}`);
    if (s) return s.id;
  }
  if (show.ids?.imdb) {
    const s = await maze(`/lookup/shows?imdb=${show.ids.imdb}`);
    if (s) return s.id;
  }
  const s = await maze(`/singlesearch/shows?q=${encodeURIComponent(show.title)}`);
  if (!s) return null;
  const premiered = s.premiered ? Number(s.premiered.slice(0, 4)) : null;
  if (show.year && premiered && Math.abs(premiered - show.year) > 1) return null;
  return s.id;
}

function slimEp(e) {
  if (!e) return null;
  return {
    id: e.id,
    season: e.season,
    number: e.number,
    name: e.name || '',
    airdate: e.airdate || '',
    airtime: e.airtime || '',
    airstamp: e.airstamp || '',
    runtime: e.runtime || null,
    summary: stripHtml(e.summary, 300),
  };
}

export async function getShowInfo(mazeId) {
  const s = await maze(`/shows/${mazeId}?embed[]=nextepisode&embed[]=previousepisode`);
  if (!s) return null;
  return {
    mazeId: s.id,
    name: s.name,
    status: s.status, // Running, Ended, To Be Determined, In Development
    network: s.network?.name || s.webChannel?.name || '',
    image: (s.image?.medium || '').replace(/^http:/, 'https:'),
    url: s.url,
    runtime: s.averageRuntime || s.runtime || 30,
    summary: stripHtml(s.summary, 400),
    updated: s.updated || 0,
    next: slimEp(s._embedded?.nextepisode),
    prev: slimEp(s._embedded?.previousepisode),
  };
}

export async function getEpisodes(mazeId) {
  const eps = await maze(`/shows/${mazeId}/episodes?specials=1`);
  return (eps || []).map(slimEp).filter(e => e.airdate);
}

// { showId: lastUpdatedEpochSeconds } for shows changed in the last day/week.
export function getUpdates(since = 'day') {
  return maze(`/updates/shows?since=${since}`);
}

export function epCode(ep) {
  if (!ep) return '';
  if (ep.number == null) return `S${String(ep.season).padStart(2, '0')} Special`;
  return `S${String(ep.season).padStart(2, '0')}E${String(ep.number).padStart(2, '0')}`;
}
