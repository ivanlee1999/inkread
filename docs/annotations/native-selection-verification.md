# Default-mode native annotation regression contract

## Why the released suite missed the failure

Released `0.16.16` (`a09e07e`) has five `EInkReaderAnnotationUiTest` methods. Its four touch-based methods first open the reading controls and click **Select text**. The remaining method intentionally creates a browser range to verify exact cross-paragraph whitespace; it is not gesture evidence.

In released production, ordinary reading places a full-size Compose clickable row above the WebView. The WebView is unfocusable until `selectionMode` is true, and its native ActionMode callback also rejects creation unless that explicit mode is true. Therefore, a long-press in the user's initial reading state cannot demonstrate the behavior those tests previously covered.

This is a source-established failure mechanism. It is not a claim that the released APK or the new APK has been run locally.

## Executable old-fails/new-passes gate

The dedicated method is:

`me.ash.reader.ui.page.home.reading.EInkReaderAnnotationUiTest#defaultLongPressShowsNativeAnnotationToolbar`

It uses only production APIs already present in the release. Keep the new test and its helpers identical when comparing released `EInkPaginatedContent.kt` against fixed production. It starts with `selecting == false`, performs one Espresso `Tap.LONG` at read-only DOM text geometry, and checks the real Android floating toolbar before asserting native touch delivery, focus, or selection ownership. It never clicks **Select text**, enables selection via JavaScript, invokes a menu callback directly, or creates a DOM selection.

On the old implementation, the expected failure is an `AssertionError` with this exact marker, caused specifically by Espresso's bounded native-root lookup throwing `NoMatchingRootException`:

`DEFAULT_SELECTION_TOOLBAR_MISSING: a native long-press in normal reading mode must show the annotation toolbar`

A compilation failure, instrumentation startup failure, arbitrary assertion, timeout elsewhere, absent XML, or skipped test is not an acceptable reproduction. The baseline gate must require this exact test identity and diagnostic. After collecting that baseline, restore fixed production before running the normal matrix.

On the fixed implementation, the same method additionally requires:

- Native WebView DOWN and UP, no CANCEL, and elapsed `eventTime` at least Android's real long-press timeout.
- Selection ownership without a page turn or a change to `innerWidth`/`innerHeight`.
- The actual platform **Highlight**, **Add note**, and **Copy** actions, including overflow when needed.
- A real **Copy** click and the exact selected text on Android's clipboard, with no annotation created.

## Coverage and retained gates

`EInkReaderAnnotationUiTest` now has ten methods, five more than the release:

1. `defaultLongPressShowsNativeAnnotationToolbar`: dedicated default-mode baseline regression plus actual Copy.
2. `realLongPressRetainsCopyAndBackLeavesSelectionBeforeReader`: default entry, native toolbar capture, both volume keys locked, first Back cancels selection, then tap/swipe/volume navigation works and the next Back exits.
3. `nativeAddNoteCapturesBeforeSelectionClearAndSuppressesEditingVolume`: default entry, exact display/raw quote capture, Add note ownership transfer, editing volume suppression, and restored navigation after editing ends.
4. `nativeHighlightRendersWithoutEditingBodyTextAndDoneRestoresPaging`: default entry, Highlight capture/rendering with unchanged article HTML, restored navigation, and the retained explicit **Select text → Done** compatibility path.
5. `nativeBackCancelsHeldCaptureBeforeItCanOpenANote`: default entry and retained cancellation of an already-captured but delayed result; no late note opens.
6. `nativeHandlesAdjustDefaultSelectionWithoutPagingAtLargeReaderFont`: 32 CSS-pixel reader font, both native handle popups visible, a real handle drag expands the exact quote to `Selection preserves`, a second deliberate drag contracts it to `Selection`, page/article/volume navigation stays locked, and Highlight captures the adjusted quote.
7. `outsideTapAndSwipeDismissOnlyThenRestoreNavigationAtSmallReaderFont`: 12 CSS-pixel reader font, outside tap dismisses without paging, a fresh selection's outside swipe cannot become an article/page gesture, and deliberate later tap/swipe/volume navigation works.
8. `linksAndMarginLongPressDoNotBecomePageTurnsOrExternalNavigation`: link taps never page or change the local document; the immediately following ordinary tap is not suppressed by stale link hit-test state; a link tap during selection only dismisses; long-pressing whitespace is inert.
9. `defaultSelectionKeepsCopyButHidesUnavailableAnnotationActions`: default entry with a null annotation callback, Copy still works, Highlight/Add note are absent from both the main toolbar and every overflow adapter row, and no page/reader exit occurs.
10. `realBrowserCrossParagraphSelectionKeepsDisplayAndRawWhitespace`: retained real-WebView browser whitespace contract, explicitly separate from touch evidence. A stable `paragraph-0` text-node target prevents the inserted link fixture from changing the range endpoint type.

