# clockwork

Scheduled jobs for the CyVerse Discovery Environment.

## Build

```docker build --rm -t discoenv/clockwork:dev .```

## Usage

To update and run clockwork locally, run the following two commands:

```
$ docker pull discoenv/clockwork:dev
$ docker run -P -d --name clockwork -v /path/to/config:/etc/iplant/de/clockwork.properties discoenv/clockwork:dev
```

You can skip the first command if you've built the clockwork Docker container image locally.

Clockwork gets its configuration settings from a configuration file. The path
to the configuration file is given with the --config command-line setting.
