alias b := build
alias c := clean
alias t := test
alias l := lint

version := env_var_or_default("VERSION", "dev")

# Build the binary
build:
    go build -ldflags "-X main.version={{version}}" .

# Run tests
test:
    go test -v ./...

# Run linter
lint:
    golangci-lint run

# Clean build artifacts
clean:
    go clean
