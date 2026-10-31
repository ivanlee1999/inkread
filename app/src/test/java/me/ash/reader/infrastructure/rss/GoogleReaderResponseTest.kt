package me.ash.reader.infrastructure.rss

import java.io.IOException
import me.ash.reader.infrastructure.rss.provider.greader.readGoogleReaderBodyLimited
import me.ash.reader.infrastructure.rss.provider.greader.safeGoogleReaderFailure
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GoogleReaderResponseTest {
    @Test fun boundedJsonPreservesUtf8AndBomDecoding() {
        val json = "{\"content\":\"中文报道\"}"
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + json.toByteArray()
        assertEquals(json, bytes.toResponseBody("application/json".toMediaType()).readGoogleReaderBodyLimited(bytes.size.toLong()))
    }

    @Test fun declaredAndStreamingOversizeResponsesAreRejected() {
        assertThrows(IOException::class.java) { "123456".toResponseBody().readGoogleReaderBodyLimited(5) }
        val source = Buffer().writeUtf8("123456789")
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        assertThrows(IOException::class.java) { body.readGoogleReaderBodyLimited(5) }
        assertEquals("789", source.readUtf8())
    }

    @Test fun diagnosticErrorsNeverRetainRawTransportOrParserDetails() {
        for (error in listOf(IOException("private-token"), JsonSyntaxException("private-token"),
            IllegalArgumentException("private-token"))) {
            error.addSuppressed(IllegalStateException("private-token"))
            val safe = safeGoogleReaderFailure(error, "reader/api/0/subscription/list")
            assertFalse(safe.stackTraceToString().contains("private-token"))
            assertNull(safe.cause)
            assertTrue(safe.suppressed.isEmpty())
        }
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, safeGoogleReaderFailure(cancellation, "reader/api/0/subscription/list"))
    }
}
