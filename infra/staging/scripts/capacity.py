#!/usr/bin/env python3
"""Record real docker stats; no sample measurements are generated."""
import argparse
import datetime
import json
import pathlib
import re
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("staging_dir", type=pathlib.Path)
parser.add_argument("--purchase", required=True, help="Verified warm-up order ID/evidence URL")
parser.add_argument("--auction", required=True, help="Verified warm-up auction ID/evidence URL")
parser.add_argument("--output", type=pathlib.Path)
args = parser.parse_args()
ids = [line.strip() for line in sys.stdin if line.strip()]
if not ids:
    sys.exit("No running Compose containers; nothing can be measured")
raw = subprocess.check_output(["docker", "stats", "--no-stream", "--format", "{{json .}}", *ids], text=True)
rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
if len(rows) != len(ids):
    sys.exit("Incomplete docker stats sample; retry when all containers are running")

def size(value):
    match = re.fullmatch(r"([0-9.]+)\s*(B|[kKMGT]i?B)", value.strip())
    if not match:
        raise ValueError(f"Unrecognized docker memory unit: {value}")
    number, unit = match.groups()
    powers = {"B": 1, "kB": 1000, "KB": 1000, "MB": 1000**2, "GB": 1000**3, "TB": 1000**4,
              "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "TiB": 1024**4}
    return float(number) * powers[unit]

rows.sort(key=lambda row: size(row["MemUsage"].split("/")[0]), reverse=True)
total = sum(size(row["MemUsage"].split("/")[0]) for row in rows)
memory = {line.split()[0].rstrip(":"): int(line.split()[1]) * 1024
          for line in pathlib.Path("/proc/meminfo").read_text().splitlines() if line.startswith(("MemTotal:", "MemAvailable:"))}
ram = memory["MemTotal"]
disk = shutil.disk_usage(args.staging_dir)
container_headroom = (ram - total) / ram
available_headroom = memory["MemAvailable"] / ram
headroom = min(container_headroom, available_headroom)
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
evidence = args.staging_dir / "evidence"
evidence.mkdir(exist_ok=True)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
(evidence / f"docker-stats-{stamp}.jsonl").write_text(raw)
output = args.output or args.staging_dir.parent.parent / "docs/staging-capacity.md"
lines = ["# STAGING capacity measurement", "", f"Measured on the Linux STAGING host at {now}.", "",
         f"Warm-up purchase: {args.purchase}. Warm-up auction: {args.auction}.", "",
         "Command: `docker stats --no-stream --format '{{json .}}'` for every running Compose container.", "",
         "Linux docker stats reports the container memory working set (cache subtracted), not a per-process RSS sum.", "",
         "| Container | Memory used / limit | CPU |", "| --- | --- | --- |"]
for row in rows:
    lines.append(f"| {row['Name']} | {row['MemUsage']} | {row['CPUPerc']} |")
lines += ["", f"Container memory total: {total / 1024**3:.3f} GiB.",
          f"Host RAM: {ram / 1024**3:.3f} GiB. Fraction not allocated to container working sets: {container_headroom:.1%}.",
          f"Host MemAvailable: {memory['MemAvailable'] / 1024**3:.3f} GiB ({available_headroom:.1%}). Effective headroom: {headroom:.1%}.",
          "The headroom check uses the smaller fraction, so OS/Docker overhead cannot be hidden by the container sum.",
          f"Disk total / used / free: {disk.total / 1024**3:.2f} / {disk.used / 1024**3:.2f} / {disk.free / 1024**3:.2f} GiB.",
          "", "Three largest containers: " + ", ".join(row["Name"] for row in rows[:3]) + ".", "",
          "Heap adjustment: pending operator review of this sample and GC/OOM logs; no adjustment is inferred from one sample."]
if headroom < .2:
    lines += ["", "**Below 20% headroom. Raise the measured shortfall today and record the issue/standup link here.**"]
output.write_text("\n".join(lines) + "\n")
print(f"Wrote {output}; {len(rows)} measured containers, {headroom:.1%} container headroom")
if headroom < .2:
    sys.exit(1)
