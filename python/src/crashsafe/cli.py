"""Command-line interface for crashsafe."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

from . import __version__
from .harness import Engine, compaction_trials, truncation_trials
from .report import write_report

EXIT_OK = 0
EXIT_ERROR = 2
ROOT = Path(__file__).resolve().parents[3]


def cmd_run(args: argparse.Namespace) -> int:
    workdir = Path(args.workdir)
    workdir.mkdir(parents=True, exist_ok=True)
    classes = workdir / "classes"
    store = workdir / "store"
    plan_path = workdir / "plan.json"

    sources = sorted(str(path) for path in (ROOT / "java" / "src" / "crashsafe").glob("*.java"))
    subprocess.run(["javac", "-d", str(classes), *sources], check=True)

    engine = Engine(classes, workdir)
    engine.write(store, args.records, args.seed, plan_path)
    plan: dict[str, Any] = json.loads(plan_path.read_text(encoding="utf-8"))
    full_state = {str(row["key"]): str(row["value"]) for row in plan["records"]}

    truncation = truncation_trials(engine, store, plan, args.trials, args.seed)
    compaction = compaction_trials(engine, store, full_state)
    stress = engine.stress(workdir / "stress", args.stress_trials, args.records, args.seed)

    failures = [row for row in truncation if not row["match"]]
    result = {
        "records": len(plan["records"]),
        "bytes": (store / "log.dat").stat().st_size,
        "truncation": truncation,
        "compaction": compaction,
        "stress": stress,
    }
    paths = write_report(result, workdir / "reports")

    print(f"crashsafe {__version__}  {result['records']:,} records  {result['bytes']:,} bytes")
    print(f"truncation trials {len(truncation)}  failures {len(failures)}")
    for row in compaction:
        print(f"  {row['scenario']:<20} keys {row['keys']:<5} preserved {row['match']}")
    print(
        f"stress {stress['trials']} trials  mismatches {stress['failures']}  "
        f"phantom {stress['phantom_recoveries']}"
    )
    print(f"report: {paths['report']}")
    return EXIT_ERROR if failures or stress["failures"] else EXIT_OK


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="crashsafe",
        description="Verify that a log-structured store recovers to the longest valid prefix.",
    )
    parser.add_argument("--version", action="version", version=f"crashsafe {__version__}")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run = subparsers.add_parser("run", help="write a store, crash it at random points, verify")
    run.add_argument("--records", type=int, default=500)
    run.add_argument("--trials", type=int, default=60)
    run.add_argument("--stress-trials", type=int, default=200)
    run.add_argument("--seed", type=int, default=7)
    run.add_argument("--workdir", default="crashsafe-run")
    run.set_defaults(func=cmd_run)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"crashsafe: {error}", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
