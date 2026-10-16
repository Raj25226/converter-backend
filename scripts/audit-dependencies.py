#!/usr/bin/env python3
"""Check a Maven dependency:list output against OSV (Python standard library only)."""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dependency_list", type=Path)
    args = parser.parse_args()
    dependencies = set()
    for line in args.dependency_list.read_text().splitlines():
        match = re.match(
            r"\s+([\w.-]+):([\w.-]+):jar:(?:[^: ]+:)?([^: ]+):(compile|runtime|test|provided)\b",
            line,
        )
        if match:
            dependencies.add((f"{match[1]}:{match[2]}", match[3]))
    if not dependencies:
        raise ValueError("No Maven dependencies found; generate the list with dependency:list.")
    dependencies = sorted(dependencies)
    findings = []
    for offset in range(0, len(dependencies), 100):
        batch = dependencies[offset : offset + 100]
        queries = [
            {"package": {"ecosystem": "Maven", "name": name}, "version": version}
            for name, version in batch
        ]
        request = urllib.request.Request(
            "https://api.osv.dev/v1/querybatch",
            data=json.dumps({"queries": queries}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            results = json.load(response)["results"]
        if len(results) != len(batch):
            raise ValueError("OSV returned an incomplete response.")
        for (name, version), result in zip(batch, results):
            for vulnerability in result.get("vulns", []):
                findings.append((name, version, vulnerability["id"]))
    for name, version, advisory in findings:
        print(f"{name}:{version}: https://osv.dev/vulnerability/{advisory}")
    print(f"Checked {len(dependencies)} dependencies; {len(findings)} advisory matches.")
    return 1 if findings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, urllib.error.URLError) as error:
        print(f"Dependency audit failed: {error}", file=sys.stderr)
        sys.exit(2)
