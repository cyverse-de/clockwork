"""Positive parity checks: the data-usage job's observable publishing behavior.

The data-usage job is the only one whose firing is observable quickly (the
infosquito job is hardcoded to 23:00; see test_infosquito.py). A large interval is
used so that, regardless of whether the implementation also fires on the interval,
only the startup behavior is exercised within the test window.
"""

import json

# Large enough that the periodic interval never fires during the test window.
LONG_INTERVAL_HOURS = 24

POSITIVE_WAIT = 40.0  # JVM startup (main) is slower than the Go binary


def _data_usage_only(exchange="de"):
    return {
        "clockwork.amqp.exchange.name": exchange,
        "clockwork.jobs.infosquito.indexing-enabled": "false",
        "clockwork.jobs.data-usage-api.indexing-enabled": "true",
        "clockwork.jobs.data-usage-api.interval": str(LONG_INTERVAL_HOURS),
    }


def test_data_usage_startup_publish(mgmt, run_clockwork, record):
    """clockwork publishes the data-usage message at startup with the expected envelope.

    The consumer-facing contract (routing key, content type, JSON body) is asserted
    strictly. Transport details (delivery mode) are recorded for a cross-branch diff
    rather than asserted, since they may legitimately differ between runtimes.
    """
    mgmt.declare_exchange("de")
    mgmt.declare_queue("du-startup")
    mgmt.bind("de", "du-startup", "index.usage.data")

    container = run_clockwork(_data_usage_only())
    msg = mgmt.wait_for_message("du-startup", timeout=POSITIVE_WAIT)

    assert msg is not None, f"no message published; container logs:\n{container.logs()}"
    assert msg["routing_key"] == "index.usage.data"

    props = msg["properties"]
    assert props.get("content_type") == "application/json"

    body = json.loads(msg["payload"])
    assert body["message"] == "Sent by clockwork"
    assert isinstance(body["timestamp_ms"], int)
    assert body["timestamp_ms"] > 0

    record(
        {
            "routing_key": msg["routing_key"],
            "content_type": props.get("content_type"),
            "delivery_mode": props.get("delivery_mode"),
            "body_keys": sorted(body.keys()),
            "message": body["message"],
        }
    )


def test_custom_exchange(mgmt, run_clockwork):
    """The configured exchange name is honored."""
    mgmt.declare_exchange("custom-de")
    mgmt.declare_queue("du-custom")
    mgmt.bind("custom-de", "du-custom", "index.usage.data")

    container = run_clockwork(_data_usage_only(exchange="custom-de"))
    msg = mgmt.wait_for_message("du-custom", timeout=POSITIVE_WAIT)

    assert msg is not None, f"no message on custom exchange; logs:\n{container.logs()}"
    assert msg["exchange"] == "custom-de"
    assert msg["routing_key"] == "index.usage.data"


def test_prod_style_vhost(vhost_broker, run_clockwork):
    """A production-style percent-encoded vhost in the URI is honored.

    Production points clockwork at a URI whose path is an encoded vhost
    (e.g. .../%2Fprod%2Fde -> "/prod/de"). Unlike a bare trailing slash, the
    encoded form is interpreted identically by both AMQP client libraries, so
    this confirms the implementation connects to the named vhost and publishes
    there.
    """
    m = vhost_broker("/prod/de")
    m.declare_exchange("de")
    m.declare_queue("du-prod")
    m.bind("de", "du-prod", "index.usage.data")

    container = run_clockwork(
        {
            "clockwork.amqp.uri": m.amqp_uri(),
            "clockwork.amqp.exchange.name": "de",
            "clockwork.jobs.infosquito.indexing-enabled": "false",
            "clockwork.jobs.data-usage-api.indexing-enabled": "true",
            "clockwork.jobs.data-usage-api.interval": str(LONG_INTERVAL_HOURS),
        }
    )
    msg = m.wait_for_message("du-prod", timeout=POSITIVE_WAIT)

    assert msg is not None, (
        f"no message published to the /prod/de vhost; logs:\n{container.logs()}"
    )
    assert msg["routing_key"] == "index.usage.data"
