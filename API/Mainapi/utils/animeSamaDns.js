'use strict';

const axios = require('axios');
const dns = require('node:dns');
const https = require('node:https');
const net = require('node:net');
const { isPublicHttpUrl } = require('./mediaSigning');

// Catalogue sites that some ISP resolvers answer with a block page address (seen 2026-11-15: 193.191.210.104 for
// anime-sama, coflix.esq and frenchstream.food). Only these names leave the system resolver.
const CATALOGUE_HOST = /^(?:www\.)?(?:anime-sama|coflix|french-?stream)\.[a-z]{2,10}$/i;

/** Resolve only catalogue hosts over authenticated HTTPS; ordinary application DNS is unchanged.
 * Google Public DNS JSON API: https://developers.google.com/speed/public-dns/docs/doh/json
 * Applies to Node 22 and axios 1.11+; destination TLS still validates the original hostname.
 */
function createAnimeLookup(resolve = async (name) => (await axios.get('https://dns.google/resolve', {
    params: { name, type: 'A' }, timeout: 3000, proxy: false, maxContentLength: 64 * 1024,
})).data, clock = Date.now) {
    const cache = new Map();
    const pending = new Map();
    return function lookup(hostname, options, callback) {
        if (!CATALOGUE_HOST.test(hostname)) return dns.lookup(hostname, options, callback);
        async function addresses() {
            const known = cache.get(hostname);
            if (known && known.until > clock()) return known.addresses;
            if (!pending.has(hostname)) pending.set(hostname, (async () => {
                const result = await resolve(hostname);
                const records = result?.Status === 0 && Array.isArray(result.Answer) ? result.Answer : [];
                const valid = records.filter((record) => record.type === 1 && net.isIP(record.data) === 4 && isPublicHttpUrl(`https://${record.data}/`));
                if (!valid.length) throw new Error('Catalogue DNS unavailable');
                const values = [...new Set(valid.map((record) => record.data))].map((address) => ({ address, family: 4 }));
                if (cache.size >= 16) cache.delete(cache.keys().next().value);
                cache.set(hostname, { addresses: values, until: clock() + Math.max(1, Math.min(300, ...valid.map((record) => Number(record.TTL) || 1))) * 1000 });
                return values;
            })().finally(() => pending.delete(hostname)));
            return pending.get(hostname);
        }
        addresses().then((values) => options?.all ? callback(null, values) : callback(null, values[0].address, 4), () => {
            const error = new Error('Catalogue DNS unavailable'); error.code = 'EAI_AGAIN'; callback(error);
        });
    };
}

const animeSamaAgent = new https.Agent({ keepAlive: true, lookup: createAnimeLookup() });
module.exports = { createAnimeLookup, animeSamaAgent, catalogueAgent: animeSamaAgent, CATALOGUE_HOST };
