package main

import (
	"bytes"
	"io"
	"net/http"
	"os"
	"testing"
	"time"
)

func TestImageUploadPublishDownload(t *testing.T) {
	r, s := setup(t)
	ws := dial(t, s)
	read(t, ws)
	clip := sample()
	clip.Blob = clip.ID
	ws.WriteJSON(Message{Type: "publish", Clip: &clip})
	if read(t, ws).Type != "error" {
		t.Fatal("accepted missing image")
	}
	encrypted := bytes.Repeat([]byte{42}, 200000)
	put := func(data []byte, auth string) int {
		req, _ := http.NewRequest("PUT", s.URL+"/v1/blobs/"+clip.ID, bytes.NewReader(data))
		req.Header.Set("Authorization", auth)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		return res.StatusCode
	}
	if put(encrypted, "") != 401 {
		t.Fatal("unauthorized upload accepted")
	}
	if put(encrypted, testAuth) != 201 || put(encrypted, testAuth) != 204 {
		t.Fatal("upload retry failed")
	}
	if put([]byte("different encrypted image data"), testAuth) != 409 {
		t.Fatal("conflicting image accepted")
	}
	get := func() (*http.Response, []byte) {
		req, _ := http.NewRequest("GET", s.URL+"/v1/blobs/"+clip.ID, nil)
		req.Header.Set("Authorization", testAuth)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		data, _ := io.ReadAll(res.Body)
		return res, data
	}
	response, _ := get()
	if response.StatusCode != 404 {
		t.Fatal("unpublished image exposed")
	}
	ws.WriteJSON(Message{Type: "publish", Clip: &clip})
	message := read(t, ws)
	read(t, ws)
	if message.Clip.BlobBytes != int64(len(encrypted)) {
		t.Fatal("image size not set by server")
	}
	response, data := get()
	if response.StatusCode != 200 || !bytes.Equal(data, encrypted) {
		t.Fatal("image download corrupted")
	}
	r.mu.Lock()
	r.state.Clips[0].Created = time.Now().Add(-25 * time.Hour).UnixMilli()
	r.mu.Unlock()
	response, _ = get()
	if response.StatusCode != 404 {
		t.Fatal("expired image exposed")
	}
}

func TestImageUploadLimitAndGarbageCollection(t *testing.T) {
	r, s := setup(t)
	clip := sample()
	req, _ := http.NewRequest("PUT", s.URL+"/v1/blobs/"+clip.ID, bytes.NewReader(make([]byte, maxBlob+1)))
	req.Header.Set("Authorization", testAuth)
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 413 {
		t.Fatal("oversized image accepted")
	}
	path := r.blobPath(clip.ID)
	if err := os.WriteFile(path, []byte("orphan"), 0600); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-2 * time.Hour)
	os.Chtimes(path, old, old)
	r.mu.Lock()
	r.gcBlobs()
	r.mu.Unlock()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("orphan retained")
	}
}
