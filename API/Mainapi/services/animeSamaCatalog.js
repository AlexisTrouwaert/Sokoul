'use strict';

/**
 * Anime-Sama page parsing and episode link resolution.
 *
 * Since 2026-11-12 season pages no longer embed video addresses. Each player array
 * (`var eps1 = ["lien:109465", "", ...]`) holds opaque references, `epsMeta` maps a
 * reference to its host, and the page resolves one reference at a time with
 * `GET /api/lien.php?j=<epsJeton>&l=<id>` (token valid for about 24 hours).
 *
 * Catalogue caches outlive the token, so a reference is stored as a token-free URL
 * (`<origin>/api/lien.php?l=<id>&p=<season page path>`) that passes the existing
 * player URL filters, and is resolved with a fresh token only when an episode is played.
 */

const LINK_PATH = '/api/lien.php';
const MAX_PARALLEL = 4;
const LINK_TIMEOUT_MS = 8000;
const PROXY_PREFIX = 'https://proxy.liyao.space/------';

function stripComments(source) {
  return String(source || '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:"'\\])\/\/[^\n]*/g, '$1');
}

/** Season cards declared by `panneauAnime("name", "saison1/vostfr")`, commented cards excluded. */
function parseSeasonPanels(html, catalogueUrl) {
  const base = String(catalogueUrl || '').replace(/\/+$/, '');
  const slug = base.split('/').pop();
  const panels = [];
  const seen = new Set();
  const re = /panneauAnime\(\s*"([^"]+)"\s*,\s*"([^"]+?)\/?(?:vostfr|vf\d*|vosteng|vostspa|vj|va|vkr)?"\s*\)/g;
  for (const match of stripComments(html).matchAll(re)) {
    const name = match[1].trim();
    let link = match[2].trim().replace(/\/+$/, '');
    if (!name || !link || name === 'nom' || link === 'url') continue;
    if (slug && link.startsWith(slug + '/')) link = link.slice(slug.length + 1);
    link = link.replace(/^\/+/, '');
    const url = `${base}/${link}`;
    if (seen.has(url)) continue;
    seen.add(url);
    const numbered = /^saison(\d+)$/i.exec(link);
    panels.push({ name, url, number: numbered ? Number(numbered[1]) : null });
  }
  return panels;
}

/** Languages the site declares for a season page (`var languesDispo = ["vostfr","vf"]`). */
function parseAvailableLanguages(html) {
  const match = /languesDispo\s*=\s*(\[[^\]]*\])/.exec(String(html || ''));
  if (!match) return null;
  try {
    const list = JSON.parse(match[1]);
    return Array.isArray(list) ? list.filter((lang) => typeof lang === 'string' && /^[a-z0-9]{1,10}$/.test(lang)) : null;
  } catch {
    return null;
  }
}

function parseMeta(source) {
  const match = /epsMeta\s*=\s*(\{[\s\S]*?\})\s*;/.exec(source);
  if (!match) return {};
  try {
    const meta = JSON.parse(match[1]);
    return meta && typeof meta === 'object' ? meta : {};
  } catch {
    return {};
  }
}

