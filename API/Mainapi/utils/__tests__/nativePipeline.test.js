'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

test('simultaneous catalogue routes share a four-worker native budget', async () => {
    let active = 0, peak = 0, release;
    const gate = new Promise(resolve => { release = resolve; });
    const { createNativeResolver } = require('../nativeExtraction');
    const resolve = createNativeResolver({ engine: async () => ({ extract: async () => {
        active++; peak = Math.max(peak, active);
        await gate;
        active--;
        return null;
    } }) });
    const jobs = Array.from({ length: 12 }, () => resolve('https://voe.example/e/one', 'fixture', 'http://relay.test'));
    await new Promise(done => setImmediate(done));
    assert.equal(active, 4);
    release();
    await Promise.all(jobs);
    assert.equal(peak, 4);
    assert.equal(active, 0);
});
process.env.INTERNAL_API_KEY = 'fixture-internal-key';
const { createNativeResolver } = require('../nativeExtraction');
const { requestWithDirectFallback } = require('../animeSamaTransport');
const { relayAuthority } = require('../relayAuthority');

test('shared extraction uses authenticated fetch adapter and registers private playback headers', async () => {
    const calls = [];
    const resolver = createNativeResolver({
        engine: async () => ({ extract: async (embed, { fetch }) => {
            assert.equal(embed, 'https://vidmoly.to/embed-a.html');
            const response = await fetch(embed, { headers: { Referer: 'https://catalogue.test/' } });
            assert.equal(await response.text(), '<html>fixture</html>');
            assert.equal(response.url, 'https://vidmoly.to/embed-a.html');
            return { success: true, source: 'vidmoly', url: 'https://media.test/a.m3u8', format: 'hls', headers: { Cookie: 'private-fixture' } };
        } }),
        post: async (url, body, options) => {
            calls.push({ url, body });
            assert.equal(options.headers['x-access-key'], 'test-vip');
            if (url.endsWith('/native-fetch')) return { data: { status: 200, url: body.url, headers: { 'Content-Type': 'text/html' }, body: Buffer.from('<html>fixture</html>').toString('base64') } };
            return { data: { url: 'http://server.test:25569/native-proxy?sid=fixture&sig=signed&exp=999', format: 'hls' } };
        },
    });
    const result = await resolver('https://vidmoly.to/embed-a.html', 'test-vip', 'http://proxy:25569');
    assert.equal(calls.length, 2);
    assert.deepEqual(calls[1].body.headers, { Cookie: 'private-fixture' });
    assert.equal(result.format, 'hls');
    assert.equal(result.headers, undefined);
});

test('native runtime or media registration failure leaves the legacy fallback eligible', async () => {
    for (const engine of [async () => { throw Error('missing runtime'); }, async () => ({ extract: async () => ({ success: false, error: 'media_missing' }) })]) {
        const resolver = createNativeResolver({ engine, post: async () => { throw Error('must not register'); } });
        assert.equal(await resolver('https://vidmoly.to/embed-a.html', 'test', 'http://proxy:25569'), null);
    }
});

test('status zero and network errors use one direct fallback but genuine empty results do not', async () => {
    let direct = 0;
    const fallback = async () => { direct++; return { status: 200, data: [] }; };
    assert.equal((await requestWithDirectFallback(async () => ({ status: 0 }), fallback)).status, 200);
    assert.equal((await requestWithDirectFallback(async () => { throw Error('network'); }, fallback)).status, 200);
    await requestWithDirectFallback(async () => ({ status: 200, data: [] }), fallback);
    await requestWithDirectFallback(async () => ({ status: 403 }), fallback);
    assert.equal(direct, 2);
    await assert.rejects(requestWithDirectFallback(async () => ({ status: 0 }), async () => { throw Error('network'); }), { code: 'ANIME_CATALOGUE_UNAVAILABLE' });
});

test('a direct 404 after a transport failure keeps its status instead of reporting unavailability', async () => {
    assert.equal((await requestWithDirectFallback(async () => ({ status: 0 }), async () => ({ status: 404 }))).status, 404);
    const missing = Object.assign(Error('missing'), { response: { status: 404 } });
    assert.equal((await requestWithDirectFallback(async () => ({ status: 0 }), async () => { throw missing; })).status, 404);
    await assert.rejects(requestWithDirectFallback(async () => ({ status: 0 }), async () => ({ status: 503 })), { code: 'ANIME_CATALOGUE_UNAVAILABLE' });
});

test('relay authority follows configured client host without rewriting unrelated media or signature', () => {
    const previous = process.env.PROXIESEMBED_PUBLIC_URL;
    process.env.PROXIESEMBED_PUBLIC_URL = 'http://192.168.0.240:25569';
    try {
        const signed = 'http://192.168.0.240:25569/native-proxy?sid=state&url=https%3A%2F%2Fmedia.test%2Fa&exp=999&sig=abc';
        const payload = { m3u8ByPlayer: { embed: signed }, media: { url: signed }, other: 'https://media.test/a.mp4' };
        const result = relayAuthority(payload, { protocol: 'http', headers: { host: '127.0.0.1:25565' } });
        assert.equal(new URL(result.media.url).host, '127.0.0.1:25569');
        assert.equal(new URL(result.media.url).searchParams.get('sig'), 'abc');
        assert.equal(result.other, payload.other);
        assert.equal(payload.media.url, signed);
        const relative = relayAuthority({ url: '/native-proxy?sid=s&url=x&exp=9&sig=abc', path: '/catalogue/a', unsigned: '/native-proxy?sid=s' }, { protocol: 'http', headers: { host: '192.168.0.240:25565' } });
        assert.equal(relative.url, 'http://192.168.0.240:25569/native-proxy?sid=s&url=x&exp=9&sig=abc');
        assert.equal(relative.path, '/catalogue/a');
        assert.equal(relative.unsigned, '/native-proxy?sid=s');
    } finally {
        if (previous == null) delete process.env.PROXIESEMBED_PUBLIC_URL; else process.env.PROXIESEMBED_PUBLIC_URL = previous;
    }
});
