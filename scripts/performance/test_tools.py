#!/usr/bin/env python3
"""Host-only tests: no SDK, account access, network, or invented benchmark measurements."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


def module(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


overlay = module("overlay")
summary = module("summarize")


class HarnessTest(unittest.TestCase):
    def fixture(self, root):
        for mode, count in (("timing", 3), ("work_counters", 1)):
            for label in ("baseline", "candidate"):
                for run_index in range(count):
                    samples = []
                    for scenario in summary.SCENARIOS:
                        for index in range(1 if scenario in summary.SINGLE else 8):
                            # TEST DATA ONLY: exercises completeness/math, never published as results.
                            sample = {metric: index + 1 for metric in summary.METRICS}
                            sample.update(scenario=scenario, warmup=False)
                            samples.append(sample)
                    run = dict(fixture="test", harness_sha256="same", width_dp=400, height_dp=1000,
                               density_dpi=160, font_scale=1, webview="test", failure=None, debuggable=False,
                               revision_label=label, mode=mode, scale="small", source_revision=label + "-sha",
                               samples=samples, cold_start_am_ms={"TotalTime": 1}, articles=200,
                               folders=24, publications=100, expanded_folder_publications=8, reader_html_bytes=200)
                    path = root / f"{mode}-{label}-{run_index}"
                    path.mkdir()
                    (path / "result.json").write_text(json.dumps(run))

    def test_summary_validates_pairs_and_keeps_counter_runs_separate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            result = summary.summarize(root)
            self.assertEqual(result["provenance"]["baseline"], "baseline-sha")
            row = next(r for r in result["comparisons"] if r["scenario"] == "reader_back" and r["metric"] == "wall_ms" and r["mode"] == "timing")
            self.assertEqual(row["baseline"]["n"], 24)
            self.assertEqual(row["median_delta"], 0)

    def test_summary_rejects_missing_pairs_or_mismatched_fixture(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            path = next(root.glob("*/result.json"))
            original = path.read_text()
            data = json.loads(original)
            data["harness_sha256"] = "different"
            path.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                summary.summarize(root)
            path.unlink()
            with self.assertRaises(ValueError):
                summary.summarize(root)

    def test_summary_rejects_missing_action(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            path = next(root.glob("*/result.json"))
            data = json.loads(path.read_text())
            data["samples"].pop()
            path.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                summary.summarize(root)

    def test_overlay_copies_only_harness_not_production(self):
        with tempfile.TemporaryDirectory() as temp:
            source, target = Path(temp) / "source", Path(temp) / "target"
            for root in (source, target):
                (root / "app/src/main").mkdir(parents=True)
                (root / "app/build.gradle.kts").write_text("// existing build\n")
                (root / "app/src/main/sentinel").write_text(root.name)
            for name in overlay.FILES:
                (source / name).parent.mkdir(parents=True, exist_ok=True)
                (source / name).write_text("fixed fixture\n")
            (source / "scripts/performance").mkdir(parents=True)
            for name in ("overlay.py", "run.py", "summarize.py", "pr-config.json"):
                (source / "scripts/performance" / name).write_text("fixed driver")
            subprocess.run(["git", "init", "-q", str(target)], check=True)
            subprocess.run(["git", "-C", str(target), "add", "."], check=True)
            subprocess.run(["git", "-C", str(target), "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "test"], check=True)
            first = overlay.overlay(source, target)
            second = overlay.overlay(source, target)
            self.assertEqual(first, second)
            self.assertEqual((target / "app/src/main/sentinel").read_text(), "target")
            self.assertEqual((target / "app/build.gradle.kts").read_text().count('apply(from = "performance.gradle")'), 1)
            # Reject untracked production additions before writing anything.
            (target / "app/src/main/untracked").write_text("not committed")
            with self.assertRaises(SystemExit):
                overlay.overlay(source, target)
            (target / "app/src/main/untracked").unlink()
            # Reject staged production changes as well as arbitrary build edits.
            (target / "app/src/main/sentinel").write_text("changed")
            subprocess.run(["git", "-C", str(target), "add", "app/src/main/sentinel"], check=True)
            with self.assertRaises(SystemExit):
                overlay.overlay(source, target)
            subprocess.run(["git", "-C", str(target), "restore", "--staged", "--worktree", "app/src/main/sentinel"], check=True)
            (target / "app/build.gradle.kts").write_text("untrusted build modification")
            with self.assertRaises(SystemExit):
                overlay.overlay(source, target)
            # An old committed applied-Kotlin hook must not silently coexist with Groovy.
            (target / "app/build.gradle.kts").write_text('apply(from = "performance.gradle.kts")\n')
            subprocess.run(["git", "-C", str(target), "add", "app/build.gradle.kts"], check=True)
            subprocess.run(["git", "-C", str(target), "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "legacy hook"], check=True)
            with self.assertRaises(SystemExit):
                overlay.overlay(source, target)


if __name__ == "__main__":
    unittest.main()
