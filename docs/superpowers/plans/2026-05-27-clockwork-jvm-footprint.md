# Clockwork JVM Footprint Recipe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the JDK 25 + Leyden AOT + distroless + GC-tuned recipe to clockwork, with before/after measurements, as a testbed for other DE Clojure services.

**Architecture:** Three-stage Dockerfile (clojure-lein builder → distroless trainer recording an AOT cache → distroless runtime consuming it). Add a SIGTERM shutdown hook so the trainer's `kill` produces a clean exit and k8s restarts are graceful. JVM heap/GC tuning lives in a tiered `JAVA_TOOL_OPTIONS` configmap (proposed in spec, not modified by this PR).

**Tech Stack:** Clojure 1.11.3, Quartzite 2.2.0, Langohr 5.4.0, Temurin OpenJDK 25 (build) / distroless OpenJDK 25 (runtime), Docker (BuildKit).

**Spec:** `docs/superpowers/specs/2026-05-27-clockwork-jvm-footprint-design.md`

---

## File Structure

Files created or modified:

- `src/clockwork/core.clj` — Refactor `init-scheduler` to return the scheduler value; add `stop-scheduler` function; register a SIGTERM shutdown hook in `-main`.
- `test/clockwork/core_test.clj` — Add tests for `init-scheduler` return value and `stop-scheduler` behavior.
- `conf/training/clockwork.properties` — New training config file (disables both jobs).
- `Dockerfile` — Rewrite as 3-stage (builder → trainer → distroless runtime).
- `docs/superpowers/specs/2026-05-27-clockwork-jvm-footprint-design.md` — Fill in measurement results table.

The clockwork core is small (one source file, 109 lines), so no file split is needed. The shutdown logic stays in `core.clj` alongside `init-scheduler`.

---

## Task 1: Capture baseline measurements (current main)

Before any code changes, measure the existing image so we have a before/after comparison.

**Files:**
- None modified yet. Outputs go into a scratch file: `/tmp/clockwork-baseline.txt`.

- [ ] **Step 1: Verify clean working tree**

Run: `git status`
Expected: `nothing to commit, working tree clean` (the spec was already committed).

- [ ] **Step 2: Build the current Dockerfile**

Run:
```bash
docker build --rm -t clockwork:baseline .
```
Expected: builds successfully, image tagged `clockwork:baseline`.

- [ ] **Step 3: Record image size**

Run:
```bash
docker images clockwork:baseline --format '{{.Size}}' | tee /tmp/clockwork-baseline.txt
```
Expected: a size like `~800MB` (Clojure + JDK + Debian = large). Save this number.

- [ ] **Step 4: Prepare a measurement config file**

Run:
```bash
cp resources/test/empty-config.properties /tmp/clockwork-measure.properties
echo 'clockwork.jobs.infosquito.indexing-enabled=false' >> /tmp/clockwork-measure.properties
echo 'clockwork.jobs.data-usage-api.indexing-enabled=false' >> /tmp/clockwork-measure.properties
```
Expected: file at `/tmp/clockwork-measure.properties` exists, contains two enable=false lines. This config keeps the scheduler from trying to publish to RabbitMQ during measurement.

- [ ] **Step 5: Measure startup time, 3 trials**

For each trial, run:
```bash
docker run --rm -d --name cw-bench \
  -v /tmp/clockwork-measure.properties:/etc/iplant/de/clockwork.properties:ro \
  clockwork:baseline --config /etc/iplant/de/clockwork.properties

START=$(date +%s.%N)
until docker logs cw-bench 2>&1 | grep -q "clockwork startup"; do sleep 0.1; done
END=$(date +%s.%N)
echo "trial: $(echo "$END - $START" | bc)s" | tee -a /tmp/clockwork-baseline.txt
docker rm -f cw-bench
```
Expected: 3 lines like `trial: 4.7s` appended to the file. Take the median.

- [ ] **Step 6: Measure idle RSS at 60s**

