# Clean editorial UI audit

## Scope and evidence

This inventory covers all **22 active Navigation 3 routes** in `ui/page/nav3/key/NavKey.kt` and their entries in `AppEntry.kt`, plus reachable overlays, the separate crash-report/widget activities, and home-screen widgets. `Route.Reading` contains both the article list and reader; search is a list state, not a separate route. The commented `Flow` key and legacy `RouteName` constants do not introduce additional live destinations.

**Static audit is not full visual validation.** Every destination below was inspected in source. Native screenshots/tests are separate evidence and must be recorded from the exact final candidate. The cloud checkout has no Android SDK/emulator, so this document does not claim that every route has been opened, rendered, or exercised end to end. The release verification record should identify the actual CI run and screenshot artifacts.

The editorial rule is hierarchy, not decoration: headline first, restrained source/actual-author/date, readable summaries, and spacing rather than repeated borders. Functional separators, input outlines, selected states, warnings, and discoverable controls remain. Existing typography, image, date, source-name, layout, and toolbar preferences remain available. No paginator, read-state, storage, WebView security, navigation, or account workflow was redesigned for this cleanup.

## Shared changes

- `ArticleMetadata.kt`: one source/author formatter for list and reader. Trims and normalizes Unicode whitespace, omits blanks, and suppresses only exact case-insensitive duplicate attribution. Bidi formatting controls are ignored for comparison only and preserved for display. It never invents an author or conflates similar names. Source-name visibility is applied after deduplication, so hiding the publication cannot bring it back as a fake author.
- `Metadata.kt`: the native/WebView-scroll reader begins with its headline, followed by a wrapping source/author/date line rather than a date above the headline plus separate author and source rows. User font, alignment, bold, uppercase, and bidirectional title handling remain. Uppercase text now follows the current article instead of a stale remembered title. Headline semantics identify the heading.
- `EInkPaginatedContent.kt`: the same deduplicated provenance; the decorative rule and double spacing below metadata are removed. All article-content escaping, resource handling, JavaScript, page calculation, and navigation remain unchanged.
- `DisplayText.kt`: compact serif page headings, 24dp rather than 48dp top spacing, accessible heading semantics, wrapping instead of one-line truncation, higher-contrast descriptions. The explicit External basic-font choice remains respected. Description visibility is explicitly nonanimated.
- `SettingItem.kt` and `SelectableSettingGroupItem.kt`: 18sp primary labels with normal wrapping and retained 14sp descriptions instead of forced one-line clipping. Padding and controls remain. The latter now honors its existing `enable=false` flag for clicks, not just appearance. Independent row/toggle actions retain their meaningful vertical separator.
- `TextFieldDialog.kt` and `RadioDialog.kt`: titles may occupy up to three lines rather than one; the limit prevents pathological titles consuming the entire dialog. Existing confirm/cancel, validation, scrolling, and dismissal behavior remain.
- `AccountsTab.kt`: wider centered account tiles, readable two-line labels instead of 11sp single-line names, and selected semantics. The text still exposes the full name to accessibility; decorative icons no longer announce it twice. The picker content is bounded and scrollable so many accounts do not hide the Add action. Switch/settings/add callbacks are unchanged.

## Active route inventory

“Shared” means the route receives the shared heading/row/dialog improvements without rewriting its content or behavior. “Retained” is an intentional audit outcome, not an omitted review.

