#!/usr/bin/env python3
"""Probe a paired relay without publishing clips or clearing existing history."""

import asyncio
import json
from pathlib import Path
import socket
import ssl
import sys
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "linux"))
from live_config import load_config, authorization
from syncclip.core import Crypto
import websockets

root = Path(__file__).resolve().parents[1]
config = load_config()
base = config["url"]
parsed = urlparse(base)
crypto = Crypto(config["key"])


def request(path, method="GET", data=None, authorized=False, headers=None):
    supplied = dict(headers or {})
    if authorized:
        supplied["Authorization"] = authorization(config)
    req = Request(base + path, method=method, data=data, headers=supplied)
    try:
        with urlopen(req, timeout=60) as response:
            return response.status, response.read(1024)
    except HTTPError as error:
        return error.code, error.read(1024)


def header_probe(path, framing):
    with socket.create_connection(
        (parsed.hostname, parsed.port or 443), timeout=10
    ) as sock:
        with ssl.create_default_context().wrap_socket(
            sock, server_hostname=parsed.hostname
        ) as conn:
            message = (
                "GET "
                + parsed.path
                + path
                + " HTTP/1.1\r\nHost: "
                + parsed.netloc
                + "\r\n"
                + framing
                + "\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n"
            )
            conn.sendall(message.encode())
            first = conn.recv(4096).split(b"\r\n", 1)[0]
            assert b" 413 " in first, first


async def main():
    for method in ("GET", "HEAD"):
        assert request("/healthz", method)[0] == 200
    assert request("/v1/ws")[0] == 401
    assert (
        request(
            "/v1/ws", authorized=True, headers={"Origin": "https://example.invalid"}
        )[0]
        == 403
    )
    for path in ("/healthz", "/v1/ws"):
        header_probe(path, "Content-Length: 1048577")
        header_probe(path, "Transfer-Encoding: chunked")
        assert request(path, "POST", b"")[0] == 405
    assert request("/v1/blobs/" + str(uuid.uuid4()), "POST", b"")[0] == 405
    assert request("/v1/blobs/" + str(uuid.uuid4()), "PUT", b"x" * 16)[0] == 401
    print(
        "PASS: health GET/HEAD, authentication/origin checks, body framing and method limits"
    )
    for version in (ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_3):
        context = ssl.create_default_context()
        context.minimum_version = context.maximum_version = version
        with socket.create_connection(
            (parsed.hostname, parsed.port or 443), timeout=10
        ) as sock:
            with context.wrap_socket(sock, server_hostname=parsed.hostname):
                pass
    print("PASS: verified certificate/hostname with TLS 1.2 and TLS 1.3")
    ids = []
    async with websockets.connect(
        base.replace("https://", "wss://", 1) + "/v1/ws",
        extra_headers={"Authorization": authorization(config)},
        max_size=32 * 1024 * 1024,
        open_timeout=15,
    ) as ws:
        hello = json.loads(await ws.recv())
        assert hello["type"] == "hello"
        epoch = hello.get("epoch", 0)
        headers = {"X-Syncclip-Epoch": str(epoch)}
        try:
            fixed = str(uuid.uuid4())
            ids.append(fixed)
            maximum = 32 * 1024 * 1024 + 16
            assert (
                request("/v1/blobs/" + fixed, "PUT", bytes(maximum), True, headers)[0]
                == 201
            )
            # Unpublished attachment bytes must never be available for download.
            assert request("/v1/blobs/" + fixed, authorized=True)[0] == 404
            chunked = str(uuid.uuid4())
            ids.append(chunked)
            assert (
                request(
                    "/v1/blobs/" + chunked,
                    "PUT",
                    iter([bytes(8), bytes(8)]),
                    True,
                    headers,
                )[0]
                == 201
            )
            oversized = str(uuid.uuid4())
            assert (
                request(
                    "/v1/blobs/" + oversized, "PUT", bytes(maximum + 1), True, headers
                )[0]
                == 413
            )
            print(
                "PASS: authenticated WebSocket, exact upload maximum, chunked upload and oversized rejection"
            )
        finally:
            # Only delete UUIDs generated here, never publish or clear real clips.
            for clip_id in ids:
                await ws.send(
                    json.dumps({"type": "delete", "id": clip_id, "epoch": epoch})
                )
                while True:
                    event = json.loads(await asyncio.wait_for(ws.recv(), 15))
                    if event.get("type") == "deleted" and event.get("id") == clip_id:
                        break
                assert request("/v1/blobs/" + clip_id, authorized=True)[0] == 404
            print(
                "PASS: synthetic upload cleanup; no clips published and existing history preserved"
            )


asyncio.run(main())
