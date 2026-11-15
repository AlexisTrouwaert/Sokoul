'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createAnimeLookup } = require('../animeSamaDns');
function ask(lookup, options = { all: true }) {
    return new Promise((resolve, reject) => lookup('anime-sama.to', options, (error, addresses, family) => error ? reject(error) : resolve({ addresses, family })));
}
test('catalogue DNS coalesces public answers, respects TTL and supports Node all-address lookup', async () => {
    let now = 1000, calls = 0;
    const lookup = createAnimeLookup(async () => { calls++; return { Status: 0, Answer: [{ type: 1, data: '104.26.13.154', TTL: 2 }, { type: 1, data: '127.0.0.1', TTL: 2 }] }; }, () => now);
    const results = await Promise.all([ask(lookup), ask(lookup)]);
    assert.deepEqual(results[0].addresses, [{ address: '104.26.13.154', family: 4 }]);
    assert.equal(calls, 1);
    assert.deepEqual(await ask(lookup, {}), { addresses: '104.26.13.154', family: 4 });
    now += 2001;
    await ask(lookup);
    assert.equal(calls, 2);
});
test('private answers, DNS refusal and network failure cannot become catalogue addresses', async () => {
    for (const result of [{ Status: 0, Answer: [{ type: 1, data: '192.168.0.1', TTL: 60 }] }, { Status: 3 }, null]) {
        await assert.rejects(ask(createAnimeLookup(async () => result)), { code: 'EAI_AGAIN' });
    }
    await assert.rejects(ask(createAnimeLookup(async () => { throw Error('network'); })), { code: 'EAI_AGAIN' });
});
test('blocked catalogue hosts use the secure lookup while unrelated hosts keep system DNS', async () => {
    const { CATALOGUE_HOST } = require('../animeSamaDns');
    for (const host of ['anime-sama.to', 'coflix.esq', 'www.coflix.click', 'frenchstream.food', 'french-stream.net']) assert.ok(CATALOGUE_HOST.test(host), host);
    for (const host of ['coflix.esq.evil.test', 'api.themoviedb.org', 'notcoflix.esq', 'frenchcloud.cam']) assert.ok(!CATALOGUE_HOST.test(host), host);
    let asked = null;
    const lookup = createAnimeLookup(async (name) => { asked = name; return { Status: 0, Answer: [{ type: 1, data: '188.114.96.2', TTL: 60 }] }; });
    const result = await new Promise((resolve, reject) => lookup('coflix.esq', { all: true }, (error, addresses) => error ? reject(error) : resolve(addresses)));
    assert.equal(asked, 'coflix.esq');
    assert.deepEqual(result, [{ address: '188.114.96.2', family: 4 }]);
});
