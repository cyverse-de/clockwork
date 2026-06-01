"""Negative parity check for the infosquito weekly job.

The infosquito job fires at 23:00 on a day of week (the hour is hardcoded in both
implementations), so it cannot be triggered quickly. What we *can* assert as a
black box is that enabling it does not crash startup and does not produce an
immediate publish — a cron/weekly trigger does not fire at process start. The
day-of-week scheduling itself is covered by the Go unit tests in jobs_test.go.
"""

NEGATIVE_WAIT = 20.0


def test_infosquito_only_no_immediate_publish(mgmt, run_clockwork):
    mgmt.declare_exchange("de")
    mgmt.declare_queue("infosquito")
    mgmt.bind("de", "infosquito", "index.all")

    container = run_clockwork(
        {
            "clockwork.amqp.exchange.name": "de",
            "clockwork.jobs.infosquito.indexing-enabled": "true",
            "clockwork.jobs.infosquito.daynum": "1",
            "clockwork.jobs.data-usage-api.indexing-enabled": "false",
        }
    )

    msg = mgmt.wait_for_message("infosquito", timeout=NEGATIVE_WAIT)
    assert msg is None, (
        "infosquito fired at startup, but a weekly job should not; "
        f"container logs:\n{container.logs()}"
    )

    # Enabling the weekly job must not crash the service.
    assert container.running(), (
        f"clockwork exited unexpectedly with infosquito enabled; logs:\n{container.logs()}"
    )
