package main

import (
	"encoding/json"
	"fmt"
	"sync"
	"testing"

	"github.com/cyverse-de/messaging/v12"
)

func TestInfosquitoSpec(t *testing.T) {
	tests := []struct {
		daynum int
		want   string
	}{
		{1, "0 23 * * 0"}, // Sunday
		{2, "0 23 * * 1"}, // Monday
		{4, "0 23 * * 3"}, // Wednesday
		{7, "0 23 * * 6"}, // Saturday
	}
	for _, tt := range tests {
		t.Run(fmt.Sprintf("daynum=%d", tt.daynum), func(t *testing.T) {
			if got := infosquitoSpec(tt.daynum); got != tt.want {
				t.Errorf("infosquitoSpec(%d) = %q, want %q", tt.daynum, got, tt.want)
			}
		})
	}
}

func TestDataUsageSpec(t *testing.T) {
	tests := []struct {
		interval int
		want     string
	}{
		{1, "@every 1h"},
		{3, "@every 3h"},
		{24, "@every 24h"},
	}
	for _, tt := range tests {
		t.Run(fmt.Sprintf("interval=%d", tt.interval), func(t *testing.T) {
			if got := dataUsageSpec(tt.interval); got != tt.want {
				t.Errorf("dataUsageSpec(%d) = %q, want %q", tt.interval, got, tt.want)
			}
		})
	}
}

// fakePublisher records the routing keys and bodies it was asked to publish.
type fakePublisher struct {
	mu     sync.Mutex
	keys   []string
	bodies [][]byte
}

func (f *fakePublisher) PublishOpts(key string, body []byte, _ *messaging.PublishingOpts) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.keys = append(f.keys, key)
	f.bodies = append(f.bodies, body)
	return nil
}

func TestBuildSchedulerRegistersEnabledJobs(t *testing.T) {
	tests := []struct {
		name           string
		infosquito     bool
		dataUsage      bool
		wantEntryCount int
	}{
		{"both enabled", true, true, 2},
		{"only infosquito", true, false, 1},
		{"only data-usage", false, true, 1},
		{"none enabled", false, false, 0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := &Config{
				InfosquitoDayNum:  1,
				InfosquitoEnabled: tt.infosquito,
				DataUsageInterval: 3,
				DataUsageEnabled:  tt.dataUsage,
			}
			c, err := buildScheduler(cfg, &fakePublisher{})
			if err != nil {
				t.Fatalf("buildScheduler returned error: %v", err)
			}
			if got := len(c.Entries()); got != tt.wantEntryCount {
				t.Errorf("got %d scheduled entries, want %d", got, tt.wantEntryCount)
			}
		})
	}
}

func TestPublishSendsRoutingKeyAndBody(t *testing.T) {
	fp := &fakePublisher{}
	publish(fp, messaging.ReindexAllKey)
	if len(fp.keys) != 1 || fp.keys[0] != messaging.ReindexAllKey {
		t.Fatalf("expected one publish of %q, got %v", messaging.ReindexAllKey, fp.keys)
	}

	var body struct {
		Message     string `json:"message"`
		TimestampMS int64  `json:"timestamp_ms"`
	}
	if err := json.Unmarshal(fp.bodies[0], &body); err != nil {
		t.Fatalf("published body is not valid JSON: %v", err)
	}
	if body.Message != messageBody {
		t.Errorf("message = %q, want %q", body.Message, messageBody)
	}
	if body.TimestampMS <= 0 {
		t.Errorf("timestamp_ms = %d, want a positive epoch-millis value", body.TimestampMS)
	}
}
