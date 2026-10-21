# Reproducible optimized responsiveness comparison

## Contract and limits

This is an **opt-in native component fixture**, not a substitute for the existing correctness UI suite, a Macrobenchmark run, a real subscription/account benchmark, or physical E-Ink measurements. The shipping `release` build and production sources receive no benchmark code, test dependency, animation, or keep rule. `-PenablePerformanceHarness=true` adds `githubPerformance`, initialized from release with R8/resource shrinking enabled, `debuggable=false`, the disposable Android debug signing key, and application ID `me.ash.reader.performance`.

The fixture renders the production `EInkArticleListHeader`, `EInkNewspaperViewport`, `FolderEditionHeader`, `FolderNewspaperEdition`, and `EInkPaginatedContent`. It uses the real article/folder page-state dispatcher, expansion state/anchor, and Android Back dispatcher. Page navigation must succeed, draw a native window frame, and restore exactly the same article IDs after reader Back. It does not simulate user touch dispatch, the real `MainActivity` navigation graph, account selection, Room/paging emissions, syncing, unread writes, network images, or image decoding.

Two startup measures are deliberately separate:

- `cold-start.txt` records `am start -W` after force-stop. The driver requires `LaunchState: COLD`. `TotalTime` includes process/Application startup and the first fixture Activity window, but does **not** mean that every article is fully paginated. The unmodified `AndroidApp` initializes as usual, including `accountInit`, `workerInit` and `checkUpdate`. Radios are disabled, but failed network checks, retries, WorkManager setup and other background initialization can still enter app-process CPU/heap observations.
- `fixture_activity_ready` starts in `onCreate` before fixture generation and waits for the article edition plus rendering barriers. It includes synthetic content creation, real native layout, and observer overhead, but excludes prior Application initialization. Never present it as total app startup.

Each interaction records wall time, app-process CPU time, Java/native allocated heap and signed heap deltas, native root draws, displayed-page/reader callbacks, fixture-screen compositions, page counts, and visible source IDs. Timers exclude result serialization and shell collection. Process CPU and heaps **exclude the separate WebView renderer**. Heap deltas can be negative due to GC and are not allocation counts or proof of a leak. `dumpsys meminfo`, `dumpsys gfxinfo ... framestats`, process list, logcat, and a final native screenshot are retained separately; these process-wide end-of-run dumps are not scenario-specific memory peaks.

The same two-vsync barrier follows every action; reader opening additionally waits for page count and `WebView.postVisualStateCallback`. This adds an explicit display-clock floor and observation cost. Warm interactions keep the app process alive; repeated reader openings create a new document/WebView but reuse the process. No artificial animations are added. `fixture_screen_compositions` counts only the fixture's screen function, not all production composables. `displayed_callbacks` is not a count of expensive layout probes.

## Fixed data and reproducibility

`PerformanceFixture.VERSION` identifies deterministic mixed English/CJK, fixed-date text, no remote images, system fonts, and a fixed 160-section reader HTML document. Output records exact content counts and HTML byte length.

| Scale | Articles | Folders | Total publications | Publications in expanded first folder |
|---|---:|---:|---:|---:|
| small | 200 | 24 | 100 | 8 |
| medium | 500 | 24 | 392 | 300 |
| stress | 1,000 | 100 | 2,980 | 1,000 |

Stress data is deliberately not described as typical. PR bootstrap runs small only from `scripts/performance/pr-config.json`; manual dispatch defaults to small and stress. The input accepts `small`, `medium`, `stress`, or a comma-separated selection. Use `small` for the initial compile/launch check.

The workflow checks out three revisions: the harness, exact baseline (`70e1e8c4d5666b736fc713be2c13ea87af0e2813`, the unoptimized 0.16.13 source), and candidate. On pull requests, both harness and candidate explicitly use the PR head SHA, never the synthetic merge commit; the baseline is fixed. Checkout credentials are not persisted and workflow permissions are read-only. `overlay.py` copies **only** performance-source/config files and appends the opt-in Gradle hook. Before writing, it refuses staged, unstaged or untracked checkout changes, except an identical previous overlay. This includes production additions and arbitrary build edits. Production changes from the harness checkout are never copied onto the baseline. Both APKs record full source SHA, fixture/config/driver SHA-256, APK SHA-256 and R8 mapping. The summary refuses differing harnesses, fixture sizes, geometry, font scale or WebView versions, incomplete pairs, debug builds or missing scenarios.

