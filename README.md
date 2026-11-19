# crashsafe

**Prove that a crash loses nothing you acknowledged.** `crashsafe` is a small
log-structured key-value store plus a harness that truncates its log at arbitrary
byte offsets and checks that recovery returns exactly the records whose last byte
was written before the cut — no partial record, no phantom key, no silent loss.

```console
$ python scripts/demo.py
crashsafe 0.1.0  500 records  24,472 bytes
truncation trials 60  failures 0
  crash before rename  keys 400   preserved True
  crash after rename   keys 400   preserved True
  clean compaction     keys 400   preserved True
stress 200 trials  mismatches 0  phantom 0
```

## The safety argument

Every record is framed as `magic(2) | length(4) | CRC32(4) | payload`, where the
payload holds a length-prefixed key and value. Recovery walks the file and stops
at the first record that fails any of three checks: wrong magic, a length that
runs past the end of the file, or a checksum mismatch.

Those three failures cover what a torn write can produce: a half-written header,
a plausible header with a garbage length, and a complete-length record whose
body never reached disk. All of them mean the same thing — the record was never
acknowledged, so it must not appear. Recovery then truncates the file to the last
valid offset, so the next append cannot land behind a torn record.

Compaction writes `snapshot.dat.tmp`, fsyncs it, and renames it over
`snapshot.dat`. The rename is the commit point: before it, the log is
authoritative; after it, the snapshot is, and replaying the leftover log is
idempotent because later writes win.

## Results

500 records, 400 distinct keys, 24,472 bytes of log:

| Check | Result |
| --- | --- |
| Truncation trials (random byte offsets) | 60 |
| Recovery mismatches | 0 |
| Largest repaired tail | 63 bytes |
| Compaction: crash before rename | state preserved |
| Compaction: crash after rename | state preserved |
| Randomised stress (in-process) | 200 trials, 0 mismatches, 0 phantom keys |

Every trial compares the recovered map against an independently computed prefix
in Python, not against the store's own opinion of what it wrote.

## Quick start

```bash
python scripts/demo.py
```

Or manually:

```bash
javac -d out java/src/crashsafe/Store.java
java -cp out crashsafe.Store write --dir data/store --records 500 --seed 7 --plan data/plan.json
java -cp out crashsafe.Store recover --dir data/store --json data/state.json --dump data/dump.tsv
java -cp out crashsafe.Store compact --dir data/store --crash-before-rename
java -cp out crashsafe.Store stress --dir data/stress --trials 200 --records 300
```

The `write` command also emits a plan containing the byte range of every record,
which is what lets the harness compute the expected state for any cut offset
without re-implementing the record format.

## What this does and does not model

- **Modelled:** the operating system may persist any prefix of the log. That is
  the strongest assumption a single-file log can make, and it is what a truncation
  test checks.
- **Not modelled:** torn sectors inside a block, power loss during `fsync`,
  filesystem reordering, or concurrent writers. A real durability claim needs
  hardware and a filesystem that lies convincingly.
- **Not a database:** there is no index, no transaction isolation, no background
  compaction, and the log is read fully into memory on open. The project is a
  testbed for the recovery rule, not a storage engine to deploy.

## Repository layout

```
java/src/crashsafe/Store.java    record framing, recovery, snapshot compaction
python/src/crashsafe/harness.py  truncation and compaction trials
python/src/crashsafe/report.py   Markdown and metrics
scripts/demo.py                  one command for the whole experiment
```

## Development

```bash
PYTHONPATH=python/src python -m unittest discover -s python/tests -t python/tests -v
cd python && pip install -e ".[dev]" && ruff check . && mypy
```

## License

MIT. See [LICENSE](LICENSE).
