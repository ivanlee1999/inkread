#!/usr/bin/env python3
"""Run paired optimized native fixtures on one existing AVD, without timing ADB dispatch."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

PACKAGE = "me.ash.reader.performance"
ACTIVITY = PACKAGE + "/me.ash.reader.performance.PerformanceActivity"
RESULT = f"/sdcard/Android/data/{PACKAGE}/files/performance/result.json"


def command(*args, check=True):
    result = subprocess.run(args, capture_output=True, text=True, timeout=300)
    if check and result.returncode:
        raise RuntimeError(f"{args} failed:\n{result.stdout}\n{result.stderr}")
    return result.stdout + result.stderr


def run(args):
    out = Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    device = {name: command("adb", "shell", *cmd).strip() for name, cmd in {
        "build_fingerprint": ["getprop", "ro.build.fingerprint"],
        "android_api": ["getprop", "ro.build.version.sdk"],
        "wm_size": ["wm", "size"], "wm_density": ["wm", "density"],
        "webview": ["dumpsys", "webviewupdate"],
    }.items()}
    (out / "device.json").write_text(json.dumps(device, indent=2))
    # Repeat both revisions on the SAME AVD. AB/BA/AB counterbalances installation order.
    order = ["baseline", "candidate", "candidate", "baseline", "baseline", "candidate"]
    for scale in args.scales.split(","):
        for index, label in enumerate(order):
            execute(args, out, scale, index, label, counters=False)
        # Counting wrappers are absent from all timing runs; never use their timings for speed claims.
        for index, label in enumerate(("baseline", "candidate")):
            execute(args, out, scale, index, label, counters=True)


def execute(args, out, scale, index, label, counters):
    apk = Path(getattr(args, label)).resolve()
    run_name = f"{scale}-{'counters' if counters else 'timing'}-{index}-{label}"
    folder = out / run_name
    folder.mkdir()
    command("adb", "uninstall", PACKAGE, check=False)
    command("adb", "install", "-r", str(apk))
    manifest_dump = command("adb", "shell", "dumpsys", "package", PACKAGE)
    (folder / "package.txt").write_text(manifest_dump)
    if "DEBUGGABLE" in manifest_dump:
        raise RuntimeError("Refusing a debuggable target APK")
    # Fixed full AOT compilation, avoiding JIT/profile installation order as an uncontrolled variable.
    compile_result = command("adb", "shell", "cmd", "package", "compile", "-f", "-m", "speed", PACKAGE)
    (folder / "compile.txt").write_text(compile_result)
    if "Success" not in compile_result:
        raise RuntimeError("Full-AOT compilation was not confirmed")
    command("adb", "shell", "am", "force-stop", PACKAGE)
    command("adb", "shell", "rm", "-f", RESULT)
    command("adb", "logcat", "-c")
    start = command("adb", "shell", "am", "start", "-W", "-n", ACTIVITY,
                    "--es", "scale", scale, "--ez", "counters", str(counters).lower())
    (folder / "cold-start.txt").write_text(start)
    if "Status: ok" not in start or "LaunchState: COLD" not in start:
        raise RuntimeError("Cold native activity startup was not confirmed")
    deadline = time.monotonic() + args.timeout_seconds
    while time.monotonic() < deadline:
        result = command("adb", "shell", "cat", RESULT, check=False)
        try:
            data = json.loads(result)
            break
        except json.JSONDecodeError:
            if not command("adb", "shell", "pidof", PACKAGE, check=False).strip():
                (folder / "logcat.txt").write_text(command("adb", "logcat", "-d"))
                raise RuntimeError(f"Fixture process exited before completing {run_name}")
            time.sleep(2)
    else:
        (folder / "logcat.txt").write_text(command("adb", "logcat", "-d"))
        raise RuntimeError(f"Timed out waiting for {run_name}; see logcat.txt")
    data.update({"revision_label": label, "pair_order": index, "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                 "source_revision": getattr(args, label + "_sha"), "harness_sha256": args.harness_sha})
    data["cold_start_am_ms"] = {line.split(":", 1)[0]: int(line.split(":", 1)[1].strip())
                                for line in start.splitlines() if line.startswith(("TotalTime:", "ThisTime:", "WaitTime:"))}
    (folder / "result.json").write_text(json.dumps(data, indent=2) + "\n")
    for name, cmd in {"meminfo.txt": ["dumpsys", "meminfo", PACKAGE],
                      "gfxinfo.txt": ["dumpsys", "gfxinfo", PACKAGE, "framestats"],
                      "processes.txt": ["ps", "-A"]}.items():
        (folder / name).write_text(command("adb", "shell", *cmd))
    (folder / "logcat.txt").write_text(command("adb", "logcat", "-d"))
    subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=(folder / "final-screen.png").open("wb"), check=True)
    if data["failure"] or data["debuggable"] or data["width_dp"] != args.width:
        raise RuntimeError(f"Native validation failed for {run_name}: {data.get('failure')} width={data['width_dp']}")
    print(f"Completed {run_name}: {len(data['samples'])} native samples", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for label in ("baseline", "candidate"):
        parser.add_argument("--" + label, required=True)
        parser.add_argument("--" + label + "-sha", required=True)
    parser.add_argument("--harness-sha", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--scales", default="small,stress")
    parser.add_argument("--timeout-seconds", type=int, default=600)
    run(parser.parse_args())
