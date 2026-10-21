# Content-aware newspaper pages

InkRead uses an original native Kotlin/Compose implementation. It does not embed a browser layout engine or add a JavaScript dependency.

## Open-source design references

- [Treesaver's page-grid selection](https://github.com/Treesaver/treesaver/blob/master/src/layout/grid.js) and [page construction](https://github.com/Treesaver/treesaver/blob/master/src/layout/page.js): compare measured page skeletons, preserve keep-together blocks, penalize unused space, and require forward progress. Treesaver offers an MIT licensing option alongside GPLv2.
- [React Photo Album's ordered row layout](https://github.com/igordanchenko/react-photo-album/blob/main/src/layouts/rows/rows.ts): bounded forward choices over contiguous source groups, squared deviation costs, and deterministic tie-breaking. The project is MIT-licensed.
- [Packery's free-rectangle packer](https://github.com/metafizzy/packery/blob/master/js/packer.js) is a useful contrast, not the chosen model. Unrestricted hole-filling can change reading order, which would make article navigation and read marking unsafe.

These are algorithmic references, not copied source or runtime dependencies. Photo-only justified rows are not a drop-in solution for mixed headlines, excerpts, photographs, and directory entries.

## Native planner

1. Compose measures complete candidate blocks with the user's width, columns, typography, and font scale. Full and Compact candidates retain readable type; the existing Minimal/HeadlineOnly fallback is reserved for unusually small panes.
2. An ordered group contains alternate presentations of the same contiguous source IDs. Date headings travel with their first story; folder headers remain with their publication group.
3. `OrderedPagePlanner` compares bounded whole-page combinations rather than accepting the first fitting row. It explores at most six variants per group, 24 retained states, and 64 groups per page. Short/headroom states and low-detail-loss states are retained alongside the current best-scoring states.
4. Overflow, crossing an existing printed-page boundary, and skipping or reordering sources are forbidden. Every chosen page makes forward progress; groups beyond a search cap continue on later pages.
5. Integer cost combines squared unused-bottom fraction with summed per-story detail loss and a small capped real-source reward. Compact penalties are not averaged away by adding more stories. Ties favor less lost detail, more used height, then stable caller choice order.
6. After membership is fixed, richer previews are restored where possible. Compact headlines recover their existing Full line limit before any extra excerpt growth, without enlarging their photographs or reducing type. Remaining room can show more existing excerpt lines or actual publication names. Empty boxes are not stretched and filler text is never generated.

Measurements and enriched pages are cached. Photo arrival, decoding failure, read state, or star state does not change the printed page's geometry. Width/font/preset changes reflow around a retained logical source anchor, including repeated reflows that temporarily place earlier sources before it. Explicit page navigation establishes a new anchor. Previously computed page boundaries stay locked when another paging batch arrives.

## Verification and limits

Pure tests cover coordinated multi-row compaction, tiny-gap readability tradeoffs, deterministic ties, exact source partitions, hard boundaries, and bounded search continuation. Native fixtures check complete child bounds, CJK, photographs and text-only sources, three font scales, five widths, reader return, read marking, and gesture ownership.

Density reports distinguish actually displayed source text and decoded fixture-photo pixels from hidden sizing subcompositions, card rectangles, rules, empty image reservations, date furniture, and padding. A page with genuinely short content, a final page, a locked batch tail, or a missing image may still have whitespace. This is a bounded heuristic, not a claim of globally optimal layout or zero gaps. Physical BOOX display behavior still requires device verification.
