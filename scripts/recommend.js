// recommend.js — suggests shows you don't have yet, based on the ones you like in Plex.
// "Like" comes from your Plex star ratings, or (if unrated) how much of a show you've watched.
// Suggestions come from TMDB's "recommendations" for each liked show.
// This product uses the TMDB API but is not endorsed or certified by TMDB.

const API = 'https://api.themoviedb.org/3';
const SEEDS = 25;          // liked shows to draw from
const DISLIKES = 10;       // low-rated shows whose neighbors get pushed down
const KEEP = 40;           // suggestions to publish
const REGION = 'US';

export function likeWeight(s) {
  if (s.rating != null) {
    if (s.rating >= 8) return 3;
    if (s.rating >= 6) return 1.5;
    if (s.rating <= 4) return -2;
    return 0.5;
  }
  if (!s.episodes || s.watched < 3) return 0;
  const watchedShare = Math.min(1, s.watched / s.episodes);
  const recent = s.lastViewed && Date.now() / 1000 - s.lastViewed < 365 * 86400 ? 0.4 : 0;
  return 0.5 + 1.5 * watchedShare + recent;
}

const norm = t => String(t || '').toLowerCase().replace(/\(\d{4}\)/g, '').replace(/[^a-z0-9]+/g, '');

export function makeClient(key, fetchImpl = fetch) {
  const bearer = key.length > 40;
  let last = 0;
  return async function tmdb(path, params = {}) {
    const wait = Math.max(0, last + 60 - Date.now());   // stay far under TMDB's rate limit
    if (wait) await new Promise(r => setTimeout(r, wait));
    last = Date.now();
    const q = new URLSearchParams(params);
    if (!bearer) q.set('api_key', key);
    const headers = { Accept: 'application/json' };
    if (bearer) headers.Authorization = `Bearer ${key}`;
    for (let attempt = 0; attempt < 3; attempt++) {
      const res = await fetchImpl(`${API}${path}?${q}`, { headers });
      if (res.status === 429) { await new Promise(r => setTimeout(r, 2000 * (attempt + 1))); continue; }
      if (res.status === 401) throw new Error('TMDB rejected the key (check the TMDB_KEY secret)');
      if (res.status === 404) return null;
      if (!res.ok) throw new Error(`TMDB HTTP ${res.status}`);
      return res.json();
    }
    throw new Error('TMDB is busy');
  };
}

async function findTmdbId(tmdb, show) {
  if (show.ids?.tmdb) return Number(show.ids.tmdb);
  for (const [id, source] of [[show.ids?.tvdb, 'tvdb_id'], [show.ids?.imdb, 'imdb_id']]) {
    if (!id) continue;
    const r = await tmdb(`/find/${id}`, { external_source: source });
    if (r?.tv_results?.[0]) return r.tv_results[0].id;
  }
  const params = { query: show.title };
  if (show.year) params.first_air_date_year = show.year;
  const r = await tmdb('/search/tv', params);
  return r?.results?.[0]?.id || null;
}

export async function recommend(plexShows, key, fetchImpl = fetch) {
  const tmdb = makeClient(key, fetchImpl);

  const weighted = plexShows.map(s => ({ s, w: likeWeight(s) }));
  const likes = weighted.filter(x => x.w > 0).sort((a, b) => b.w - a.w).slice(0, SEEDS);
  const dislikes = weighted.filter(x => x.w < 0).slice(0, DISLIKES);
  if (!likes.length) throw new Error('Not enough watch history in Plex to go on yet');

  // What you already have, so we never suggest it.
  const ownedIds = new Set(plexShows.map(s => Number(s.ids?.tmdb)).filter(Boolean));
  const ownedTitles = new Set(plexShows.map(s => norm(s.title)));

  const genreList = await tmdb('/genre/tv/list');
  const genres = new Map((genreList?.genres || []).map(g => [g.id, g.name]));

  const cand = new Map();   // tmdb id -> { r, score, reasons: Map(title -> contribution) }
  for (const { s, w } of [...likes, ...dislikes]) {
    try {
      const id = await findTmdbId(tmdb, s);
      if (!id) continue;
      ownedIds.add(id);
      let list = (await tmdb(`/tv/${id}/recommendations`))?.results || [];
      if (!list.length) list = (await tmdb(`/tv/${id}/similar`))?.results || [];
      list.slice(0, 20).forEach((r, rank) => {
        const add = w * (1 - rank / 25);
        const c = cand.get(r.id) || { r, score: 0, reasons: new Map() };
        c.score += add;
        if (add > 0) c.reasons.set(s.title, (c.reasons.get(s.title) || 0) + add);
        cand.set(r.id, c);
      });
    } catch (e) {
      if (/rejected the key/.test(e.message)) throw e;
      console.warn(`TMDB: skipped ${s.title}: ${e.message}`);
    }
  }

  const ranked = [...cand.values()]
    .filter(c => !ownedIds.has(c.r.id) && !ownedTitles.has(norm(c.r.name)) && !ownedTitles.has(norm(c.r.original_name)))
    .filter(c => c.score > 0 && (c.r.vote_count || 0) >= 30 && c.reasons.size > 0)
    .map(c => ({ ...c, final: c.score * (1 + 0.15 * (c.reasons.size - 1)) + ((c.r.vote_average || 6) - 6) * 0.1 }))
    .sort((a, b) => b.final - a.final)
    .slice(0, KEEP);

  const items = [];
  for (const c of ranked) {
    let providers = [];
    try {
      const p = await tmdb(`/tv/${c.r.id}/watch/providers`);
      const here = p?.results?.[REGION] || {};
      providers = [...new Set([...(here.flatrate || []), ...(here.free || []), ...(here.ads || [])].map(x => x.provider_name))].slice(0, 4);
    } catch {}
    items.push({
      id: c.r.id,
      name: c.r.name,
      year: (c.r.first_air_date || '').slice(0, 4),
      overview: String(c.r.overview || '').trim().slice(0, 600),
      poster: c.r.poster_path ? `https://image.tmdb.org/t/p/w342${c.r.poster_path}` : '',
      rating: Math.round((c.r.vote_average || 0) * 10) / 10,
      genres: (c.r.genre_ids || []).map(g => genres.get(g)).filter(Boolean).slice(0, 3),
      because: [...c.reasons].sort((a, b) => b[1] - a[1]).map(([t]) => t).slice(0, 3),
      providers,
      url: `https://www.themoviedb.org/tv/${c.r.id}`,
    });
  }

  return {
    generated: new Date().toISOString(),
    basedOn: likes.map(x => x.s.title).slice(0, 12),
    items,
  };
}
