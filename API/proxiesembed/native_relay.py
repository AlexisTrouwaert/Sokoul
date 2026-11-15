"""Authenticated native extractor transport and signed direct-egress media relay.

The public route only accepts capabilities minted by internal, VIP-gated callers.
State owns the approved headers; query parameters can never supply upstream headers.
"""

import asyncio
import base64
import json
import re
import secrets
import time
from collections import OrderedDict
from urllib.parse import urlencode, urljoin, urlsplit

import aiohttp
from aiohttp import web
from media_signing import append_signature, is_public_http_url, verify_request, SIGNATURE_TTL, signing_configured
from seekstreaming_utils import has_hls_manifest_signature

MAX_BODY = 4 * 1024 * 1024
MAX_REQUEST = 1024 * 1024
STATE_LIMIT = 512
MAX_REDIRECTS = 5
ALLOWED_HEADERS = {"accept", "accept-language", "content-type", "user-agent", "referer", "origin", "cookie", "range", "authorization", "x-requested-with"}
REPLY_HEADERS = {"content-type", "content-range", "accept-ranges", "last-modified", "etag", "location"}
URI_RE = re.compile(r"""(?i)\bURI=(?:"([^"\r\n]+)"|'([^'\r\n]+)'|([^,\s]+))""")


def public_url(value):
    if not isinstance(value, str) or len(value) > 16384 or re.search(r"[\x00-\x20\x7f]", value):
        raise ValueError("invalid_url")
    parts = urlsplit(value)
    if not is_public_http_url(value) or parts.username or parts.password or parts.fragment or parts.port not in (None, 80, 443):
        raise ValueError("invalid_url")
    return value


def safe_headers(value):
    if value is None:
        return {}
    if not isinstance(value, dict) or len(value) > 16:
        raise ValueError("invalid_headers")
    out = {}
    for key, content in value.items():
        if not isinstance(key, str) or not isinstance(content, str) or len(content) > 2048 or re.search(r"[\r\n\x00]", content):
            raise ValueError("invalid_headers")
        if key.lower() in ALLOWED_HEADERS:
            out[key.lower()] = content
    if sum(len(k) + len(v) for k, v in out.items()) > 8192:
        raise ValueError("invalid_headers")
    return out


