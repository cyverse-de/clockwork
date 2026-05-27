# syntax=docker/dockerfile:1.7

#####################
# Stage 1: builder
#####################
FROM clojure:temurin-25-lein-bookworm AS builder
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
# NOTE: We use the :debug tag here (not :latest) because :latest ships the JRE
# build of Temurin 25 while :debug ships the JDK build, and their lib/modules
# files differ in size — which makes an AOT cache trained on one incompatible
# with the other ("Unable to map shared spaces"). Both stages must use the
# same OpenJDK build for the cache to load.
FROM gcr.io/distroless/java25-debian13:debug
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
