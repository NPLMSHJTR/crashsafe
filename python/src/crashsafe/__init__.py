"""crashsafe: crash-point verification for a log-structured store."""

from __future__ import annotations

__version__ = "0.1.0"

from .harness import Engine, expected_state  # noqa: E402

__all__ = ["Engine", "__version__", "expected_state"]
