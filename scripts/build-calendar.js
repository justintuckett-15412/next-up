// Builds episodes.ics (a calendar feed of upcoming episodes for your Plex shows).
// Run by the GitHub Action twice a day. Needs Node 20+.
//
// Settings (GitHub repo secrets / variables):
//   PLEX_TOKEN   (secret)   your Plex token — copy it from Next Up > Settings
//   PLEX_SERVER  (variable) optional: server name, if you have more than one
//   CAL_FILE     (variable) optional: output file name (default episodes.ics)
//
// If Plex can't be reached (e.g. Remote Access is off), it falls back to shows.json.

import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import * as core from '../core.js';

const CLIENT_ID = 'next-up-calendar-job';
const DAYS_BACK = 14;
const DAYS_AHEAD = 120;
const SHOWS_FILE = 'shows.json';

// ---------- iCalendar helpers ----------
export function icsEscape(s = '') {
  return String(s).replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n');
}

// Lines must be folded at 75 bytes (continuation lines start with a space).
export function fold(line) {
  if (Buffer.byteLength(line) <= 75) return line;
  const out = []; let cur = ''; let limit = 75;
  for (const ch of line) {
    if (Buffer.byteLength(cur + ch) > limit) { out.push(cur); cur = ''; limit = 74; }
    cur += ch;
  }
  out.push(cur);
  return out.join('\r\n ');
}

const utcStamp = d => d.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const dateOnly = s => s.replace(/-/g, '');
function nextDay(s) {
  const d = new Date(s + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10).replace(/-/g, '');
}

export function eventLines(show, ep, stamp) {
  const timed = !!(ep.airtime && ep.airstamp);
  const title = `${show.name} · ${core.epCode(ep)}${ep.name ? ' · ' + ep.name : ''}`;
  const desc = [show.network, ep.summary].filter(Boolean).join('\n\n');
  const lines = ['BEGIN:VEVENT', `UID:tvmaze-ep-${ep.id}@next-up`, `DTSTAMP:${stamp}`];
  if (timed) {
    const start = new Date(ep.airstamp);
    const end = new Date(start.getTime() + (ep.runtime || show.runtime || 30) * 60000);
    lines.push(`DTSTART:${utcStamp(start)}`, `DTEND:${utcStamp(end)}`);
  } else {
    lines.push(`DTSTART;VALUE=DATE:${dateOnly(ep.airdate)}`, `DTEND;VALUE=DATE:${nextDay(ep.airdate)}`);
  }
  lines.push(`SUMMARY:${icsEscape(title)}`);
  if (desc) lines.push(`DESCRIPTION:${icsEscape(desc)}`);
  if (show.url) lines.push(`URL:${show.url}`);
  lines.push('TRANSP:TRANSPARENT', 'END:VEVENT');
  return lines;
}

export function buildIcs(entries, stamp = utcStamp(new Date())) {
  const lines = [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Next Up//Plex TV air dates//EN',
    'CALSCALE:GREGORIAN', 'METHOD:PUBLISH',
    'X-WR-CALNAME:TV Episodes', 'X-WR-CALDESC:Upcoming episodes for shows on my Plex server',
    'REFRESH-INTERVAL;VALUE=DURATION:PT12H', 'X-PUBLISHED-TTL:PT12H',
  ];
  for (const { show, ep } of entries) lines.push(...eventLines(show, ep, stamp));
  lines.push('END:VCALENDAR');
  return lines.map(fold).join('\r\n') + '\r\n';
}

// ---------- show list ----------
async function readSavedShows() {
  try { return JSON.parse(await readFile(SHOWS_FILE, 'utf8')); } catch { return []; }
}

async function showsFromPlex(saved) {
  const token = process.env.PLEX_TOKEN;
  if (!token) throw new Error('PLEX_TOKEN is not set');
  const servers = await core.getServers(CLIENT_ID, token);
  const wanted = process.env.PLEX_SERVER;
  const server = servers.find(s => wanted ? s.name === wanted : s.owned) || servers[0];
  if (!server) throw new Error('No Plex server on this account');
  const base = await core.connectToServer(server, CLIENT_ID, { preferLocal: false });
  const libs = await core.getShowLibraries(base, CLIENT_ID, server.token);
  const shows = [];
  for (const l of libs) shows.push(...await core.getShows(base, CLIENT_ID, server.token, l.key));
  console.log(`Plex: ${shows.length} shows from ${server.name}`);

  const known = new Map(saved.map(s => [s.ratingKey, s.mazeId]));
  const rows = [];
  for (const s of shows) {
    let mazeId = known.get(s.ratingKey);
    if (mazeId === undefined) {
      try { mazeId = (await core.findMazeId(s)) || 0; } catch { mazeId = 0; }
    }
    rows.push({ title: s.title, ratingKey: s.ratingKey, mazeId });
  }
  return rows;
}

// ---------- main ----------
export async function main() {
  const out = process.env.CAL_FILE || 'episodes.ics';
  const saved = await readSavedShows();
  let rows;
  try {
    rows = await showsFromPlex(saved);
    await writeFile(SHOWS_FILE, JSON.stringify(rows, null, 1) + '\n');
  } catch (e) {
    console.warn(`Plex not reachable (${e.message}); using ${SHOWS_FILE}.`);
    rows = saved;
  }
  const ids = [...new Set(rows.map(r => r.mazeId).filter(Boolean))];
  if (!ids.length) throw new Error(`No shows to look up. Set PLEX_TOKEN, or upload ${SHOWS_FILE} from the app.`);

  const today = new Date();
  const from = new Date(today.getTime() - DAYS_BACK * 86400000).toISOString().slice(0, 10);
  const to = new Date(today.getTime() + DAYS_AHEAD * 86400000).toISOString().slice(0, 10);
  const entries = [];

  for (const id of ids) {
    try {
      const show = await core.getShowInfo(id);
      if (!show) continue;
      const recent = show.prev && show.prev.airdate >= from;
      if (!show.next && !recent) continue; // nothing in the window
      const eps = await core.getEpisodes(id);
      for (const ep of eps) if (ep.airdate >= from && ep.airdate <= to) entries.push({ show, ep });
    } catch (e) {
      console.warn(`Skipped show ${id}: ${e.message}`);
    }
  }
  entries.sort((a, b) => (a.ep.airstamp || a.ep.airdate).localeCompare(b.ep.airstamp || b.ep.airdate));

  const ics = buildIcs(entries);
  // Only rewrite if something besides the timestamps changed (avoids pointless commits).
  const strip = s => s.replace(/^DTSTAMP:.*$/gm, '');
  let old = '';
  try { old = await readFile(out, 'utf8'); } catch {}
  if (strip(old) === strip(ics)) console.log(`${out}: no changes (${entries.length} episodes).`);
  else { await writeFile(out, ics); console.log(`${out}: wrote ${entries.length} episodes.`); }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(e => { console.error(e.message); process.exit(1); });
}
