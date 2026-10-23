package main

import (
	"os"
	"path/filepath"
	"time"
)

// The caller holds mu. Snapshots reconcile devices that were offline during a deletion.
func (r *Relay) snapshot() Message {
	deleted := make([]string, 0, len(r.state.Deleted))
	for id := range r.state.Deleted {
		deleted = append(deleted, id)
	}
	return Message{Type: "hello", Clips: append([]Clip{}, r.state.Clips...), Epoch: r.state.Epoch, Deleted: deleted}
}
func (r *Relay) cloneState() State {
	state := r.state
	state.Clips = append([]Clip{}, state.Clips...)
	state.Deleted = make(map[string]int64)
	for id, at := range r.state.Deleted {
		state.Deleted[id] = at
	}
	state.Clears = make(map[string]int64)
	for id, at := range r.state.Clears {
		state.Clears[id] = at
	}
	return state
}
func (r *Relay) remove(p *peer, m Message) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !idPattern.MatchString(m.ID) {
		r.enqueue(p, Message{Type: "error", Error: "Invalid clip ID"})
		return
	}
	r.prune(time.Now())
	if m.Epoch != r.state.Epoch {
		if m.Type == "clear" {
			if _, done := r.state.Clears[m.ID]; done {
				r.enqueue(p, Message{Type: "cleared", ID: m.ID, Epoch: r.state.Epoch})
				return
			}
		}
		r.enqueue(p, r.snapshot())
		return
	}
	if m.Type == "clear" {
		if _, done := r.state.Clears[m.ID]; done {
			r.enqueue(p, Message{Type: "cleared", ID: m.ID, Epoch: r.state.Epoch})
			return
		}
	}
	previous := r.cloneState()
	event := Message{Type: "deleted", ID: m.ID, Epoch: r.state.Epoch}
	if m.Type == "clear" {
		r.state.Epoch++
		r.state.Clips = []Clip{}
		r.state.Clears[m.ID] = time.Now().UnixMilli()
		event.Type, event.Epoch = "cleared", r.state.Epoch
	} else {
		kept := make([]Clip, 0, len(r.state.Clips))
		for _, clip := range r.state.Clips {
			if clip.ID != m.ID {
				kept = append(kept, clip)
			}
		}
		r.state.Clips = kept
		r.state.Deleted[m.ID] = time.Now().UnixMilli()
	}
	if err := r.save(); err != nil {
		r.state = previous
		r.enqueue(p, Message{Type: "error", Error: "Could not clear history; please retry"})
		return
	}
	// Only delete ciphertext after the authoritative state has been persisted.
	if m.Type == "clear" {
		entries, _ := os.ReadDir(filepath.Dir(r.blobPath("unused")))
		for _, entry := range entries {
			if !entry.IsDir() {
				os.Remove(filepath.Join(filepath.Dir(r.blobPath("unused")), entry.Name()))
			}
		}
	} else {
		os.Remove(r.blobPath(m.ID))
	}
	for other := range r.peers {
		r.enqueue(other, event)
	}
}
