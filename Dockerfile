FROM clojure:alpine

VOLUME ["/etc/iplant/de"]

ARG git_commit=unknown
ARG version=unknown
LABEL org.iplantc.de.clockwork.git-ref="$git_commit" \
      org.iplantc.de.clockwork.version="$version"

COPY . /usr/src/app
COPY conf/main/logback.xml /usr/src/app/logback.xml

WORKDIR /usr/src/app

RUN apk add --update git && \
    rm -rf /var/cache/apk

RUN lein uberjar && \
    cp target/clockwork-standalone.jar .

RUN ln -s "/usr/bin/java" "/bin/clockwork"

ENTRYPOINT ["clockwork", "-Dlogback.configurationFile=/etc/iplant/de/logging/clockwork-logging.xml", "-cp", ".:clockwork-standalone.jar", "clockwork.core"]
CMD ["--help"]
