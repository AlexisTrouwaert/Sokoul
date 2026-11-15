'use strict';

const RELAY_PATHS = new Set(['native', 'voe', 'fsvid', 'vidzy', 'vidmoly', 'sibnet', 'uqload', 'doodstream', 'lulustream', 'veev', 'vidara', 'seekstreaming', 'cinep', 'kisskh', 'streamed'].map((host) => `/${host}-proxy`));

/** Keep signed relays on the hostname the native client actually configured. Signatures exclude authority. */
function relayAuthority(payload, request) {
    let publicBase;
    let incoming;
    try {
        publicBase = new URL(process.env.PROXIESEMBED_PUBLIC_URL || process.env.PROXY_SERVER_URL || 'http://127.0.0.1:25569');
        const host = typeof request.get === 'function' ? request.get('host') : request.headers?.host;
        incoming = new URL(`${request.protocol === 'https' ? 'https' : 'http'}://${host}`);
        if (incoming.username || incoming.password) return payload;
    } catch { return payload; }
    function rewrite(value) {
        if (typeof value === 'string') {
            try {
                // proxiesembed signs root-relative relay paths when PROXY_BASE is unset; native players need an origin.
                const relative = value.startsWith('/') && !value.startsWith('//');
                const url = new URL(value, publicBase);
                if ((relative || url.origin === publicBase.origin) && RELAY_PATHS.has(url.pathname) && url.searchParams.has('sig') && url.searchParams.has('exp')) {
                    url.hostname = incoming.hostname;
                    url.protocol = incoming.protocol;
                    return url.href;
                }
            } catch { /* Most catalogue strings are titles, not URLs. */ }
            return value;
        }
        if (Array.isArray(value)) return value.map(rewrite);
        if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, rewrite(item)]));
        return value;
    }
    return rewrite(payload);
}

module.exports = { relayAuthority };