function parseArrays(source) {
  const arrays = [];
  const re = /\beps(\d+)\s*=\s*\[([\s\S]*?)\]/g;
  for (const match of source.matchAll(re)) {
    const entries = [];
    const entryRe = /(["'])((?:\\.|(?!\1).)*)\1/g;
    for (const entry of match[2].matchAll(entryRe)) entries.push(entry[2].trim());
    arrays[Number(match[1]) - 1] = entries;
  }
  return arrays.filter(Array.isArray);
}

function isPlayerUrl(url) {
  return typeof url === 'string' && /^https?:\/\/[^/\s]+\.[^/\s]+/i.test(url);
}

function referenceUrl(pageUrl, id, host) {
  const page = new URL(pageUrl);
  const params = new URLSearchParams({ l: id, p: page.pathname });
  if (host) params.set('h', host);
  return `${page.origin}${LINK_PATH}?${params}`;
}

/**
 * Player references per episode: result[episodeIndex] = [url, ...], in player order.
 * Accepts a season page (inline arrays) or a legacy episodes.js file.
 */
function parseEpisodePlayers(source, pageUrl) {
  const text = stripComments(source);
  const arrays = parseArrays(text);
  if (!arrays.length) return [];
  const meta = parseMeta(String(source || ''));
  const length = Math.max(...arrays.map((list) => list.length));
  const episodes = [];
  for (let index = 0; index < length; index++) {
    const live = [];
    const flagged = [];
    for (const list of arrays) {
      const entry = list[index];
      if (!entry) continue;
      const reference = /^lien:(\d{1,12})$/.exec(entry);
      if (reference) {
        const info = Array.isArray(meta[reference[1]]) ? meta[reference[1]] : [];
        const host = typeof info[0] === 'string' && /^[a-z0-9.-]{1,80}$/i.test(info[0]) ? info[0] : '';
        // A second meta value marks a video the site reports as removed: try it last.
        (info.length > 1 && info[1] ? flagged : live).push(referenceUrl(pageUrl, reference[1], host));
        continue;
      }
      const url = entry.startsWith(PROXY_PREFIX) ? entry.slice(PROXY_PREFIX.length) : entry;
      if (isPlayerUrl(url)) live.push(url);
    }
    episodes.push([...new Set([...live, ...flagged])]);
  }
  while (episodes.length && episodes[episodes.length - 1].length === 0) episodes.pop();
  return episodes;
}

function parseReference(url) {
  try {
    const parsed = new URL(url);
    if (parsed.pathname !== LINK_PATH || !/^anime-sama\.[a-z]{2,10}$/i.test(parsed.hostname)) return null;
    const id = parsed.searchParams.get('l');
    const path = parsed.searchParams.get('p');
    if (!/^\d{1,12}$/.test(id || '') || !/^\/catalogue\/[^?#]+\/$/.test(path || '')) return null;
    return { id, page: parsed.origin + path, origin: parsed.origin };
  } catch {
    return null;
  }
}

function headerValues(headers, name) {
  if (!headers) return [];
  const key = Object.keys(headers).find((candidate) => candidate.toLowerCase() === name);
  const value = key ? headers[key] : undefined;
  return Array.isArray(value) ? value : value ? [String(value)] : [];
}

function sessionCookie(headers) {
  return headerValues(headers, 'set-cookie')
    .flatMap((value) => String(value).split(/,(?=\s*[A-Za-z0-9_-]+=)/))
    .map((value) => value.split(';')[0].trim())
    .filter((pair) => /^[A-Za-z0-9_-]+=[^;\s]*$/.test(pair))
    .join('; ');
}

function asJson(data) {
  if (data && typeof data === 'object') return data;
  try {
    return JSON.parse(String(data || ''));
  } catch {
    return null;
  }
}

async function mapLimited(items, limit, task) {
  const results = new Array(items.length);
  let next = 0;
  async function worker() {
    while (next < items.length) {
      const index = next++;
      results[index] = await task(items[index]);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

/**
 * Replaces `lien.php` references of one episode with the player URLs the site returns now.
 * Unresolved references are dropped; ordinary player URLs are kept unchanged.
 */
async function resolveEpisodeReferences(episode, request) {
  const links = Array.isArray(episode?.streaming_links) ? episode.streaming_links : [];
  const stats = { resolved: 0, expired: 0, failed: 0 };
  const sessions = new Map();

  async function session(page, fresh = false) {
    if (!fresh && sessions.has(page)) return sessions.get(page);
    const pending = (async () => {
      const response = await request({ method: 'get', url: page, headers: { Referer: new URL(page).origin + '/' } });
      const token = /epsJeton\s*=\s*"([^"]{8,200})"/.exec(String(response.data || ''))?.[1];
      if (!token) throw new Error('Anime-Sama page token missing');
      return { token, cookie: sessionCookie(response.headers) };
    })();
    sessions.set(page, pending);
    pending.catch(() => sessions.delete(page));
    return pending;
  }

  async function resolve(reference) {
    for (let attempt = 0; attempt < 2; attempt++) {
      let state;
      try {
        state = await session(reference.page, attempt > 0);
      } catch {
        return null;
      }
      try {
        // Same request metadata as the page's own `fetch(..., { credentials: 'same-origin' })`;
        // the endpoint answers 403 {"erreur":"origine"} without it (verified 2026-11-15, owner-approved).
        const headers = {
          Referer: reference.page, Accept: '*/*',
          'Sec-Fetch-Site': 'same-origin', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': 'empty',
        };
        if (state.cookie) headers.Cookie = state.cookie;
        const response = await request({
          method: 'get',
          url: `${reference.origin}${LINK_PATH}?j=${encodeURIComponent(state.token)}&l=${reference.id}`,
          headers,
          timeout: LINK_TIMEOUT_MS,
        });
        const body = asJson(response.data);
        const url = typeof body?.u === 'string' ? body.u.trim() : '';
        if (isPlayerUrl(url)) return url;
        if (body?.erreur === 'expire' && attempt === 0) {
          stats.expired++;
          continue;
        }
        return null;
      } catch {
        return null;
      }
    }
    return null;
  }

  const references = new Map();
  for (const link of links) {
    for (const player of Array.isArray(link?.players) ? link.players : []) {
      const reference = parseReference(player);
      if (reference) references.set(player, reference);
    }
  }
  const keys = [...references.keys()];
  const values = await mapLimited(keys, MAX_PARALLEL, (key) => resolve(references.get(key)));
  const resolved = new Map(keys.map((key, index) => [key, values[index]]));
  for (const value of values) value ? stats.resolved++ : stats.failed++;

  const streamingLinks = links.map((link) => ({
    ...link,
    players: [...new Set((Array.isArray(link?.players) ? link.players : [])
      .map((player) => (references.has(player) ? resolved.get(player) : player))
      .filter(isPlayerUrl))],
  })).filter((link) => link.players.length > 0);

  return { streaming_links: streamingLinks, referenceResolution: stats };
}

module.exports = {
  parseSeasonPanels,
  parseAvailableLanguages,
  parseEpisodePlayers,
  resolveEpisodeReferences,
  parseReference,
};
