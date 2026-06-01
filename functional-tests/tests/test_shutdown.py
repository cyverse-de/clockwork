"""Parity check: clockwork shuts down gracefully on SIGTERM.

Both implementations install a SIGTERM handler that drains in-flight jobs before
exiting. A graceful exit means the process terminates well within the grace period
rather than being SIGKILLed when it expires. Exit codes differ legitimately by
runtime — the JVM exits 143 (128+SIGTERM) while the Go binary returns from main
and exits 0 — so we assert "not SIGKILLed" rather than a specific code.
"""

GRACE = 30
SIGKILL_EXIT = 137  # 128 + 9, what docker stop uses if the grace period expires


def test_graceful_shutdown(mgmt, run_clockwork):
    mgmt.declare_exchange("de")
    mgmt.declare_queue("du-shutdown")
    mgmt.bind("de", "du-shutdown", "index.usage.data")

    container = run_clockwork(
        {
            "clockwork.amqp.exchange.name": "de",
            "clockwork.jobs.infosquito.indexing-enabled": "false",
            "clockwork.jobs.data-usage-api.indexing-enabled": "true",
            "clockwork.jobs.data-usage-api.interval": "24",
        }
    )

    # Let it fully start (and publish its startup message) before stopping.
    assert mgmt.wait_for_message("du-shutdown", timeout=40.0) is not None, (
        f"clockwork never started cleanly; logs:\n{container.logs()}"
    )

    exit_code, elapsed = container.stop(timeout=GRACE)

    assert exit_code != SIGKILL_EXIT, (
        f"clockwork was SIGKILLed after the {GRACE}s grace period; logs:\n{container.logs()}"
    )
    assert elapsed < GRACE, (
        f"shutdown took {elapsed:.1f}s, did not drain within the grace period"
    )
