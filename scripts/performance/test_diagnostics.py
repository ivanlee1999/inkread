#!/usr/bin/env python3
"""Synthetic schema/validation tests, never benchmark evidence."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import diagnostic_summary as summary


def point(index):
    return {"elapsed_realtime_ns": index * 1000, "epoch_ms": index, "main_thread_cpu_ns": index * 100,
            "app_process_cpu_ms": index, "java_heap_bytes": 100, "native_heap_bytes": 100,
            "art_counters": {name: index for name in summary.COUNTERS}, "counter_capture_wall_ns": 1}


def fixture(label="baseline", order=0):
    actions = []
    action_order = [(name, index, index < 0) for index in range(-2, 8)
                    for name in ("reader_open", "reader_back")]
    action_order += [("folder_screen_open", 0, False)] + [(name, index, index < 0)
        for index in range(-2, 8) for name in ("folder_expand", "folder_page_next", "folder_page_previous", "folder_collapse")]
    for i, (name, iteration, warmup) in enumerate(action_order):
        actions.append({"scenario": name, "iteration": iteration, "warmup": warmup,
                        "before": point(2*i), "after": point(2*i+1),
                        "phase_elapsed_realtime_ns": {name: 2000*i + 100*(j+1) for j, name in enumerate(summary.PHASES)},
                        "art_counter_deltas": {name: 1 for name in summary.COUNTERS},
                        "main_thread_cpu_ns_delta": 100, "app_process_cpu_ms_delta": 1})
    samples = [{"scenario": name, "iteration": iteration, "warmup": warmup,
                **{key: [] if key.endswith("source_ids") else 1 for key in summary.NATIVE_IDENTITY}}
               for name, iteration, warmup in summary.expected(summary.SCENARIOS)]
    idle = [{"target_idle_ms": target, "actual_idle_ms": target, "pss_kib": 100,
             "before_memory_query": point(200+i*2), "after_memory_query": point(201+i*2)}
            for i, target in enumerate([0, 2000, 5000])]
    return dict(fixture="SCHEMA TEST ONLY", mode="resource_diagnostic", failure=None, debuggable=False,
                width_dp=1000, height_dp=1176, density_dpi=160, font_scale=1, webview="test", scale="stress",
                articles=1000, folders=100, publications=2980, expanded_folder_publications=1000, reader_html_bytes=100,
                harness_sha256="same", source_revision=label, apk_sha256=label, revision_label=label, pair_order=order,
                samples=samples, resource_diagnostic=dict(version="inkread-resource-diagnostic-v2", forced_gc=False,
                    inter_action_idle_ms=0, pid=10, main_thread_tid=10, actions=actions, idle_snapshots=idle))


class DiagnosticTest(unittest.TestCase):
    def test_valid_native_schema_and_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for index, label in enumerate(["baseline", "candidate", "candidate", "baseline", "baseline", "candidate"]):
                folder = root / str(index)
                folder.mkdir()
                (folder / "result.json").write_text(json.dumps(fixture(label, index)))
            result = summary.summarize(root)
            self.assertEqual(len(result["per_run"]), 6)
            self.assertEqual(result["mode"], "resource_diagnostic")
            # Reader observations must not contaminate the existing whole-folder totals.
            self.assertEqual(result["per_run"][0]["whole_folder_counter_deltas_including_warmups_and_observer_gaps"]["allocated_bytes"], 81)
            self.assertEqual({r["scenario"] for r in result["comparisons"]}, summary.OBSERVED)
            self.assertTrue(all(row["baseline"]["median"] == row["candidate"]["median"] for row in result["comparisons"]))

    def test_rejects_counter_mismatch_missing_action_idle_or_score_mode(self):
        changes = [
            lambda r: r.update(mode="timing"),
            lambda r: r["resource_diagnostic"].update(forced_gc=True),
            lambda r: r["resource_diagnostic"].update(version="inkread-resource-diagnostic-v1"),
            lambda r: r["resource_diagnostic"]["actions"].pop(0),
            lambda r: r["resource_diagnostic"]["actions"][0]["phase_elapsed_realtime_ns"].update(readiness_met=-1),
            lambda r: r["resource_diagnostic"].update(main_thread_tid=11),
            lambda r: r["resource_diagnostic"]["actions"].pop(),
            lambda r: r["resource_diagnostic"]["idle_snapshots"].pop(),
            lambda r: r["resource_diagnostic"]["actions"][0]["art_counter_deltas"].update({summary.COUNTERS[0]: 2}),
            lambda r: r["resource_diagnostic"]["actions"].reverse(),
        ]
        for change in changes:
            with self.subTest(change=change):
                run = fixture()
                change(run)
                with self.assertRaises(ValueError):
                    summary.validate_run(run)

    def test_rejects_unavailable_thread_cpu_even_with_zero_delta(self):
        run = fixture()
        action = run["resource_diagnostic"]["actions"][0]
        action["before"]["main_thread_cpu_ns"] = -1
        action["after"]["main_thread_cpu_ns"] = -1
        action["main_thread_cpu_ns_delta"] = 0
        with self.assertRaisesRegex(ValueError, "counter unavailable"):
            summary.validate_run(run)
        run = fixture()
        run["resource_diagnostic"]["idle_snapshots"][0]["before_memory_query"]["main_thread_cpu_ns"] = -1
        with self.assertRaisesRegex(ValueError, "counter unavailable"):
            summary.validate_run(run)

    def test_rejects_cross_revision_geometry_or_source_mismatch(self):
        for mutation in [lambda r: r["samples"][0].update(folder_page=5),
                         lambda r: r.update(source_revision="another-source")]:
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                for index, label in enumerate(["baseline", "candidate", "candidate", "baseline", "baseline", "candidate"]):
                    run = fixture(label, index)
                    if index == 5:
                        mutation(run)
                    folder = root / str(index)
                    folder.mkdir()
                    (folder / "result.json").write_text(json.dumps(run))
                with self.assertRaises(ValueError):
                    summary.summarize(root)


if __name__ == "__main__":
    unittest.main()
