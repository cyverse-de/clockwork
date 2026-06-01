"""Parity check: a disabled job publishes nothing."""

# How long to wait while asserting that NO message appears. Must comfortably
# exceed worst-case (JVM) startup so a missing message means "disabled", not
# "not started yet".
NEGATIVE_WAIT = 20.0


def test_data_usage_disabled_no_publish(mgmt, run_clockwork):
    mgmt.declare_exchange("de")
    mgmt.declare_queue("du-disabled")
    mgmt.bind("de", "du-disabled", "index.usage.data")

    container = run_clockwork(
        {
            "clockwork.amqp.exchange.name": "de",
            "clockwork.jobs.infosquito.indexing-enabled": "false",
            "clockwork.jobs.data-usage-api.indexing-enabled": "false",
        }
    )

    msg = mgmt.wait_for_message("du-disabled", timeout=NEGATIVE_WAIT)
    assert msg is None, (
        "data-usage is disabled but a message was published; "
        f"container logs:\n{container.logs()}"
    )
