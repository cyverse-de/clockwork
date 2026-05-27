# Clockwork JVM Footprint & Startup Recipe

**Status:** Design
**Date:** 2026-05-27
**Service:** clockwork (testbed for a recipe to be applied to other DE Clojure services)

## Goal

Reduce idle memory footprint and startup time for `clockwork`, and in the process produce a portable recipe — Dockerfile pattern + `JAVA_TOOL_OPTIONS` tiers + k8s sizing guidance — that can be applied to the other DE Clojure services (terrain, apps, data-info, metadata, etc.) with minimal per-service adaptation.

Clockwork is a long-running daemon that mostly idles between scheduled jobs, so steady-state memory matters more than startup time *for this service specifically*. The recipe, however, needs to cover startup-sensitive services too, which is why it includes Leyden's AOT cache.

## Techniques

Four levers, in order of footprint impact:

1. **GC and heap tuning.** Pinned small heap + SerialGC for tiny idle daemons. Tiered defaults in the shared `java-tool-options` configmap (`low` / `medium` / `high`).
2. **Application Class Data Sharing (AppCDS).** Subsumed by AOT cache below; not configured separately.
3. **Project Leyden AOT cache** (JDK 24+, LTS in 25). `-XX:AOTMode=record` during build, `-XX:AOTMode=on -XX:AOTCache=app.aot` at runtime. Shrinks startup and reduces footprint by eliminating per-start JIT/class-loading work.
4. **JDK upgrade to 25 LTS.** Enables Leyden. Also lets us drop to a distroless runtime base, shrinking image size.

## Architecture

Three-stage Docker build, distroless runtime, with AOT cache baked into the image.

### Stage 1 — Builder

- Base: `clojure:temurin-25-lein-jammy`
- Runs `lein uberjar` exactly as today (uberjar is `:aot :all`, jar is JVM-agnostic).
- Output: `clockwork-standalone.jar`.

### Stage 2 — AOT trainer

- Base: `gcr.io/distroless/java25-debian13:debug`
- Same OpenJDK build as the runtime stage — required because AOT caches are sensitive to JDK vendor/version/build.
- Copies in the uberjar from Stage 1 and a training config file from the repo.
- Two commands:
  ```
  java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf \
       -cp clockwork-standalone.jar clockwork.core \
       --config conf/training/clockwork.properties &
  PID=$!; sleep 5; kill -TERM $PID; wait $PID

  java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf \
       -XX:AOTCache=app.aot \
       -cp clockwork-standalone.jar clockwork.core
  ```
- Output: `app.aot`.

### Stage 3 — Runtime

- Base: `gcr.io/distroless/java25-debian13`
- Copies in: `clockwork-standalone.jar`, `app.aot`, `logback.xml`.
- ENTRYPOINT (exec form, no shell):
  ```
  ["java",
   "-XX:AOTMode=on", "-XX:AOTCache=/usr/src/app/app.aot",
   "-Dorg.terracotta.quartz.skipUpdateCheck=true",
   "-Dlogback.configurationFile=/usr/src/app/logback.xml",
   "-cp", "/usr/src/app/clockwork-standalone.jar",
   "clockwork.core"]
  ```
- The cosmetic `/bin/clockwork` symlink is dropped — distroless has no shell to create it, and the cost is purely cosmetic.

## JVM option strategy

Two homes for JVM options, by concern:

### Per-service (Dockerfile ENTRYPOINT)

Options that depend on this image's filesystem layout:

- `-XX:AOTMode=on -XX:AOTCache=/usr/src/app/app.aot`
- `-Dlogback.configurationFile=/usr/src/app/logback.xml`
- `-Dorg.terracotta.quartz.skipUpdateCheck=true`

Convention: every service places its AOT cache at `/usr/src/app/app.aot`, matching the existing `WORKDIR`.

### Shared (`java-tool-options` configmap, tiered)

Heap and GC settings, picked per-service. The configmap is managed in a separate git repo; the spec proposes the tiers but does not modify the configmap directly.

| Tier | Options | Use for |
|---|---|---|
| `low` | `-Xms128m -Xmx128m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError` | Tiny idle daemons (clockwork, info-typer, bulk-typer) |
| `medium` | `-XX:MaxRAMPercentage=60 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` | API services with moderate load |
| `high` | `-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` | Heavyweight services |

Layering: `JAVA_TOOL_OPTIONS` is processed before explicit args, so per-service ENTRYPOINT options can override tier defaults.

Rationale for `low`:
- **Pinned heap (`-Xms == -Xmx`)**: prevents GC-driven heap growth churn on a service that rarely allocates.
- **SerialGC**: lowest footprint GC, ideal for small heaps and low job rates. The two clockwork jobs run at minutes/hours intervals; throughput is irrelevant.
- **`+ExitOnOutOfMemoryError`**: fail fast, let k8s restart, rather than degrading.

Explicitly *not* set: `MaxMetaspaceSize`, `AlwaysPreTouch`, GC pause-time targets. Adds risk without meaningful benefit at this scale.

## Code changes in clockwork

### Shutdown hook in `src/clockwork/core.clj`

Today `qs/start` returns and `-main` exits; Quartz keeps the JVM alive via non-daemon threads, but on SIGTERM the JVM tears down abruptly. Add a `Runtime/getRuntime addShutdownHook` that calls `qs/shutdown` on the scheduler. ~5 lines.

