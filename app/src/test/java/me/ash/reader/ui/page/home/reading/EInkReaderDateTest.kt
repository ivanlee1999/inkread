package me.ash.reader.ui.page.home.reading

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class EInkReaderDateTest {
    @Test fun defaultRssDateRemainsUnchanged() {
        val date = Date(0)
        assertEquals(SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(date), einkReaderDate(date))
        assertEquals(einkReaderDate(date), einkReaderDate(date, ""))
    }

    @Test fun savedDateIsExplicitAndItsRenderedIdentitySurvivesReopen() {
        val first = einkReaderDate(Date(0), "Saved")
        val reopened = einkReaderDate(Date(0), "Saved")
        assertEquals("Saved ${einkReaderDate(Date(0))}", first)
        assertEquals(first, reopened)
        assertEquals(EInkReadingDocumentFingerprint.create("body-v1", "Title", first),
            EInkReadingDocumentFingerprint.create("body-v1", "Title", reopened))
        assertNotEquals(EInkReadingDocumentFingerprint.create("body-v1", "Title", first),
            EInkReadingDocumentFingerprint.create("body-v1", "Title", einkReaderDate(Date(0))))
    }
}
