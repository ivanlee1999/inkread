package me.ash.reader.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleMetadataTest {
    @Test fun distinctActualAuthorFollowsPublication() {
        assertEquals("The Journal · Ada Chen", articleByline("The Journal", "Ada Chen"))
    }

    @Test fun nullOrBlankAuthorsNeverInventAttribution() {
        for (author in listOf(null, "", " ", "\t\n")) {
            assertEquals("The Journal", articleByline("The Journal", author))
        }
    }

    @Test fun duplicateAuthorIsNotRepeatedWithDifferentCaseOrSpacing() {
        assertEquals("The Journal", articleByline("  The  Journal  ", " the\tjournal\n"))
    }

    @Test fun absentPublicationStillPreservesActualAuthor() {
        assertEquals("Ada Chen", articleByline("  ", " Ada\nChen "))
    }

    @Test fun missingMetadataProducesNoPunctuationOrPlaceholder() {
        assertEquals("", articleByline("\t", null))
        assertEquals("", articleByline("", "\n "))
    }

    @Test fun similarNamesAreNotMistakenForDuplicates() {
        assertEquals("Ada News · Ada", articleByline("Ada News", "Ada"))
        assertEquals("The Journal · Journal", articleByline("The Journal", "Journal"))
    }

    @Test fun internationalNamesArePreservedVerbatim() {
        assertEquals("城市观察 · 陈晓明", articleByline("城市观察", "陈晓明"))
        assertEquals("أخبار العالم · ليلى", articleByline("أخبار العالم", "ليلى"))
    }

    @Test fun multipleRealAuthorsAndPunctuationArePreserved() {
        assertEquals("Science & Nature · Ada Chen, Bill Park", articleByline("Science & Nature", "Ada Chen, Bill Park"))
    }

    @Test fun unicodeSpacesDoNotCreateASecondCopyOfTheSource() {
        assertEquals("The Journal", articleByline("The Journal", "The\u00a0Journal"))
        assertEquals("The Journal", articleByline("The Journal", "The\u202fJournal"))
        assertEquals("The Journal", articleByline("The\u2003Journal", "The Journal"))
        assertEquals("The Journal", articleByline("The Journal", "\u0085The\u0085Journal\u0085"))
    }

    @Test fun invisibleDirectionalMarksDoNotCreateDuplicateAttribution() {
        for (author in listOf("\u200eBBC\u200e", "\u2066BBC\u2069", "\u202aBBC\u202c")) {
            assertEquals("BBC", articleByline("BBC", author))
        }
    }

    @Test fun distinctDirectionalNamesKeepTheirDisplayFormatting() {
        assertEquals("BBC · \u2067ليلى\u2069", articleByline("BBC", "\u2067ليلى\u2069"))
    }

    @Test fun formattingOnlyLabelsDoNotCreateEmptyAttribution() {
        assertEquals("BBC", articleByline("BBC", "\u061c\u200e\u200f\u2069"))
        assertEquals("Ada", articleByline("\u200f", "Ada"))
        assertEquals("", articleByline("\u200f", "\u2066\u2069"))
    }


    @Test fun hiddenPublicationCannotReappearAsItsOwnAuthor() {
        assertEquals("", articleByline("The Journal", " the\u00a0journal ", showSource = false))
        assertEquals("", articleByline("BBC", "\u200eBBC\u200e", showSource = false))
        assertEquals("", articleByline("The Journal", null, showSource = false))
        assertEquals("Ada Chen", articleByline("The Journal", "Ada Chen", showSource = false))
    }

}