For each 400dp and 1000dp matrix job, both revisions run on the same API-34 AVD at density 160 and font scale 1.0. System animations and radios are disabled on that disposable AVD. Both APKs are freshly installed and full-AOT compiled (`cmd package compile -f -m speed`) before each process run; this is not a default-install/JIT/Baseline-Profile comparison. Three process runs per side use **baseline/candidate, candidate/baseline, baseline/candidate** order. Every interaction has two excluded warmups then eight measured samples, for 24 samples per side/size/scenario. Only three cold-process samples per side are available. OS and disk caches are not flushed.

Each individual readiness wait has a 120-second coroutine timeout and reader visual callback has 30 seconds. The host process run has a 600-second watchdog, with logcat and last scenario/state on failure. A synchronous blocked main thread may prevent the coroutine watchdog from firing; the host watchdog remains the backstop.

## Separate deterministic work counters

Timing runs use ordinary immutable lists. A distinct counter-only run wraps each folder's publication list in an `AbstractList` with unchanged values, equality and hash outputs, but counts `hashCode()` calls and the number of publication elements hashed. It exposes repeated deep hashing from production folder/planner data structures without adding production hooks. Counting wrappers add work and may alter optimization; **never use their elapsed timings for speed claims**. Counts measure only this clearly defined hash work, not total CPU operations, all structural equality, cache probes or recompositions. Paired counter-only outputs use the same wrappers and content.

## Running and reading evidence

Before the new workflow is on the default branch, opening/updating an authorized PR bootstraps it through its scoped `pull_request` trigger. It runs the exact PR head against the fixed baseline, with `small` selected in `scripts/performance/pr-config.json`. After small passes, an ordinary reviewed commit changing that file to `{"scales":["small","stress"]}` starts broader coverage on the same PR; no labels, privileged trigger, default-branch merge or repository permission change is needed. The config can select only the three allowlisted scales, not change the baseline.

After the workflow exists on the default branch, manual dispatch can choose baseline/candidate revisions and scales. Setting candidate equal to baseline is available for a harness-only repeatability study. This is a read-only workflow, does not use `pull_request_target` or secrets, and does not publish an APK/release. The parent/operator controls PR creation, pushes, dispatch and publication. GitHub may still require owner approval for workflows from first-time/fork contributors; the bootstrap does not bypass that review.

Host checks, no Android SDK required:

```sh
python3 scripts/performance/test_tools.py
python3 -m py_compile scripts/performance/*.py
```

Local Android build (only in an already provisioned Android environment):

```sh
./gradlew -PenablePerformanceHarness=true assembleGithubPerformance
```

Keep the JSON samples, APKs/mappings, source SHA and harness hash together. `summary.json` reports sample count, median, nearest-rank p95, range, absolute and percentage deltas. `summary.md` is a short comparison, not a pass/fail speed score. Cold timing, warm actions and counter-only data are kept separate. Compare the same scale and geometry; repeated same-device runs are evidence, not a universal performance threshold. A claimed improvement should be supported by correctness checks, consistent native direction and an explained reduction in work. Emulator CPU/graphics scheduling and fixed-vsync floors can hide small gains. Physical BOOX refresh, battery use, touch-to-photon latency and real-account responsiveness remain unverified until measured on relevant devices.

References: [GitHub workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows), [Android performance environment](https://developer.android.com/topic/performance/appstartup/setup-env), [advanced variant test setup](https://developer.android.com/studio/test/advanced-test-setup), [WebView visual state callback](https://developer.android.com/reference/android/webkit/WebView#postVisualStateCallback(long,%20android.webkit.WebView.VisualStateCallback)).