Run:
```bash
docker run --rm -d --name cw-bench \
  -v /tmp/clockwork-measure.properties:/etc/iplant/de/clockwork.properties:ro \
  clockwork:baseline --config /etc/iplant/de/clockwork.properties
sleep 60
docker stats --no-stream --format '{{.MemUsage}}' cw-bench | tee -a /tmp/clockwork-baseline.txt
docker rm -f cw-bench
```
Expected: a line like `285MiB / 7.66GiB` appended. The first number is what we want.

- [ ] **Step 7: Stash baseline numbers**

Run: `cat /tmp/clockwork-baseline.txt`
Expected: shows image size, 3 startup trials, and RSS. Keep this file — Task 8 will fold these numbers into the spec.

No commit (no repo changes yet).

---

## Task 2: Refactor `init-scheduler` to return the scheduler

The current `init-scheduler` returns whatever the last `when` form evaluates to — implicit and untested. Make it return the scheduler explicitly so a shutdown hook can be registered against it. TDD: write a test that asserts the return value first.

**Files:**
- Modify: `src/clockwork/core.clj:75-82`
- Test: `test/clockwork/core_test.clj`

- [ ] **Step 1: Write the failing test**

Add to `test/clockwork/core_test.clj`:

```clojure
(ns clockwork.core-test
  (:use clojure.test)
  (:require [clockwork.config :as config]
            [clockwork.core :as core]
            [clojurewerkz.quartzite.scheduler :as qs]))

(defn with-empty-config [f]
  (require 'clockwork.config :reload)
  (config/load-config-from-file "resources/test/empty-config.properties")
  (f))

(use-fixtures :once with-empty-config)

(deftest test-config-defaults
  (testing "configuration defaults"
    (is (= (config/irods-host) "irods"))
    (is (= (config/irods-port) "1247"))
    (is (= (config/irods-user) "rods"))
    (is (= (config/irods-password) "notprod"))
    (is (= (config/irods-home) "/iplant/home"))
    (is (= (config/irods-zone) "iplant"))
    (is (= (config/irods-resource) ""))
    (is (= (config/infosquito-job-basename) "indexing.1"))
    (is (= (config/infosquito-job-daynum) 1))
    (is (= (config/amqp-uri) "amqp://guest:guest@rabbit:5672/"))
    (is (= (config/exchange-name) "de"))))

(deftest test-init-scheduler-returns-started-scheduler
  (testing "init-scheduler returns a started Quartz scheduler"
    (let [s (#'core/init-scheduler)]
      (try
        (is (some? s))
        (is (qs/started? s))
        (finally
          (qs/shutdown s))))))
```

The `#'core/init-scheduler` notation reaches the private var. The `try`/`finally` shuts down the scheduler so it doesn't leak between tests.

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only clockwork.core-test/test-init-scheduler-returns-started-scheduler`
Expected: FAIL — either `is (some? s)` fails (current code returns `nil` from the `when` form when both job-enable flags are false) or the assertion behaviour is otherwise unmet. The point is to see it fail before we change the code.

- [ ] **Step 3: Modify `init-scheduler` to return the scheduler**

In `src/clockwork/core.clj`, replace lines 75–82 (the `init-scheduler` defn) with:

```clojure
(defn- init-scheduler
  "Initializes the scheduler and returns it."
  []
  (let [s (-> (qs/initialize) qs/start)]
    (when (config/infosquito-indexing-enabled)
      (schedule-infosquito-indexing s))
    (when (config/data-usage-api-indexing-enabled)
      (schedule-data-usage-api s))
    s))
```

The only change: add `s` as the last form in the `let` body so it's the return value.

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only clockwork.core-test/test-init-scheduler-returns-started-scheduler`
Expected: PASS, no other tests broken.

- [ ] **Step 5: Run full test suite**

