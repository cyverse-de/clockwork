"""Shared fixtures for the clockwork functional/parity tests.

The tests treat clockwork as an opaque container (the image under test is named by
the CLOCKWORK_IMAGE environment variable) and assert only on externally observable
behavior: the AMQP messages it publishes, observed through the RabbitMQ management
HTTP API, and how it terminates.
"""

import json
import os
import pathlib
import re
import subprocess
import time
from urllib.parse import quote

import pytest
import requests

HERE = pathlib.Path(__file__).parent
ARTIFACTS = HERE / "artifacts"

NETWORK = "clockwork-test-net"
BROKER_ALIAS = "rabbitmq"  # compose service name == network alias
BROKER_USER = "de"
BROKER_PASS = "de"
MGMT_PORT = int(os.environ.get("RABBITMQ_MGMT_PORT", "15672"))
MGMT_BASE = f"http://localhost:{MGMT_PORT}/api"

CONFIG_IN_CONTAINER = "/etc/iplant/de/clockwork.properties"


def amqp_uri(vhost="/"):
    """Build the AMQP URI clockwork uses to reach the broker over the compose network.

    The vhost is percent-encoded into the path (e.g. "/" -> "%2F", "/prod/de" ->
    "%2Fprod%2Fde") rather than left as a bare trailing slash: the RabbitMQ Java
    client (Clojure/langohr) reads a bare "amqp://host/" as the *empty* vhost while
    amqp091-go reads it as "/", so a bare slash would not point the two
    implementations at the same vhost. (That URI-parsing divergence is itself a
    noted finding; see README.)
    """
    return f"amqp://{BROKER_USER}:{BROKER_PASS}@{BROKER_ALIAS}:5672/{quote(vhost, safe='')}"


# Default URI targeting the "/" vhost, injected by run_clockwork unless a test
# overrides clockwork.amqp.uri.
AMQP_URI = amqp_uri("/")


def _run(cmd, **kwargs):
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def _mgmt_ready():
    try:
        r = requests.get(
            f"{MGMT_BASE}/overview", auth=(BROKER_USER, BROKER_PASS), timeout=2
        )
        return r.status_code == 200
    except requests.RequestException:
        return False


