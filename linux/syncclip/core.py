"""Platform-independent wire protocol and durable encrypted outbox."""

import asyncio
import base64
import hashlib
import hmac
import json
import mimetypes
import shutil
import os
from pathlib import Path
import random
import re
import threading
import tempfile
import time
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import websockets

MAX_TEXT = 64 * 1024
MAX_IMAGE = 8 * 1024 * 1024
MAX_FILE = 32 * 1024 * 1024
IMAGE_TYPES = {"image/png", "image/jpeg", "image/webp"}
TTL_MS = 24 * 60 * 60 * 1000


def b64(data):
    return base64.b64encode(data).decode("ascii")


def image_type(data):
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    raise ValueError("Choose a PNG, JPEG, or WebP image")


def safe_filename(name):
    name = str(name).replace("\\", "/").rsplit("/", 1)[-1]
    name = "".join(c for c in name if c.isprintable() and c not in "/\\")[:180].strip()
    return name if name not in ("", ".", "..") else "Shared file"


def file_mime(name):
    return mimetypes.guess_type(name)[0] or "application/octet-stream"


def parse_pairing(value):
    uri = urlparse(value.strip())
    if uri.scheme != "syncclip" or uri.netloc != "pair":
        raise ValueError("Paste a Syncclip pairing link")
    query = parse_qs(uri.query)
    url = query.get("url", [""])[0].rstrip("/")
    parsed = urlparse(url)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("The server must be an HTTPS address")
    key = query.get("key", [""])[0]
    raw = base64.urlsafe_b64decode(key + "=" * (-len(key) % 4))
    if len(raw) != 32:
        raise ValueError("Invalid pairing key")
    config = {"url": url, "key": base64.urlsafe_b64encode(raw).decode().rstrip("=")}
    invite = query.get("invite", [""])[0]
    if invite:
        if not re.fullmatch(r"[a-f0-9]{64}", invite):
            raise ValueError("Invalid pairing invitation")
        config["invite"] = invite
    return config


def pairing_link(config):
    return "syncclip://pair?" + urlencode(
        {k: config[k] for k in ("url", "key", "invite") if config.get(k)}
    )


