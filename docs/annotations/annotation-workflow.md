# Annotation workflow (0.16.18 candidate)

## User-facing scope

- In the paginated reader, tap a visible saved highlight to open its current note, local-delete, resize and export controls. Resize restores the exact range; tap that range to expose native selection handles, then choose **Save highlight** or **Cancel**. Back cancels before leaving the article. No quote or anchor is changed during handle dragging.
- Saved's existing overflow menu opens **Highlights and notes**. Search quotes, notes, titles, sources, authors or URLs. Notes filters nonempty notes. Unsynced filters queued, sending, failed, paused and review-required exports, excluding intentionally local-only items. Source removal does not delete the saved quotation. Opening an RSS source requires the original currently active account and cannot silently mark another account's article read.
- **Automatically highlight selected text** is off by default, independent of Readwise export. A completed native selection gesture may save one local highlight. A second handle adjustment in that session does not silently update or create another record. Explicit Add note on that same captured range edits the accepted automatic highlight, even if its local save callback is still pending.
- Automatic saves have a persisted 10-second initial export grace. Undo deletes locally and cancels unclaimed outbox work; it cannot recall a request already sent. There is no remote DELETE. Undo remains available in the selection menu and existing reader/annotation controls, not a new permanent footer.
- Reader controls summarize all current article export states, including older failures/review states rather than only the newest success. Existing per-highlight detail/retry and Settings queue status remain the underlying mechanism.

The notebook's local search/filter work runs off the UI thread; rows are lazy and bounded in batches. The repository still observes all annotations in memory: this is not database-paged search. No animations, full-screen overlay bars, account setup, remote imports or real-account testing are introduced.

## Compatibility and limitations

Existing database schemas, signing identity and package ID remain unchanged. Resize preserves source metadata, local ID, note, created date and remote binding, while incrementing its revision. A frozen CREATE is never rewritten; subsequent changes use UPDATE after its acknowledgment. Readwise exports update quote/note, not Reader document anchors or the original remote ordering hint. Local-only or replacement-account annotations do not gain export consent through editing.

Notebook-origin RSS reading uses the annotation-capable paginated reader without changing the global reader preference. Ordinary legacy scrolling-reader annotation parity is not included. RSS extraction and unrelated performance experiments are outside this update.

## Verification plan

Run exact-head Android compile, unit tests, lint and APK build. Run the existing five-width/two-font annotation matrix plus new real-touch cases for highlight tap, native resize handles, Save/Cancel/Back, auto-save and Undo; production UI cases cover delayed auto-save callbacks, repeated identical text at distinct offsets, live status, source/session changes, notebook search/filter/Back and missing/wrong-account sources. Existing newspaper, Saved, formatting and alignment suites remain required.

The historical released0.16.16 default-longpress failure was verified in run39904058620 (released source SHA256912bfcf2ab12a892d1348d73f26fca7d27db59aede923cec0097caa9851106ff). The newer reader API cannot compile a single-file source swap from that release, so that obsolete experiment is removed; its actual default-longpress regression remains in the candidate native matrix. No new historical baseline reproduction is claimed.

Local standalone repository tests currently pass 36 cases, notebook model/position tests 9, and JavaScript suites 86. Native tests added for this candidate have not yet been executed. Physical BOOX behavior and real Readwise accounts remain unverified. Publication requires separate authorization and all final gates passing.
