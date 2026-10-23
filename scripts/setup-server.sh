#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ $# -lt 1 || $# -gt 2 || ( $# -eq 2 && "$2" != "--public" ) ]]; then
    echo 'Usage: ./scripts/setup-server.sh https://sync.example.com/syncclip [--public]' >&2
    echo 'Use --public for automatic HTTPS on unused ports 80/443; omit behind an existing HTTPS proxy.' >&2
    exit 2
fi
command -v docker >/dev/null
command -v python3 >/dev/null
docker compose version >/dev/null
python3 scripts/configure-server.py "$1"
build_flag=--build
if [[ $(uname -m) == x86_64 && -f images/relay-linux-amd64.tar.gz ]]; then
    docker load -i images/relay-linux-amd64.tar.gz
    if [[ "${2:-}" == --public ]]; then
        [[ -f images/https-linux-amd64.tar.gz ]] || { echo 'Missing bundled HTTPS image' >&2; exit 1; }
        docker load -i images/https-linux-amd64.tar.gz
    fi
    build_flag=--no-build
fi
if [[ "${2:-}" == --public ]]; then
    docker compose --profile public up -d "$build_flag" --wait --wait-timeout 180
else
    docker compose up -d "$build_flag" --wait --wait-timeout 120 relay
fi
echo 'Relay ready. Pair using private/pairing-link.txt; keep that file secret.'
echo 'Check: docker compose ps; docker compose logs --tail 50 relay'