class NativeRelay:
    def __init__(self, owner, resolver_factory, public_base, clock=time.monotonic):
        self.owner = owner
        self.resolver_factory = resolver_factory
        self.public_base = public_base.rstrip("/")
        self.clock = clock
        self.states = OrderedDict()
        self.session = None
        owner.app.router.add_post("/api/native-fetch", self.fetch)
        owner.app.router.add_post("/api/native-media", self.register)
        owner.app.router.add_get("/native-proxy", self.media)
        owner.app.on_cleanup.append(self.close)

    async def close(self, _app=None):
        if self.session is not None:
            await self.session.close()

    def get_session(self):
        if self.session is None or self.session.closed:
            connector = aiohttp.TCPConnector(resolver=self.resolver_factory(), family=2, limit=32, ttl_dns_cache=60)
            self.session = aiohttp.ClientSession(connector=connector, trust_env=False, cookie_jar=aiohttp.DummyCookieJar(), timeout=aiohttp.ClientTimeout(total=30, connect=10, sock_read=20))
        return self.session

    async def permitted(self, request):
        denied = self.owner._require_internal(request)
        if denied is not None:
            return denied
        if not await self.owner._check_vip(request):
            return self.owner._vip_denied_response()
        return None

    async def payload(self, request):
        if request.content_length is not None and request.content_length > MAX_REQUEST:
            raise ValueError("request_too_large")
        raw = await request.content.read(MAX_REQUEST + 1)
        if len(raw) > MAX_REQUEST:
            raise ValueError("request_too_large")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("invalid_request")
        return value

    async def open(self, url, method="GET", headers=None, body=None, manual=False):
        current = public_url(url)
        outgoing = dict(headers or {})
        for hop in range(MAX_REDIRECTS + 1):
            response = await self.get_session().request(method, current, headers=outgoing, data=body, allow_redirects=False)
            if manual or response.status not in (301, 302, 303, 307, 308):
                return response
            location = response.headers.get("location")
            if not location:
                return response
            response.close()
            if hop == MAX_REDIRECTS:
                raise ValueError("too_many_redirects")
            following = public_url(urljoin(current, location))
            if (urlsplit(following).scheme, urlsplit(following).netloc) != (urlsplit(current).scheme, urlsplit(current).netloc):
                outgoing.pop("cookie", None)
                outgoing.pop("authorization", None)
            if response.status == 303 or (response.status in (301, 302) and method == "POST"):
                method, body = "GET", None
                outgoing.pop("content-type", None)
            current = following
        raise ValueError("too_many_redirects")

    async def read_prefix(self, response, size=32):
        data = bytearray()
        while len(data) < size:
            chunk = await response.content.read(size - len(data))
            if not chunk:
                break
            data.extend(chunk)
        return bytes(data)

    async def read_bounded(self, response, limit=MAX_BODY):
        data = bytearray()
        async for chunk in response.content.iter_chunked(65536):
            data.extend(chunk)
            if len(data) > limit:
                raise ValueError("response_too_large")
        return bytes(data)

    async def fetch(self, request):
        denied = await self.permitted(request)
        if denied is not None:
            return denied
        response = None
        try:
            payload = await self.payload(request)
            method = str(payload.get("method", "GET")).upper()
            if method not in ("GET", "POST", "HEAD"):
                raise ValueError("invalid_method")
            body = payload.get("body")
            if body is not None and not isinstance(body, str):
                raise ValueError("invalid_body")
            if body is not None and payload.get("encoding") == "base64":
                body = base64.b64decode(body, validate=True)
            if body is not None and len(body.encode("utf-8") if isinstance(body, str) else body) > MAX_REQUEST:
                raise ValueError("request_too_large")
            response = await self.open(payload.get("url"), method, safe_headers(payload.get("headers")), body, payload.get("redirect") == "manual")
            # Extractors use a one-byte range to resolve CDN redirects without consuming a movie.
            one_byte = safe_headers(payload.get("headers")).get("range", "").lower() == "bytes=0-0"
            data = await response.content.read(1) if one_byte else await self.read_bounded(response)
            reply_headers = {k: v for k, v in response.headers.items() if k.lower() in REPLY_HEADERS}
            cookies = response.headers.getall("Set-Cookie", [])
            return web.json_response({"status": response.status, "url": str(response.url), "headers": reply_headers, "setCookies": cookies, "body": base64.b64encode(data).decode("ascii"), "encoding": "base64"})
        except (ValueError, TypeError, json.JSONDecodeError):
            return web.json_response({"error": "invalid_native_request"}, status=400)
        except (aiohttp.ClientError, asyncio.TimeoutError):
            return web.json_response({"error": "native_transport_failed"}, status=502)
        finally:
            if response is not None:
                response.close()

    def prune(self):
        for sid, state in list(self.states.items()):
            if state["until"] <= self.clock():
                del self.states[sid]
        while len(self.states) >= STATE_LIMIT:
            self.states.popitem(last=False)

    def signed(self, sid, target):
        public_url(target)
        route = "/native-proxy"
        query = urlencode({"sid": sid, "url": target})
        return append_signature(f"{self.public_base}{route}?{query}", f"{route}/{sid}", target, ttl=min(SIGNATURE_TTL, 43200))

    async def register(self, request):
        denied = await self.permitted(request)
        if denied is not None:
            return denied
        if not signing_configured():
            return web.json_response({"error": "native_signing_unavailable"}, status=503)
        try:
            payload = await self.payload(request)
            target = public_url(payload.get("url"))
            media_format = payload.get("format")
            if media_format not in ("hls", "mp4"):
                raise ValueError("invalid_format")
            headers = safe_headers(payload.get("headers"))
            self.prune()
            sid = secrets.token_urlsafe(24)
            state = {"until": self.clock() + min(SIGNATURE_TTL, 43200), "headers": headers, "format": media_format, "target": target, "subtitles": set()}
            self.states[sid] = state
            subtitles = []
            for item in payload.get("subtitles", [])[:8]:
                if not isinstance(item, dict):
                    continue
                try:
                    subtitle = public_url(item.get("url"))
                except ValueError:
                    continue
                state["subtitles"].add(subtitle)
                subtitles.append({"url": self.signed(sid, subtitle), "lang": str(item.get("lang", "und"))[:8], "label": str(item.get("label", "Subtitle"))[:80], "format": "srt" if item.get("format") == "srt" else "vtt"})
            return web.json_response({"url": self.signed(sid, target), "format": media_format, "subtitles": subtitles})
        except (ValueError, TypeError, json.JSONDecodeError):
            return web.json_response({"error": "invalid_native_media"}, status=400)

    def rewrite(self, text, base, sid):
        def resource(value):
            return self.signed(sid, public_url(urljoin(base, value)))
        lines = []
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped:
                lines.append(line)
            elif stripped.startswith("#"):
                lines.append(URI_RE.sub(lambda m: 'URI="' + resource(next(v for v in m.groups() if v is not None)) + '"', line))
            else:
                lines.append(resource(stripped))
        return "\n".join(lines) + "\n"

    async def media(self, request):
        sid = request.query.get("sid", "")
        target = request.query.get("url", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{32}", sid) or not verify_request(request, f"/native-proxy/{sid}", target)[0]:
            return web.json_response({"error": "invalid_native_signature"}, status=403)
        state = self.states.get(sid)
        if state is None or state["until"] <= self.clock():
            return web.json_response({"error": "native_media_expired"}, status=410)
        response = None
        stream = None
        try:
            headers = dict(state["headers"])
            if (urlsplit(target).scheme, urlsplit(target).netloc) != (urlsplit(state["target"]).scheme, urlsplit(state["target"]).netloc):
                headers.pop("cookie", None)
                headers.pop("authorization", None)
            range_header = request.headers.get("Range")
            playlist = urlsplit(target).path.lower().endswith(".m3u8") or (target == state["target"] and state["format"] == "hls")
            if range_header and not playlist:
                if not re.fullmatch(r"bytes=\d*-\d*", range_header):
                    raise ValueError("invalid_range")
                headers["range"] = range_header
            else:
                headers.pop("range", None)
            response = await self.open(target, headers=headers)
            if response.status not in (200, 206):
                return web.json_response({"error": "native_upstream_failed"}, status=response.status if response.status in (403, 404, 410, 429) else 502)
            content_type = response.headers.get("Content-Type", "").lower()
            prefix = await self.read_prefix(response)
            if "text/html" in content_type or prefix.lstrip().lower().startswith((b"<!doctype html", b"<html")):
                raise ValueError("invalid_media")
            if playlist or "mpegurl" in content_type or has_hls_manifest_signature(prefix):
                content = prefix + await self.read_bounded(response)
                if len(content) > MAX_BODY or not has_hls_manifest_signature(content):
                    raise ValueError("invalid_manifest")
                rewritten = self.rewrite(content.decode("utf-8-sig"), str(response.url), sid)
                return web.Response(text=rewritten, content_type="application/vnd.apple.mpegurl", headers={"Cache-Control": "no-store"})
            reply = {k: v for k, v in response.headers.items() if k.lower() in REPLY_HEADERS and k.lower() != "location"}
            # aiohttp transparently decompresses responses, so never forward the compressed byte count.
            if not response.headers.get("Content-Encoding") and response.headers.get("Content-Length"):
                reply["Content-Length"] = response.headers["Content-Length"]
            stream = web.StreamResponse(status=response.status, headers=reply)
            await stream.prepare(request)
            if request.method != "HEAD":
                await stream.write(prefix)
                async for chunk in response.content.iter_chunked(65536):
                    await stream.write(chunk)
            await stream.write_eof()
            return stream
        except (ValueError, UnicodeDecodeError, aiohttp.ClientError, asyncio.TimeoutError):
            if stream is not None and stream.prepared:
                return stream
            return web.json_response({"error": "native_media_failed"}, status=502)
        except (ConnectionResetError, ConnectionAbortedError):
            return stream if stream is not None else web.Response(status=499)
        finally:
            if response is not None:
                response.close()
