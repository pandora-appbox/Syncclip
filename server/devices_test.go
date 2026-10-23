package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func deviceRequest(t *testing.T, s *httptest.Server, path, method, auth string, input any, want int) []byte {
	t.Helper()
	var body io.Reader
	if input != nil {
		data, _ := json.Marshal(input)
		body = bytes.NewReader(data)
	}
	req, _ := http.NewRequest(method, s.URL+path, body)
	req.Header.Set("Authorization", auth)
	req.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, _ := io.ReadAll(response.Body)
	if response.StatusCode != want {
		t.Fatalf("%s %s: got %d want %d (%s)", method, path, response.StatusCode, want, data)
	}
	return data
}
func TestDeviceEnrollmentRevocationAndRestart(t *testing.T) {
	hash, _ := hex.DecodeString(authHash(testAuth))
	r, err := newRelay(filepath.Join(t.TempDir(), "state.json"), hex.EncodeToString(hash))
	if err != nil {
		t.Fatal(err)
	}
	s := httptest.NewServer(r.handler())
	defer s.Close()
	id := "11111111-2222-4333-8444-555555555555"
	token := strings.Repeat("a", 64)
	auth := "Bearer " + token
	input := map[string]string{"id": id, "name": "Desktop", "platform": "linux", "token": token}
	deviceRequest(t, s, "/v1/devices", "GET", testAuth, nil, 401)
	deviceRequest(t, s, "/v1/enroll", "POST", testAuth, input, 200)
	deviceRequest(t, s, "/v1/enroll", "POST", testAuth, input, 401)
	data := deviceRequest(t, s, "/v1/devices", "GET", auth, nil, 200)
	if bytes.Contains(data, []byte("token_hash")) || bytes.Contains(data, []byte(token)) {
		t.Fatal("registry leaked credential")
	}
	var invitation map[string]any
	json.Unmarshal(deviceRequest(t, s, "/v1/invitations", "POST", auth, nil, 200), &invitation)
	secondID := "22222222-2222-4333-8444-555555555555"
	secondAuth := "Bearer " + strings.Repeat("b", 64)
	second := map[string]string{"id": secondID, "name": "Phone", "platform": "android", "token": strings.Repeat("b", 64)}
	inviteAuth := "Bearer " + invitation["invite"].(string)
	deviceRequest(t, s, "/v1/enroll", "POST", inviteAuth, second, 200)
	deviceRequest(t, s, "/v1/enroll", "POST", inviteAuth, second, 401)
	conn, response, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(s.URL, "http")+"/v1/ws", http.Header{"Authorization": []string{secondAuth}})
	if err != nil {
		if response != nil {
			response.Body.Close()
		}
		t.Fatal(err)
	}
	defer conn.Close()
	read(t, conn)
	// Revoke also destroys invitations previously created by that device.
	json.Unmarshal(deviceRequest(t, s, "/v1/invitations", "POST", secondAuth, nil, 200), &invitation)
	deviceRequest(t, s, "/v1/devices/"+secondID, "DELETE", auth, nil, 204)
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := conn.ReadMessage(); err == nil {
		t.Fatal("revoked socket remained open")
	}
	for _, path := range []string{"/v1/devices", "/v1/blobs/" + sample().ID} {
		deviceRequest(t, s, path, "GET", secondAuth, nil, 401)
	}
	deviceRequest(t, s, "/v1/blobs/"+sample().ID, "PUT", secondAuth, "unused", 401)
	deviceRequest(t, s, "/v1/enroll", "POST", "Bearer "+invitation["invite"].(string), second, 401)
	deviceRequest(t, s, "/v1/enroll", "POST", testAuth, second, 401)
	r.mu.Lock()
	restored, err := newRelay(r.path, hex.EncodeToString(hash))
	r.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	rs := httptest.NewServer(restored.handler())
	defer rs.Close()
	deviceRequest(t, rs, "/v1/devices", "GET", auth, nil, 200)
	deviceRequest(t, rs, "/v1/devices", "GET", secondAuth, nil, 401)
	deviceRequest(t, rs, "/v1/devices/"+id, "DELETE", auth, nil, 204)
	// Removing every device does not reopen the old provisioning credential.
	deviceRequest(t, rs, "/v1/enroll", "POST", testAuth, second, 401)
}
func TestDeviceInvalidInvitationOriginAndRollback(t *testing.T) {
	r, s := setup(t)
	req := httptest.NewRequest("POST", "/v1/invitations", nil)
	req.Header.Set("Authorization", testAuth)
	req.Header.Set("Origin", "https://evil.invalid")
	w := httptest.NewRecorder()
	r.createInvitation(w, req)
	if w.Code != 401 {
		t.Fatal("browser origin accepted")
	}
	token := strings.Repeat("c", 64)
	id := "33333333-2222-4333-8444-555555555555"
	input := map[string]string{"id": id, "name": "Device", "platform": "android", "token": token}
	r.mu.Lock()
	r.accounts.Invitations[authHash("Bearer expired")] = time.Now().Add(-time.Second).UnixMilli()
	r.mu.Unlock()
	deviceRequest(t, s, "/v1/enroll", "POST", "Bearer expired", input, 401)
	var invitation map[string]any
	json.Unmarshal(deviceRequest(t, s, "/v1/invitations", "POST", testAuth, nil, 200), &invitation)
	r.mu.Lock()
	old := r.path
	r.path = "/dev/null/state.json"
	r.mu.Unlock()
	deviceRequest(t, s, "/v1/enroll", "POST", "Bearer "+invitation["invite"].(string), input, 503)
	r.mu.Lock()
	_, exists := r.accounts.Devices[id]
	r.path = old
	r.mu.Unlock()
	if exists {
		t.Fatal("failed enrollment changed registry")
	}
	deviceRequest(t, s, "/v1/enroll", "POST", "Bearer "+invitation["invite"].(string), input, 200)
	r.mu.Lock()
	r.path = "/dev/null/state.json"
	r.mu.Unlock()
	deviceRequest(t, s, "/v1/devices/"+id, "DELETE", testAuth, nil, 503)
	r.mu.Lock()
	_, exists = r.accounts.Devices[id]
	r.path = old
	r.mu.Unlock()
	if !exists {
		t.Fatal("failed revocation removed credential")
	}
}
func TestInvitationConsumedOnceUnderConcurrentEnrollment(t *testing.T) {
	_, s := setup(t)
	var invite map[string]any
	json.Unmarshal(deviceRequest(t, s, "/v1/invitations", "POST", testAuth, nil, 200), &invite)
	var wg sync.WaitGroup
	codes := make(chan int, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			id := []string{"44444444-2222-4333-8444-555555555555", "55555555-2222-4333-8444-555555555555"}[i]
			token := strings.Repeat([]string{"d", "e"}[i], 64)
			body, _ := json.Marshal(map[string]string{"id": id, "name": "Concurrent", "platform": "linux", "token": token})
			req, _ := http.NewRequest("POST", s.URL+"/v1/enroll", bytes.NewReader(body))
			req.Header.Set("Authorization", "Bearer "+invite["invite"].(string))
			response, err := http.DefaultClient.Do(req)
			if err != nil {
				codes <- 0
				return
			}
			response.Body.Close()
			codes <- response.StatusCode
		}(i)
	}
	wg.Wait()
	close(codes)
	ok, rejected := 0, 0
	for c := range codes {
		if c == 200 {
			ok++
		}
		if c == 401 {
			rejected++
		}
	}
	if ok != 1 || rejected != 1 {
		t.Fatalf("one-use invitation was not atomic: %d/%d", ok, rejected)
	}
}