This is needed for:
- The trainer stage's `kill -TERM` to produce a clean exit (cleaner AOT recording).
- Clean k8s rolling restarts in production (jobs in flight aren't killed mid-execution).

### Training config `conf/training/clockwork.properties`

All `clockwork.config` properties have defaults via `defprop-opt*`, so the file only needs to override the two enable flags:

```
clockwork.jobs.infosquito.indexing-enabled=false
clockwork.jobs.data-usage-api.indexing-enabled=false
```

This lets the scheduler initialize fully — exercising the code paths AOT recording cares about — without firing any jobs (which would try to reach RabbitMQ).

## Files touched

In this PR (clockwork repo):

- `Dockerfile` — rewritten as 3-stage.
- `src/clockwork/core.clj` — add SIGTERM shutdown hook.
- `conf/training/clockwork.properties` — new file.
- `docs/superpowers/specs/2026-05-27-clockwork-jvm-footprint-design.md` — this spec.

Not in this PR, documented for follow-up:

- `k8s/clockwork.yml` — proposed `resources` change: request `256Mi`/`100m`, limit `512Mi`/`650m`. (Heap is pinned at 128m, but Clojure metaspace + native memory + AOT cache mmaps push real RSS to roughly 200–280MB, so request needs headroom over `-Xmx`.) Applied via the git-managed manifests repo separately.
- `java-tool-options` configmap (separate repo) — proposal to define `low` / `medium` / `high` tiers as described above.

## Verification — before/after measurement

Captured locally for both old (current `main`) and new images, three trials each, median reported.

| Metric | How to measure |
|---|---|
| Image size | `docker images --format '{{.Repository}} {{.Size}}'` |
| Startup time | `time docker run --rm <image> --config <test-config>` from `docker run` invocation to log line `clockwork startup`. Three trials, take median. |
| Idle RSS | Run container 60s with a test config; `docker stats --no-stream --format '{{.MemUsage}}'` at t=60s. |
| AOT cache hit (new only) | Start with `-Xlog:aot=info`; confirm cache load message in logs. |

Results (measured locally on macOS / Docker Desktop, arm64):

| Metric | Baseline | With recipe | Δ |
|---|---|---|---|
| Image size | 1.06 GB | 633 MB | −427 MB (−40%) |
| Cold startup (trial 1) | 3.28 s | 3.16 s | −0.12 s |
| Warm startup (median of trials 2–3) | 0.72 s | 0.43 s | −0.29 s (−40%) |
| Idle RSS at 60 s | 137.6 MiB | 117.5 MiB | −20.1 MiB (−15%) |
| AOT cache loaded | n/a | yes | — |

AOT cache load confirmation from `-Xlog:aot=info` at runtime:

```
[0.004s][info][aot] Opened AOT cache /usr/src/app/app.aot.
[0.004s][info][aot] The AOT cache was created with UseCompressedOops = 1, UseCompressedClassPointers = 1, UseCompactObjectHeaders = 0
```

The cache loads 4 ms into JVM startup, before application classes are linked.

### Implementation deviations from this spec

Two discoveries during implementation:

1. **Builder base image tag.** `clojure:temurin-25-lein-jammy` is not published on Docker Hub. The Dockerfile uses `clojure:temurin-25-lein-bookworm` instead (Debian 13 base, Temurin 25, Lein).

2. **Runtime image must be `gcr.io/distroless/java25-debian13:debug`, not `:latest`.** The `:debug` tag ships the Temurin JDK; `:latest` ships the JRE. Their `lib/modules` differ in size, and the Leyden AOT cache rejects the runtime if the modules image differs from the one used during recording:

   ```
   [warning][aot] This file is not the one used while building the shared archive file:
   '/usr/lib/jvm/temurin-25-jre-arm64/lib/modules', size has changed
   [error][aot] Unable to map shared spaces
   ```

   Pinning both trainer and runtime to `:debug` guarantees compatibility but adds a small busybox layer to the runtime, which is why the recipe image lands at 633 MB rather than the ~250–350 MB the original spec anticipated. Other DE services applying this recipe should expect the same constraint until distroless ships a JRE variant whose modules match the JDK variant.

If startup or RSS don't improve materially on other services, this is a signal to revisit before applying further.

## Risks

- **AOT cache JDK mismatch.** Mitigated by training and runtime stages using the same distroless OpenJDK 25 build. If a future Debian point release in distroless ships a JDK rebuild, the existing AOT cache may be silently rejected and the service will fall back to slow start. The recipe's verification step (AOT cache hit log line) catches this.
- **Training run fails to reach scheduler init.** A `sleep 5; kill` is generous for clockwork's startup but every service author applying the recipe needs to tune this for their service's startup time.
- **Distroless ergonomics.** No shell in the runtime image means no `kubectl exec sh` for debugging. The `:debug` tag exists for that case; document switching to it as a debug-only workflow.

## Portability to other DE Clojure services

What other services need to change when applying this recipe:

1. Switch Dockerfile to the 3-stage pattern (mechanical).
2. Provide a training config file that initializes their service's main code paths without making external calls.
3. Add a SIGTERM shutdown hook if they don't have one.
4. Pick a `JAVA_TOOL_OPTIONS` tier (`low` / `medium` / `high`).
5. Resize k8s `resources` based on local measurement.

API services (terrain, apps, etc.) will need a slightly more elaborate training run — e.g., a brief `curl` against a known endpoint after server start — to record realistic request-path code. That extension lives outside this spec.
