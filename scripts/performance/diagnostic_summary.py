#!/usr/bin/env python3
"""Validate and summarize only resource diagnostics; never promote them to speed scores."""
from collections import Counter, defaultdict
import json
from pathlib import Path

from summarize import SCENARIOS, SINGLE, distribution

COUNTERS = ("art.gc.bytes-allocated", "art.gc.bytes-freed", "art.gc.gc-count", "art.gc.gc-time",
            "art.gc.blocking-gc-count", "art.gc.blocking-gc-time")
FOLDER = {name for name in SCENARIOS if name.startswith("folder_")}
READER = {"reader_open", "reader_back"}
OBSERVED = FOLDER | READER
PHASES = ("action_start", "action_returned", "readiness_met", "render_barriers_done")
METRICS = {"allocated_bytes": "art.gc.bytes-allocated", "freed_bytes": "art.gc.bytes-freed",
           "gc_count": "art.gc.gc-count", "gc_time_ms": "art.gc.gc-time",
           "blocking_gc_count": "art.gc.blocking-gc-count", "blocking_gc_time_ms": "art.gc.blocking-gc-time"}
NATIVE_IDENTITY = ("article_pages", "folder_pages", "article_page", "folder_page", "article_source_ids",
                   "folder_source_ids", "reader_pages", "cached_enriched_article_pages")


def key(sample):
    return sample["scenario"], sample["iteration"], sample["warmup"]


def expected(scenarios):
    return {(name, index, index < 0) for name in scenarios
            for index in ([0] if name in SINGLE else range(-2, 8))}


def validate_run(run):
    if run["failure"] or run["debuggable"] or run["mode"] != "resource_diagnostic":
        raise ValueError("Failed, debuggable, or non-diagnostic run")
    samples = run["samples"]
    if len(samples) != 82 or {key(s) for s in samples} != expected(SCENARIOS):
        raise ValueError("Missing, duplicate, or unexpected native action")
    probe = run["resource_diagnostic"]
    if probe["version"] != "inkread-resource-diagnostic-v2" or probe["forced_gc"] or probe["inter_action_idle_ms"] != 0:
        raise ValueError("Incompatible diagnostic protocol")
    if probe["pid"] != probe["main_thread_tid"]:
        raise ValueError("CPU observer did not run on main thread")
    actions = probe["actions"]
    if len(actions) != 61 or {key(s) for s in actions} != expected(OBSERVED):
        raise ValueError("Missing, duplicate, or unexpected resource action")
    action_order = [(name, index, index < 0) for index in range(-2, 8)
                    for name in ("reader_open", "reader_back")]
    action_order += [("folder_screen_open", 0, False)] + [(name, index, index < 0)
        for index in range(-2, 8) for name in ("folder_expand", "folder_page_next", "folder_page_previous", "folder_collapse")]
    if [key(a) for a in actions] != action_order:
        raise ValueError("Diagnostic action order mismatch")
    previous_end = -1
    for action in actions:
        before, after = action["before"], action["after"]
        if not previous_end <= before["elapsed_realtime_ns"] <= after["elapsed_realtime_ns"]:
            raise ValueError("Invalid diagnostic phase timestamps")
        previous_end = after["elapsed_realtime_ns"]
        phases = action["phase_elapsed_realtime_ns"]
        stamps = [before["elapsed_realtime_ns"], *(phases[name] for name in PHASES), after["elapsed_realtime_ns"]]
        if stamps != sorted(stamps):
            raise ValueError("Invalid diagnostic action subphase timestamps")
        if before["main_thread_cpu_ns"] < 0 or after["main_thread_cpu_ns"] < 0:
            raise ValueError("Main-thread CPU counter unavailable")
        for name in COUNTERS:
            delta = after["art_counters"][name] - before["art_counters"][name]
            if delta < 0 or delta != action["art_counter_deltas"][name]:
                raise ValueError("Missing or inconsistent ART counter delta")
        if action["main_thread_cpu_ns_delta"] != after["main_thread_cpu_ns"] - before["main_thread_cpu_ns"] or action["main_thread_cpu_ns_delta"] < 0:
            raise ValueError("Invalid main-thread CPU delta")
        if action["app_process_cpu_ms_delta"] != after["app_process_cpu_ms"] - before["app_process_cpu_ms"] or action["app_process_cpu_ms_delta"] < 0:
            raise ValueError("Invalid process CPU delta")
    idle = probe["idle_snapshots"]
    if [s["target_idle_ms"] for s in idle] != [0, 2000, 5000]:
        raise ValueError("Incomplete equal-idle snapshots")
    for snapshot in idle:
        if snapshot["actual_idle_ms"] < snapshot["target_idle_ms"] or snapshot["pss_kib"] <= 0:
            raise ValueError("Invalid idle deadline or memory sample")
        before, after = snapshot["before_memory_query"], snapshot["after_memory_query"]
        if not previous_end <= before["elapsed_realtime_ns"] <= after["elapsed_realtime_ns"]:
            raise ValueError("Invalid idle timestamps")
        previous_end = after["elapsed_realtime_ns"]
        if before["main_thread_cpu_ns"] < 0 or after["main_thread_cpu_ns"] < 0:
            raise ValueError("Idle main-thread CPU counter unavailable")


