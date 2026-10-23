// Syncclip is a single-household, end-to-end encrypted clipboard relay.
package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

const (
	maxClips  = 50
	maxWire   = 550 * 1024
	maxCipher = 400 * 1024 // JSON escaping can expand a 64 KB text payload sixfold.
	retention = 24 * time.Hour
)

var idPattern = regexp.MustCompile(`^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$`)

type Clip struct {
	ID         string `json:"id"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
	Seq        int64  `json:"seq"`
	Created    int64  `json:"created"`
	Blob       string `json:"blob,omitempty"`
	BlobBytes  int64  `json:"blob_bytes,omitempty"`
}
type State struct {
	Epoch   int64            `json:"epoch"`
	Deleted map[string]int64 `json:"deleted,omitempty"`
	Clears  map[string]int64 `json:"clears,omitempty"`
	Seq     int64            `json:"seq"`
	Clips   []Clip           `json:"clips"`
}
type Message struct {
	Epoch   int64    `json:"epoch"`
	Deleted []string `json:"deleted,omitempty"`
	Type    string   `json:"type"`
	Clip    *Clip    `json:"clip,omitempty"`
	Clips   []Clip   `json:"clips,omitempty"`
	ID      string   `json:"id,omitempty"`
	Error   string   `json:"error,omitempty"`
}
type peer struct {
	conn     *websocket.Conn
	send     chan Message
	deviceID string
}
type Relay struct {
	mu        sync.Mutex
	state     State
	path      string
	tokenHash []byte
	peers     map[*peer]bool
	accounts  Accounts
}

func newRelay(path, tokenHash string) (*Relay, error) {
	hash, err := hex.DecodeString(tokenHash)
	if err != nil || len(hash) != 32 {
		return nil, errors.New("SYNC_TOKEN_SHA256 must be 64 hex characters")
	}
	r := &Relay{path: path, tokenHash: hash, peers: make(map[*peer]bool), state: State{Clips: []Clip{}}}
	data, err := os.ReadFile(path)
	if err == nil {
		if err = json.Unmarshal(data, &r.state); err != nil {
			return nil, errors.New("invalid relay state; restore a valid backup")
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	if err := r.loadAccounts(); err != nil {
		return nil, err
	}
	r.prune(time.Now())
	return r, nil
}
func (r *Relay) prune(now time.Time) {
	if r.state.Deleted == nil {
		r.state.Deleted = make(map[string]int64)
	}
	if r.state.Clears == nil {
		r.state.Clears = make(map[string]int64)
	}
	for id, at := range r.state.Deleted {
		if at <= now.Add(-retention).UnixMilli() {
			delete(r.state.Deleted, id)
		}
	}
	for id, at := range r.state.Clears {
		if at <= now.Add(-retention).UnixMilli() {
			delete(r.state.Clears, id)
		}
	}
	cutoff := now.Add(-retention).UnixMilli()
	kept := make([]Clip, 0, len(r.state.Clips))
	for _, c := range r.state.Clips {
		if c.Created > cutoff {
			kept = append(kept, c)
		}
	}
	if len(kept) > maxClips {
		kept = kept[len(kept)-maxClips:]
	}
	var bytes int64
	for i := len(kept) - 1; i >= 0; i-- {
		bytes += kept[i].BlobBytes
		if bytes > 64*1024*1024 {
			kept = kept[i+1:]
			break
		}
	}
	r.state.Clips = kept
}
func (r *Relay) save() error {
	if err := os.MkdirAll(filepath.Dir(r.path), 0700); err != nil {
		return err
	}
	data, err := json.Marshal(r.state)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(r.path+".tmp", os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	defer os.Remove(r.path + ".tmp")
	if _, err = f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err = f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	if err = os.Rename(r.path+".tmp", r.path); err != nil {
		return err
	}
	dir, err := os.Open(filepath.Dir(r.path))
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}
func validate(c *Clip) bool {
	if c == nil || !idPattern.MatchString(c.ID) {
		return false
	}
	if c.Blob != "" && c.Blob != c.ID {
		return false
	}
	n, err := base64.StdEncoding.Strict().DecodeString(c.Nonce)
	if err != nil || len(n) != 12 {
		return false
	}
	b, err := base64.StdEncoding.Strict().DecodeString(c.Ciphertext)
	return err == nil && len(b) >= 16 && len(b) <= maxCipher
}
func (r *Relay) enqueue(p *peer, m Message) {
	select {
	case p.send <- m:
	default:
		p.conn.Close()
	}
}
func (r *Relay) publish(p *peer, c *Clip, epoch int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.peerActiveLocked(p) {
		return
	}
	if epoch != r.state.Epoch {
		r.enqueue(p, r.snapshot())
		return
	}
	if !validate(c) {
		r.enqueue(p, Message{Type: "error", Error: "Invalid clipboard envelope"})
		return
	}
	r.prune(time.Now())
	if _, deleted := r.state.Deleted[c.ID]; deleted {
		r.enqueue(p, Message{Type: "deleted", ID: c.ID, Epoch: r.state.Epoch})
		return
	}
	for _, old := range r.state.Clips {
		if old.ID == c.ID {
			if old.Nonce != c.Nonce || old.Ciphertext != c.Ciphertext {
				r.enqueue(p, Message{Type: "error", Error: "Clipboard ID conflict"})
				return
			}
			r.enqueue(p, Message{Type: "ack", ID: c.ID})
			return
		}
	}
	previous := r.cloneState()
	c.BlobBytes = 0
	if c.Blob != "" {
		info, err := os.Stat(r.blobPath(c.Blob))
		if err != nil {
			r.enqueue(p, Message{Type: "error", Error: "Upload the attachment before sending it"})
			return
		}
		c.BlobBytes = info.Size()
	}
	r.state.Seq++
	c.Seq = r.state.Seq
	c.Created = time.Now().UnixMilli()
	r.state.Clips = append(r.state.Clips, *c)
	r.prune(time.Now())
	if err := r.save(); err != nil {
		r.state = previous
		r.enqueue(p, Message{Type: "error", Error: "Storage unavailable; retry shortly"})
		return
	}
	for other := range r.peers {
		r.enqueue(other, Message{Type: "clip", Clip: c})
	}
	r.enqueue(p, Message{Type: "ack", ID: c.ID})
}
func (r *Relay) socket(w http.ResponseWriter, req *http.Request) {
	// Credentials never appear in URLs or access logs. Browser origins are refused.
	if req.Header.Get("Origin") != "" {
		http.Error(w, "Native clients only", http.StatusForbidden)
		return
	}
	r.mu.Lock()
	device := r.authorizeLocked(req)
	if device == nil {
		r.mu.Unlock()
		http.Error(w, "Unauthorized", 401)
		return
	}
	if len(r.peers) >= 16 {
		r.mu.Unlock()
		http.Error(w, "Too many devices", http.StatusServiceUnavailable)
		return
	}
	conn, err := (&websocket.Upgrader{HandshakeTimeout: 10 * time.Second}).Upgrade(w, req, nil)
	if err != nil {
		r.mu.Unlock()
		return
	}
	p := &peer{conn: conn, send: make(chan Message, 128), deviceID: device.ID}
	device.LastSeen = time.Now().UnixMilli()
	r.prune(time.Now())
	r.peers[p] = true
	r.enqueue(p, r.snapshot())
	r.mu.Unlock()
	done := make(chan struct{})
	defer func() {
		r.mu.Lock()
		delete(r.peers, p)
		if d := r.accounts.Devices[p.deviceID]; d != nil {
			d.LastSeen = time.Now().UnixMilli()
		}
		r.mu.Unlock()
		close(done)
		conn.Close()
	}()
	go func() {
		tick := time.NewTicker(25 * time.Second)
		defer tick.Stop()
		for {
			select {
			case m := <-p.send:
				conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if conn.WriteJSON(m) != nil {
					conn.Close()
					return
				}
			case <-tick.C:
				if conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second)) != nil {
					conn.Close()
					return
				}
			case <-done:
				return
			}
		}
	}()
	conn.SetReadLimit(maxWire)
	conn.SetReadDeadline(time.Now().Add(75 * time.Second))
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(75 * time.Second)) })
	// A small burst is useful for reconnects; sustained clipboard floods are closed.
	tokens := 20.0
	last := time.Now()
	for {
		var m Message
		if conn.ReadJSON(&m) != nil {
			return
		}
		now := time.Now()
		tokens += now.Sub(last).Seconds() * 2
		last = now
		if tokens > 20 {
			tokens = 20
		}
		tokens--
		if tokens < 0 {
			return
		}
		if m.Type == "delete" || m.Type == "clear" {
			r.remove(p, m)
			continue
		}
		if m.Type != "publish" {
			r.mu.Lock()
			r.enqueue(p, Message{Type: "error", Error: "Unknown message"})
			r.mu.Unlock()
			continue
		}
		r.publish(p, m.Clip, m.Epoch)
	}
}
func (r *Relay) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"status":"ok"}`))
	})
	mux.HandleFunc("POST /v1/enroll", r.enroll)
	mux.HandleFunc("GET /v1/devices", r.listDevices)
	mux.HandleFunc("DELETE /v1/devices/{id}", r.revokeDevice)
	mux.HandleFunc("POST /v1/invitations", r.createInvitation)
	mux.HandleFunc("GET /v1/ws", r.socket)
	mux.HandleFunc("PUT /v1/blobs/{id}", r.blobUpload)
	mux.HandleFunc("GET /v1/blobs/{id}", r.blobDownload)
	return mux
}
func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
func main() {
	if len(os.Args) == 2 && os.Args[1] == "--healthcheck" {
		client := &http.Client{Timeout: 3 * time.Second}
		response, err := client.Get("http://127.0.0.1:8787/healthz")
		if err != nil {
			os.Exit(1)
		}
		response.Body.Close()
		if response.StatusCode != http.StatusOK {
			os.Exit(1)
		}
		return
	}

	r, err := newRelay(env("SYNC_STATE", "/var/lib/syncclip/state.json"), os.Getenv("SYNC_TOKEN_SHA256"))
	if err != nil {
		log.Fatal(err)
	}
	server := &http.Server{Addr: env("SYNC_LISTEN", "127.0.0.1:8787"), Handler: r.handler(), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8192}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				r.mu.Lock()
				r.prune(time.Now())
				if err := r.save(); err != nil {
					log.Print("Cannot persist clipboard retention")
				}
				r.gcBlobs()
				r.mu.Unlock()
			case <-ctx.Done():
				return
			}
		}
	}()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		server.Shutdown(shutdown)
		r.mu.Lock()
		for p := range r.peers {
			p.conn.Close()
		}
		r.mu.Unlock()
	}()
	log.Printf("Syncclip relay listening on %s", server.Addr)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
