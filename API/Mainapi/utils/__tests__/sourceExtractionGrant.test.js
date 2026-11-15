const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function helper(verifyAccessKey) {
  const module = { exports: {} };
  const source = fs.readFileSync(path.join(__dirname, '..', 'embedExtraction.js'), 'utf8');
  const stubs = {
    axios: {}, './mediaSigning': { internalHeaders: x => x, internalKeyConfigured: () => true, isPublicHttpUrl: () => true },
    './wiflixProxyTelemetry': { isValidDiscordWebhookUrl: () => false },
    './nativeExtraction': { extractNative: async () => { throw Error('Unexpected network'); } },
    './relayAuthority': { relayAuthority: x => x }, '../checkVip': { verifyAccessKey },
  };
  vm.runInNewContext(source, { require: name => {
    if (!(name in stubs)) throw Error(`Unexpected dependency ${name}`);
    return stubs[name];
  }, module, process: { env: {} }, console: { error() {} }, Date, URL, Buffer, setTimeout, clearTimeout });
  return module.exports.respondWithResolvedSources;
}

async function resolve(verify, query = { resolve: '1' }) {
  const payload = { player_links: [{ link: 'https://unsupported.test/embed/one' }] };
  const response = { status() { return this; }, json(value) { this.body = value; return this; } };
  await helper(verify)({ query, headers: { 'x-access-key': 'fixture' } }, response, payload, { movieMapKey: 'player_links' });
  return { payload, result: response.body };
}

test('explicit VIP authorization survives an unsupported host so native fallback has a positive grant', async () => {
  const { payload, result } = await resolve(async key => { assert.equal(key, 'fixture'); return { vip: true }; });
  assert.equal(result.extractionStatus, 'authorized');
  assert.equal(result.player_links[0].m3u8Url, undefined);
  assert.equal(payload.extractionStatus, undefined, 'catalogue cache must not gain caller authorization');
});

test('denied or unavailable VIP verification cannot grant native fallback', async () => {
  assert.equal((await resolve(async () => ({ vip: false }))).result.extractionStatus, 'vip_required');
  assert.equal((await resolve(async () => { throw Error('Unavailable'); })).result.extractionStatus, undefined);
  assert.equal((await resolve(async () => { throw Error('Must not verify'); }, {})).result.extractionStatus, undefined);
});
