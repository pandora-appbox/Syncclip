package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

func TestDeletePreventsReplayAndPersists(t *testing.T) {
	r, s := setup(t)
	a, b := dial(t, s), dial(t, s)
	read(t, a)
	read(t, b)
	clip := sample()
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	read(t, a)
	read(t, a)
	read(t, b)
	if err := os.MkdirAll(r.blobPath(""), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(r.blobPath(clip.ID), []byte("ciphertext"), 0600); err != nil {
		t.Fatal(err)
	}
	a.WriteJSON(Message{Type: "delete", ID: clip.ID})
	if read(t, a).Type != "deleted" || read(t, b).Type != "deleted" {
		t.Fatal("delete not broadcast")
	}
	if _, err := os.Stat(r.blobPath(clip.ID)); !os.IsNotExist(err) {
		t.Fatal("encrypted attachment not removed")
	}
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	if read(t, a).Type != "deleted" {
		t.Fatal("deleted clip replay accepted")
	}
	snapshot := read(t, dial(t, s))
	if len(snapshot.Clips) != 0 || len(snapshot.Deleted) != 1 {
		t.Fatal("offline device would restore deleted clip")
	}
	h := sha256.Sum256([]byte(testAuth))
	restored, err := newRelay(r.path, hex.EncodeToString(h[:]))
	if err != nil || len(restored.state.Deleted) != 1 || len(restored.state.Clips) != 0 {
		t.Fatal("restart lost deletion")
	}
}
func TestClearChangesEpochAndDuplicateCannotDeleteNewClips(t *testing.T) {
	r, s := setup(t)
	a, b := dial(t, s), dial(t, s)
	read(t, a)
	read(t, b)
	clip := sample()
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	read(t, a)
	read(t, a)
	read(t, b)
	request := Message{Type: "clear", ID: "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee"}
	a.WriteJSON(request)
	m := read(t, a)
	read(t, b)
	if m.Type != "cleared" || m.Epoch != 1 {
		t.Fatal("clear not broadcast with new epoch")
	}
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	if snapshot := read(t, a); snapshot.Type != "hello" || snapshot.Epoch != 1 || len(snapshot.Clips) != 0 {
		t.Fatal("old outbox accepted after clear")
	}
	a.WriteJSON(Message{Type: "publish", Clip: &clip, Epoch: 1})
	read(t, a)
	read(t, a)
	read(t, b)
	a.WriteJSON(request)
	if read(t, a).Type != "cleared" {
		t.Fatal("clear retry not acknowledged")
	}
	snapshot := read(t, dial(t, s))
	if len(snapshot.Clips) != 1 || snapshot.Epoch != 1 {
		t.Fatal("duplicate clear deleted newer clip")
	}
	h := sha256.Sum256([]byte(testAuth))
	restored, err := newRelay(r.path, hex.EncodeToString(h[:]))
	if err != nil || restored.state.Epoch != 1 {
		t.Fatal("epoch lost at restart")
	}
	_ = r
}
func TestDeletionRollbackOnStorageFailure(t *testing.T) {
	r, s := setup(t)
	a := dial(t, s)
	read(t, a)
	clip := sample()
	a.WriteJSON(Message{Type: "publish", Clip: &clip})
	read(t, a)
	read(t, a)
	original := r.path
	r.mu.Lock()
	r.path = r.path + "/invalid"
	r.mu.Unlock()
	a.WriteJSON(Message{Type: "delete", ID: clip.ID})
	if read(t, a).Type != "error" {
		t.Fatal("deletion with no storage acknowledged")
	}
	r.mu.Lock()
	r.path = original
	missing := len(r.state.Clips) != 1 || len(r.state.Deleted) != 0
	r.mu.Unlock()
	if missing {
		t.Fatal("failed delete changed authoritative history")
	}
}
func TestBlobUploadFromClearedEpochRejected(t *testing.T) {
	r, _ := setup(t)
	r.mu.Lock()
	r.state.Epoch = 1
	r.mu.Unlock()
	// Use the handler directly so this also checks a body that arrived after clearing.
	req := httptest.NewRequest("PUT", "/v1/blobs/"+sample().ID, bytes.NewReader(make([]byte, 32)))
	req.Header.Set("Authorization", testAuth)
	recorder := httptest.NewRecorder()
	r.handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusGone {
		t.Fatalf("old upload accepted: %d", recorder.Code)
	}
}
