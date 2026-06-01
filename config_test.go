package main

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func writeConfig(t *testing.T, contents string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "clockwork.properties")
	if err := os.WriteFile(path, []byte(contents), 0o600); err != nil {
		t.Fatalf("writing temp config: %v", err)
	}
	return path
}

func TestLoadConfigDefaults(t *testing.T) {
	cfg, err := LoadConfig(writeConfig(t, ""))
	if err != nil {
		t.Fatalf("LoadConfig returned error: %v", err)
	}

	want := Config{
		InfosquitoBasename: "indexing.1",
		InfosquitoDayNum:   1,
		InfosquitoEnabled:  true,
		DataUsageBasename:  "data-usage.1",
		DataUsageInterval:  3,
		DataUsageEnabled:   true,
		AMQPURI:            "amqp://guest:guest@rabbit:5672/",
		ExchangeName:       "de",
	}
	if *cfg != want {
		t.Errorf("defaults mismatch:\n got %+v\nwant %+v", *cfg, want)
	}
}

func TestLoadConfigOverrides(t *testing.T) {
	contents := `clockwork.jobs.infosquito.basename=indexing.2
clockwork.jobs.infosquito.daynum=5
clockwork.jobs.infosquito.indexing-enabled=false
clockwork.jobs.data-usage-api.basename=data-usage.2
clockwork.jobs.data-usage-api.interval=6
clockwork.jobs.data-usage-api.indexing-enabled=false
clockwork.amqp.uri=amqp://user:pass@broker:5672/
clockwork.amqp.exchange.name=custom
`
	cfg, err := LoadConfig(writeConfig(t, contents))
	if err != nil {
		t.Fatalf("LoadConfig returned error: %v", err)
	}

	want := Config{
		InfosquitoBasename: "indexing.2",
		InfosquitoDayNum:   5,
		InfosquitoEnabled:  false,
		DataUsageBasename:  "data-usage.2",
		DataUsageInterval:  6,
		DataUsageEnabled:   false,
		AMQPURI:            "amqp://user:pass@broker:5672/",
		ExchangeName:       "custom",
	}
	if *cfg != want {
		t.Errorf("overrides mismatch:\n got %+v\nwant %+v", *cfg, want)
	}
}

func TestLoadConfigValidation(t *testing.T) {
	tests := []struct {
		name    string
		config  string
		wantErr bool
	}{
		{"daynum too low", "clockwork.jobs.infosquito.daynum=0", true},
		{"daynum too high", "clockwork.jobs.infosquito.daynum=8", true},
		{"daynum lower bound", "clockwork.jobs.infosquito.daynum=1", false},
		{"daynum upper bound", "clockwork.jobs.infosquito.daynum=7", false},
		{"interval zero", "clockwork.jobs.data-usage-api.interval=0", true},
		{"interval negative", "clockwork.jobs.data-usage-api.interval=-1", true},
		{"interval one", "clockwork.jobs.data-usage-api.interval=1", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := LoadConfig(writeConfig(t, tt.config))
			if tt.wantErr {
				var cfgErr *ConfigError
				if !errors.As(err, &cfgErr) {
					t.Errorf("expected a *ConfigError, got %v", err)
				}
			} else if err != nil {
				t.Errorf("unexpected error: %v", err)
			}
		})
	}
}
