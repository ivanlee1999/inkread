package me.ash.reader.ui.page.home.reading

import org.junit.Assert.*
import org.junit.Test

class EInkReadingDocumentFingerprintTest {
    @Test fun sameBodyRevisionAndRenderedMetadataReopenIdentically() {
        assertEquals(EInkReadingDocumentFingerprint.create("body-v1", "Title", "Source · Date"),
            EInkReadingDocumentFingerprint.create("body-v1", "Title", "Source · Date"))
    }

    @Test fun changedBodyTitleOrMetadataInvalidatesTheExactNodeAnchor() {
        val original = EInkReadingDocumentFingerprint.create("body-v1", "Title", "Source · Date")
        assertNotEquals(original, EInkReadingDocumentFingerprint.create("body-v2", "Title", "Source · Date"))
        assertNotEquals(original, EInkReadingDocumentFingerprint.create("body-v1", "New title", "Source · Date"))
        assertNotEquals(original, EInkReadingDocumentFingerprint.create("body-v1", "Title", "New author · Date"))
    }

    @Test fun lengthDelimitedFieldsCannotAliasEachOther() {
        assertNotEquals(EInkReadingDocumentFingerprint.create("ab", "c", "d"),
            EInkReadingDocumentFingerprint.create("a", "bc", "d"))
    }
}
