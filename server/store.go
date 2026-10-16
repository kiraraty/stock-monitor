package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type Store struct {
	mu     sync.Mutex
	dir    string
	events []Event
	seen   map[string]bool
	Error  string
}

func OpenStore(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	s := &Store{dir: dir, seen: map[string]bool{}}
	files, err := filepath.Glob(filepath.Join(dir, "events-*.jsonl"))
	if err != nil {
		return nil, err
	}
	for _, p := range files {
		f, err := os.Open(p)
		if err != nil {
			return nil, err
		}
		scan := bufio.NewScanner(f)
		scan.Buffer(make([]byte, 4096), 1024*1024)
		for scan.Scan() {
			var e Event
			if json.Unmarshal(scan.Bytes(), &e) != nil {
				f.Close()
				return nil, fmt.Errorf("invalid event record in %s", p)
			}
			s.remember(e)
		}
		err = scan.Err()
		f.Close()
		if err != nil {
			return nil, err
		}
	}
	return s, nil
}
func (s *Store) remember(e Event) {
	if (e.Kind == "news" || e.Kind == "important_news") && e.Notification == nil {
		// Legacy keyword-based news eligibility is not a delivery authorization.
		// Preserve the original on-disk record, but never replay it as a push.
		e.AlertEligible = false
		e.Notification = &NotificationPlan{Mode: "record_only"}
	}
	s.events = append(s.events, e)
	s.seen[e.ID] = true
	if len(s.events) > 5000 {
		delete(s.seen, s.events[0].ID)
		s.events = s.events[1:]
	}
}
func (s *Store) Add(e Event) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.seen[e.ID] {
		return nil
	}
	p := filepath.Join(s.dir, "events-"+time.UnixMilli(e.Observed).UTC().Format("2006-01-02")+".jsonl")
	f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err == nil {
		b, _ := json.Marshal(e)
		_, err = f.Write(append(b, '\n'))
		if err == nil {
			err = f.Sync()
		}
		ce := f.Close()
		if err == nil {
			err = ce
		}
	}
	if err != nil {
		s.Error = err.Error()
		return err
	}
	s.Error = ""
	s.remember(e)
	return nil
}
func (s *Store) Snapshot(after int64, limit int) ([]Event, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []Event{}
	for i := len(s.events) - 1; i >= 0 && len(out) < limit; i-- {
		if s.events[i].Observed > after {
			out = append(out, s.events[i])
		}
	}
	return out, s.Error
}

func (s *Store) Contains(id string) bool { s.mu.Lock(); defer s.mu.Unlock(); return s.seen[id] }