| Route | Page and scope | Decision and reason |
| --- | --- | --- |
| `Startup` | Welcome, illustration, terms summary, agree action; terms dialog | Shared heading. Retain first-run explanation, legal text and explicit Agree/Cancel; do not disguise or remove consent controls. |
| `Feeds` | Folder list, expanded folders, publication/subscription lists, newspaper/mosaic editions, account selector, filters, sync and add/settings controls | Changed folder/publication presentation: remove redundant repeated rules/count phrasing, preserve folder identity, publication source, unread affordances and controls. Account picker labels separately improved. Pagination/group expansion behavior retained. |
| `Reading` | Adaptive list/detail, article list, search results, native/scroll-WebView/E-Ink reader | Changed article hierarchy/bylines/summary presentation and reader metadata. Preserve distinct panes, search, selection, star/read/share/menu actions, full-content/TTS, image viewer and page controls. |
| `Settings` | Settings index and update banner | Shared compact headings and wrapping navigation rows. Keep category icons/descriptions and available-update notice; these provide navigation and status, not redundant decoration. |
| `Accounts` | Account list, account advice and add-account entry | Shared headings/rows. Keep account type, add entry and explanatory advice; do not obscure account identity or deletion scope. |
| `AccountDetails` | Name, Local/FreshRSS/Fever/Google Reader connection fields, sync, retention, export, clear/delete | Shared headings/rows/dialog titles. Keep semantic section labels, connection descriptions, validation and dangerous-action confirmations. No credential or account-state changes. |
| `AddAccounts` | Local and self-hosted service choices | Shared headings/rows. Retain service names/icons/descriptions and service grouping; necessary to distinguish account types. |
| `ColorAndStyle` | Palette selection, dark/E-Ink mode, font downloads, size/word spacing, page-style links | Shared typography plus removal of the large decorative palette illustration with an empty click handler. Real palette choices, selected states, font-download feedback and all settings remain. |
| `DarkTheme` | Theme mode selection and AMOLED option | Shared headings/rows. Retain mutually exclusive choices, selected state and AMOLED switch. |
| `FeedsPageStyle` | Live feeds preview, top bar, groups, filter bar | Shared headings/rows/dialog titles; preview inherits production feed changes. Retain preview and section labels because they explain the controls being adjusted. |
| `FlowPageStyle` | Live article preview, icons/sources/images/descriptions/dates, read styling, filter bar | Shared headings/rows/dialog titles; preview inherits production article changes. Preserve every saved visibility/layout preference, even when its default is visually quieter. |
| `ReadingPageStyle` | Reader preview, renderer/font, toolbar and advanced page links | Shared headings/rows/dialog titles. Retain renderer choice, preview, toolbar elevation and all customization links. |
| `ReadingBoldCharacters` | Bold-character example and toggle | Shared heading/row. Retain the example because it communicates the actual transformation. |
| `ReadingPageTitle` | Headline/subhead preview, bold, uppercase and alignment | Shared heading/rows/dialog titles. Retain separate Title/Subhead sections; their similar controls affect different text and are not redundant. |
| `ReadingPageText` | Text preview, general/Chinese/English sizing, bold, spacing, line height, padding, alignment | Shared heading/rows/dialog titles. Retain language-specific options, constraints and live preview. |
| `ReadingPageImage` | Image preview, corner/padding/size choices | Shared heading/rows/dialog titles. Retain image preview and controls; no media-loading or sizing semantics changed. |
| `ReadingPageVideo` | Unfinished video-style settings | Removed empty decorative preview/no-op click target. Rounded-corner and horizontal-padding placeholders are explicitly disabled with the existing localized Coming soon description; they no longer pretend to change a setting. No implemented video preference is removed. Shared heading/rows remain. |
| `Interaction` | Startup, folder/list, reading, external links, share behavior | Shared heading/rows/dialog titles. Retain descriptive section labels and switches; no swipe, read-state, startup or external-app preference changes. |
| `Languages` | Help-translate action and language choices | Shared heading/rows. Names can wrap; preserve current-language selection and locale behavior. |
| `Troubleshooting` | Bug-report link, JSON import/export, worker and sync diagnostics, import warning | Shared heading/rows. Retain diagnostic detail and invalid-import confirmation rather than hiding actionable failure information. |
| `TipsAndSupport` | App/about/update links, documentation/community, sponsorship | Retained. This is an informational/support surface, not another news list; no fabricated grid or decorative rules added. Existing support artwork/behavior is outside this surgical cleanup. |
| `LicenseList` | Third-party license list and project license link | Retained. Preserve the library-provided legally meaningful hierarchy, reading and linking behavior. |

## Overlays, states, and other entry points

