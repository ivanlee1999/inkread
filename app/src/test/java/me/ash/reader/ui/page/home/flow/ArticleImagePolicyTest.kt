package me.ash.reader.ui.page.home.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleImagePolicyTest {
    private val previews = listOf(null) + NewspaperPreview.entries

    @Test fun imagePreferenceOffNeverPreparesAnyPreview() {
        previews.forEach { preview ->
            assertFalse(shouldPrepareArticleImage(false, preview, measurementOnly = false))
        }
    }

    @Test fun measurementSlotsNeverPrepareImagesEvenForPhotoPreviews() {
        previews.forEach { preview ->
            assertFalse(shouldPrepareArticleImage(true, preview, measurementOnly = true))
        }
    }

    @Test fun textOnlyPreviewsNeverPrepareHiddenImages() {
        listOf(NewspaperPreview.Minimal, NewspaperPreview.HeadlineOnly).forEach { preview ->
            assertFalse(shouldPrepareArticleImage(true, preview, measurementOnly = false))
        }
    }

    @Test fun continuousFullAndCompactPreviewsStillPreparePhotos() {
        listOf(null, NewspaperPreview.Full, NewspaperPreview.Compact).forEach { preview ->
            assertTrue(shouldPrepareArticleImage(true, preview, measurementOnly = false))
        }
    }
}
