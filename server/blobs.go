package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

const maxBlob = 32*1024*1024 + 16

func (r *Relay) blobPath(id string) string { return filepath.Join(filepath.Dir(r.path), "images", id) }
func (r *Relay) blobAuthorized(w http.ResponseWriter, req *http.Request) bool {
	hash := sha256.Sum256([]byte(req.Header.Get("Authorization")))
	if req.Header.Get("Origin") != "" || subtle.ConstantTimeCompare(hash[:], r.tokenHash) != 1 {
		http.Error(w, "Unauthorized", 401)
		return false
	}
	if !idPattern.MatchString(req.PathValue("id")) {
		http.Error(w, "Invalid attachment ID", 400)
		return false
	}
	return true
}
func (r *Relay) blobUpload(w http.ResponseWriter, req *http.Request) {
	if !r.blobAuthorized(w, req) {
		return
	}
	dir := filepath.Dir(r.blobPath(req.PathValue("id")))
	if os.MkdirAll(dir, 0700) != nil {
		http.Error(w, "Storage unavailable", 503)
		return
	}
	file, err := os.CreateTemp(dir, "upload-")
	if err != nil {
		http.Error(w, "Storage unavailable", 503)
		return
	}
	defer os.Remove(file.Name())
	defer file.Close()
	req.Body = http.MaxBytesReader(w, req.Body, maxBlob)
	digest := sha256.New()
	size, err := io.Copy(io.MultiWriter(file, digest), req.Body)
	if err != nil || size < 16 {
		http.Error(w, "File must be at most 32 MB", 413)
		return
	}
	if file.Sync() != nil {
		http.Error(w, "Storage unavailable", 503)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	epoch := int64(0)
	if value := req.Header.Get("X-Syncclip-Epoch"); value != "" {
		var err error
		epoch, err = strconv.ParseInt(value, 10, 64)
		if err != nil {
			http.Error(w, "Invalid history version", 400)
			return
		}
	}
	if epoch != r.state.Epoch {
		http.Error(w, "History was cleared", 410)
		return
	}
	if _, deleted := r.state.Deleted[req.PathValue("id")]; deleted {
		http.Error(w, "Attachment deleted", 410)
		return
	}
	target := r.blobPath(req.PathValue("id"))
	if existing, err := os.Open(target); err == nil {
		defer existing.Close()
		previous := sha256.New()
		io.Copy(previous, existing)
		if subtle.ConstantTimeCompare(previous.Sum(nil), digest.Sum(nil)) != 1 {
			http.Error(w, "Attachment ID conflict", 409)
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	r.gcBlobs()
	entries, _ := os.ReadDir(dir)
	var used int64
	for _, entry := range entries {
		if info, err := entry.Info(); err == nil {
			used += info.Size()
		}
	}
	if used > 256*1024*1024 {
		http.Error(w, "Attachment storage full; retry later", 507)
		return
	}
	if err := os.Rename(file.Name(), target); err != nil {
		http.Error(w, "Storage unavailable", 503)
		return
	}
	directory, err := os.Open(dir)
	if err != nil {
		http.Error(w, "Storage unavailable", 503)
		return
	}
	defer directory.Close()
	if directory.Sync() != nil {
		http.Error(w, "Storage unavailable", 503)
		return
	}
	w.WriteHeader(http.StatusCreated)
}
func (r *Relay) blobDownload(w http.ResponseWriter, req *http.Request) {
	if !r.blobAuthorized(w, req) {
		return
	}
	r.mu.Lock()
	r.prune(time.Now())
	allowed := false
	for _, clip := range r.state.Clips {
		if clip.Blob == req.PathValue("id") {
			allowed = true
			break
		}
	}
	var file *os.File
	var err error
	if allowed {
		file, err = os.Open(r.blobPath(req.PathValue("id")))
	}
	r.mu.Unlock()
	if !allowed || err != nil {
		http.NotFound(w, req)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		http.Error(w, "Storage unavailable", 503)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, req, "image", info.ModTime(), file)
}

// Call while holding mu. Keep unpublished uploads for an hour so upload/publish retries are safe.
func (r *Relay) gcBlobs() {
	retained := make(map[string]bool)
	for _, clip := range r.state.Clips {
		retained[clip.Blob] = true
	}
	dir := filepath.Dir(r.blobPath("unused"))
	entries, _ := os.ReadDir(dir)
	for _, entry := range entries {
		if retained[entry.Name()] || entry.IsDir() {
			continue
		}
		if info, err := entry.Info(); err == nil && time.Since(info.ModTime()) > time.Hour {
			os.Remove(filepath.Join(dir, entry.Name()))
		}
	}
}
