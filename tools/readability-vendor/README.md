# Mozilla Readability engine assets

The source in `upstream/` is copied unchanged from the published npm package
`@mozilla/readability@0.6.0` (https://github.com/mozilla/readability). Source SHA-256
values are enforced in `build.mjs`. Readability is Apache-2.0; JSDOMParser is MPL-2.0.
Original sources and licenses are retained, with notices/licenses in the APK too.

Jsoup 1.17.2 is declared directly as the tested extraction baseline rather than relying
on Readability4J's transitive Jsoup 1.11.2. Check Gradle dependency resolution in CI
for a higher transitive selection. No sanitizer implementation is changed.

Rhino 1.9.1 is pinned in the Gradle catalog. It is Mozilla's current release as
verified on 2026-10-24: https://github.com/mozilla/rhino/releases/tag/Rhino1_9_1_Release .
Its Java 11 bytecode is compatible with Android's toolchain; upstream documents
Android minSdk 26 and interpreter-only operation:
https://github.com/mozilla/rhino/blob/Rhino1_9_1_Release/it-android/README.md .
This application uses interpreter-only mode and does not enable Rhino's optional
thread-safe object mode, which depends on newer Android VarHandle APIs.

## Reproduce generated assets

With Node 24 and npm 11, from this directory:

    npm ci --ignore-scripts
    npm run build
    npm test

Pinned Babel 8 transforms syntax (including unsupported spread and Unicode-regexp
syntax) for Rhino; it does not rewrite extraction rules. Runtime includes only
the two generated JS files, not Node or Babel. `verify.mjs` compares original and
transformed core output on the same well-formed synthetic inputs.

The generated files under `app/src/main/resources/readability/` have these hashes:

- Readability.js: `9f1128f67cb167cc69a2160aee49e870238195b8076188349672744a15c04c2f`
- JSDOMParser.js: `30399c0d8d1ea1974882420f884ca872ab8d7b9e2fcc527834b1b35ffae896cf`

## Adapter boundary

`ModernReadability.extract(html, finalUrl, checkCancelled)` is synchronous and must
run off the main thread. Return values contain extracted content and metadata,
not sanitized renderer HTML. Access-eligibility checks and the existing rendering
sanitizer stay in the caller. Publisher scripts are never evaluated, and no Java
objects, browser APIs, filesystem/network APIs, or Android bridge are exposed.

Jsoup parses bounded input, validates node count/depth, removes executable script
and style data, XML-escapes JSON-LD, and resolves URLs before/after core extraction.
This is necessary because JSDOMParser expects well-formed XML-like HTML. A tiny
absolute-URL classifier supports Readability's author-URL exclusion; actual URL
resolution is done by Jsoup, including promoted lazy-image sources and srcsets.

Defaults: 4 MiB UTF-8 input, 20,000 nodes, depth 128, 500 million Rhino instructions,
8 seconds elapsed time, 4 MiB output characters. Time/cancellation are cooperative
at Reader reads, DOM traversal, and Rhino instruction checkpoints, not a process
memory ceiling or hard real-time preemption inside a native library operation.
Instruction exhaustion escapes JS catch blocks. Cancellation stays cancellation.
The caller retains its legacy/feed fallback for bounded or runtime-compatibility
failures. No source-content or annotation-store migration is performed here.

## Verification

- `./gradlew :app:testGithubDebugUnitTest --tests '*ModernReadabilityTest'`
- Device class: `me.ash.reader.infrastructure.html.ModernReadabilitySmokeTest`.
  Run on a minSdk-like runtime and a release-like/minified build before rollout;
  desktop JVM passing does not establish Android/R8 or BOOX performance.
- The only added ProGuard keeps are the reflectively-created interpreter's
  no-argument constructor and the RegExp ServiceLoader implementation.

Local 2026-10-24 regression check used the previously frozen public Verge deals
article and Solidot Chinese article. Original versus transformed core JSON was
identical on the same Jsoup-normalized inputs. Actual Rhino adapter text, image
URLs, and anchor text/URLs matched that original output. Deals retained 3/3 long
editorial paragraphs and 3 images; no claim is made that all boilerplate is removed.
The two other previously captured Verge examples were excluded once explicit
subscription restrictions were identified. No new page fetching or bypass was
needed for these local data-only tests.
