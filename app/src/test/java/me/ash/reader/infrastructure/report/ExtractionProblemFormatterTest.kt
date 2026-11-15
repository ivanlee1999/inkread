package me.ash.reader.infrastructure.report

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ExtractionProblemFormatterTest {
    private fun snapshot() = ExtractionProblemSnapshot(
        capturedAtEpochMillis = 1_794_700_000_000L, appVersion = "0.16.24", appVersionCode = 69,
        deviceModel = "BOOX Palma Pro", androidApi = 30,
        publicArticleUrl = "https://www.newyorker.com/books/public-story?utm_source=reader#paragraph",
        requestedSourceMode = ExtractionSourceMode.LOCAL, displayedSourceMode = ExtractionSourceMode.SERVER,
        feedMetrics = ExtractionContentMetrics(120, 1, 1), displayedMetrics = ExtractionContentMetrics(120, 1, 1),
    )

    @Test fun noAttemptIsExplicitAndUnknownHttpBytesAndSourceAreNeverInvented() {
        val json = JsonParser.parseString(ExtractionProblemFormatter.json(snapshot(), "Photo missing")).asJsonObject
        assertEquals(1, json["schema_version"].asInt)
        assertTrue(json["last_local_attempt"].isJsonNull)
        assertFalse(json.has("source_revision"))
        assertEquals("https://www.newyorker.com/books/public-story", json["public_article_url"].asString)
        assertTrue(json["url_redacted"].asBoolean)
        assertFalse(json.has("http_status"))
        val markdown = ExtractionProblemFormatter.markdown(snapshot(), "Photo missing")
        assertTrue(markdown.contains("No local extraction attempt recorded"))
        assertFalse(markdown.contains("HTTP status: 200"))
        assertFalse(markdown.contains("parsing failed"))
        assertFalse(markdown.contains("utm_source"))
        assertTrue(markdown.contains("normalized text characters"))
    }

    @Test fun successfulParserWithImageLossIsReportedAsQualityVetoRatherThanParserOrAccessFailure() {
        val before = ExtractionContentMetrics(120, 1, 1)
        val candidate = ExtractionContentMetrics(9_000, 23, 0)
        val attempt = ExtractionAttemptSnapshot(1_794_700_000_000L, 640, ExtractionAttemptOrigin.FRESH,
            ExtractionCacheState.REFRESH_REQUESTED, true, ExtractionAttemptOutcome.FEED_RETAINED,
            ExtractionFailureStage.QUALITY, ExtractionDiagnosticReason.QUALITY_REJECTED,
            httpStatus = 200, responseBytesRead = 869_084, responseBytesComplete = true, parserElapsedMillis = 220,
            fetchedCandidate = candidate,
            readerComparison = ExtractionSelectionSnapshot(ExtractionSelectedSource.FEED,
                ExtractionSelectionReason.FEED_IS_BETTER, ExtractionQualityVeto.IMAGE_LOSS, before, candidate, before))
        val report = snapshot().copy(lastLocalAttempt = attempt)
        val json = JsonParser.parseString(ExtractionProblemFormatter.json(report, "")).asJsonObject
        val local = json["last_local_attempt"].asJsonObject
        assertEquals("quality", local["failure_stage"].asString)
        assertEquals("feed_retained", local["outcome"].asString)
        assertEquals("image_loss", local["reader_comparison"].asJsonObject["detail"].asString)
        assertEquals(23, local["fetched_candidate"].asJsonObject["paragraph_blocks"].asInt)
        assertEquals(0, local["fetched_candidate"].asJsonObject["editorial_images"].asInt)
        assertEquals(869_084, local["response_bytes_read"].asInt)
        val markdown = ExtractionProblemFormatter.markdown(report, "")
        assertTrue(markdown.contains("Stage: quality"))
        assertTrue(markdown.contains("detail=image_loss"))
        assertFalse(markdown.contains("parser failed"))
        assertFalse(markdown.contains("paywall"))
    }

    @Test fun savedExportsOmitLocalIdentitiesAndEveryUrlOrCredentialInNotes() {
        val saved = SavedExtractionProblem("local-marker-private", 1_000, 2_000, snapshot(),
            "Still short\nCookie: synthetic-private-cookie\nhttps://rss.private.example.org/feed?token=synthetic-private-token",
            accountId = 987, articleId = "private-article-identifier")
        val outputs = listOf(ExtractionProblemFormatter.markdown(saved), ExtractionProblemFormatter.json(saved))
        outputs.forEach { output ->
            listOf("local-marker-private", "private-article-identifier", "account_id", "article_id", "report_id",
                "987", "synthetic-private-cookie", "synthetic-private-token", "rss.private.example.org", "utm_source").forEach {
                assertFalse("Unexpected local/private field $it", output.contains(it))
            }
            assertTrue(output.contains("Still short"))
        }
        val json = JsonParser.parseString(outputs[1]).asJsonObject
        assertFalse(json.has("title"))
        assertFalse(json.has("created_at_epoch_millis"))
        assertEquals(ExtractionProblemFormatter.json(saved.snapshot, saved.notes), outputs[1])
        assertEquals(ExtractionProblemFormatter.markdown(saved.snapshot, saved.notes), outputs[0])
    }

    @Test fun privateNewsletterSnapshotNeverExportsTheUrlEvenWhenAttemptHasUsefulNumbers() {
        val report = snapshot().copy(publicArticleUrl = "https://newsletter.private.example.org/mailbox/secret",
            requestedSourceMode = ExtractionSourceMode.NEWSLETTER, displayedSourceMode = ExtractionSourceMode.NEWSLETTER)
        val json = JsonParser.parseString(ExtractionProblemFormatter.json(report, "")).asJsonObject
        assertTrue(json["public_article_url"].isJsonNull)
        assertTrue(json["privacy_omissions"].asJsonArray.map { it.asString }.contains("newsletter"))
        assertFalse(ExtractionProblemFormatter.markdown(report, "").contains("newsletter.private.example.org"))
    }

    @Test fun typedAccessAndResourceReasonsOnlyExportTheirKnownFieldsAndPendingPresentationIsExplicit() {
        val access = snapshot().copy(presentationUpdatePending = true,
            lastLocalAttempt = ExtractionAttemptSnapshot(1_794_700_000_000L, 450, ExtractionAttemptOrigin.FRESH,
                ExtractionCacheState.MISS, false, ExtractionAttemptOutcome.FAILED, ExtractionFailureStage.ACCESS,
                ExtractionDiagnosticReason.ACCESS_GATE, httpStatus = 403))
        val json = JsonParser.parseString(ExtractionProblemFormatter.json(access, "")).asJsonObject
        assertTrue(json["presentation_update_pending"].asBoolean)
        val attempt = json["last_local_attempt"].asJsonObject
        assertEquals(403, attempt["http_status"].asInt)
        assertTrue(attempt["response_bytes_read"].isJsonNull)
        assertEquals("access_gate", attempt["reason"].asString)
        val resource = access.copy(lastLocalAttempt = access.lastLocalAttempt!!.copy(failureStage = ExtractionFailureStage.RESOURCE,
            reason = ExtractionDiagnosticReason.TIME_LIMIT, httpStatus = null))
        val text = ExtractionProblemFormatter.markdown(resource, "")
        assertTrue(text.contains("Stage: resource"))
        assertTrue(text.contains("Reason: time limit"))
        assertTrue(text.contains("HTTP status: Not recorded"))
    }
}
