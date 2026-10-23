# Run a Syncclip server

The server can be a home computer, NAS, dedicated machine or rented VPS. Docker runs the small Go relay; no database or cloud account is needed. Client content is encrypted before it reaches the relay.

## Requirements

- Linux server with Docker Engine and the Compose plugin, plus Python 3. Your user needs permission to run Docker; otherwise run setup with sudo.
- A hostname pointing to an address reachable by your devices and a trusted HTTPS certificate.
- For automatic HTTPS: a domain/subdomain (dynamic DNS is fine) and unused TCP ports 80/443.

At home, point an A record to your public IPv4 and forward TCP 80/443 from the router to this server. Do not forward 8787. If your connection uses CGNAT, normal port forwarding cannot make it reachable; use an appropriate VPN/tunnel or a different server. A bare IPv4 can work with your own trusted certificate and reverse proxy; the automatic installer assumes a DNS hostname. Both clients require HTTPS and verify certificates.

## Install with automatic HTTPS

Download and extract `Syncclip-0.4.2-server.tar.gz` from a private release, or clone the repository. From that directory:

```sh
./scripts/setup-server.sh https://sync.example.com/syncclip --public
```

The script validates the URL and creates private pairing credentials. The release server archive includes ready Linux x86-64 images and loads them automatically; no Go build is needed. A source checkout or another architecture builds the pinned relay and patched Caddy images instead (the first build can take several minutes). It then starts the services with HTTPS. Certificate issuance needs public DNS/port reachability. It does not install Docker, change your firewall, or overwrite an existing conflicting configuration.

Pair clients using `private/pairing-link.txt`. Linux can display this pairing link as a QR code for Android. Keep the entire `private/` directory secret and backed up. Do not paste pairing links into public issues or CI.

## If you already have nginx or another HTTPS proxy

Leave its ports and certificates in place:

```sh
./scripts/setup-server.sh https://your.example.com/syncclip
```

Only the relay starts, bound to **127.0.0.1:8787** on the host. Include `deploy/nginx-syncclip.conf` inside the matching HTTPS virtual host, test `sudo nginx -t`, then reload nginx. That snippet includes WebSocket support, streaming attachment uploads and endpoint-specific body/method limits. For another proxy, strip `/syncclip` before forwarding to the relay, preserve WebSocket upgrades and authentication headers, stream bodies with a 33 MiB cap, and avoid request access logging.

For an HTTPS proxy in the same Compose network, the upstream is `relay:8787`. Do not expose the relay's unencrypted port directly.

## Configure and operate

The installer stores the public hostname in `.env` and the relay's authentication **hash**, not the content-encryption key, in `private/relay.env`. The relay image never contains credentials. `compose.yaml` documents memory/CPU/process limits, read-only root filesystem, dropped capabilities, non-root UID 10001 and restart policy. Data lives in the named `syncclip-data` volume; `SYNC_DATA_DIR=/absolute/path` can select a prepared bind directory owned by UID 10001. Do not change internal port 8787; the image healthcheck uses it.

```sh
docker compose ps
docker compose logs --tail 50 relay
docker compose restart relay
curl --fail https://sync.example.com/syncclip/healthz
```

For public mode, use `docker compose --profile public up -d --build --wait` after updates. With an existing proxy, use `docker compose up -d --build --wait relay`. Updating/recreating containers preserves the named data volume. Do not run `docker compose down -v` unless you intend to delete stored history and TLS state.

The optional Caddy binary is rebuilt from the pinned 2.11.7 source with Go 1.27.3 and a checked-in dependency lock; the official runtime base is pinned by digest. Keep Docker, the host and proxy image security updates current. The scratch relay image has no shell/package manager; its Go runtime still needs periodic rebuilt updates. Docker administrators can inspect container environment/state, so treat host administrators as trusted.

## Backup and restore

Back up `private/`, `.env`, your Compose/configuration files and the persistent data volume. In public mode also preserve `caddy-data` for certificates/account data. Stop the relay briefly for a consistent volume snapshot:

```sh
docker compose stop relay
# Snapshot/copy the syncclip_syncclip-data Docker volume with your backup tool.
# For a bind mount, copy the configured directory while the relay is stopped.
docker compose start relay
```

Restore data while the relay is stopped, preserving UID 10001 and private directory/file permissions, then start it. Never run two relays against the same writable state directory. An old backup may contain deleted ciphertext, so retain and delete backups according to your own policy.

To revoke a lost device, create a fresh pairing group in a separate directory, replace the relay authentication hash and pair all remaining devices again. Disconnect alone only removes keys on that particular device.
