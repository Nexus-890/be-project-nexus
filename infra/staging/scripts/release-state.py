#!/usr/bin/env python3
"""Atomic release metadata; callers serialize operator commands with flock."""
import argparse
import json
import os
from pathlib import Path
import re
import tempfile


def valid_sha(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Release state requires a full lowercase commit SHA")
    return value


class ReleaseState:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.path = self.directory / "release.json"

    def load(self):
        if self.path.exists():
            data = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(data, dict) or data.get("version") != 1:
                raise ValueError("Unsupported or damaged release state")
            if set(data) != {"version", "current", "previous", "pending"}:
                raise ValueError("Release state fields are incomplete")
        else:
            # Read the first implementation's files without modifying them.
            data = {"version": 1, "current": None, "previous": None, "pending": None}
            for name in ("current", "previous", "pending"):
                legacy = self.directory / name
                if legacy.exists():
                    data[name] = valid_sha(legacy.read_text(encoding="utf-8").strip())
            if data["pending"] is not None and data["pending"] == data["current"]:
                raise ValueError("Legacy pending/current are ambiguous; confirm the last successful image set before migrating state")
        for name in ("current", "previous", "pending"):
            if data[name] is not None:
                valid_sha(data[name])
        if data["previous"] is not None and data["current"] is None:
            raise ValueError("Previous release exists without a current release")
        return data

    def save(self, data):
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd, temporary = tempfile.mkstemp(prefix=".release-", suffix=".tmp", dir=self.directory)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as output:
                json.dump(data, output, indent=2)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)
        finally:
            Path(temporary).unlink(missing_ok=True)

    def begin(self, candidate):
        valid_sha(candidate)
        data = self.load()
        if data["current"] is not None and data["pending"] not in (None, candidate):
            raise ValueError("Resolve the pending deployment before selecting another candidate")
        data["pending"] = candidate
        self.save(data)

    def commit(self, candidate):
        valid_sha(candidate)
        data = self.load()
        if data["pending"] != candidate:
            if data["pending"] is None and data["current"] == candidate:
                return
            raise ValueError("Candidate does not match the pending deployment")
        if data["current"] != candidate:
            data["previous"] = data["current"]
        data.update(current=candidate, pending=None)
        self.save(data)

    def rollback_target(self):
        data = self.load()
        target = data["current"] if data["pending"] is not None else data["previous"]
        if target is None:
            raise ValueError("No successful image set is available for rollback")
        return target

    def rollback_success(self, target):
        valid_sha(target)
        if target != self.rollback_target():
            raise ValueError("Rollback target changed; release state was left untouched")
        data = self.load()
        if data["pending"] is None:
            data["current"], data["previous"] = target, data["current"]
        data["pending"] = None
        self.save(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("begin", "commit", "rollback-success"):
        commands.add_parser(name).add_argument("sha")
    commands.add_parser("rollback-target")
    commands.add_parser("show")
    commands.add_parser("current").add_argument("--fallback", required=True)
    args = parser.parse_args()
    state = ReleaseState(args.state_dir)
    try:
        if args.command == "begin":
            state.begin(args.sha)
        elif args.command == "commit":
            state.commit(args.sha)
        elif args.command == "rollback-success":
            state.rollback_success(args.sha)
        elif args.command == "rollback-target":
            print(state.rollback_target())
        elif args.command == "current":
            print(state.load()["current"] or valid_sha(args.fallback))
        else:
            print(json.dumps(state.load(), indent=2))
    except (ValueError, OSError) as error:
        parser.exit(1, f"ERROR: {error}\n")


if __name__ == "__main__":
    main()