| Surface | Decision and coverage required |
| --- | --- |
| Search field, query/results, clear/close and keyboard | Retain the single outlined/tonal input affordance and clear action. Result articles inherit the new hierarchy. Check query preservation, empty results, keyboard dismissal and returning to the prior list; source review alone does not prove these flows. |
| Account picker (`AccountsTab`) | Changed only identity readability and selected semantics. Check long English/Chinese names, current account, switch, add/manage, dismissal and many-account scrolling on narrow/large-text screens. |
| Subscribe dialog: input, searching, configure, error, OPML import | Retain URL input, error text, selected group, notification/full-content/browser presets, Search/Subscribe/Cancel/Import. Shared rename/new-group dialogs receive wrapping titles. Existing subscription-specific title and transition are not altered. Check keyboard, failed search, cancel during search, re-open and configure. |
| Feed management drawer | Retain editable feed identity, URL, settings, folder move and clear/delete actions. Shared Rename/Change URL/New group dialogs receive wrapping titles. No deletion, move or notification logic changes. |
| Folder management drawer | Retain folder identity, presets, move-to-group and clear/delete actions. All notification/full-content/browser batch confirmation dialogs, move confirmation and destructive confirmations remain. |
| New account dialogs: Local, FreshRSS, Google Reader, Fever | Retain bounded scrollable forms, loading/read-only state, certificate/password fields and explicit Add/Cancel. No authentication behavior changes. Shared connection-edit dialogs wrap their titles; bespoke account-creation titles remain. Requires a real DI-backed account fixture for runtime coverage. |
| Account-detail dialogs | Rename, sync interval, retention, export mode, clear-all and delete-account remain; shared text/radio titles improve. Never test destructive confirm paths against real data. |
| Appearance/interaction dialogs | Fonts, palette hex, layout preset, numeric sizing/spacing and radio choices retain validation and persistence. Shared title wrapping improves context; confirm/cancel must remain reachable with large system text and the keyboard. |
| Reader toolbar and pagination controls | Retained. Close/list, style, sharing, read/star/full-content/TTS and page/volume/tap navigation are operational controls. User-requested outlined toolbar separators remain preference-driven, not stripped globally. |
| Reader full-screen image viewer | Retained image, close/save actions and menus. Check image error, dismiss/back and return to the same article/page. No gesture/media behavior changes. |
| Reader loading, missing article and full-content error | Retain existing loading placeholder/indicator and error-content path. Do not replace error information with decorative empty cards or alter page ownership during loading. These states need actual app-flow validation beyond the metadata fixture. |
| Empty folders/subscriptions, empty filters/results, sync/loading | Preserve meaningful controls and state handling; no invented count, source, author, image, or filler card. Folder/list empty-state fixtures are distinct from successful live synchronization. |
| Terms, update/changelog, sponsor, invalid-import warnings | Retained explicit actions, warnings and scrollable content. Do not remove information that explains a commitment or failure. |
| Crash report activity (`CrashReportPage`) | Retained readable error heading, explanation/link, copy action and monospaced trace. This emergency diagnostic surface should not inherit a newspaper mosaic. |
| Widget configuration activity | Retain account/group/feed source selection, serif/sans-serif theme choice and Done. It is a separate host-launched activity, not a Navigation 3 route. Requires widget-host validation. |
| Article list and article card home-screen widgets | Retain Glance layouts and host-size behavior. No custom app-list formatting is forced into the widget renderer. Check host resizing, no-content states and article launch separately. |
| Platform-owned browser, share, document/certificate picker, permission and installer screens | Not restyled. Preserve integrations and Android-provided controls; these are not app-authored pages. |
| Unreferenced reader `StyleOptionDrawer`/preview stubs and legacy route constants | Reviewed but deliberately not promoted to active UI or rewritten. Live style navigation uses the settings routes above. |

## Verification and screenshots

Verification plan for the final candidate (only executed cases may be reported as passed):

1. Existing unit/pagination/read-state suites, lint, debug test compilation and release assembly. Byline unit cases must cover distinct author, exact duplicates with case/whitespace differences, missing source/author, similar-but-not-identical names, multilingual names and punctuation.
2. Native production folder/article captures on narrow phone and expanded/tablet widths, English/Chinese content, image/no-image/error, long titles, empty/single/large libraries, dark/normal/E-Ink and large text. Inspect article title, byline, summary, source controls and retained useful column rules rather than only pixel density.
3. `EditorialChromeUiTest`: actual callback-only settings routes where feasible; production shared settings fixtures where Hilt-backed pages cannot be instantiated independently; native reader metadata; actual account picker; shared dialogs. Test headings and title order, wrapping, disabled clicks, selected states, action reachability, cancel/back and repeated open/dismiss. A component fixture is not proof of the containing route's DI/navigation/account flow.
4. Existing `EInkReaderUiTest` with metadata assertions/screenshots, tap/page navigation and reader reopen. Header whitespace changes alter measured content height; they must not skip/duplicate pages or break viewport repagination. Inspect native WebView captures, not a browser recreation of its HTML. These tests alone do not establish hardware-volume-key behavior.
5. Untouched DI-backed startup/terms, account create/edit, subscribe search/error/configure, feed/folder management, crash handling and widget-host workflows are source-reviewed but not fully exercised by this targeted cleanup. Their coverage gap is explicit; new real-account creation or terms acceptance is not a prerequisite introduced by this visual change. Physical BOOX refresh/ghosting and hardware-volume-key behavior cannot be established by the representative screenshots.

At source-audit completion, `git diff --check` passed for the edited checkout. Native runtime execution and screenshot review are **pending external Android CI/device evidence**, not asserted here. Update this final verification record with the candidate commit, run IDs, executed tests, inspected screenshot paths, and any unrendered routes before describing the result as fully verified.