class Crypto:
    def __init__(self, key):
        raw = base64.urlsafe_b64decode(key + "=" * (-len(key) % 4))
        if len(raw) != 32:
            raise ValueError("Invalid pairing key")
        derive = lambda label: hmac.new(raw, label.encode(), hashlib.sha256).digest()
        self.authorization = "Bearer " + derive("syncclip-auth-v1").hex()
        self.aes = AESGCM(derive("syncclip-encryption-v1"))

    def encrypt(self, text, device):
        if not text or len(text.encode()) > MAX_TEXT:
            raise ValueError("Send between 1 byte and 64 KB of text")
        return self._envelope({"text": text}, device)

    def _envelope(self, value, device, clip_id=None):
        clip_id, nonce = clip_id or str(uuid.uuid4()), os.urandom(12)
        payload = json.dumps(
            dict(value, device=device[:80], sent=int(time.time() * 1000)),
            ensure_ascii=False,
        ).encode()
        encrypted = self.aes.encrypt(
            nonce, payload, ("syncclip-v1:" + clip_id).encode()
        )
        return {"id": clip_id, "nonce": b64(nonce), "ciphertext": b64(encrypted)}

    def encrypt_image(self, data, device):
        if not data or len(data) > MAX_IMAGE:
            raise ValueError("Images can be up to 8 MB")
        mime = image_type(data)
        clip_id, nonce = str(uuid.uuid4()), os.urandom(12)
        encrypted = self.aes.encrypt(
            nonce, data, ("syncclip-image-v1:" + clip_id).encode()
        )
        clip = self._envelope(
            {
                "kind": "image",
                "mime": mime,
                "bytes": len(data),
                "image_nonce": b64(nonce),
            },
            device,
            clip_id,
        )
        clip["blob"] = clip_id
        return clip, encrypted

    def encrypt_file(self, data, name, device, mime=None):
        if len(data) > MAX_FILE:
            raise ValueError("Files can be up to 32 MB")
        name = safe_filename(name)
        clip_id, nonce = str(uuid.uuid4()), os.urandom(12)
        encrypted = self.aes.encrypt(
            nonce, data, ("syncclip-file-v1:" + clip_id).encode()
        )
        clip = self._envelope(
            {
                "kind": "file",
                "name": name,
                "mime": mime or file_mime(name),
                "bytes": len(data),
                "file_nonce": b64(nonce),
            },
            device,
            clip_id,
        )
        clip["blob"] = clip_id
        return clip, encrypted

    def decrypt_file(self, clip, encrypted):
        value = self.decrypt(clip)
        if value.get("kind") != "file" or len(encrypted) != value["bytes"] + 16:
            raise ValueError("Invalid file attachment")
        return self.aes.decrypt(
            base64.b64decode(value["file_nonce"], validate=True),
            encrypted,
            ("syncclip-file-v1:" + clip["id"]).encode(),
        )

    def decrypt_attachment(self, clip, encrypted):
        return (
            self.decrypt_file(clip, encrypted)
            if self.decrypt(clip).get("kind") == "file"
            else self.decrypt_image(clip, encrypted)
        )

    def decrypt_image(self, clip, encrypted):
        value = self.decrypt(clip)
        if value.get("kind") != "image" or len(encrypted) != value["bytes"] + 16:
            raise ValueError("Invalid image attachment")
        data = self.aes.decrypt(
            base64.b64decode(value["image_nonce"], validate=True),
            encrypted,
            ("syncclip-image-v1:" + clip["id"]).encode(),
        )
        if image_type(data) != value["mime"]:
            raise ValueError("Image format does not match its description")
        return data

    def decrypt(self, clip):
        payload = self.aes.decrypt(
            base64.b64decode(clip["nonce"], validate=True),
            base64.b64decode(clip["ciphertext"], validate=True),
            ("syncclip-v1:" + clip["id"]).encode(),
        )
        value = json.loads(payload)
        if value.get("kind") == "file":
            if (
                not isinstance(value.get("device"), str)
                or not isinstance(value.get("name"), str)
                or safe_filename(value["name"]) != value["name"]
                or not isinstance(value.get("mime"), str)
                or not re.fullmatch(
                    r"[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+", value["mime"]
                )
                or not isinstance(value.get("bytes"), int)
                or not 0 <= value["bytes"] <= MAX_FILE
                or clip.get("blob") != clip["id"]
                or len(base64.b64decode(value.get("file_nonce", ""), validate=True))
                != 12
            ):
                raise ValueError("Invalid file description")
            return value
        if value.get("kind") == "image":
            if (
                not isinstance(value.get("device"), str)
                or value.get("mime") not in IMAGE_TYPES
                or not isinstance(value.get("bytes"), int)
                or not 0 < value["bytes"] <= MAX_IMAGE
                or clip.get("blob") != clip["id"]
                or len(base64.b64decode(value.get("image_nonce", ""), validate=True))
                != 12
            ):
                raise ValueError("Invalid image description")
            return value
        if (
            not isinstance(value.get("text"), str)
            or len(value["text"].encode()) > MAX_TEXT
            or not isinstance(value.get("device"), str)
        ):
            raise ValueError("Invalid clipboard content")
        return value


def atomic_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temp = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as file:
            json.dump(data, file)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp, path)
    finally:
        Path(temp).unlink(missing_ok=True)


