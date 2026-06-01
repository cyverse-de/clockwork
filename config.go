package main

import (
	"fmt"
	"os"

	"github.com/magiconair/properties"
)

// amqpURIEnvVar lets the credential-bearing AMQP URI be supplied via the
// environment (e.g. direnv for local runs) instead of the config file. It takes
// precedence over the config-file value.
const amqpURIEnvVar = "CLOCKWORK_AMQP_URI"

// Config holds the settings clockwork needs to schedule its jobs and publish
// AMQP messages. It mirrors the keys read by the original Clojure service.
type Config struct {
	InfosquitoBasename string
	InfosquitoDayNum   int
	InfosquitoEnabled  bool

	DataUsageBasename string
	DataUsageInterval int
	DataUsageEnabled  bool

	AMQPURI      string
	ExchangeName string
}

// ConfigError describes an invalid configuration value.
type ConfigError struct {
	Key     string
	Message string
}

func (e *ConfigError) Error() string {
	return fmt.Sprintf("invalid configuration for %s: %s", e.Key, e.Message)
}

// LoadConfig reads clockwork's settings from a Java-style .properties file,
// applying defaults for any missing keys and validating the result.
func LoadConfig(path string) (*Config, error) {
	p, err := properties.LoadFile(path, properties.UTF8)
	if err != nil {
		return nil, err
	}

	cfg := &Config{
		InfosquitoBasename: p.GetString("clockwork.jobs.infosquito.basename", "indexing.1"),
		InfosquitoDayNum:   p.GetInt("clockwork.jobs.infosquito.daynum", 1),
		InfosquitoEnabled:  p.GetBool("clockwork.jobs.infosquito.indexing-enabled", true),
		DataUsageBasename:  p.GetString("clockwork.jobs.data-usage-api.basename", "data-usage.1"),
		DataUsageInterval:  p.GetInt("clockwork.jobs.data-usage-api.interval", 3),
		DataUsageEnabled:   p.GetBool("clockwork.jobs.data-usage-api.indexing-enabled", true),
		AMQPURI:            amqpURI(p),
		ExchangeName:       p.GetString("clockwork.amqp.exchange.name", "de"),
	}

	if err := cfg.validate(); err != nil {
		return nil, err
	}

	return cfg, nil
}

// amqpURI resolves the AMQP connection URI, preferring the environment variable
// so the credential-bearing URI need not live in the config file.
func amqpURI(p *properties.Properties) string {
	if uri := os.Getenv(amqpURIEnvVar); uri != "" {
		return uri
	}
	return p.GetString("clockwork.amqp.uri", "")
}

// validate rejects values that would produce an invalid schedule or connection.
// The original Clojure service performed no such checks and would fail later at
// trigger registration or first publish; failing fast here gives a clearer error.
func (c *Config) validate() error {
	if c.AMQPURI == "" {
		return &ConfigError{
			Key:     "clockwork.amqp.uri",
			Message: fmt.Sprintf("required: set it in the config file or the %s environment variable", amqpURIEnvVar),
		}
	}
	if c.InfosquitoDayNum < 1 || c.InfosquitoDayNum > 7 {
		return &ConfigError{
			Key:     "clockwork.jobs.infosquito.daynum",
			Message: "must be between 1 (Sunday) and 7 (Saturday)",
		}
	}
	if c.DataUsageInterval < 1 {
		return &ConfigError{
			Key:     "clockwork.jobs.data-usage-api.interval",
			Message: "must be at least 1 hour",
		}
	}
	return nil
}