class Mgmt:
    """Thin wrapper over the RabbitMQ management HTTP API for test topology.

    Operates within a single vhost (default "/"). A non-default vhost can be
    created with ensure_vhost() and is removed in cleanup().
    """

    def __init__(self, vhost="/"):
        self.auth = (BROKER_USER, BROKER_PASS)
        self.vhost = vhost
        self.vseg = quote(vhost, safe="")  # percent-encoded for use in URL paths
        self._created_vhost = False
        self._queues = set()
        self._exchanges = set()

    def amqp_uri(self):
        return amqp_uri(self.vhost)

    def ensure_vhost(self):
        """Create this vhost and grant the broker user full permissions on it."""
        r = requests.put(f"{MGMT_BASE}/vhosts/{self.vseg}", auth=self.auth, timeout=5)
        r.raise_for_status()
        self._created_vhost = True
        r = requests.put(
            f"{MGMT_BASE}/permissions/{self.vseg}/{BROKER_USER}",
            json={"configure": ".*", "write": ".*", "read": ".*"},
            auth=self.auth,
            timeout=5,
        )
        r.raise_for_status()

    def declare_exchange(self, name, kind="topic"):
        r = requests.put(
            f"{MGMT_BASE}/exchanges/{self.vseg}/{name}",
            json={"type": kind, "durable": True},
            auth=self.auth,
            timeout=5,
        )
        r.raise_for_status()
        self._exchanges.add(name)

    def declare_queue(self, name):
        r = requests.put(
            f"{MGMT_BASE}/queues/{self.vseg}/{name}",
            json={"durable": False, "auto_delete": False},
            auth=self.auth,
            timeout=5,
        )
        r.raise_for_status()
        self._queues.add(name)

    def bind(self, exchange, queue, routing_key):
        r = requests.post(
            f"{MGMT_BASE}/bindings/{self.vseg}/e/{exchange}/q/{queue}",
            json={"routing_key": routing_key},
            auth=self.auth,
            timeout=5,
        )
        r.raise_for_status()

    def get_messages(self, queue, count=10):
        r = requests.post(
            f"{MGMT_BASE}/queues/{self.vseg}/{queue}/get",
            json={"count": count, "ackmode": "ack_requeue_false", "encoding": "auto"},
            auth=self.auth,
            timeout=5,
        )
        r.raise_for_status()
        return r.json()

    def wait_for_message(self, queue, timeout=30.0, interval=0.5):
        """Poll the queue until a message arrives or the timeout elapses."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            msgs = self.get_messages(queue)
            if msgs:
                return msgs[0]
            time.sleep(interval)
        return None

    def cleanup(self):
        # Deleting a non-default vhost cascades to its queues and exchanges.
        if self._created_vhost:
            requests.delete(
                f"{MGMT_BASE}/vhosts/{self.vseg}", auth=self.auth, timeout=5
            )
            return
        for q in self._queues:
            requests.delete(
                f"{MGMT_BASE}/queues/{self.vseg}/{q}", auth=self.auth, timeout=5
            )
        for e in self._exchanges:
            requests.delete(
                f"{MGMT_BASE}/exchanges/{self.vseg}/{e}", auth=self.auth, timeout=5
            )
        self._queues.clear()
        self._exchanges.clear()


@pytest.fixture(scope="session")
def broker():
    """Ensure a RabbitMQ broker is reachable, starting one if necessary.

    If the broker is already up (e.g. started by run-parity.sh) it is left running;
    otherwise this fixture brings the compose stack up and tears it down afterward.
    """
    started_here = False
    if not _mgmt_ready():
        up = _run(["docker", "compose", "up", "-d"], cwd=HERE)
        if up.returncode != 0:
            pytest.fail(f"failed to start the broker:\n{up.stderr}")
        started_here = True
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if _mgmt_ready():
                break
            time.sleep(2)
        else:
            pytest.fail("broker management API did not become ready in time")

    yield

    if started_here:
        _run(["docker", "compose", "down", "-v"], cwd=HERE)


@pytest.fixture
def mgmt(broker):
    m = Mgmt()
    yield m
    m.cleanup()


@pytest.fixture
def vhost_broker(broker):
    """Factory yielding a Mgmt bound to a given vhost, creating it if non-default."""
    created = []

    def _make(vhost):
        m = Mgmt(vhost)
        if vhost != "/":
            m.ensure_vhost()
        created.append(m)
        return m

    yield _make

    for m in created:
        m.cleanup()


class Container:
    """Handle to a running clockwork container."""

    def __init__(self, cid, name):
        self.id = cid
        self.name = name

    def logs(self):
        out = _run(["docker", "logs", self.id])
        return out.stdout + out.stderr

    def running(self):
        out = _run(["docker", "inspect", "-f", "{{.State.Running}}", self.id])
        return out.stdout.strip() == "true"

    def exit_code(self):
        out = _run(["docker", "inspect", "-f", "{{.State.ExitCode}}", self.id])
        return int(out.stdout.strip())

    def stop(self, timeout=30):
        """SIGTERM the container (docker stop), then return (exit_code, elapsed_seconds)."""
        start = time.monotonic()
        _run(["docker", "stop", "-t", str(timeout), self.id])
        elapsed = time.monotonic() - start
        return self.exit_code(), elapsed

    def remove(self):
        _run(["docker", "rm", "-f", self.id])


@pytest.fixture
def run_clockwork():
    """Factory that starts a clockwork container with the given config properties.

    The broker URI is injected automatically; tests supply only job/exchange settings.
    """
    image = os.environ.get("CLOCKWORK_IMAGE")
    if not image:
        pytest.fail("CLOCKWORK_IMAGE is not set; point it at the image under test")

    containers = []
    tmpfiles = []

    def _run_clockwork(props, name="clockwork-under-test"):
        merged = {"clockwork.amqp.uri": AMQP_URI}
        merged.update(props)
        body = "".join(f"{k}={v}\n" for k, v in merged.items())

        # Unique container name so repeated runs in one test don't collide.
        cname = f"{name}-{len(containers)}"

        cfg = HERE / f".{cname}.properties"
        cfg.write_text(body)
        tmpfiles.append(cfg)

        # No --rm: the shutdown test needs to inspect the exit code after the
        # container stops. Teardown removes the container explicitly.
        proc = _run(
            [
                "docker",
                "run",
                "-d",
                "--name",
                cname,
                "--network",
                NETWORK,
                "-v",
                f"{cfg}:{CONFIG_IN_CONTAINER}:ro",
                image,
                "--config",
                CONFIG_IN_CONTAINER,
            ]
        )
        if proc.returncode != 0:
            pytest.fail(f"failed to start clockwork container:\n{proc.stderr}")
        container = Container(proc.stdout.strip(), cname)
        containers.append(container)
        return container

    yield _run_clockwork

    for c in containers:
        c.remove()
    for f in tmpfiles:
        f.unlink(missing_ok=True)


@pytest.fixture
def record():
    """Persist an observed value under artifacts/, keyed by the image under test.

    Used to capture transport-level details (e.g. delivery mode) so a separate
    step can diff them across the two implementations without failing the
    consumer-facing contract assertions.
    """

    def _record(data):
        ARTIFACTS.mkdir(exist_ok=True)
        safe = re.sub(
            r"[^A-Za-z0-9._-]", "_", os.environ.get("CLOCKWORK_IMAGE", "unknown")
        )
        (ARTIFACTS / f"envelope-{safe}.json").write_text(
            json.dumps(data, indent=2, sort_keys=True)
        )

    return _record