The fixture is synthetic/offline and records external-link callbacks without opening a browser. It has enough paragraphs to exceed two pages even at the 12 CSS-pixel font on the widest tablet.

Run all methods at all existing five configurations: 320×900/160 dpi, 400×1000/160 dpi, 1080×2400/420 dpi, 800×1100/160 dpi, and 1000×1200/160 dpi; each at system font scales 1.0 and 1.6. This retains the original 146-case matrix and adds 50 cases: **196 fixed-head cases total**, **19 per ordinary cell**, **25 for phone/1.0** including the same six persistence/credential cases. Require zero failures, errors, and skips. The deliberate single baseline failure is a separate gate and must not be counted as a passing fixed-head case.

Keep all four prior PNG requirements and add `newspaper-annotation-native-adjusted-selection-*.png`. Each cell must contain one of each expected capture, with the exact configured physical pixel dimensions. Preserve source SHA, WebView version, size/density, result XML, and logcat evidence.

## Native event and popup rules

The test's DOM access only measures layout or reads selection state in gesture paths. The only intentional Selection mutation remains inside the separately named browser-whitespace case. The held-capture test retains its existing delayed capture wrapper; it does not manufacture a selection.

Long-presses use Espresso's native `GeneralClickAction(Tap.LONG)`. Drags inject a single DOWN/MOVE/UP stream through Espresso's `UiController`, sampling `SystemClock.uptimeMillis()` per event and servicing the real main loop between moves. The helper asserts elapsed time and monotonic timestamps and logs them. There is no direct `dispatchTouchEvent`, test-clock-only long press, gesture replay, blanket retry, or weakened expected quote.

Native selection handles live in separate PopupWindows, not in the WebView's child hierarchy. The handle test matches their small displayed popup roots near the measured range endpoints, then preserves the pointer-to-endpoint offset while dragging. It does not depend on private/obfuscated WebView implementation class names. Exact expansion and contraction assertions must remain; finding a popup alone is not proof of a functioning handle.

The toolbar uses the existing real `floating_toolbar` root tag. Its overflow is closed through the native **Close overflow** control, not Back, so toolbar inspection cannot accidentally dismiss selection. Default selection uses Back/outside dismissal, without adding a Done control over article text. Only the explicit fallback checks **Done** in the already-open controls area.

Platform structure references: [Chromium WebView handle implementation](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/java/src/org/chromium/android_webview/PopupTouchHandleDrawable.java) and [AOSP floating-toolbar controls](https://android.googlesource.com/platform/frameworks/base/+/7f9be1cbfaafe9f92f3c073a7b48bbfaa12230ba%5E2..7f9be1cbfaafe9f92f3c073a7b48bbfaa12230ba/).

## Verification status

Local checks performed for the test change: standalone Kotlin PSI syntax parsing, source-contract assertions, and `git diff --check`. These are not Android compilation or device tests. Native behavior, handle positioning, and released-fails/fixed-passes remain unverified until the offline native CI run produces the required evidence. No real Readwise account or token is used by these fixtures.
