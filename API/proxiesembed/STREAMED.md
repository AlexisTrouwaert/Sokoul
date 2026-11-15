# Streamed relay

Mainapi keeps the catalogue, the VIP check and the extraction of the `embed.st` player. The extraction POST and the
intermediate pages go through its `SOCKS5_PROXIES` pool, rotated through Redis.

Clients receive a URL signed for **proxiesembed** on `/streamed-proxy`. Playlists, segments and keys are downloaded by
`streamed_proxy.py` with a SOCKS5 session drawn at random from `PROXIES_SOCKS5_JSON` for each upstream request, redirects
included. Identical concurrent downloads are coalesced; live playlists are not cached.

The relay keeps the variant with the highest announced bitrate: Streamed's SD and HD variants can have independent
MPEG-TS clocks, and switching between them can freeze playback around 8–12 seconds despite HTTP 200 answers. PNG/WebP
envelopes around segments are removed before delivery.

## Access and configuration

- `MEDIA_SIGNING_SECRET` must be identical in Mainapi and proxiesembed.
- Mainapi exposes the relay through `PROXIESEMBED_PUBLIC_URL`, falling back to the origin of `IPTV_STREAM_PROXY`.
- `SOCKS5_PROXIES` configures extraction in Mainapi; `PROXIES_SOCKS5_JSON` configures media downloads here.
- Without a valid signature: 403 before any network access. Without SOCKS5: 503, never a direct connection.

The signature covers the route, the target and the expiry. Mainapi limits access to two hours and child URLs keep their
parent's exact expiry. The Referer is limited to `https://embed.st/` and `https://exposestrat.com/`; destinations and
redirects are limited to known Streamed CDNs. DNS resolution checks for public addresses before handing the IP to the
SOCKS5 socket; TLS keeps the CDN's name and verifies its certificate.

Players must keep the URL of a child playlist when reloading it, even when the master playlist has a single variant.

## Deployment and old links

Deploy **proxiesembed before Mainapi**. Old `/api/livetv/streamed/media.m3u8` links are still checked by Mainapi, then
redirected (307) to proxiesembed without any media download in Mainapi. Logs tagged `[STREAMED-PROXY]` give the host and
the upstream status or error type, never a signed URL or a SOCKS5 identity.

## Checks

From the repository root, without starting the services:

```powershell
python -m unittest discover -s API/proxiesembed/tests -p test_streamed_proxy.py
node --test API/Mainapi/routes/__tests__/streamedNativeRoutes.test.js
```

They cover Node/Python signatures, refused access, rotation, redirects, DNS, video envelopes and child playlist refresh,
with simulated upstreams.