class Store:
    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.RLock()
        self.data = {
            "history": [],
            "pending": [],
            "last_id": "",
            "epoch": 0,
            "deleted": [],
        }
        if self.path.exists():
            self.data.update(json.loads(self.path.read_text()))
        self.prune()

    def save(self):
        atomic_json(self.path, self.data)

    def blob_path(self, clip_id):
        if not re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            clip_id,
        ):
            raise ValueError("Invalid image ID")
        return self.path.parent / "images" / clip_id

    def put_blob(self, clip_id, data):
        if len(data) > MAX_FILE + 16:
            raise ValueError("Files can be up to 32 MB")
        with self.lock:
            if clip_id in self.data["deleted"]:
                raise ValueError("This clip was deleted")
            path = self.blob_path(clip_id)
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            temp = path.with_suffix(".tmp")
            with os.fdopen(
                os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "wb"
            ) as file:
                file.write(data)
                file.flush()
                os.fsync(file.fileno())
            os.replace(temp, path)

    def prune(self):
        with self.lock:
            cutoff = int(time.time() * 1000) - TTL_MS
            for key in ("history", "pending"):
                self.data[key] = [
                    c for c in self.data[key] if c.get("created", 0) > cutoff
                ][-50:]
            self.save()
            retained = {
                c.get("blob") for k in ("history", "pending") for c in self.data[k]
            }
            folder = self.path.parent / "images"
            if folder.exists():
                for file in folder.iterdir():
                    if (
                        file.name not in retained
                        and file.is_file()
                        and time.time() - file.stat().st_mtime > 3600
                    ):
                        file.unlink(missing_ok=True)

            exports = self.path.parent / "exports"
            if exports.exists():
                for folder in exports.iterdir():
                    if folder.is_dir() and folder.name not in retained:
                        shutil.rmtree(folder, ignore_errors=True)

    def queue(self, clip):
        with self.lock:
            self.prune()
            if len(self.data["pending"]) >= 20:
                raise ValueError("20 clips are waiting. Reconnect before sending more.")
            if clip["id"] in self.data["deleted"]:
                raise ValueError("This clip was deleted")
            clip = dict(clip, created=int(time.time() * 1000))
            self.data["pending"].append(clip)
            self.save()

    def acknowledge(self, clip_id):
        with self.lock:
            self.data["pending"] = [
                c for c in self.data["pending"] if c["id"] != clip_id
            ]
            self.save()

    def remember(self, clips):
        with self.lock:
            by_id = {c["id"]: c for c in self.data["history"]}
            by_id.update(
                {c["id"]: c for c in clips if c["id"] not in self.data["deleted"]}
            )
            self.data["history"] = sorted(
                by_id.values(), key=lambda c: c.get("seq", 0)
            )[-50:]
            self.prune()

    def export_file(self, clip_id, name, data):
        with self.lock:
            if not self.contains(clip_id):
                raise ValueError("This file was removed")
            folder = self.path.parent / "exports" / clip_id
            folder.mkdir(parents=True, exist_ok=True, mode=0o700)
            path = folder / safe_filename(name)
            with os.fdopen(
                os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "wb"
            ) as output:
                output.write(data)
            return path

    def remove(self, clip_id):
        with self.lock:
            self.data["deleted"] = list(set(self.data["deleted"]) | {clip_id})
            for key in ("history", "pending"):
                self.data[key] = [c for c in self.data[key] if c["id"] != clip_id]
            self.blob_path(clip_id).unlink(missing_ok=True)
            shutil.rmtree(self.path.parent / "exports" / clip_id, ignore_errors=True)
            if self.data["last_id"] == clip_id:
                self.data["last_id"] = ""
            self.save()

    def reconcile(self, epoch, deleted):
        with self.lock:
            if epoch != self.data["epoch"]:
                self.data.update(history=[], pending=[], last_id="", epoch=epoch)
                for folder in ("images", "exports"):
                    shutil.rmtree(self.path.parent / folder, ignore_errors=True)
            self.data["deleted"] = list(deleted)
            for clip_id in deleted:
                self.remove(clip_id)
            self.save()

    def contains(self, clip_id):
        with self.lock:
            return any(
                c["id"] == clip_id for k in ("history", "pending") for c in self.data[k]
            )

    def mark_applied(self, clip_id):
        with self.lock:
            self.data["last_id"] = clip_id
            self.save()

    def snapshot(self):
        with self.lock:
            return json.loads(json.dumps(self.data))


