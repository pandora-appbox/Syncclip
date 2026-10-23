package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const testAuth = "Bearer test-token-that-is-not-a-production-key"

func setup(t *testing.T) (*Relay, *httptest.Server) {
	t.Helper()
	h := sha256.Sum256([]byte(testAuth))
	r, err := newRelay(filepath.Join(t.TempDir(), "state.json"), hex.EncodeToString(h[:]))
	if err != nil {
		t.Fatal(err)
	}
	s := httptest.NewServer(r.handler())
	t.Cleanup(s.Close)
	return r, s
}
func dial(t *testing.T, s *httptest.Server) *websocket.Conn {
	t.Helper()
	h := http.Header{"Authorization": []string{testAuth}}
	ws, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(s.URL, "http")+"/v1/ws", h)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ws.Close() })
	return ws
}
func read(t *testing.T, ws *websocket.Conn) Message {
	t.Helper()
	ws.SetReadDeadline(time.Now().Add(2 * time.Second))
	var m Message
	if err := ws.ReadJSON(&m); err != nil {
		t.Fatal(err)
	}
	return m
}
func sample() Clip {
	return Clip{ID: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", Nonce: base64.StdEncoding.EncodeToString(make([]byte, 12)), Ciphertext: base64.StdEncoding.EncodeToString(make([]byte, 32))}
}

func TestAuthenticationAndOrigin(t *testing.T) {
	_, s := setup(t)
	for _, test := range []struct {
		header http.Header
		want   int
	}{{http.Header{}, 401}, {http.Header{"Authorization": []string{testAuth}, "Origin": []string{"https://example.com"}}, 403}} {
		_, response, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(s.URL, "http")+"/v1/ws", test.header)
		if err == nil || response.StatusCode != test.want {
			t.Fatalf("expected rejection %d", test.want)
		}
		response.Body.Close()
	}
}
func TestBroadcastAckReplayAndRestart(t *testing.T) {
	r, s := setup(t)
	a, b := dial(t, s), dial(t, s)
	if read(t, a).Type != "hello" || read(t, b).Type != "hello" {
		t.Fatal("missing hello")
	}
	clip := sample()
	clip.Seq = 999
	clip.Created = 1
	if err := a.WriteJSON(Message{Type: "publish", Clip: &clip}); err != nil {
		t.Fatal(err)
	}
	received := read(t, b)
	if received.Type != "clip" || received.Clip.Seq != 1 || received.Clip.Created < time.Now().Add(-time.Minute).UnixMilli() {
		t.Fatal("server metadata not authoritative")
	}
	if read(t, a).Type != "clip" || read(t, a).Type != "ack" {
		t.Fatal("missing sender acknowledgement")
	}
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	if read(t, a).Type != "ack" {
		t.Fatal("retry was not idempotent")
	}
	reconnect := dial(t, s)
	snapshot := read(t, reconnect)
	if len(snapshot.Clips) != 1 {
		t.Fatal("reconnect snapshot incorrect")
	}
	h := sha256.Sum256([]byte(testAuth))
	restored, err := newRelay(r.path, hex.EncodeToString(h[:]))
	if err != nil || len(restored.state.Clips) != 1 || restored.state.Seq != 1 {
		t.Fatal("restart lost history")
	}
}
func TestRejectsMalformedEnvelopes(t *testing.T) {
	_, s := setup(t)
	a := dial(t, s)
	read(t, a)
	for _, clip := range []Clip{{}, {ID: sample().ID, Nonce: "bad", Ciphertext: sample().Ciphertext}, {ID: "bad", Nonce: sample().Nonce, Ciphertext: sample().Ciphertext}} {
		a.WriteJSON(Message{Type: "publish", Clip: &clip})
		if read(t, a).Type != "error" {
			t.Fatal("accepted malformed clip")
		}
	}
}
func TestRetentionAndLimit(t *testing.T) {
	r, _ := setup(t)
	now := time.Now()
	r.state.Clips = []Clip{{Created: now.Add(-25 * time.Hour).UnixMilli()}}
	for i := 0; i < 60; i++ {
		r.state.Clips = append(r.state.Clips, Clip{Seq: int64(i), Created: now.UnixMilli()})
	}
	r.prune(now)
	if len(r.state.Clips) != 50 || r.state.Clips[0].Seq != 10 {
		t.Fatal("retention or cap failed")
	}
	r.prune(now.Add(25 * time.Hour))
	if len(r.state.Clips) != 0 {
		t.Fatal("expired clips retained")
	}
}
func TestDiskFailureDoesNotAckOrAdvance(t *testing.T) {
	r, s := setup(t)
	a := dial(t, s)
	read(t, a)
	r.mu.Lock()
	r.path = "/dev/null/state.json"
	r.mu.Unlock()
	clip := sample()
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	if read(t, a).Type != "error" {
		t.Fatal("storage failure acknowledged")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.state.Seq != 0 || len(r.state.Clips) != 0 {
		t.Fatal("storage failure advanced state")
	}
}
func TestConflictingDuplicateRejected(t *testing.T) {
	_, s := setup(t)
	a := dial(t, s)
	read(t, a)
	clip := sample()
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	read(t, a)
	read(t, a)
	clip.Ciphertext = base64.StdEncoding.EncodeToString([]byte("different ciphertext with same id"))
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	if read(t, a).Type != "error" {
		t.Fatal("ID conflict accepted")
	}
}
func TestHealthContainsNoState(t *testing.T) {
	_, s := setup(t)
	response, err := http.Get(s.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var body map[string]string
	if json.NewDecoder(response.Body).Decode(&body) != nil || len(body) != 1 || body["status"] != "ok" {
		t.Fatal("unexpected health response")
	}
}
