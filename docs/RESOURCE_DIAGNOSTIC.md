# Bounded reader and folder resource diagnostic

Status: prepared locally only. No Android diagnostic execution or new performance claim yet.
The latest frozen normal comparison is run `39699797047`, baseline `70e1e8c4d5666b736fc713be2c13ea87af0e2813`
versus `38c4b411c00bd93ce33e13481aecee99a36a446a`. Its raw results and earlier run `39689422826`
must not be replaced.

## Question

Does the faster folder workload allocate more Java bytes, retain more memory after a comparable
idle interval, or mostly change concurrent-GC pacing? Existing final logs show extra main-thread
allocation waits and higher endpoint PSS at 1000 dp stress, despite faster folder responses.
Rounded GC-reclaimed bytes are not total allocated bytes and cannot establish causation.
The latest small-workload comparison also shows slower Back medians in all three matched pairs
at both widths. Does that phase use more main-thread CPU, show GC activity, or spend longer awaiting
readiness/render barriers? Near-flat full-cycle wall time does not establish that Back is harmless.

## Separate protocol

The `resource_diagnostic` intent boolean is false by default. It is mutually exclusive with
publication hash-counter mode. It exists only in the opt-in minified, nondebuggable performance
source set. No production file, shipping build variant, dependency, animation, or GC policy changes.

The fixture, reader sequence, native state/Back dispatch, render barriers, 2 warmups and 8 samples
are unchanged. Reader warmup/teardown remains ahead of folder actions, preserving that source of
background work. There is no extra dwell between interactions.

Protocol v2 observes reader open/Back plus folder screen open, expansion, next page, previous page
and collapse (61 observations per process, including warmups). Earlier folder-only v1 is rejected:

- Raw before/after `Debug.getRuntimeStats()` values: bytes allocated/freed, GC count/time,
  blocking-GC count/time. Missing/non-numeric counters fail rather than becoming zero.
- Monotonic elapsed and epoch timestamps for every observed action, plus logcat begin/end markers.
- Three additional diagnostic-only monotonic reads after action dispatch returns, after the existing
  readiness coroutine returns, and after the existing render barriers finish. No extra polling,
  waits, mid-action ART reads or callbacks. These delimit diagnostic dispatch/readiness/barrier
  intervals, not CPU subphases or the exact instant a readiness state first changed.
- Main-thread CPU using `Debug.threadCpuTimeNanos()`, and process CPU. The observer verifies it
  executes on the main thread. Java/native allocated heap and counter-read duration are retained.
- Existing draw and exact page/source-identity correctness assertions remain active.
- After the last folder collapse, self-process `Debug.getMemoryInfo` snapshots at nominal 0, 2 and
  5 seconds of idle. Actual timestamps and query duration are saved. No `System.gc`,
  `Runtime.gc`, finalization, or memory-pressure stimulus occurs in this protocol.
- External `dumpsys meminfo` is collected only **after** the native equal-idle snapshots/result.
  Its filename explicitly says `post-diagnostic`; that platform dump may request GC and is not
  interchangeable with the in-process snapshots.

Counters are approximate and process-wide. Concurrent work/late counter publication can cross
phase boundaries. Counter capture, log markers and result recording themselves allocate objects
and can perturb GC; main-thread CPU deltas include observer overhead. The primary action wall
snapshots bracket the action/barriers, not the counter reads, but the entire run is still a
perturbed **diagnostic**, not a replacement latency score. Blocking-GC duration is not a direct
sum of all main-thread allocation waits; retain logcat to correlate those waits separately.
An idle checkpoint is not proof of steady-state memory or of persistent retention. Memory and
CPU cover the app process, not an isolated WebView renderer or physical E-Ink hardware.

Official API contracts:
- https://developer.android.com/reference/android/os/Debug#getRuntimeStats()
- https://developer.android.com/reference/android/os/Debug#getRuntimeStat(java.lang.String)
- https://developer.android.com/reference/android/os/Debug#threadCpuTimeNanos()
- https://developer.android.com/reference/android/os/Debug#getMemoryInfo(android.os.Debug.MemoryInfo)

## Build and run, only after the coordinator authorizes remote execution

Use the same reviewed `overlay.py` harness checkout for two clean, explicit source-revision
checkouts. Keep the baseline fixed at the full 0.16.13 SHA above. The overlay fingerprints this
observer and the diagnostic driver/summary, and never copies production source. Build both with
`-PenablePerformanceHarness=true assembleGithubPerformance`, as for the normal harness.

On one existing synthetic emulator per width, run `scripts/performance/diagnostic_run.py` with
`--baseline`, `--candidate` (APK paths), `--baseline-sha`, `--candidate-sha`, `--harness-sha`,
`--width 400` or `--width 1000`. Run `--scale small` then `--scale stress` into **separate**
scale output directories on that AVD. This matches the scale order of the preserved normal run.
The runner installs/AOT-compiles/cold-starts both revisions in B,C,C,B,B,C order, retaining results
immediately if a later run fails. It creates `diagnostic-summary.json` after strict completeness,
source/APK, protocol, fixture, device and native identity validation. Six fresh processes per
scale/width (three matched pairs) are required, twelve per width total. OS/WebView service state
is not reset between processes or scales. No universal speed/memory threshold is applied.

Normal `run.py` is unchanged. The normal summary explicitly rejects diagnostic mode, including
when diagnostic results are accidentally mixed with a complete normal result matrix. The separate `resource-diagnostic.yml` workflow is scoped to PR changes (exact head) or a manual
workflow revision, with fixed 0.16.13 baseline, small-then-stress 400/1000 dp jobs, read-only permissions
and separate artifacts. It has not yet been published or run. A proposed production change needs a fresh **normal** paired
benchmark as well; diagnostic counters are additional explanatory evidence, not that score.

## Interpretation / stopping conditions

Compare matched process pairs and action allocation deltas, not just endpoint PSS or pooled CPU.
Reader actions are reported separately; their observations do not contaminate whole-folder totals.
Inspect whole-folder cumulative deltas as well, because per-action deltas exclude observer gaps.
Map epoch logcat allocation waits to phase marker bounds. Report mixed pairs, collector time,
allocation-wait time, and action wall time separately. Do not add waits on top of action latency.

If allocation volume and post-idle memory are comparable while collection timing differs, report
that bounded finding rather than claiming an identified leak or universal RAM improvement.
If a bounded cache candidate materially reduces allocation and the new normal paired benchmark
improves without correctness regressions, retain both bodies of evidence. If still inconclusive,
return the uncertainty for review before adding a paced workload, forced-GC experiment, profiler,
new production cache policy, or broader matrix.

Host checks: `python3 -m unittest discover -s scripts/performance -p 'test_*.py'` and
`git diff --check`. They validate schema/provenance separation only, not Android compilation.
