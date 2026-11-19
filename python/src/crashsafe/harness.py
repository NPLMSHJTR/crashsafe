"""Crash-point harness: prove that recovery equals the longest valid prefix."""

from __future__ import annotations

import json
import random
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

JAVA_MAIN = "crashsafe.Store"


class Engine:
    """Runs the Java store and parses its single-line JSON output."""

    def __init__(self, classes: Path, workdir: Path) -> None:
        self.classes = classes
        self.workdir = workdir

    def call(self, *arguments: str) -> dict[str, Any]:
        completed = subprocess.run(
            ["java", "-cp", str(self.classes), JAVA_MAIN, *arguments],
            capture_output=True,
            text=True,
            check=True,
        )
        lines = [line for line in completed.stdout.splitlines() if line.strip()]
        for line in reversed(lines):
            if line.startswith("{"):
                parsed = json.loads(line)
                if isinstance(parsed, dict):
                    return parsed
        raise ValueError(f"no JSON in output: {completed.stdout!r}")

    def write(self, directory: Path, records: int, seed: int, plan: Path) -> None:
        completed = subprocess.run(
            [
                "java",
                "-cp",
                str(self.classes),
                JAVA_MAIN,
                "write",
                "--dir",
                str(directory),
                "--records",
                str(records),
                "--seed",
                str(seed),
                "--plan",
                str(plan),
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        if "wrote" not in completed.stdout:
            raise ValueError(f"unexpected write output: {completed.stdout!r}")

    def recover(self, directory: Path, crash_at: int | None = None) -> dict[str, Any]:
        with tempfile.TemporaryDirectory() as tmp:
            dump = Path(tmp) / "dump.tsv"
            state = Path(tmp) / "state.json"
            arguments = [
                "recover",
                "--dir",
                str(directory),
                "--dump",
                str(dump),
                "--json",
                str(state),
            ]
            if crash_at is not None:
                arguments.extend(["--crash-at", str(crash_at)])
            self.call(*arguments)
            rows = {}
            for line in dump.read_text(encoding="utf-8").splitlines():
                if "\t" in line:
                    key, value = line.split("\t", 1)
                    rows[key] = value
            # ``call`` already parsed the JSON; re-read it so the caller owns a
            # value that survives the temporary directory.
            return {**json.loads(state.read_text(encoding="utf-8")), "data": rows}

    def compact(self, directory: Path, before: bool = False, after: bool = False) -> None:
        arguments = ["compact", "--dir", str(directory)]
        if before:
            arguments.append("--crash-before-rename")
        if after:
            arguments.append("--crash-after-rename")
        subprocess.run(
            ["java", "-cp", str(self.classes), JAVA_MAIN, *arguments],
            capture_output=True,
            text=True,
            check=True,
        )

    def stress(self, directory: Path, trials: int, records: int, seed: int) -> dict[str, Any]:
        return self.call(
            "stress",
            "--dir",
            str(directory),
            "--trials",
            str(trials),
            "--records",
            str(records),
            "--seed",
            str(seed),
        )


def expected_state(plan: dict[str, Any], offset: int) -> dict[str, str]:
    """State implied by every record whose end offset is at or before ``offset``."""
    state: dict[str, str] = {}
    for record in plan["records"]:
        if int(record["end"]) <= offset:
            state[str(record["key"])] = str(record["value"])
    return state


def truncation_trials(
    engine: Engine,
    store: Path,
    plan: dict[str, Any],
    trials: int,
    seed: int,
) -> list[dict[str, Any]]:
    """Copy the store, truncate at a chosen byte, recover, and compare."""
    log = store / "log.dat"
    size = log.stat().st_size
    rng = random.Random(seed)
    offsets = [0, 1, size // 3, size // 2, size - 1, size]
    offsets.extend(rng.randrange(0, size + 1) for _ in range(max(0, trials - len(offsets))))

    results: list[dict[str, Any]] = []
    for offset in offsets:
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp) / "store"
            shutil.copytree(store, copy)
            state = engine.recover(copy, crash_at=offset)
            expected = expected_state(plan, offset)
            recovered = state["data"]
            results.append(
                {
                    "offset": offset,
                    "expected_keys": len(expected),
                    "recovered_keys": len(recovered),
                    "match": recovered == expected,
                    "repaired_bytes": state["repaired_bytes"],
                    "file_bytes": size,
                }
            )
    return results


def compaction_trials(
    engine: Engine, store: Path, full_state: dict[str, str]
) -> list[dict[str, Any]]:
    """Crash before and after the snapshot rename; both must recover the state."""
    results: list[dict[str, Any]] = []
    for label, before, after in (
        ("crash before rename", True, False),
        ("crash after rename", False, True),
        ("clean compaction", False, False),
    ):
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp) / "store"
            shutil.copytree(store, copy)
            engine.compact(copy, before=before, after=after)
            state = engine.recover(copy)
            results.append(
                {
                    "scenario": label,
                    "keys": len(state["data"]),
                    "match": state["data"] == full_state,
                }
            )
    return results
