FROM golang:1.27.3-alpine AS build
WORKDIR /src
COPY deploy/https/go.mod deploy/https/go.sum ./
RUN go mod download
COPY deploy/https/main.go ./
RUN CGO_ENABLED=0 go build -buildvcs=false -trimpath -ldflags='-s -w' -o /caddy .

# Preserve the official image's CA roots, entrypoint, storage and certificate setup.
FROM caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f
COPY --from=build /caddy /usr/bin/caddy
COPY deploy/https/licenses/ /usr/share/licenses/syncclip-https/
