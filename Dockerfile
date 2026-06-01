FROM golang:1.26 AS builder

ENV CGO_ENABLED=0

WORKDIR /src/clockwork
COPY . .
RUN apt-get update && \
    apt-get install -y --no-install-recommends just && \
    rm -rf /var/lib/apt/lists/* && \
    just test && \
    just build

FROM gcr.io/distroless/static-debian13:nonroot

WORKDIR /app

COPY --from=builder /src/clockwork/clockwork /bin/clockwork

ENTRYPOINT ["clockwork"]
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
