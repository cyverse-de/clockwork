# clockwork

Scheduled jobs for the CyVerse Discovery Environment.

Clockwork is a small daemon that runs two periodic jobs, each of which publishes
a JSON message to the `de` RabbitMQ topic exchange:

- **infosquito indexing** — weekly, on a configurable day of week at 23:00, with
  routing key `index.all`.
- **data-usage-api updates** — every N hours (default 3), with routing key
  `index.usage.data`.

## Build

Clockwork uses [`just`](https://github.com/casey/just) for build tasks:

```
$ just build   # build the ./clockwork binary
$ just test    # run the unit tests
$ just lint    # run golangci-lint
```

To build the container image locally:

```
$ docker build --rm -t clockwork .
```

The published image is built and tagged by CI (skaffold, tagged by git commit); the
local tag above is just for ad-hoc local runs.

## Usage

```
$ ./clockwork --config /path/to/clockwork.properties
```

Run with a local broker:

```
$ docker run -d --name clockwork \
    -v /path/to/config:/etc/iplant/de/clockwork.properties \
    clockwork \
    --config /etc/iplant/de/clockwork.properties
```

### Flags

- `--config` — path to the configuration file (default
  `/etc/iplant/de/clockwork.properties`).
- `--log-level` — one of `trace`, `debug`, `info`, `warn`, `error`, `fatal`, or
  `panic` (default `info`).
- `--version` — print the version and exit.

## Configuration

Clockwork reads a Java-style `.properties` file. All keys are optional and fall
back to the defaults below.

| Key | Default | Description |
|---|---|---|
| `clockwork.jobs.infosquito.basename` | `indexing.1` | Identifier used in logs for the infosquito job. |
| `clockwork.jobs.infosquito.daynum` | `1` | Day of week to run, `1`=Sunday … `7`=Saturday. |
| `clockwork.jobs.infosquito.indexing-enabled` | `true` | Whether the infosquito job is scheduled. |
| `clockwork.jobs.data-usage-api.basename` | `data-usage.1` | Identifier used in logs for the data-usage job. |
| `clockwork.jobs.data-usage-api.interval` | `3` | Interval in hours between data-usage updates. |
| `clockwork.jobs.data-usage-api.indexing-enabled` | `true` | Whether the data-usage job is scheduled. |
| `clockwork.amqp.uri` | `amqp://guest:guest@rabbit:5672/` | AMQP connection URI. |
| `clockwork.amqp.exchange.name` | `de` | Name of the AMQP exchange to publish to. |
