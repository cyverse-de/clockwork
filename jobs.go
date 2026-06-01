package main

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/cyverse-de/messaging/v12"
	"github.com/robfig/cron/v3"
	"github.com/sirupsen/logrus"
)

// dataUsageKey is the routing key used to trigger data-usage-api recalculation.
// The messaging library only defines a constant for the infosquito key
// (messaging.ReindexAllKey), so this one is defined locally.
const dataUsageKey = "index.usage.data"

const messageBody = "Sent by clockwork"

// publisher is the subset of the messaging client that jobs depend on, so the
// scheduler can be exercised with a fake in tests.
type publisher interface {
	PublishOpts(key string, body []byte, opts *messaging.PublishingOpts) error
}

// publish sends the same JSON envelope the Clojure service used:
// {"message": ..., "timestamp_ms": <epoch millis>} with a JSON content type.
func publish(p publisher, key string) {
	body, err := json.Marshal(map[string]any{
		"message":      messageBody,
		"timestamp_ms": time.Now().UnixMilli(),
	})
	if err != nil {
		logrus.WithError(err).WithField("routing-key", key).Error("failed to encode AMQP message")
		return
	}
	if err := p.PublishOpts(key, body, messaging.JSONPublishingOpts); err != nil {
		logrus.WithError(err).WithField("routing-key", key).
			Error("failed to publish AMQP message; the broker may be unreachable")
	}
}

// infosquitoSpec builds the weekly cron spec for the infosquito job. Quartz
// numbers days 1=Sunday..7=Saturday; cron uses 0=Sunday..6=Saturday, so the
// day number is shifted down by one. The job fires at 23:00, matching the
// original weekly-on-day-and-hour-and-minute schedule.
func infosquitoSpec(daynum int) string {
	return fmt.Sprintf("0 23 * * %d", daynum-1)
}

// dataUsageSpec builds the interval spec for the data-usage job.
func dataUsageSpec(intervalHours int) string {
	return fmt.Sprintf("@every %dh", intervalHours)
}

// buildScheduler registers the enabled jobs on a new cron scheduler. Jobs are
// skipped if a prior run is still in flight, matching the service's
// drop-don't-backlog posture.
func buildScheduler(cfg *Config, p publisher) (*cron.Cron, error) {
	c := cron.New(
		cron.WithLocation(time.Local),
		cron.WithChain(cron.SkipIfStillRunning(cron.DefaultLogger)),
	)

	if cfg.InfosquitoEnabled {
		if _, err := c.AddFunc(infosquitoSpec(cfg.InfosquitoDayNum), func() {
			publish(p, messaging.ReindexAllKey)
		}); err != nil {
			return nil, fmt.Errorf("scheduling infosquito job %q: %w", cfg.InfosquitoBasename, err)
		}
		logrus.WithField("job", cfg.InfosquitoBasename).Info("scheduled infosquito indexing")
	}

	if cfg.DataUsageEnabled {
		if _, err := c.AddFunc(dataUsageSpec(cfg.DataUsageInterval), func() {
			publish(p, dataUsageKey)
		}); err != nil {
			return nil, fmt.Errorf("scheduling data-usage job %q: %w", cfg.DataUsageBasename, err)
		}
		logrus.WithField("job", cfg.DataUsageBasename).Info("scheduled data-usage-api updates")
	}

	return c, nil
}
