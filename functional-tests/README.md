# clockwork functional / parity tests

Language-agnostic, black-box functional tests for `clockwork`. They drive the
service as an opaque container and assert only on externally observable behavior —
the AMQP messages it publishes (observed via the RabbitMQ management HTTP API) and
how it shuts down. The same suite runs unchanged against the Clojure (`main`) and
Go (`golang`) builds to verify they behave identically.

**`main` is the source of truth.** The suite encodes the behavioral contract; a
test on which the two builds disagree is a divergence to reconcile, not an
automatic pass for either side.

## Requirements

- Docker (with `docker compose`)
- [`uv`](https://docs.astral.sh/uv/) for running the Python suite

## Run the full parity comparison

```
./build-images.sh     # builds clockwork:main and clockwork:golang from git worktrees
./run-parity.sh       # broker up -> suite vs each image -> envelope diff -> summary
```

`run-parity.sh` runs the identical suite against both images, diffs the recorded
message envelopes, and prints a summary. It exits non-zero if either build fails
the suite.

## Iterate against a single build

With a broker already running (`docker compose up -d`):

```
CLOCKWORK_IMAGE=clockwork:golang uv run pytest -v
```

The `broker` fixture starts RabbitMQ itself if one isn't already up, so the above
also works from a cold start.

## What is covered

- **test_publishing** — the data-usage job publishes at startup with routing key
  `index.usage.data`, content type `application/json`, and body
  `{"message":"Sent by clockwork","timestamp_ms":<int>}`; the configured exchange
  name is honored; and a production-style percent-encoded vhost in the URI
  (`.../%2Fprod%2Fde` → `/prod/de`) is connected to and published on. Transport
  details (delivery mode) are recorded and diffed rather than asserted, since they
  may differ by runtime.
- **test_config_gating** — a disabled data-usage job publishes nothing.
- **test_infosquito** — enabling the weekly infosquito job neither crashes startup
  nor fires immediately (its 23:00 day-of-week schedule can't be triggered quickly;
  the day-of-week mapping is covered by the Go unit tests in `jobs_test.go`).
- **test_shutdown** — on SIGTERM (`docker stop`) the service drains and terminates
  within the grace period rather than being SIGKILLed. Exit codes differ
  legitimately by runtime (JVM 143 vs Go 0), so the check is "not SIGKILLed".

## Divergences this suite found

- **Delivery mode (fixed).** The Clojure original published transient messages
  (langohr sets no delivery mode); the initial Go port used persistent delivery.
  The Go port was changed to publish transient, restoring parity. The recorded
  envelopes now match.
- **Vhost from a bare-slash URI (no production impact).** Given `amqp://host:5672/`,
  the RabbitMQ Java client (Clojure) requests the empty vhost while amqp091-go
  requests `/`. This only affects the bare-slash form; production configures an
  explicit percent-encoded vhost (`/prod/de`), which both libraries interpret
  identically — confirmed by `test_prod_style_vhost`. The bare-slash default still
  lives in the config defaults and could optionally be made explicit (`…/%2f`).
