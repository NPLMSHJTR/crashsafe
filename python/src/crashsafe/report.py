"""Report for a crashsafe run."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def write_report(result: dict[str, Any], out_dir: Path) -> dict[str, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    metrics_path = out_dir / "metrics.json"
    metrics_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

    truncation = result["truncation"]
    failures = [row for row in truncation if not row["match"]]
    compaction = result["compaction"]

    lines = [
        "# crashsafe report",
        "",
        f"- records written: {result['records']:,}",
        f"- log size: {result['bytes']:,} bytes",
        f"- truncation trials: {len(truncation)}",
        f"- truncation failures: {len(failures)}",
        "- largest repaired tail: "
        f"{max((row['repaired_bytes'] for row in truncation), default=0):,} bytes",
        "",
        "## Truncation recovery",
        "",
        "| offset | expected keys | recovered keys | match | repaired bytes |",
        "| --- | --- | --- | --- | --- |",
    ]
    for row in truncation[:12]:
        lines.append(
            f"| {row['offset']:,} | {row['expected_keys']} | {row['recovered_keys']} | "
            f"{'yes' if row['match'] else 'NO'} | {row['repaired_bytes']:,} |"
        )
    if len(truncation) > 12:
        lines.append(f"| … | | | | ({len(truncation) - 12} more rows in metrics.json) |")

    lines.extend(
        ["", "## Compaction", "", "| scenario | keys | state preserved |", "| --- | --- | --- |"]
    )
    for row in compaction:
        lines.append(f"| {row['scenario']} | {row['keys']} | {'yes' if row['match'] else 'NO'} |")

    stress = result["stress"]
    lines.extend(
        [
            "",
            "## Randomised stress",
            "",
            f"- trials: {stress['trials']}",
            f"- mismatches: {stress['failures']}",
            f"- phantom keys (recovered but never written): {stress['phantom_recoveries']}",
            "",
            "## What this proves and what it does not",
            "",
            "Every trial truncates the log at an arbitrary byte and requires recovery "
            "to return exactly the records whose final byte was written before the "
            "cut. A torn record must disappear, not become a partial key.",
            "",
            "This is a crash model, not a filesystem model: it assumes the operating "
            "system may persist any prefix of the file, which is the strongest "
            "assumption a single-file log can make. It does not test torn sectors, "
            "power loss during `fsync`, or concurrent writers.",
        ]
    )
    report_path = out_dir / "report.md"
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return {"report": report_path, "metrics": metrics_path}