class DeviceDisconnected(ValueError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class SyncClient:
    def __init__(
        self,
        config,
        store,
        on_clip,
        on_status,
        on_history,
        on_config=None,
        on_revoked=None,
    ):
        self.config, self.store = config, store
        self.on_config = on_config or (lambda: None)
        self.on_revoked = on_revoked or (lambda: None)
        self.device_lock = threading.RLock()
        self.opener = build_opener(NoRedirect())
        self.crypto = Crypto(config["key"])
        self.on_clip, self.on_status, self.on_history = on_clip, on_status, on_history
        self.stopped = threading.Event()
        self.loop = None
        self.socket = None
        self.wake = None
        self.thread = threading.Thread(
            target=lambda: asyncio.run(self.run()), daemon=True
        )

    @property
    def authorization(self):
        if not self.config.get("device_token"):
            raise ValueError("Pair again using a new invitation")
        return "Bearer " + self.config["device_token"]

    def api(self, path, method="GET", data=None, authorization=None):
        request = Request(
            self.config["url"] + path,
            method=method,
            data=json.dumps(data).encode()
            if data is not None
            else (b"" if method == "POST" else None),
            headers={
                "Authorization": authorization or self.authorization,
                "Content-Type": "application/json",
            },
        )
        with self.opener.open(request, timeout=12) as response:
            body = response.read(64 * 1024)
            return json.loads(body) if body else {}

    def ensure_device(self):
        with self.device_lock:
            if self.config.get("revoked"):
                raise DeviceDisconnected(
                    "Device disconnected. Pair again with a new invitation."
                )
            if not self.config.get("device_token"):
                if not self.config.get("invite"):
                    raise ValueError("Pair again using a new invitation")
                self.config.update(
                    device_id=str(uuid.uuid4()), device_token=os.urandom(32).hex()
                )
                self.on_config()
            try:
                result = self.api("/v1/devices")
            except HTTPError as error:
                if error.code != 401:
                    raise
                if self.config.get("enrolled"):
                    raise DeviceDisconnected(
                        "Device disconnected. Pair again with a new invitation."
                    ) from None
                if not self.config.get("invite"):
                    raise ValueError("Pair again using a new invitation") from None
                self.api(
                    "/v1/enroll",
                    "POST",
                    {
                        "id": self.config["device_id"],
                        "token": self.config["device_token"],
                        "name": self.config.get("device", "Linux")[:80],
                        "platform": "linux",
                    },
                    "Bearer " + self.config["invite"],
                )
                result = self.api("/v1/devices")
            changed = not self.config.get("enrolled") or "invite" in self.config
            self.config["enrolled"] = True
            self.config.pop("invite", None)
            if changed:
                self.on_config()
            return result

    def devices(self):
        return self.ensure_device()

    def invite(self):
        self.ensure_device()
        invitation = self.api("/v1/invitations", "POST")
        return pairing_link(dict(self.config, invite=invitation["invite"]))

    def disconnect_device(self, device_id):
        if not re.fullmatch(r"[a-f0-9-]{36}", device_id):
            raise ValueError("Invalid device")
        self.api("/v1/devices/" + device_id, "DELETE")

    def start(self):
        self.thread.start()

    def stop(self):
        self.stopped.set()
        if self.loop and self.loop.is_running():
            if self.socket:
                asyncio.run_coroutine_threadsafe(self.socket.close(), self.loop)
            if self.wake:
                self.loop.call_soon_threadsafe(self.wake.set)

    def send(self, text):
        if self.config.get("revoked"):
            raise DeviceDisconnected(
                "Device disconnected. Pair again with a new invitation."
            )
        clip = self.crypto.encrypt(text, self.config.get("device", "Linux"))
        self.store.queue(clip)
        if self.loop and self.wake:
            self.loop.call_soon_threadsafe(self.wake.set)
        self.on_history()

    def send_image(self, data):
        if self.config.get("revoked"):
            raise DeviceDisconnected(
                "Device disconnected. Pair again with a new invitation."
            )
        clip, encrypted = self.crypto.encrypt_image(
            data, self.config.get("device", "Linux")
        )
        self.store.put_blob(clip["id"], encrypted)
        self.store.queue(clip)
        if self.loop and self.wake:
            self.loop.call_soon_threadsafe(self.wake.set)
        self.on_history()

    def send_file(self, path):
        if self.config.get("revoked"):
            raise DeviceDisconnected(
                "Device disconnected. Pair again with a new invitation."
            )
        path = Path(path)
        if not path.is_file():
            raise ValueError("Choose a file, not a folder")
        with path.open("rb") as file:
            data = file.read(MAX_FILE + 1)
        clip, encrypted = self.crypto.encrypt_file(
            data, path.name, self.config.get("device", "Linux")
        )
        self.store.put_blob(clip["id"], encrypted)
        self.store.queue(clip)
        if self.loop and self.wake:
            self.loop.call_soon_threadsafe(self.wake.set)
        self.on_history()

    def clear_history(self, clip_id=None):
        if not self.loop or not self.socket:
            raise ValueError("Reconnect to clear shared history")
        message = {
            "type": "delete" if clip_id else "clear",
            "id": clip_id or str(uuid.uuid4()),
            "epoch": self.store.snapshot()["epoch"],
        }
        future = asyncio.run_coroutine_threadsafe(
            self.socket.send(json.dumps(message)), self.loop
        )

        def sent(result):
            try:
                result.result()
            except Exception:
                self.on_status("Could not clear history. Reconnect and retry.")

        future.add_done_callback(sent)
        self.on_status("Clearing shared history…")

    def upload_image(self, clip):
        encrypted = self.store.blob_path(clip["id"]).read_bytes()
        request = Request(
            self.config["url"] + "/v1/blobs/" + clip["id"],
            data=encrypted,
            method="PUT",
            headers={
                "Authorization": self.authorization,
                "Content-Type": "application/octet-stream",
                "X-Syncclip-Epoch": str(self.store.snapshot()["epoch"]),
            },
        )
        with self.opener.open(request, timeout=45) as response:
            response.read(1024)

    def load_image(self, clip):
        epoch = self.store.snapshot()["epoch"]
        path = self.store.blob_path(clip["id"])
        if path.exists():
            encrypted = path.read_bytes()
        else:
            request = Request(
                self.config["url"] + "/v1/blobs/" + clip["id"],
                headers={"Authorization": self.authorization},
            )
            with self.opener.open(request, timeout=45) as response:
                encrypted = response.read(MAX_FILE + 17)
            # Authenticate before caching an attachment received from the network.
            self.crypto.decrypt_attachment(clip, encrypted)
            with self.store.lock:
                if epoch != self.store.data["epoch"]:
                    raise ValueError("History was cleared")
                self.store.put_blob(clip["id"], encrypted)
        return self.crypto.decrypt_attachment(clip, encrypted)

    async def transmit(self, ws):
        uploaded = set()
        uploads = {}
        retry_at = {}
        slots = asyncio.Semaphore(2)

        async def upload(clip):
            clip_id = clip["id"]
            try:
                async with slots:
                    await asyncio.to_thread(self.upload_image, clip)
                uploaded.add(clip_id)
                self.wake.set()
            except Exception:
                retry_at[clip_id] = self.loop.time() + 15
                self.on_status("File waiting · retrying upload")
            finally:
                uploads.pop(clip_id, None)

        try:
            while not self.stopped.is_set():
                self.wake.clear()
                self.store.prune()
                for clip in self.store.snapshot()["pending"]:
                    clip_id = clip["id"]
                    if clip.get("blob") and clip_id not in uploaded:
                        if clip_id not in uploads and self.loop.time() >= retry_at.get(
                            clip_id, 0
                        ):
                            uploads[clip_id] = asyncio.create_task(upload(clip))
                        continue
                    if self.store.contains(clip_id):
                        await ws.send(
                            json.dumps(
                                {
                                    "type": "publish",
                                    "clip": clip,
                                    "epoch": self.store.snapshot()["epoch"],
                                }
                            )
                        )
                # Text can publish while attachments upload on separate connections.
                # Resending until acknowledgement covers lost ACKs via server deduplication.
                waiter = asyncio.create_task(self.wake.wait())
                try:
                    await asyncio.wait({waiter}, timeout=15)
                finally:
                    waiter.cancel()
                    await asyncio.gather(waiter, return_exceptions=True)
        finally:
            pending = list(uploads.values())
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)

    def receive(self, message):
        kind = message.get("type")
        if kind == "hello":
            self.store.reconcile(message.get("epoch", 0), message.get("deleted", []))
        if kind == "cleared":
            self.store.reconcile(message["epoch"], [])
            self.on_status("Shared history cleared")
            self.on_history()
            return
        if kind == "deleted":
            self.store.remove(message["id"])
            self.on_status("Clip deleted")
            self.on_history()
            return
        if kind == "ack":
            self.store.acknowledge(message["id"])
            self.on_history()
            return
        if kind == "error":
            self.on_status(message.get("error", "Sync unavailable"))
            return
        clips = (
            message.get("clips", [])
            if kind == "hello"
            else [message["clip"]]
            if kind == "clip"
            else []
        )
        if not clips:
            return
        valid = []
        for clip in clips:
            try:
                content = self.crypto.decrypt(clip)
                if clip["id"] in self.store.snapshot()["deleted"]:
                    continue
                valid.append((clip, content))
            except Exception:
                self.on_status("A clip could not be decrypted. Check the pairing key.")
        if not valid:
            return
        previous = self.store.snapshot()
        own_ids = {c["id"] for c in previous["pending"]}
        self.store.remember([c for c, _ in valid])
        clip, content = valid[-1]
        awaiting_image = False
        if clip["id"] != previous["last_id"] and clip["id"] not in own_ids:
            # Pending local edits take precedence over an older reconnect snapshot.
            if kind != "hello" or not previous["pending"]:
                awaiting_image = content.get("kind") in ("image", "file")
                self.on_clip(clip, content)
        if not awaiting_image:
            self.store.mark_applied(clip["id"])
        self.on_history()

    async def run(self):
        self.loop = asyncio.get_running_loop()
        self.wake = asyncio.Event()
        delay = 1
        while not self.stopped.is_set():
            self.on_status("Connecting…")
            try:
                await asyncio.to_thread(self.ensure_device)
                url = self.config["url"].replace("https://", "wss://", 1) + "/v1/ws"
                async with websockets.connect(
                    url,
                    extra_headers={"Authorization": self.authorization},
                    open_timeout=10,
                    ping_interval=25,
                    ping_timeout=30,
                    max_size=32 * 1024 * 1024,
                ) as ws:
                    self.socket = ws
                    self.receive(json.loads(await ws.recv()))
                    delay = 1
                    self.on_status("Connected")
                    sender = asyncio.create_task(self.transmit(ws))
                    try:
                        async for raw in ws:
                            self.receive(json.loads(raw))
                    finally:
                        sender.cancel()
                        await asyncio.gather(sender, return_exceptions=True)
            except Exception as error:
                if isinstance(error, DeviceDisconnected) or (
                    isinstance(error, websockets.ConnectionClosed)
                    and error.code == 4003
                ):
                    self.stopped.set()
                    self.on_status(
                        "Device disconnected. Pair again with a new invitation."
                    )
                    self.on_revoked()
                    break
                if isinstance(error, HTTPError) and error.code == 401:
                    self.stopped.set()
                    self.on_status(
                        "Invitation expired or already used. Create a new one."
                    )
                    self.on_revoked()
                    break
                if (
                    isinstance(error, websockets.InvalidStatusCode)
                    and error.status_code == 401
                ):
                    self.on_status(
                        "Pairing rejected. Check the server and pairing key."
                    )
                elif not self.stopped.is_set():
                    self.on_status("Offline · clips will send when reconnected")
            finally:
                self.socket = None
            if not self.stopped.is_set():
                await asyncio.to_thread(self.stopped.wait, delay + random.random())
                delay = min(delay * 2, 30)
