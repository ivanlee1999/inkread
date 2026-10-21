package me.ash.reader.ui.page.home.flow

/** Prepare photos only for rendered variants that can actually display them. */
internal fun shouldPrepareArticleImage(
    imagesEnabled: Boolean,
    preview: NewspaperPreview?,
    measurementOnly: Boolean,
): Boolean = imagesEnabled && !measurementOnly &&
    preview != NewspaperPreview.Minimal && preview != NewspaperPreview.HeadlineOnly