Run: `lein test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/clockwork/core.clj test/clockwork/core_test.clj
git commit -m "$(cat <<'EOF'
Make init-scheduler return the scheduler

Lets callers register a shutdown hook against the running scheduler. No
behavior change for production paths.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add `stop-scheduler` and wire up SIGTERM shutdown hook

Add a small function that shuts down a Quartzite scheduler cleanly, and register it as a JVM shutdown hook in `-main`. TDD the function; the hook registration itself is one line of Java interop.

**Files:**
- Modify: `src/clockwork/core.clj`
- Test: `test/clockwork/core_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/clockwork/core_test.clj`:

```clojure
(deftest test-stop-scheduler-shuts-down-running-scheduler
  (testing "stop-scheduler stops a running Quartz scheduler"
    (let [s (#'core/init-scheduler)]
      (is (qs/started? s) "precondition: scheduler is started")
      (#'core/stop-scheduler s)
      (is (qs/shutdown? s) "scheduler should be shut down"))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only clockwork.core-test/test-stop-scheduler-shuts-down-running-scheduler`
Expected: FAIL with `Unable to resolve var: core/stop-scheduler` or similar.

- [ ] **Step 3: Add `stop-scheduler` to `src/clockwork/core.clj`**

Insert this function in `src/clockwork/core.clj` between `init-scheduler` and `svc-info` (after the closing paren of `init-scheduler`):

```clojure
(defn- stop-scheduler
  "Cleanly shuts down a Quartz scheduler, letting in-flight jobs finish."
  [s]
  (when s
    (qs/shutdown s true)))
```

The `true` argument tells Quartz to wait for jobs in flight to complete before returning.

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only clockwork.core-test/test-stop-scheduler-shuts-down-running-scheduler`
Expected: PASS.

- [ ] **Step 5: Register the shutdown hook in `-main`**

In `src/clockwork/core.clj`, replace the `-main` defn with:

```clojure
(defn -main
  [& args]
  (tc/with-logging-context svc-info
    (let [{:keys [options arguments errors summary]} (ccli/handle-args svc-info args cli-options)]
      (when-not (fs/exists? (:config options))
        (ccli/exit 1 (str "The config file does not exist.")))
      (when-not (fs/readable? (:config options))
        (ccli/exit 1 "The config file is not readable."))
      (log/info "clockwork startup")
      (config/load-config-from-file (:config options))
      (let [scheduler (init-scheduler)]
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable #(stop-scheduler scheduler)))))))
```

Changes:
- Bind the scheduler returned by `init-scheduler` to `scheduler`.
- Register a `Thread` shutdown hook that calls `stop-scheduler` on it.
- The `^Runnable` hint avoids a reflection warning on the `Thread.` constructor (Clojure's reflection prefers `Runnable` here).

- [ ] **Step 6: Run full test suite**

Run: `lein test`
Expected: all tests pass.

- [ ] **Step 7: Verify no reflection warnings on the new code**

Run: `lein check 2>&1 | grep -i reflection || echo "no reflection warnings"`
Expected: `no reflection warnings` (or the existing ones, none new from `-main`).

- [ ] **Step 8: Commit**

```bash
git add src/clockwork/core.clj test/clockwork/core_test.clj
git commit -m "$(cat <<'EOF'
Add SIGTERM shutdown hook to drain Quartz scheduler

On SIGTERM (k8s rolling restart, AOT trainer kill), wait for in-flight
jobs to complete instead of aborting them. Also enables cleaner AOT cache
recording during build.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Create the AOT training config file

A minimal properties file used only during the Dockerfile trainer stage. It satisfies `config/load-config-from-file` and lets the scheduler initialize, but disables both jobs so nothing tries to reach RabbitMQ during recording.

**Files:**
- Create: `conf/training/clockwork.properties`

- [ ] **Step 1: Create the file**

Write to `conf/training/clockwork.properties`:

```
# Training-only configuration used during the Dockerfile AOT-cache recording
# stage. Disables all scheduled jobs so AOT recording exercises scheduler
# init paths without firing any jobs (which would try to reach RabbitMQ).
clockwork.jobs.infosquito.indexing-enabled=false
clockwork.jobs.data-usage-api.indexing-enabled=false
```

All other clockwork.config properties have defaults via `defprop-opt*`, so they don't need to appear here.

- [ ] **Step 2: Verify it parses against the existing test fixture**

Run:
```bash
cd /Users/sarahr/src/de/clockwork
lein run -- --config conf/training/clockwork.properties &
PID=$!
sleep 3
kill -TERM $PID
wait $PID 2>/dev/null
```
Expected: the process starts, logs `clockwork startup`, runs for 3 seconds, then exits cleanly when SIGTERM arrives (thanks to Task 3's shutdown hook). No stack traces about missing config. (`lein run` picks up `:main clockwork.core` from `project.clj`.)

- [ ] **Step 3: Commit**

```bash
git add conf/training/clockwork.properties
git commit -m "$(cat <<'EOF'
Add training config for AOT cache recording

Used only by the Dockerfile trainer stage. All jobs disabled so AOT
recording exercises scheduler init without firing jobs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Rewrite the Dockerfile as a 3-stage build

Replace the current single-stage Dockerfile with builder → trainer → distroless runtime.

**Files:**
- Modify: `Dockerfile` (full rewrite)

- [ ] **Step 1: Verify required base images are available**

Run:
```bash
docker pull clojure:temurin-25-lein-jammy
docker pull gcr.io/distroless/java25-debian13:debug
docker pull gcr.io/distroless/java25-debian13:latest
```
Expected: all three pulls succeed. If `clojure:temurin-25-lein-jammy` doesn't exist, use `clojure:temurin-25-lein` (the non-OS-suffixed tag) or fall back to `clojure:temurin-25-lein-bookworm` — confirm the tag exists on Docker Hub before proceeding.

- [ ] **Step 2: Replace the Dockerfile**

Replace the entire contents of `Dockerfile` with:

```dockerfile
# syntax=docker/dockerfile:1.7

#####################
# Stage 1: builder
#####################
FROM clojure:temurin-25-lein-jammy AS builder
WORKDIR /usr/src/app

RUN apt-get update && \
    apt-get install -y --no-install-recommends git && \
    rm -rf /var/lib/apt/lists/*

COPY project.clj /usr/src/app/
RUN lein deps

COPY . /usr/src/app
RUN lein uberjar && cp target/clockwork-standalone.jar /usr/src/app/clockwork-standalone.jar

#####################
# Stage 2: AOT trainer
#####################
# Use the same OpenJDK build as the runtime stage so the AOT cache is
# guaranteed compatible. The :debug tag adds busybox (sh + kill).
FROM gcr.io/distroless/java25-debian13:debug AS trainer
WORKDIR /usr/src/app

COPY --from=builder /usr/src/app/clockwork-standalone.jar ./
COPY conf/training/clockwork.properties ./training-config.properties

# Step 1: record an AOT configuration by running the app briefly. The SIGTERM
# shutdown hook makes this exit cleanly.
RUN ["/busybox/sh", "-c", "\
    /usr/bin/java \
      -XX:AOTMode=record \
      -XX:AOTConfiguration=app.aotconf \
      -cp clockwork-standalone.jar clockwork.core \
      --config training-config.properties & \
    PID=$!; sleep 5; kill -TERM $PID; wait $PID || true \
"]

# Step 2: create the AOT cache from the recorded configuration.
RUN ["/busybox/sh", "-c", "\
    /usr/bin/java \
      -XX:AOTMode=create \
      -XX:AOTConfiguration=app.aotconf \
      -XX:AOTCache=app.aot \
      -cp clockwork-standalone.jar clockwork.core \
"]

#####################
# Stage 3: runtime
#####################
FROM gcr.io/distroless/java25-debian13
WORKDIR /usr/src/app

COPY --from=builder /usr/src/app/clockwork-standalone.jar ./
COPY --from=builder /usr/src/app/conf/main/logback.xml ./logback.xml
COPY --from=trainer /usr/src/app/app.aot ./app.aot

ENTRYPOINT ["/usr/bin/java", \
            "-XX:AOTMode=on", \
            "-XX:AOTCache=/usr/src/app/app.aot", \
            "-Dorg.terracotta.quartz.skipUpdateCheck=true", \
            "-Dlogback.configurationFile=/usr/src/app/logback.xml", \
            "-cp", "/usr/src/app/clockwork-standalone.jar", \
            "clockwork.core"]
CMD ["--help"]

ARG git_commit=unknown
ARG version=unknown
ARG descriptive_version=unknown

LABEL org.cyverse.git-ref="$git_commit"
LABEL org.cyverse.version="$version"
LABEL org.cyverse.descriptive-version="$descriptive_version"
LABEL org.label-schema.vcs-ref="$git_commit"
LABEL org.label-schema.vcs-url="https://github.com/cyverse-de/clockwork"
LABEL org.label-schema.version="$descriptive_version"
```

Key points:
- The trainer stage uses `/busybox/sh` — distroless `:debug` puts busybox at `/busybox/`, not on PATH.
- Both trainer RUN commands use `-XX:AOTMode=create` second, after `-XX:AOTMode=record`. The order matters: record produces the `.aotconf`, create reads it and emits the `.aot`.
- The `clockwork.core` invocation in the create step does *not* need `--config` — `-XX:AOTMode=create` exits after producing the cache and doesn't execute the program.
- The runtime stage's `java` binary lives at `/usr/bin/java` in distroless java images.

- [ ] **Step 3: Build the new image**

Run:
```bash
DOCKER_BUILDKIT=1 docker build --rm -t clockwork:recipe .
```
Expected: build completes successfully. The trainer stage logs `clockwork startup` (from the record phase) and then the create phase produces `app.aot`.

If the trainer stage fails (e.g., `kill -TERM` doesn't trigger a clean exit, or the create step errors): re-read the trainer stage RUN output and diagnose. The most common issue is the JVM still being warming up when `kill` fires — increase `sleep 5` to `sleep 10`.

- [ ] **Step 4: Verify image runs**

Run:
```bash
docker run --rm \
  -v /tmp/clockwork-measure.properties:/etc/iplant/de/clockwork.properties:ro \
  clockwork:recipe --config /etc/iplant/de/clockwork.properties &
sleep 5
docker ps --filter ancestor=clockwork:recipe --format '{{.ID}}' | head -1 | xargs -I {} docker logs {} 2>&1 | grep -E "(clockwork startup|AOT)"
docker ps --filter ancestor=clockwork:recipe --format '{{.ID}}' | head -1 | xargs docker rm -f
```
Expected: log contains `clockwork startup`. (AOT log lines appear only with `-Xlog:aot=info`, exercised in Task 6.)

- [ ] **Step 5: Commit**

```bash
git add Dockerfile
git commit -m "$(cat <<'EOF'
Rewrite Dockerfile as 3-stage build with AOT cache on distroless

Builder runs lein uberjar; trainer records and creates a Leyden AOT cache
on the same OpenJDK build as the runtime; runtime is distroless Java 25.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Capture new-image measurements

Mirror Task 1 but for `clockwork:recipe`, and additionally verify the AOT cache actually loads.

**Files:**
- Outputs to `/tmp/clockwork-recipe.txt`.

- [ ] **Step 1: Record image size**

Run:
```bash
docker images clockwork:recipe --format '{{.Size}}' | tee /tmp/clockwork-recipe.txt
```
Expected: a value materially smaller than `clockwork:baseline` (the distroless runtime drops Clojure, lein, OS packages — typically ~250–350MB vs ~800MB+).

- [ ] **Step 2: Measure startup time, 3 trials**

For each trial:
```bash
docker run --rm -d --name cw-bench \
  -v /tmp/clockwork-measure.properties:/etc/iplant/de/clockwork.properties:ro \
  clockwork:recipe --config /etc/iplant/de/clockwork.properties

START=$(date +%s.%N)
until docker logs cw-bench 2>&1 | grep -q "clockwork startup"; do sleep 0.1; done
END=$(date +%s.%N)
echo "trial: $(echo "$END - $START" | bc)s" | tee -a /tmp/clockwork-recipe.txt
docker rm -f cw-bench
```
Expected: 3 trials; median should be lower than baseline.

- [ ] **Step 3: Measure idle RSS at 60s**

Run:
```bash
docker run --rm -d --name cw-bench \
  -e JAVA_TOOL_OPTIONS="-Xms128m -Xmx128m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError" \
  -v /tmp/clockwork-measure.properties:/etc/iplant/de/clockwork.properties:ro \
  clockwork:recipe --config /etc/iplant/de/clockwork.properties
sleep 60
docker stats --no-stream --format '{{.MemUsage}}' cw-bench | tee -a /tmp/clockwork-recipe.txt
docker rm -f cw-bench
```
Expected: RSS lower than baseline. Note: this exercises the `low` JAVA_TOOL_OPTIONS tier directly via `-e` (the configmap that would set this in cluster is out-of-scope for this PR).

- [ ] **Step 4: Verify the AOT cache is actually loaded**

Run:
```bash
docker run --rm \
  -e JAVA_TOOL_OPTIONS="-Xlog:aot=info" \
  -v /tmp/clockwork-measure.properties:/etc/iplant/de/clockwork.properties:ro \
  clockwork:recipe --config /etc/iplant/de/clockwork.properties 2>&1 | \
  grep -E "(AOT|aot)" | head -10 | tee -a /tmp/clockwork-recipe.txt
```
Expected: at least one line indicating the AOT cache was loaded successfully (e.g. `Opened AOT cache app.aot.` or `AOT cache loaded ... classes`). If the log shows `AOT cache rejected` or no AOT lines at all, the cache isn't being consumed — diagnose before proceeding.

- [ ] **Step 5: Stash the recipe numbers**

Run: `cat /tmp/clockwork-recipe.txt`
Expected: image size, 3 startup trials, RSS line, AOT log lines.

No commit yet.

---

## Task 7: Update the spec with measurement results

Fold the numbers from Tasks 1 and 6 into the spec's results table.

**Files:**
- Modify: `docs/superpowers/specs/2026-05-27-clockwork-jvm-footprint-design.md`

- [ ] **Step 1: Compute medians and deltas**

From `/tmp/clockwork-baseline.txt` and `/tmp/clockwork-recipe.txt`, compute:
- Median startup time for each (sort the three trials, take the middle one).
- Δ = recipe − baseline (negative is good).
- For image size and RSS, just record both values and compute the difference.

- [ ] **Step 2: Replace the TBD table in the spec**

In `docs/superpowers/specs/2026-05-27-clockwork-jvm-footprint-design.md`, find the section titled `Results table to be filled in during implementation:` and replace the placeholder TBDs with the measured values. Format the delta column as `−Xs (Y% reduction)` for startup and `−ZMiB (W% reduction)` for memory.

For the `AOT cache loaded` row, write `yes` (if Task 6 Step 4 confirmed it) followed by the exact log line in code formatting.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-05-27-clockwork-jvm-footprint-design.md
git commit -m "$(cat <<'EOF'
Record measured before/after for clockwork JVM recipe

Fills in the verification table with local docker measurements of image
size, startup time, idle RSS, and AOT cache load confirmation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Final verification

A pass over the working tree to confirm nothing is left dangling.

**Files:**
- None modified. This task is verification only.

- [ ] **Step 1: Confirm clean working tree**

Run: `git status`
Expected: `nothing to commit, working tree clean`.

- [ ] **Step 2: Confirm full test suite still passes**

Run: `lein test`
Expected: all tests pass; no failures or errors.

- [ ] **Step 3: Confirm the image still builds from scratch**

Run:
```bash
docker build --rm --no-cache -t clockwork:final-check .
```
Expected: fresh, no-cache build succeeds. Catches stale-cache surprises.

- [ ] **Step 4: Confirm final image runs and loads the AOT cache**

Run:
```bash
docker run --rm \
  -e JAVA_TOOL_OPTIONS="-Xlog:aot=info" \
  -v /tmp/clockwork-measure.properties:/etc/iplant/de/clockwork.properties:ro \
  clockwork:final-check --config /etc/iplant/de/clockwork.properties 2>&1 | \
  grep -E "(clockwork startup|AOT)" | head -5
```
Expected: shows both `clockwork startup` and AOT cache load log lines.

- [ ] **Step 5: Summarize commits**

Run: `git log --oneline main..HEAD` (or `git log --oneline -10`)
Expected: ~5 commits: spec was already there; this work adds Tasks 2, 3, 4, 5, 7 (Task 1 and 6 have no commits, Task 8 has none).

The plan is complete when all of the above is green.
