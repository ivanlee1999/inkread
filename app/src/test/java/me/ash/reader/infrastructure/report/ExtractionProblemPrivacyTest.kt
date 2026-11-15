package me.ash.reader.infrastructure.report

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ExtractionProblemPrivacyTest {
    private fun snapshot() = ExtractionProblemSnapshot(
        capturedAtEpochMillis = 1_794_700_000_000L, appVersion = "0.16.24", appVersionCode = 69,
        deviceModel = "BOOX Palma Pro", androidApi = 30,
        requestedSourceMode = ExtractionSourceMode.LOCAL, displayedSourceMode = ExtractionSourceMode.SERVER,
    )

    @Test fun benignPublicQueryAndFragmentAreRemovedWithExplicitFlags() {
        val result = ExtractionProblemPrivacy.articleUrl("https://www.newyorker.com/books/page-turner/public-story?utm_source=reader#section", false)
        assertEquals("https://www.newyorker.com/books/page-turner/public-story", result.url)
        assertTrue(result.redacted)
        assertTrue(result.omissions.containsAll(setOf(ExtractionPrivacyOmission.TITLE,
            ExtractionPrivacyOmission.URL_QUERY, ExtractionPrivacyOmission.URL_FRAGMENT)))
        assertFalse(result.omissions.contains(ExtractionPrivacyOmission.PRIVATE_URL))
    }

    @Test fun newslettersCredentialsPrivateHostsAndTokenizedPathsNeverExportAUrl() {
        val private = listOf(
            "https://username:not-a-real-password@publisher.example.org/article",
            "https://publisher.example.org/article?access_token=synthetic-value",
            "https://publisher.example.org/private/article",
            "https://publisher.example.org/%2574oken/synthetic-value",
            "https://publisher.example.org/article/550e8400-e29b-41d4-a716-446655440000",
            "http://127.0.0.1/article", "http://[::1]/article", "https://reader.internal/article",
            "https://publisher.example.org/feed/everything/rss", "https://publisher.example.org/feed.xml",
            "https://rss.example.org/p/i/?a=reader", "https://rss.example.org/api/greader.php",
            "https://publisher.example.org/account/private-profile", "https://publisher.example.org/mail/message",
        )
        private.forEach {
            val result = ExtractionProblemPrivacy.articleUrl(it, false)
            assertNull(it, result.url)
            assertTrue(it, result.redacted)
            assertTrue(it, result.omissions.contains(ExtractionPrivacyOmission.PRIVATE_URL))
        }
        val newsletter = ExtractionProblemPrivacy.articleUrl("https://publisher.example.org/ordinary-public-post", true)
        assertNull(newsletter.url)
        assertTrue(newsletter.omissions.contains(ExtractionPrivacyOmission.NEWSLETTER))
        assertNull(ExtractionProblemPrivacy.articleUrl("not a URL", false).url)
        assertNull(ExtractionProblemPrivacy.articleUrl("https://publisher.example.org/\nprivate", false).url)
    }

    @Test fun notesRedactLinksCredentialFieldsIdentifiersAndActiveMarkup() {
        val raw = "Photo missing at https://publisher.example.org/article?token=synthetic-secret#private\n" +
            "Authorization: Bearer synthetic-auth-value\nCookie: session=synthetic-cookie-value\n" +
            "ArticleId=private-article-identifier\n" +
            "bearer synthetic-bearer-value\n" +
            "ghp_SYNTHETICVALUETHATISNOTAREALTOKEN\n" +
            "person@example.org 1794591044503150\n" +
            "<p>One image was missing.</p><script>SHOULD_NOT_APPEAR</script>\n" +
            "pass\u200bword=zero-width-secret\n" +
            "pass${String(Character.toChars(0xE0001))}word=supplementary-format-secret\n"
        val safe = ExtractionProblemPrivacy.sanitizeNotes(raw)
        listOf("publisher.example.org", "synthetic-secret", "synthetic-auth-value", "synthetic-cookie-value",
            "private-article-identifier", "synthetic-bearer-value", "ghp_", "person@example.org",
            "1794591044503150", "SHOULD_NOT_APPEAR", "zero-width-secret", "supplementary-format-secret", "<p>").forEach {
            assertFalse("Leaked $it", safe.contains(it))
        }
        assertTrue(safe.contains("One image was missing."))
        assertTrue(safe.contains("redacted"))
    }

    @Test fun longNotesPreserveValidUnicodeWithoutSplittingOrKeepingLoneSurrogates() {
        val emoji = "\uD83D\uDE42"
        val atBoundary = ExtractionProblemPrivacy.sanitizeNotes("x".repeat(1999) + emoji)
        assertEquals(1999, atBoundary.length)
        assertFalse(Character.isHighSurrogate(atBoundary.last()))
        val valid = ExtractionProblemPrivacy.sanitizeNotes("x".repeat(1998) + emoji + "ignored")
        assertEquals(2000, valid.length)
        assertTrue(valid.endsWith(emoji))
        val malformed = ExtractionProblemPrivacy.sanitizeNotes("A\uD800B\uDC00C")
        assertEquals("ABC", malformed)
        assertEquals(MAX_EXTRACTION_REPORT_NOTES_CHARS,
            ExtractionProblemPrivacy.sanitizeNotes("x".repeat(100_000)).length)
    }

    @Test fun unknownNumericFieldsAndInvalidSourceAreNotInventedAsKnownValues() {
        val attempt = ExtractionAttemptSnapshot(-1, -4, ExtractionAttemptOrigin.FRESH, ExtractionCacheState.MISS,
            false, ExtractionAttemptOutcome.FAILED, httpStatus = -2, responseBytesRead = Long.MAX_VALUE,
            parserElapsedMillis = -9, fetchedCandidate = ExtractionContentMetrics(-1, 2, 1))
        val safe = ExtractionProblemPrivacy.sanitizeSnapshot(snapshot().copy(sourceRevision = "not-a-git-revision",
            deviceModel = "https://private.example.org/account", appVersionCode = -1, androidApi = -1,
            feedMetrics = ExtractionContentMetrics(1, 257, 1), lastLocalAttempt = attempt))
        assertNull(safe.sourceRevision)
        assertEquals("unavailable", safe.deviceModel)
        assertEquals(0, safe.appVersionCode)
        assertEquals(0, safe.androidApi)
        assertNull(safe.feedMetrics)
        assertNull(safe.lastLocalAttempt!!.elapsedMillis)
        assertNull(safe.lastLocalAttempt.httpStatus)
        assertNull(safe.lastLocalAttempt.responseBytesRead)
        assertNull(safe.lastLocalAttempt.fetchedCandidate)
        assertEquals("abcdef0123456789", ExtractionProblemPrivacy.sanitizeSnapshot(snapshot().copy(sourceRevision = "ABCDEF0123456789")).sourceRevision)
    }

    @Test fun existingPrivacySuppressionAndOmissionSetsSurviveResanitizationWithoutMutableAliases() {
        val omissions = mutableSetOf(ExtractionPrivacyOmission.NEWSLETTER)
        val safe = ExtractionProblemPrivacy.sanitizeSnapshot(snapshot().copy(
            publicArticleUrl = "https://publisher.example.org/article", privacyOmissions = omissions))
        omissions.clear()
        assertNull(safe.publicArticleUrl)
        assertTrue(safe.privacyOmissions.contains(ExtractionPrivacyOmission.NEWSLETTER))
        assertTrue(safe.privacyOmissions.contains(ExtractionPrivacyOmission.TITLE))
        assertNull(ExtractionProblemPrivacy.sanitizeSnapshot(snapshot().copy(
            publicArticleUrl = "https://publisher.example.org/article",
            privacyOmissions = setOf(ExtractionPrivacyOmission.PRIVATE_URL))).publicArticleUrl)
    }

    @Test fun malformedGsonEnumIsRejectedRatherThanInventingASourceOrSuccessfulAttempt() {
        val json = JsonParser.parseString(Gson().toJson(snapshot())).asJsonObject
        json.addProperty("requestedSourceMode", "UNKNOWN_PRIVATE_STRING")
        val malformed = Gson().fromJson(json, ExtractionProblemSnapshot::class.java)
        assertThrows(IllegalArgumentException::class.java) { ExtractionProblemPrivacy.sanitizeSnapshot(malformed) }
        json.addProperty("requestedSourceMode", "LOCAL")
        json.add("appVersion", null)
        assertEquals("unavailable", ExtractionProblemPrivacy.sanitizeSnapshot(Gson().fromJson(json,
            ExtractionProblemSnapshot::class.java)).appVersion)
    }
}
