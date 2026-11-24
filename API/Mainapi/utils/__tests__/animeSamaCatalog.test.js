'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  parseSeasonPanels, parseAvailableLanguages, seasonLanguages, parseEpisodePlayers, resolveEpisodeReferences, parseReference,
} = require('../../services/animeSamaCatalog');

const fixture = (name) => fs.readFileSync(path.join(__dirname, 'fixtures', 'animeSama', name), 'utf8');
const PAGE = 'https://anime-sama.to/catalogue/one-piece/saison1/vf/';

test('season cards exclude commented and template panels and keep numbers only for plain seasons', () => {
  const panels = parseSeasonPanels(fixture('catalogue.html'), 'https://anime-sama.to/catalogue/one-piece/');
  assert.deepEqual(panels.map((panel) => [panel.name, panel.url.replace('https://anime-sama.to/catalogue/one-piece/', ''), panel.number]), [
    ['Saga 1 East Blue [Episode 1 à 61]', 'saison1', 1],
    ['Saga 2 Alabasta [Episode 62 à 143]', 'saison2', 2],
    ['Films', 'film', null],
    ['One Piece Heroines', 'saison2hs', null],
    ['Kai - Saga 1 (East Blue)', 'kai', null],
  ]);
});

test('declared languages are read from the season page', () => {
  assert.deepEqual(parseAvailableLanguages(fixture('season-vf.html')), ['vostfr', 'vf', 'vf2']);
  assert.equal(parseAvailableLanguages('<html></html>'), null);
});

test('declared numbered French editions remain available beyond VF2', () => {
  const declared = parseAvailableLanguages('<script>var languesDispo = ["vostfr","vf3","vf3","vkr","unsafe/path"];</script>');
  assert.deepEqual(seasonLanguages(declared), ['vostfr', 'vkr', 'vf3']);
  assert.ok(seasonLanguages(null).includes('vf2'));
});

test('the season panel keeps the French edition when it is linked directly', () => {
  const [panel] = parseSeasonPanels('panneauAnime("Saison 1", "saison1/vf3");', 'https://anime-sama.to/catalogue/example/');
  assert.equal(panel.url, 'https://anime-sama.to/catalogue/example/saison1');
  assert.equal(panel.number, 1);
  assert.equal(panel.language, 'vf3');
  assert.ok(seasonLanguages(null, panel.language).includes('vf3'));
});

test('tokenised references keep episode positions, skip blanks and put removed videos last', () => {
  const episodes = parseEpisodePlayers(fixture('season-vf.html'), PAGE);
  assert.equal(episodes.length, 3);
  assert.deepEqual(episodes[0].map((url) => parseReference(url).id), ['272886', '109465']);
  assert.deepEqual(episodes[1].map((url) => parseReference(url).id), ['109468']);
  assert.deepEqual(episodes[2].map((url) => parseReference(url).id), ['109471', '272887']);
  const reference = new URL(episodes[1][0]);
  assert.equal(reference.searchParams.get('j'), null, 'the expiring token is never stored');
  assert.equal(reference.searchParams.get('h'), 'ansembed.net');
  assert.equal(parseReference(episodes[1][0]).page, PAGE);
});

test('legacy episodes.js player URLs remain supported', () => {
  const legacy = "var eps1 = ['https://vidmoly.to/embed-a.html', 'https://proxy.liyao.space/------https://video.sibnet.ru/shell.php?videoid=1'];\nvar eps2 = ['https://sendvid.com/embed/x'];";
  assert.deepEqual(parseEpisodePlayers(legacy, PAGE), [
    ['https://vidmoly.to/embed-a.html', 'https://sendvid.com/embed/x'],
    ['https://video.sibnet.ru/shell.php?videoid=1'],
  ]);
});

function fakeSite({ expireFirst = false, links = {} } = {}) {
  const calls = { pages: 0, links: [] };
  let tokenAge = 0;
  const request = async ({ url, headers }) => {
    if (url === PAGE) {
      calls.pages++;
      tokenAge++;
      return { status: 200, data: `var epsJeton = "token-${tokenAge}-abcdef";`, headers: { 'set-cookie': ['PHPSESSID=s1; path=/; HttpOnly'] } };
    }
    const parsed = new URL(url);
    assert.equal(parsed.pathname, '/api/lien.php');
    assert.equal(headers.Referer, PAGE);
    assert.equal(headers.Cookie, 'PHPSESSID=s1');
    assert.equal(headers['Sec-Fetch-Site'], 'same-origin');
    const id = parsed.searchParams.get('l');
    calls.links.push([id, parsed.searchParams.get('j')]);
    if (expireFirst && parsed.searchParams.get('j') === 'token-1-abcdef') return { status: 200, data: '{"erreur":"expire"}' };
    if (links[id] instanceof Error) throw links[id];
    return { status: 200, data: links[id] ?? '{}' };
  };
  return { request, calls };
}

test('references resolve with one page token shared by the episode and unresolved ones are dropped', async () => {
  const [first] = parseEpisodePlayers(fixture('season-vf.html'), PAGE);
  const { request, calls } = fakeSite({ links: { 272886: '{"u":"https://sendvid.com/embed/abc"}', 109465: '<html>challenge</html>' } });
  const episode = { streaming_links: [{ language: 'vf', players: first }, { language: 'vostfr', players: ['https://vidmoly.to/embed-b.html'] }] };
  const result = await resolveEpisodeReferences(episode, request);
  assert.equal(calls.pages, 1);
  assert.deepEqual(result.streaming_links, [
    { language: 'vf', players: ['https://sendvid.com/embed/abc'] },
    { language: 'vostfr', players: ['https://vidmoly.to/embed-b.html'] },
  ]);
  assert.deepEqual(result.referenceResolution, { resolved: 1, expired: 0, failed: 1 });
});

test('an expired token is refreshed once from the season page', async () => {
  const [, second] = parseEpisodePlayers(fixture('season-vf.html'), PAGE);
  const { request, calls } = fakeSite({ expireFirst: true, links: { 109468: { u: 'https://ansembed.net/embed-x.html' } } });
  const result = await resolveEpisodeReferences({ streaming_links: [{ language: 'vf', players: second }] }, request);
  assert.deepEqual(result.streaming_links, [{ language: 'vf', players: ['https://ansembed.net/embed-x.html'] }]);
  assert.equal(calls.pages, 2);
  assert.deepEqual(calls.links.map(([, token]) => token), ['token-1-abcdef', 'token-2-abcdef']);
  assert.equal(result.referenceResolution.expired, 1);
});

test('a missing token or network failure drops references without throwing', async () => {
  const [first] = parseEpisodePlayers(fixture('season-vf.html'), PAGE);
  const result = await resolveEpisodeReferences({ streaming_links: [{ language: 'vf', players: first }] }, async () => ({ status: 200, data: '<html></html>' }));
  assert.deepEqual(result.streaming_links, []);
  assert.equal(result.referenceResolution.failed, 2);
});

test('references outside Anime-Sama catalogue pages are rejected', () => {
  assert.equal(parseReference('https://evil.test/api/lien.php?l=1&p=/catalogue/x/saison1/vf/'), null);
  assert.equal(parseReference('https://anime-sama.to/api/lien.php?l=1&p=https://evil.test/'), null);
  assert.equal(parseReference('https://anime-sama.to/api/lien.php?l=abc&p=/catalogue/x/saison1/vf/'), null);
});
