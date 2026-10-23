FROM golang:1.27.3-alpine AS build
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /syncclip-server . && mkdir /state && chmod 700 /state

FROM scratch
COPY --from=build /syncclip-server /syncclip-server
COPY --from=build --chown=10001:10001 /state /var/lib/syncclip
COPY LICENSE licenses/Go.txt licenses/gorilla-websocket.txt /licenses/
USER 10001:10001
EXPOSE 8787
ENV SYNC_LISTEN=0.0.0.0:8787 SYNC_STATE=/var/lib/syncclip/state.json
ENTRYPOINT ["/syncclip-server"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD ["/syncclip-server", "--healthcheck"]
