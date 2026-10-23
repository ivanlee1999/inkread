# Article sources and extraction

InkRead keeps the feed/server body separate from downloaded full text. FreshRSS may already provide a complete article; choosing full text must not silently replace it with an error page or a clearly shorter extraction.

## Choose a source

Open the reader's on-demand controls and choose **Source**. In the scrolling reader, use the article-content button.

- **Use feed/server content** restores the original stored body without rewriting its markup, image proxy URLs or anchors.
- **Use full article (keep feed if better)** uses the cached extraction when available, otherwise downloads it. A conservative comparison may retain the feed body when the extraction loses substantial text or images.
- **Fetch full article again** explicitly refreshes the extraction. Failed or clearly worse refreshes retain useful offline content.

The dialog shows which source is displayed. Feed content is not overwritten by these choices. The existing feed-level full-content preference remains the default on opening an article; there is no new global automatic extraction setting.

## Reading and annotation stability

If selection, annotation capture/editing or formatting starts while extraction is in flight, the downloaded result may be cached but does not replace the body being used. Finish the interaction and make another explicit source choice to display it. Source reports when new content is ready. A refresh-status notice alone does not change an annotation's document identity or discard a note draft.

A genuinely different source can have different pagination and quote locations. Existing annotation matching remains responsible for reattaching passages; source switching does not manufacture new annotation identities.

## Text and images

RSS and Saved page downloads share bounded HTML decoding: Unicode BOM, supported HTTP charset, supported HTML charset metadata, then UTF-8. Relative article links and images use the final redirect location and effective HTML base, including non-default ports.

Saved articles prefer useful responsive image sizes and try at most four candidates per logical image. Existing storage budgets still apply. Partial image failure does not discard article text; successful retained images remain available offline and are skipped by later retries. Absolute server/proxy URLs are not rewritten by the URL correction.

## Limits

The engine remains Readability4J 1.0.8. Content comparison is conservative and language-independent, not a guarantee of perfect extraction. Short legitimate Chinese and English articles do not need a minimum word count. Login-only, paywalled or JavaScript-only pages may still be unavailable. This app update does not change FreshRSS configuration, supply credentials, or bypass access restrictions.

RSS full-text cache remains disposable app cache. The Saved library is the durable offline reading path. This update does not turn every RSS folder into a permanently downloaded edition.
