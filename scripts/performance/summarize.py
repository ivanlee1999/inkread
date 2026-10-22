#!/usr/bin/env python3
"""Validate provenance/completeness and summarize data. Never enforce timing thresholds."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics

SCENARIOS = {"fixture_activity_ready", "article_page_next", "article_page_previous", "reader_open", "reader_back",
             "folder_screen_open", "folder_expand", "folder_collapse", "folder_page_next", "folder_page_previous"}
SINGLE = {"fixture_activity_ready", "folder_screen_open"}
METRICS = ["wall_ms", "app_process_cpu_ms", "java_heap_bytes", "native_heap_bytes",
           "java_heap_delta_bytes", "native_heap_delta_bytes", "root_draws", "displayed_callbacks",
           "reader_page_callbacks", "fixture_screen_compositions", "publication_list_hash_calls", "publication_elements_hashed"]


def distribution(values):
    ordered = sorted(values)
    # Nearest-rank p95 retains real observed values, including small sample sets.
    import math
    return {"n": len(values), "median": statistics.median(values),
            "p95": ordered[math.ceil(len(values) * .95) - 1], "min": ordered[0], "max": ordered[-1]}


def summarize(root):
    runs = [json.loads(path.read_text()) for path in Path(root).glob("*/result.json")]
    if not runs:
        raise ValueError("No performance results found")
    identity = {(r["fixture"], r["harness_sha256"], r["width_dp"], r["height_dp"], r["density_dpi"], r["font_scale"], r["webview"]) for r in runs}
    if len(identity) != 1:
        raise ValueError("Fixture, harness, device geometry, font, or WebView mismatch")
    grouped = defaultdict(list)
    run_counts = defaultdict(int)
    revisions = defaultdict(set)
    for run in runs:
        if run["failure"] or run["debuggable"]:
            raise ValueError("Failed or debuggable fixture")
        label, mode, scale = run["revision_label"], run["mode"], run["scale"]
        if mode not in {"timing", "work_counters"}:
            raise ValueError("Resource diagnostics must not enter the normal score summary")
        run_counts[(scale, mode, label)] += 1
        revisions[label].add(run["source_revision"])
        present = defaultdict(list)
        for sample in run["samples"]:
            if not sample["warmup"]:
                present[sample["scenario"]].append(sample)
                for metric in METRICS:
                    grouped[(scale, mode, sample["scenario"], metric, label)].append(sample[metric])
        if set(present) != SCENARIOS or any(len(values) != (1 if scenario in SINGLE else 8) for scenario, values in present.items()):
            raise ValueError("Missing, duplicate, or unexpected native samples")
        if mode == "timing":
            for name in ("TotalTime", "ThisTime", "WaitTime"):
                if name in run["cold_start_am_ms"]:
                    grouped[(scale, mode, "cold_process_activity_start", name + "_ms", label)].append(run["cold_start_am_ms"][name])
    if set(revisions) != {"baseline", "candidate"} or any(len(shas) != 1 for shas in revisions.values()):
        raise ValueError("Each side must be one exact source revision")
    for scale in {r["scale"] for r in runs}:
        for mode, count in (("timing", 3), ("work_counters", 1)):
            for label in ("baseline", "candidate"):
                if run_counts[(scale, mode, label)] != count:
                    raise ValueError(f"Incomplete paired runs: {scale}, {mode}, {label}")
        sizes = {(r["articles"], r["folders"], r["publications"], r["expanded_folder_publications"], r["reader_html_bytes"]) for r in runs if r["scale"] == scale}
        if len(sizes) != 1:
            raise ValueError("Synthetic data differs between baseline and candidate")
    rows = []
    for scale, mode, scenario, metric in sorted({key[:-1] for key in grouped}):
        before = distribution(grouped[(scale, mode, scenario, metric, "baseline")])
        after = distribution(grouped[(scale, mode, scenario, metric, "candidate")])
        rows.append({"scale": scale, "mode": mode, "scenario": scenario, "metric": metric,
                     "baseline": before, "candidate": after,
                     "median_delta": after["median"] - before["median"],
                     "median_delta_pct": (after["median"] / before["median"] - 1) * 100 if before["median"] > 0 else None})
    return {"schema": 1, "provenance": {label: next(iter(shas)) for label, shas in revisions.items()},
            "harness_sha256": runs[0]["harness_sha256"], "width_dp": runs[0]["width_dp"],
            "fixtures": {r["scale"]: {key: r[key] for key in ("articles", "folders", "publications", "expanded_folder_publications", "reader_html_bytes")} for r in runs},
            "warning": "Comparative emulator evidence, not physical E-Ink timing. Timing runs exclude counting wrappers; process CPU/heap exclude the WebView renderer. See contract for vsync/observer overhead. No universal pass thresholds.",
            "comparisons": rows}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root")
    args = parser.parse_args()
    result = summarize(args.root)
    Path(args.root, "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    lines = ["# Optimized native fixture comparison", "", result["warning"], "",
             f"Baseline: `{result['provenance']['baseline']}`", f"Candidate: `{result['provenance']['candidate']}`", "",
             "| Scale | Scenario | Metric | Baseline median (p95) | Candidate median (p95) | Median delta |", "|---|---|---|---:|---:|---:|"]
    for row in result["comparisons"]:
        wanted = row["metric"] in ({"wall_ms", "app_process_cpu_ms", "TotalTime_ms"} if row["mode"] == "timing" else {"publication_elements_hashed"})
        if wanted:
            a, b = row["baseline"], row["candidate"]
            lines.append(f"| {row['scale']} | {row['scenario']} | {row['metric']} | {a['median']:.2f} ({a['p95']:.2f}) | {b['median']:.2f} ({b['p95']:.2f}) | {row['median_delta']:+.2f} |")
    Path(args.root, "summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
