package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/cyverse-de/messaging/v12"
	"github.com/sirupsen/logrus"
)

// log stamps every entry with service=clockwork, matching the field the previous
// logback configuration added and the convention used across the DE Go services.
var log = logrus.WithField("service", "clockwork")

// drainTimeout bounds how long shutdown waits for in-flight jobs to finish.
// It is kept under the default Kubernetes terminationGracePeriodSeconds (30s)
// so the pod drains cleanly before SIGKILL.
const drainTimeout = 25 * time.Second

func main() {
	configPath := flag.String("config", "/etc/iplant/de/clockwork.properties", "Path to the config file")
	logLevel := flag.String("log-level", "info", "One of trace, debug, info, warn, error, fatal, or panic")
	flag.Parse()

	setupLogging(*logLevel)

	if err := run(*configPath); err != nil {
		log.Fatal(err)
	}
}

func setupLogging(level string) {
	logrus.SetFormatter(&logrus.JSONFormatter{})
	lvl, err := logrus.ParseLevel(level)
	if err != nil {
		log.WithError(err).Warnf("invalid log level %q; defaulting to info", level)
		lvl = logrus.InfoLevel
	}
	logrus.SetLevel(lvl)
}

func run(configPath string) error {
	if _, err := os.Stat(configPath); err != nil {
		return fmt.Errorf("config file %s is not accessible: %w", configPath, err)
	}

	log.Info("clockwork startup")
	cfg, err := LoadConfig(configPath)
	if err != nil {
		return err
	}

	client, err := messaging.NewClient(cfg.AMQPURI, true)
	if err != nil {
		return fmt.Errorf("connecting to AMQP: %w", err)
	}
	defer client.Close()

	if err := client.SetupPublishing(cfg.ExchangeName); err != nil {
		return fmt.Errorf("setting up AMQP publishing on exchange %q: %w", cfg.ExchangeName, err)
	}

	scheduler, err := buildScheduler(cfg, client)
	if err != nil {
		return err
	}
	scheduler.Start()

	// Quartz's calendar-interval trigger fired at startup; cron's @every only
	// fires after the first interval elapses, so kick off the data-usage job
	// once now to preserve the original fire-at-start behavior.
	if cfg.DataUsageEnabled {
		publish(client, dataUsageKey)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
	// Restore default signal handling so a second signal during the drain
	// terminates the process immediately instead of being swallowed.
	stop()

	log.Info("shutdown requested; draining in-flight jobs")
	select {
	case <-scheduler.Stop().Done():
		log.Info("all in-flight jobs drained")
	case <-time.After(drainTimeout):
		log.Warn("drain timed out; shutting down with jobs still running")
	}

	return nil
}