def summarize(root):
    runs = [json.loads(p.read_text()) for p in sorted(Path(root).glob("*/result.json"))]
    if len(runs) != 6:
        raise ValueError("Expected three same-AVD diagnostic process pairs")
    for run in runs:
        validate_run(run)
    protocol = {(r["fixture"], r["harness_sha256"], r["width_dp"], r["height_dp"], r["density_dpi"],
                 r["font_scale"], r["webview"], r["scale"], r["articles"], r["folders"], r["publications"],
                 r["expanded_folder_publications"], r["reader_html_bytes"]) for r in runs}
    if len(protocol) != 1:
        raise ValueError("Diagnostic fixture/harness/device mismatch")
    if Counter((r["revision_label"], r["pair_order"]) for r in runs) != Counter({("baseline", 0): 1, ("candidate", 1): 1,
            ("candidate", 2): 1, ("baseline", 3): 1, ("baseline", 4): 1, ("candidate", 5): 1}):
        raise ValueError("Diagnostic process order mismatch")
    for label in ("baseline", "candidate"):
        if len({(r["source_revision"], r["apk_sha256"]) for r in runs if r["revision_label"] == label}) != 1:
            raise ValueError("Each side requires one exact source/APK")
    identities = defaultdict(set)
    for run in runs:
        for sample in run["samples"]:
            identities[key(sample)].add(json.dumps({k: sample[k] for k in NATIVE_IDENTITY}, sort_keys=True))
    if any(len(values) != 1 for values in identities.values()):
        raise ValueError("Native action geometry/source identity mismatch")
    per_run = []
    grouped = defaultdict(list)
    for run in runs:
        actions = run["resource_diagnostic"]["actions"]
        values = defaultdict(list)
        for action in actions:
            if action["warmup"]:
                continue
            metrics = {name: action["art_counter_deltas"][stat] for name, stat in METRICS.items()}
            phases = action["phase_elapsed_realtime_ns"]
            metrics.update(main_thread_cpu_ms=action["main_thread_cpu_ns_delta"] / 1e6,
                           app_process_cpu_ms=action["app_process_cpu_ms_delta"],
                           diagnostic_action_dispatch_ms=(phases["action_returned"] - phases["action_start"]) / 1e6,
                           diagnostic_readiness_wait_ms=(phases["readiness_met"] - phases["action_returned"]) / 1e6,
                           diagnostic_render_barriers_ms=(phases["render_barriers_done"] - phases["readiness_met"]) / 1e6)
            for name, value in metrics.items():
                values[(action["scenario"], name)].append(value)
                grouped[(action["scenario"], name, run["revision_label"])].append(value)
        folder_actions = [action for action in actions if action["scenario"] in FOLDER]
        before, after = folder_actions[0]["before"], folder_actions[-1]["after"]
        per_run.append({"revision_label": run["revision_label"], "pair_order": run["pair_order"],
                        "whole_folder_counter_deltas_including_warmups_and_observer_gaps": {
                            name: after["art_counters"][stat] - before["art_counters"][stat] for name, stat in METRICS.items()},
                        "actions": [{"scenario": scenario, "metric": metric, **distribution(v)}
                                    for (scenario, metric), v in sorted(values.items())],
                        "idle_snapshots": run["resource_diagnostic"]["idle_snapshots"]})
    comparisons = [{"scenario": scenario, "metric": metric,
                    "baseline": distribution(grouped[(scenario, metric, "baseline")]),
                    "candidate": distribution(grouped[(scenario, metric, "candidate")])}
                   for scenario, metric in sorted({k[:2] for k in grouped})]
    return {"schema": 1, "mode": "resource_diagnostic", "harness_sha256": runs[0]["harness_sha256"],
            "provenance": {r["revision_label"]: r["source_revision"] for r in runs},
            "warning": "Diagnostic observer overhead and approximate process-wide counters; not normal timing scores. "
                       "Main-thread CPU includes observer work. GC durations are not equivalent to allocation-wait pauses. "
                       "Equal idle does not establish steady state. Three process pairs, not 24 independent observations.",
            "per_run": per_run, "comparisons": comparisons}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root")
    args = parser.parse_args()
    print(json.dumps(summarize(args.root), indent=2))
