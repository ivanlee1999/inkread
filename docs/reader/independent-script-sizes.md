# Independent article script sizes

## Behavior

The existing `fontSize`/`einkFontSize` remains the English/Latin base size. A new `chineseFontSize`/`einkChineseFontSize` stores an independent 12–48 CSS-pixel CJK size. Missing CJK settings inherit the user's old common size without writing a migration. Both default to 18, preserving the default appearance. The live Text panel has separate English and Chinese size controls; Apply writes one snapshot, Cancel restores both, and Reset previews both defaults. Existing quick A controls move both scripts by the same bounded delta.

Han (including supplementary ideographs), kana, bopomofo, CJK/full-width punctuation and full-width digits use the Chinese size. ASCII letters, ASCII digits/punctuation and other scripts retain the base size. Combining marks and variation selectors stay with their preceding base. Code/preformatted text, math, SVG and raw-text/form elements retain their existing specialized typography. Relative heading/byline sizes, font choices, line height and margins remain in effect. No new permanent toolbar or animation is added.

## Rendering and compatibility

`size-adjust` on the CJK font face alone cannot cover absent local fonts or glyph fallback. Instead, `eink-script-fonts.js` installs transparent inline runs once before pagination. Subsequent previews update only a CSS variable, with no HTML reload or repeated wrapping. The runs inherit the containing heading/paragraph size and multiply it by `Chinese / English`. Font fallbacks therefore obey the setting too.

The helper preserves the original text-node data, lengths, parent context and ordering. Pagination still persists the original node/UTF-16 coordinate. Annotation maps still fingerprint the exact original text and node lengths, and convert selections and native ranges through the fragment mapping. This intentionally keeps existing version-1 saved positions and repeated-quote anchors compatible, rather than invalidating or guessing them. Text content, link targets, quote/context strings and scalar boundaries are unchanged.

## Local verification

- Pure Kotlin model/payload JUnit tests exercise defaults, independent edits/clamps and script sizes in the bridge.
- Preference tests cover non-mutating legacy migration, distinct-size persistence and atomic combined quick-size changes (require Android Gradle dependencies to execute).
- JavaScript tests cover exact logical/physical mappings, legacy node ordering, whitespace, supplementary Han, combining/variation sequences, punctuation, code exclusions, element selection boundaries and repeated installation.
- Existing formatting/annotation runtime tests remain required. Added formatting tests cover independent CSS updates, anchor preservation and largest-script viewport allowance.
- Kotlin parser checks are syntax checks, not an Android build.

### Verified in the cloud workspace (2026-10-23)

104 JavaScript tests pass, including helper + actual annotation-runtime integration with byte-equivalent legacy anchors, ambiguous duplicate quotes, resize/jump and element endpoints. 13 pure Kotlin JUnit model/payload tests pass. All 14 changed Kotlin files pass syntax parsing; JavaScript syntax and `git diff --check` pass. These are not Android build results.

A real Chromium launch did not reach any assertions: the sandbox blocked its Unix socket and the allowed escalation failed during read-only mount setup before launch. No Chromium/Android/BOOX pass is claimed. The optional `tools/verify-script-fonts-browser.cjs` runner preserves those pending geometry/fallback/legacy-anchor checks for an environment with Playwright and Chromium available; it does not install dependencies.

## Required native regression before release

Run the full existing formatting, alignment, annotation and Saved-reader workflows on the combined release candidate, including genuine process-restart phases. The formatting native suite now measures independent English/CJK computed sizes, unchanged text/run counts, no reload, logical-anchor visibility, Apply/Cancel/Reset, and distinct sizes surviving process restart. Alignment probes resolve the preserved original coordinate to an actual DOM Range and continue measuring glyph geometry.

Use phone/Palma-like and full-width tablet/Note-Air-like profiles, font scales 1.0 and 1.6, portrait/landscape and short viewports. Test English 12/CJK 48 and English 48/CJK 12, all four margins, line-height extremes, selected/downloaded/default fonts, unavailable-font fallback and late font/image reflow. Confirm title hierarchy, punctuation, numbers and mixed-language links.

For annotations, select across English/CJK/link runs using real long-press handles; create notes/highlights, resize, jump from notebook, reopen, switch content source and restore previously saved repeated-quote anchors. Confirm exact quote/context, no duplicate/unmatched highlights and no selection ownership regression. Verify Back and volume/page gestures still work after closing formatting. Physical BOOX devices have not been tested by this change.
