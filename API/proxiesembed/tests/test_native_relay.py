"""Offline native transport/relay capabilities, bounds, and media rewriting."""
import asyncio
import ast
import socket
from typing import Optional
import base64
from pathlib import Path
import sys
import unittest
from urllib.parse import parse_qs, urlsplit

import aiohttp
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from aiohttp.abc import AbstractResolver
from aiohttp.resolver import DefaultResolver
from multidict import CIMultiDict
from yarl import URL

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import media_signing
from native_relay import NativeRelay, MAX_BODY, public_url
from seekstreaming_utils import validate_seekstreaming_resolved_address


class Content:
    def __init__(self, data):
        self.data = data
        self.read_bytes = 0
    async def read(self, count=-1):
        count = len(self.data) if count < 0 else count
        chunk, self.data = self.data[:count], self.data[count:]
        self.read_bytes += len(chunk)
        return chunk
    async def iter_chunked(self, count):
        while self.data:
            yield await self.read(count)


class Response:
    def __init__(self, url, data=b"", status=200, headers=None):
        self.url = URL(url)
        self.status = status
        self.headers = CIMultiDict(headers or {})
        self.content = Content(data)
        self.closed = False
    def close(self):
        self.closed = True


class Session:
    closed = False
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []
    async def request(self, method, url, **options):
        self.calls.append((method, url, options))
        if not self.responses:
            raise AssertionError("Unexpected network request")
        return self.responses.pop(0)
    async def close(self):
        self.closed = True


class Owner:
    def __init__(self):
        self.app = web.Application()
    def _require_internal(self, request):
        return None if request.headers.get("x-internal-key") == "internal" else web.json_response({"error": "internal"}, status=403)
    async def _check_vip(self, request):
        return request.headers.get("x-access-key") == "vip"
    def _vip_denied_response(self):
        return web.json_response({"error": "vip"}, status=403)


class NativeRelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.old_secret = media_signing.SIGNING_SECRET
        media_signing.SIGNING_SECRET = "fixture-signing-secret"
        self.owner = Owner()
        self.now = 100.0
        self.relay = NativeRelay(self.owner, lambda: None, "http://proxy.test", clock=lambda: self.now)
        self.relay.session = Session([])
        self.client = TestClient(TestServer(self.owner.app))
        await self.client.start_server()
        self.auth = {"x-internal-key": "internal", "x-access-key": "vip"}
    async def asyncTearDown(self):
        await self.client.close()
        media_signing.SIGNING_SECRET = self.old_secret
    async def register(self, **kwargs):
        response = await self.client.post("/api/native-media", headers=self.auth, json={"url": "https://cdn.example/master.m3u8", "format": "hls", **kwargs})
        self.assertEqual(200, response.status)
        return await response.json()
    def path(self, value):
        split = urlsplit(value)
        return split.path + "?" + split.query
    async def test_internal_and_vip_are_both_required(self):
        for headers in ({}, {"x-internal-key": "internal"}, {"x-access-key": "vip"}):
            response = await self.client.post("/api/native-fetch", headers=headers, json={"url": "https://embed.example/"})
            self.assertEqual(403, response.status)
        self.assertEqual([], self.relay.session.calls)
    async def test_fetch_caps_body_and_does_not_forward_internal_credentials(self):
        upstream = Response("https://embed.example/", b"fixture", headers={"Content-Type": "text/html", "Set-Cookie": "gate=1; Secure"})
        self.relay.session.responses = [upstream]
        response = await self.client.post("/api/native-fetch", headers=self.auth, json={"url": "https://embed.example/", "headers": {"x-internal-key": "never-upstream", "Referer": "https://embed.example/"}})
        payload = await response.json()
        self.assertEqual(b"fixture", base64.b64decode(payload["body"]))
        self.assertEqual(["gate=1; Secure"], payload["setCookies"])
        self.assertNotIn("x-internal-key", self.relay.session.calls[0][2]["headers"])
        self.assertTrue(upstream.closed)
        self.relay.session.responses = [Response("https://embed.example/", b"x" * (MAX_BODY + 1))]
        response = await self.client.post("/api/native-fetch", headers=self.auth, json={"url": "https://embed.example/"})
        self.assertEqual(400, response.status)
    async def test_one_byte_probe_closes_ignored_range_without_download(self):
        upstream = Response("https://cdn.example/video.mp4", b"x" * 100000, headers={"Content-Length": "100000"})
        self.relay.session.responses = [upstream]
        response = await self.client.post("/api/native-fetch", headers=self.auth, json={"url": "https://cdn.example/video.mp4", "headers": {"Range": "bytes=0-0"}})
        self.assertEqual(b"x", base64.b64decode((await response.json())["body"]))
        self.assertEqual(1, upstream.content.read_bytes)
        self.assertTrue(upstream.closed)
    async def test_private_redirect_is_rejected_and_public_cross_origin_drops_secrets(self):
        first = Response("https://embed.example/", status=302, headers={"Location": "http://127.0.0.1/private"})
        self.relay.session.responses = [first]
        response = await self.client.post("/api/native-fetch", headers=self.auth, json={"url": "https://embed.example/"})
        self.assertEqual(400, response.status)
        self.assertEqual(1, len(self.relay.session.calls))
        self.assertTrue(first.closed)
        self.relay.session.responses = [Response("https://embed.example/", status=302, headers={"Location": "https://cdn.example/file"}), Response("https://cdn.example/file", b"ok")]
        response = await self.client.post("/api/native-fetch", headers=self.auth, json={"url": "https://embed.example/", "headers": {"Cookie": "gate=1", "Authorization": "Bearer fixture"}})
        self.assertEqual(200, response.status)
        self.assertNotIn("cookie", self.relay.session.calls[-1][2]["headers"])
        self.assertNotIn("authorization", self.relay.session.calls[-1][2]["headers"])
    async def test_public_only_resolver_rejects_private_dns_answers(self):
        tree = ast.parse((Path(__file__).resolve().parents[1] / "server.py").read_text(encoding="utf-8-sig"))
        declaration = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "PublicOnlyResolver")
        namespace = {"AbstractResolver": AbstractResolver, "DefaultResolver": DefaultResolver, "Optional": Optional, "socket": socket, "validate_seekstreaming_resolved_address": validate_seekstreaming_resolved_address}
        exec(compile(ast.fix_missing_locations(ast.Module(body=[declaration], type_ignores=[])), "server.py", "exec"), namespace)
        class Delegate:
            async def resolve(self, *args):
                return [{"host": "127.0.0.1"}]
            async def close(self):
                pass
        resolver = namespace["PublicOnlyResolver"](Delegate())
        with self.assertRaises(OSError):
            await resolver.resolve("cdn.example", 443)

    async def test_child_on_another_origin_does_not_receive_origin_credentials(self):
        result = await self.register(headers={"Cookie": "private=fixture", "Authorization": "Bearer fixture", "User-Agent": "fixture-agent"})
        state_id = parse_qs(urlsplit(result["url"]).query)["sid"][0]
        child = self.relay.signed(state_id, "https://other.example/seg.ts")
        self.relay.session.responses = [Response("https://other.example/seg.ts", b"segment", headers={"Content-Type": "video/mp2t"})]
        response = await self.client.get(self.path(child))
        self.assertEqual(200, response.status)
        await response.read()
        headers = self.relay.session.calls[-1][2]["headers"]
        self.assertNotIn("cookie", headers)
        self.assertNotIn("authorization", headers)
        self.assertEqual("fixture-agent", headers["user-agent"])

    async def test_signature_binds_state_and_target_and_expires_with_state(self):
        first = await self.register()
        second = await self.register()
        first_query = parse_qs(urlsplit(first["url"]).query)
        second_sid = parse_qs(urlsplit(second["url"]).query)["sid"][0]
        forged = self.path(first["url"]).replace(first_query["sid"][0], second_sid)
        response = await self.client.get(forged)
        self.assertEqual(403, response.status)
        response = await self.client.get(self.path(first["url"]).replace("master.m3u8", "other.m3u8"))
        self.assertEqual(403, response.status)
        self.now += 43201
        response = await self.client.get(self.path(first["url"]))
        self.assertEqual(410, response.status)
        self.assertEqual([], self.relay.session.calls)
    async def test_hls_rewrites_child_playlist_keys_and_segments_with_same_state(self):
        result = await self.register(headers={"Referer": "https://vidara.to/", "Cookie": "fixture=1"})
        self.relay.session.responses = [Response("https://cdn.example/master.m3u8", b'#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.key"\n720/index.m3u8\n', headers={"Content-Type": "application/vnd.apple.mpegurl"})]
        response = await self.client.get(self.path(result["url"]))
        self.assertEqual(200, response.status)
        rewritten = await response.text()
        sid = parse_qs(urlsplit(result["url"]).query)["sid"][0]
        self.assertIn("sid=" + sid, rewritten)
        self.assertIn("%2Fkey.key", rewritten)
        child = rewritten.splitlines()[-1]
        self.relay.session.responses = [Response("https://cdn.example/720/index.m3u8", b"#EXTM3U\n#EXTINF:5,\nseg0.ts\n#EXT-X-ENDLIST\n")]
        response = await self.client.get(self.path(child))
        self.assertEqual(200, response.status)
        self.assertIn("%2F720%2Fseg0.ts", await response.text())
        self.assertEqual("fixture=1", self.relay.session.calls[-1][2]["headers"]["cookie"])
    async def test_manifest_cannot_mint_private_target_capabilities(self):
        result = await self.register()
        self.relay.session.responses = [Response("https://cdn.example/master.m3u8", b"#EXTM3U\nhttp://169.254.169.254/latest/meta-data\n")]
        response = await self.client.get(self.path(result["url"]))
        self.assertEqual(502, response.status)
    async def test_mp4_range_and_subtitle_capability_preserve_headers(self):
        result = await self.register(url="https://cdn.example/movie.mp4", format="mp4", headers={"Referer": "https://vidara.to/"}, subtitles=[{"url": "https://cdn.example/fr.vtt", "lang": "fr"}])
        self.assertEqual("fr", result["subtitles"][0]["lang"])
        upstream = Response("https://cdn.example/movie.mp4", b"data", status=206, headers={"Content-Type": "video/mp4", "Content-Range": "bytes 0-3/100", "Content-Length": "4"})
        self.relay.session.responses = [upstream]
        response = await self.client.get(self.path(result["url"]), headers={"Range": "bytes=0-3"})
        self.assertEqual(206, response.status)
        self.assertEqual("bytes 0-3/100", response.headers["Content-Range"])
        self.assertEqual(b"data", await response.read())
        self.assertEqual("bytes=0-3", self.relay.session.calls[-1][2]["headers"]["range"])
        self.assertTrue(upstream.closed)
    async def test_manual_redirect_returns_location_without_following(self):
        self.relay.session.responses = [Response("https://embed.example/", status=302, headers={"Location": "https://cdn.example/file"})]
        response = await self.client.post("/api/native-fetch", headers=self.auth, json={"url": "https://embed.example/", "redirect": "manual"})
        self.assertEqual(302, (await response.json())["status"])
        self.assertEqual(1, len(self.relay.session.calls))
    def test_public_url_rejects_non_public_and_credential_urls(self):
        for value in ("http://127.1/", "http://localhost/", "http://10.0.0.1/", "https://user:secret@cdn.example/", "http://[::1]/", "https://cdn.example:8080/", "https://cdn.example/a\nInjected"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                public_url(value)


if __name__ == "__main__":
    unittest.main()
