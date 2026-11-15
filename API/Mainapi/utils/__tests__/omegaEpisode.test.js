const test = require('node:test');
const assert = require('node:assert/strict');
const { selectOmegaEpisode } = require('../omegaEpisode');

test('Omega selects the requested numbered episode across language versions without changing its catalogue', () => {
  const player = { link: 'https://vidmoly.example/embed-target.html', name: 'Vidmoly' };
  const payload = { series: [{ seasons: [
    { number: 2, episodes: [{ number: '7', versions: { vf: { players: [player] }, vostfr: { players: [{ link: 'https://voe.example/e/sub' }] } } }] },
    { number: 1, episodes: [{ number: '7', versions: { vf: { players: [{ link: 'https://wrong.example/e/7' }] } } }] },
  ] }, { seasons: [{ number: 2, episodes: [{ number: '7', versions: { vf: { players: [player] } } }] }] }] };
  const before = JSON.stringify(payload);
  const selected = selectOmegaEpisode(payload, '2', '7');
  assert.deepEqual(Object.keys(selected.languages), ['vf', 'vostfr']);
  assert.equal(selected.languages.vf.length, 1);
  assert.equal(selected.languages.vf[0].link, player.link);
  assert.equal(selected.languages.vf[0].language, 'vf');
  assert.equal(JSON.stringify(payload), before);
  assert.notEqual(selected.languages.vf[0], player);
});

test('Omega never substitutes an array rank or a nearby episode for a missing identity', () => {
  const payload = { series: [{ seasons: [{ number: 4, episodes: [{ number: '9', versions: { vf: { players: [{ link: 'https://host.test/9' }] } } }] }] }] };
  assert.deepEqual(selectOmegaEpisode(payload, '1', '1').languages, {});
  for (const [season, episode] of [['0', '1'], ['1', '-1'], ['2x', '1'], [undefined, '1'], ['1', '999999']]) {
    assert.equal(selectOmegaEpisode(payload, season, episode), null);
  }
});
