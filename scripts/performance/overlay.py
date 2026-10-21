#!/usr/bin/env python3
"""Install ONLY fixed harness inputs on an explicitly checked-out app revision."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

HOOK = '''\n// Opt-in native performance fixture. The shipping release source set is unchanged.
if (providers.gradleProperty("enablePerformanceHarness").orNull == "true") {
    apply(from = "performance.gradle.kts")
}
'''
FILES = ["app/performance.gradle.kts", "app/src/performance/AndroidManifest.xml",
         "app/src/performance/java/me/ash/reader/performance/PerformanceActivity.kt",
         "app/src/performance/java/me/ash/reader/performance/PerformanceFixture.kt"]


def overlay(source, target):
    source, target = Path(source).resolve(), Path(target).resolve()
    revision = subprocess.check_output(["git", "-C", str(target), "rev-parse", "HEAD"], text=True).strip()
    inputs = {name: (source / name).read_bytes() for name in FILES}
    fingerprints = {name: hashlib.sha256(data).hexdigest() for name, data in inputs.items()}
    for name in ("overlay.py", "run.py", "summarize.py", "pr-config.json"):
        fingerprints["scripts/performance/" + name] = hashlib.sha256((source / "scripts/performance" / name).read_bytes()).hexdigest()
    fingerprints["gradle_hook"] = hashlib.sha256(HOOK.encode()).hexdigest()
    harness_sha = hashlib.sha256(json.dumps(fingerprints, sort_keys=True).encode()).hexdigest()
    result = {"source_revision": revision, "harness_sha256": harness_sha, "harness_inputs": fingerprints}
    build = target / "app/build.gradle.kts"
    committed_build = subprocess.check_output(["git", "-C", str(target), "show", "HEAD:app/build.gradle.kts"], text=True)
    if 'apply(from = "performance.gradle.kts")' in committed_build:
        if HOOK.strip() not in committed_build:
            raise SystemExit("Existing performance hook differs; refusing an ambiguous overlay")
        expected_build = committed_build
    else:
        expected_build = committed_build.rstrip() + "\n" + HOOK
    # A standalone invocation must be as trustworthy as the workflow's fresh checkout.
    # Include staged changes and untracked files, not merely unstaged main-source edits.
    dirty = subprocess.check_output(["git", "-C", str(target), "diff", "HEAD", "--name-only", "-z"], text=True)
    untracked = subprocess.check_output(["git", "-C", str(target), "ls-files", "--others", "--exclude-standard", "-z"], text=True)
    for name in set((dirty + untracked).split("\0")) - {""}:
        path = target / name
        if name in inputs and path.is_file() and path.read_bytes() == inputs[name]:
            continue
        if name == "app/build.gradle.kts" and path.is_file() and path.read_text() == expected_build:
            continue
        if name == "performance-provenance.json" and path.is_file():
            try:
                if json.loads(path.read_text()) == result:
                    continue
            except json.JSONDecodeError:
                pass
        raise SystemExit("Refusing a modified checkout before overlay: " + name)
    for name, data in inputs.items():
        (target / name).parent.mkdir(parents=True, exist_ok=True)
        (target / name).write_bytes(data)
    build.write_text(expected_build)
    (target / "performance-provenance.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: overlay.py HARNESS_CHECKOUT TARGET_CHECKOUT")
    print(json.dumps(overlay(*sys.argv[1:]), indent=2))
