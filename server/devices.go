package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/gorilla/websocket"
)

type Device struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Platform  string `json:"platform"`
	TokenHash string `json:"token_hash"`
	LastSeen  int64  `json:"last_seen"`
}
type Accounts struct {
	Initialized bool               `json:"initialized"`
	Devices     map[string]*Device `json:"devices"`
	Invitations map[string]int64   `json:"invitations"`
}
type deviceView struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Platform string `json:"platform"`
	Online   bool   `json:"online"`
	LastSeen int64  `json:"last_seen"`
}

func (r *Relay) loadAccounts() error {
	r.accounts = Accounts{Devices: map[string]*Device{}, Invitations: map[string]int64{}}
	data, err := os.ReadFile(filepath.Join(filepath.Dir(r.path), "devices.json"))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if json.Unmarshal(data, &r.accounts) != nil || r.accounts.Devices == nil || r.accounts.Invitations == nil {
		return errors.New("invalid device registry; restore a valid backup")
	}
	if len(r.accounts.Devices) > 16 || len(r.accounts.Invitations) > 8 {
		return errors.New("device registry exceeds limits")
	}
	return nil
}
func (r *Relay) saveAccounts() error {
	path := filepath.Join(filepath.Dir(r.path), "devices.json")
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	data, err := json.Marshal(r.accounts)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(path+".tmp", os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	defer os.Remove(path + ".tmp")
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
	if err = os.Rename(path+".tmp", path); err != nil {
		return err
	}
	dir, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}
func authHash(authorization string) string {
	h := sha256.Sum256([]byte(authorization))
	return hex.EncodeToString(h[:])
}
func (r *Relay) authorizeLocked(req *http.Request) *Device {
	if req.Header.Get("Origin") != "" {
		return nil
	}
	hash := authHash(req.Header.Get("Authorization"))
	for _, d := range r.accounts.Devices {
		if subtle.ConstantTimeCompare([]byte(hash), []byte(d.TokenHash)) == 1 {
			return d
		}
	}
	return nil
}
func (r *Relay) peerActiveLocked(p *peer) bool { _, ok := r.accounts.Devices[p.deviceID]; return ok }
func privateJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	json.NewEncoder(w).Encode(value)
}
func readJSON(w http.ResponseWriter, req *http.Request, value any) bool {
	if req.Header.Get("Origin") != "" {
		http.Error(w, "Native clients only", 403)
		return false
	}
	req.Body = http.MaxBytesReader(w, req.Body, 4096)
	decoder := json.NewDecoder(req.Body)
	decoder.DisallowUnknownFields()
	if decoder.Decode(value) != nil || decoder.Decode(new(any)) != io.EOF {
		http.Error(w, "Invalid request", 400)
		return false
	}
	return true
}
func (r *Relay) enroll(w http.ResponseWriter, req *http.Request) {
	var input struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		Platform string `json:"platform"`
		Token    string `json:"token"`
	}
	if !readJSON(w, req, &input) {
		return
	}
	token, err := hex.DecodeString(input.Token)
	if !idPattern.MatchString(input.ID) || err != nil || len(token) != 32 || len(input.Name) == 0 || len(input.Name) > 160 || !utf8.ValidString(input.Name) || strings.IndexFunc(input.Name, unicode.IsControl) >= 0 || (input.Platform != "linux" && input.Platform != "android") {
		http.Error(w, "Invalid device", 400)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	inviteHash := authHash(req.Header.Get("Authorization"))
	expires, invited := r.accounts.Invitations[inviteHash]
	// The provisioning credential can enroll the first device exactly once, even if
	// the last device is later revoked. Group encryption keys never authorize sync.
	bootstrap := !r.accounts.Initialized && subtle.ConstantTimeCompare([]byte(inviteHash), []byte(hex.EncodeToString(r.tokenHash))) == 1
	if (!invited || expires <= time.Now().UnixMilli()) && !bootstrap {
		http.Error(w, "Invitation expired or already used", 401)
		return
	}
	if len(r.accounts.Devices) >= 16 {
		http.Error(w, "Too many devices", 409)
		return
	}
	if _, exists := r.accounts.Devices[input.ID]; exists {
		http.Error(w, "Device already exists", 409)
		return
	}
	// Reject token reuse: revocation must affect exactly one credential.
	hash := authHash("Bearer " + input.Token)
	if hash == hex.EncodeToString(r.tokenHash) {
		http.Error(w, "Choose a separate device credential", 400)
		return
	}
	for _, d := range r.accounts.Devices {
		if d.TokenHash == hash {
			http.Error(w, "Device token already exists", 409)
			return
		}
	}
	initialized := r.accounts.Initialized
	r.accounts.Initialized = true
	delete(r.accounts.Invitations, inviteHash)
	r.accounts.Devices[input.ID] = &Device{ID: input.ID, Name: input.Name, Platform: input.Platform, TokenHash: hash, LastSeen: time.Now().UnixMilli()}
	if err := r.saveAccounts(); err != nil {
		delete(r.accounts.Devices, input.ID)
		r.accounts.Initialized = initialized
		if invited {
			r.accounts.Invitations[inviteHash] = expires
		}
		http.Error(w, "Storage unavailable", 503)
		return
	}
	privateJSON(w, map[string]string{"id": input.ID})
}
func (r *Relay) listDevices(w http.ResponseWriter, req *http.Request) {
	r.mu.Lock()
	defer r.mu.Unlock()
	self := r.authorizeLocked(req)
	if self == nil {
		http.Error(w, "Unauthorized", 401)
		return
	}
	devices := []deviceView{}
	for _, d := range r.accounts.Devices {
		online := false
		for p := range r.peers {
			if p.deviceID == d.ID {
				online = true
				break
			}
		}
		devices = append(devices, deviceView{d.ID, d.Name, d.Platform, online, d.LastSeen})
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].ID < devices[j].ID })
	privateJSON(w, map[string]any{"self": self.ID, "devices": devices})
}
func (r *Relay) createInvitation(w http.ResponseWriter, req *http.Request) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.authorizeLocked(req) == nil {
		http.Error(w, "Unauthorized", 401)
		return
	}
	now := time.Now().UnixMilli()
	for hash, expires := range r.accounts.Invitations {
		if expires <= now {
			delete(r.accounts.Invitations, hash)
		}
	}
	if len(r.accounts.Invitations) >= 8 {
		http.Error(w, "Too many invitations; wait for expiry", 429)
		return
	}
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		http.Error(w, "Cannot create invitation", 503)
		return
	}
	token := hex.EncodeToString(raw[:])
	hash := authHash("Bearer " + token)
	expires := time.Now().Add(10 * time.Minute).UnixMilli()
	r.accounts.Invitations[hash] = expires
	if r.saveAccounts() != nil {
		delete(r.accounts.Invitations, hash)
		http.Error(w, "Storage unavailable", 503)
		return
	}
	privateJSON(w, map[string]any{"invite": token, "expires": expires})
}
func (r *Relay) revokeDevice(w http.ResponseWriter, req *http.Request) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.authorizeLocked(req) == nil {
		http.Error(w, "Unauthorized", 401)
		return
	}
	id := req.PathValue("id")
	device, exists := r.accounts.Devices[id]
	if !exists {
		http.NotFound(w, req)
		return
	}
	delete(r.accounts.Devices, id)
	// Close outstanding invitations too: a removed device must not use an invite
	// it created earlier to enroll itself under a fresh ID.
	invitations := r.accounts.Invitations
	r.accounts.Invitations = map[string]int64{}
	if r.saveAccounts() != nil {
		r.accounts.Devices[id] = device
		r.accounts.Invitations = invitations
		http.Error(w, "Storage unavailable", 503)
		return
	}
	for p := range r.peers {
		if p.deviceID == id {
			go func(p *peer) {
				p.conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(4003, "Device disconnected"), time.Now().Add(time.Second))
				p.conn.Close()
			}(p)
		}
	}
	w.WriteHeader(http.StatusNoContent)
}
