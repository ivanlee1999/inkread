#!/usr/bin/env python3
"""Separate opt-in diagnostic runner. Never produces the normal timing/counter matrix."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

from run import ACTIVITY, PACKAGE, RESULT, command
from diagnostic_summary import summarize


def execute(args, output, index, label):
    apk = Path(getattr(args, label)).resolve()
    folder = output / f"{args.scale}-resource-diagnostic-{index}-{label}"
    folder.mkdir()
    command("adb", "uninstall", PACKAGE, check=False)
    command("adb", "install", "-r", str(apk))
    package = command("adb", "shell", "dumpsys", "package", PACKAGE)
    (folder / "package.txt").write_text(package)
    if "DEBUGGABLE" in package:
        raise RuntimeError("Refusing a debuggable diagnostic APK")
    compiled = command("adb", "shell", "cmd", "package", "compile", "-f", "-m", "speed", PACKAGE)
    (folder / "compile.txt").write_text(compiled)
    if "Success" not in compiled:
        raise RuntimeError("Full-AOT compilation was not confirmed")
    command("adb", "shell", "am", "force-stop", PACKAGE)
    command("adb", "shell", "rm", "-f", RESULT)
    command("adb", "logcat", "-c")
    start = command("adb", "shell", "am", "start", "-W", "-n", ACTIVITY,
                    "--es", "scale", args.scale, "--ez", "resource_diagnostic", "true",
                    "--ez", "counters", "false")
    (folder / "cold-start.txt").write_text(start)
    if "Status: ok" not in start or "LaunchState: COLD" not in start:
        raise RuntimeError("Cold diagnostic Activity startup was not confirmed")
    deadline = time.monotonic() + args.timeout_seconds
    data = None
    try:
        while time.monotonic() < deadline:
            raw = command("adb", "shell", "cat", RESULT, check=False)
            try:
                data = json.loads(raw)
                break
            except json.JSONDecodeError:
                if not command("adb", "shell", "pidof", PACKAGE, check=False).strip():
                    raise RuntimeError(f"Fixture exited before completing {folder.name}")
                time.sleep(2)
        if data is None:
            raise RuntimeError(f"Timed out waiting for {folder.name}")
        data.update({"revision_label": label, "pair_order": index,
                     "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                     "source_revision": getattr(args, label + "_sha"), "harness_sha256": args.harness_sha})
        # Preserve successful/partial native evidence immediately, even if later validation fails.
        (folder / "result.json").write_text(json.dumps(data, indent=2) + "\n")
        if data["mode"] != "resource_diagnostic" or data["failure"] or data["debuggable"] or data["width_dp"] != args.width:
            raise RuntimeError(f"Native diagnostic failed: {folder.name}: {data.get('failure')}")
        # This dump runs AFTER all equal-idle native snapshots and can explicitly trigger GC.
        (folder / "post-diagnostic-meminfo.txt").write_text(command("adb", "shell", "dumpsys", "meminfo", PACKAGE))
        (folder / "gfxinfo.txt").write_text(command("adb", "shell", "dumpsys", "gfxinfo", PACKAGE, "framestats"))
        (folder / "processes.txt").write_text(command("adb", "shell", "ps", "-A"))
        with (folder / "final-screen.png").open("wb") as output_image:
            subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=output_image, check=True, timeout=60)
    finally:
        (folder / "logcat-epoch.txt").write_text(command("adb", "logcat", "-v", "epoch", "-d", check=False))
    print(f"Completed {folder.name}", flush=True)


def run(args):
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    device = {name: command("adb", "shell", *cmd).strip() for name, cmd in {
        "build_fingerprint": ["getprop", "ro.build.fingerprint"],
        "android_api": ["getprop", "ro.build.version.sdk"],
        "wm_size": ["wm", "size"], "wm_density": ["wm", "density"],
        "webview": ["dumpsys", "webviewupdate"],
    }.items()}
    (output / "device.json").write_text(json.dumps(device, indent=2) + "\n")
    for index, label in enumerate(("baseline", "candidate", "candidate", "baseline", "baseline", "candidate")):
        execute(args, output, index, label)
    (output / "diagnostic-summary.json").write_text(json.dumps(summarize(output), indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for label in ("baseline", "candidate"):
        parser.add_argument("--" + label, required=True)
        parser.add_argument("--" + label + "-sha", required=True)
    parser.add_argument("--harness-sha", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--scale", choices=("small", "medium", "stress"), default="stress")
    parser.add_argument("--timeout-seconds", type=int, default=600)
    run(parser.parse_args())
