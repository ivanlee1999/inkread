# Highlights notebook

Open **Saved → library menu → Highlights and notes**. The notebook includes local RSS and Saved highlights across articles and accounts. It never switches accounts or fetches an unavailable source from the network.

- Search matches every word across quote, note, article title, source name, author and source URL, case-insensitively.
- Notes means a nonblank note. Unsynced means queued, sending, failed, paused or review-required export. Intentional local-only highlights and fully exported highlights are excluded. The two filters intersect.
- Newest creation first, with ID as a deterministic tie-breaker. Editing a note or changing export status does not change ordering.
- Search is debounced by 180 ms; matching/sorting runs on the default dispatcher. Presentation is bounded to 30 lazy rows per page, with clipped previews and complete text in the action dialog. The existing observeAll repository still loads all annotation records; this is not database paging.
- Search, filters, page and list position are retained when returning from a reader. Nav3 retains the notebook entry; SavedStateHandle and saveable LazyListState retain navigation/recreation state. Position flows update retained UI state through a lightweight combine, without re-running search/sort; the initial loading placeholder cannot overwrite the saved position.
- Tapping a quote opens its original RSS/Saved reader and requests the existing exact logical-anchor jump. An RSS jump verifies source/account identity before loading and before read-state changes. Another account requires explicitly switching to that account first. Back returns directly to the notebook, not an RSS list.
- An unavailable or removed source does not remove the saved quote/note. The notebook explains the failure and keeps local edit/delete available. Restored reader routes also preserve the quotation when their source disappears.
- Note edit, local deletion, retry and export labels reuse the reader's actions/UI. Local deletion does not delete an exported Readwise copy. Review-required entries do not gain an unsafe retry action.

## Verification

Standalone JVM: AnnotationNotebookModelTest covers matching, intersecting filters, explicit export-state membership, stable order, bounded pages/clamping, missing-source preservation, account isolation and retained/stale navigation policies, and live position restoration without repeated filtering.

Offline native fixtures: AnnotationNotebookUiTest covers actual search/filter/page controls; readable missing sources and editing; cross-account refusal; local-delete confirmation and canceled notes; notebook Back restoration including lazy-list position; canceled late source lookups; live export status/retry updates; and a deleted highlight during note editing without discarding the typed draft; and restoration through an initial loading frame. These fixtures require Android instrumentation and were not executed locally.

The notebook's reader integration additionally depends on the reader's expected-account guard and requested-annotation jump parameters. Syntax/JVM-only checks are not Android compilation or physical e-ink validation. No real account, token, or Readwise request is needed by these fixtures.
