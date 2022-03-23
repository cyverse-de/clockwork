FROM clojure:openjdk-17-lein-alpine

WORKDIR /usr/src/app

RUN apk add --no-cache git

RUN ln -s "/opt/openjdk-17/bin/java" "/bin/clockwork"

ENV OTEL_TRACES_EXPORTER none

COPY project.clj /usr/src/app/
RUN lein deps

COPY conf/main/logback.xml /usr/src/app/
COPY . /usr/src/app

RUN lein uberjar && \
    cp target/clockwork-standalone.jar .

ENTRYPOINT ["clockwork", "-Dorg.terracotta.quartz.skipUpdateCheck=true", "-Dlogback.configurationFile=/etc/iplant/de/logging/clockwork-logging.xml", "-javaagent:/usr/src/app/opentelemetry-javaagent.jar", "-Dotel.resource.attributes=service.name=clockwork", "-cp", ".:clockwork-standalone.jar", "clockwork.core"]
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
