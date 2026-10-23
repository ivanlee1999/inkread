package me.ash.reader.infrastructure.html

import java.io.IOException
import java.nio.charset.Charset
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class HtmlResponseDecoderTest {
    private val bilingual = "<p>中文报道与 English reporting stay readable.</p>"

    @Test fun unlabelledUtf8PreservesChineseAndEnglish() {
        assertEquals(bilingual, decode(bilingual.toByteArray()))
    }

    @Test fun httpCharsetDecodesWesternPunctuation() {
        val html = "<p>Café, £5 and “quoted” reporting.</p>"
        assertEquals(html, decode(html.toByteArray(Charset.forName("windows-1252")), "text/html; charset=windows-1252"))
    }

    @Test fun httpCharsetDecodesChineseAndEnglish() {
        assertEquals(bilingual, decode(bilingual.toByteArray(Charset.forName("GB18030")), "text/html; charset=GB18030"))
    }

    @Test fun htmlMetaDecodesChineseAndEnglishWithoutHttpCharset() {
        val html = "<html><head><meta charset='GB18030'></head><body>$bilingual</body></html>"
        assertEquals(html, decode(html.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun contentTypeMetaAcceptsCaseWhitespaceQuotesAndOtherParameters() {
        val html = "<html><head><meta HTTP-EQUIV='Content-Type' content='text/html; CHARSET = &quot;Big5&quot;; other=value'></head>" +
            "<body><p>中文報導 English reporting.</p></body></html>"
        assertEquals(html, decode(html.toByteArray(Charset.forName("Big5"))))
    }

    @Test fun supportedHttpCharsetWinsOverConflictingMeta() {
        val html = "<meta charset='windows-1252'>$bilingual"
        assertEquals(html, decode(html.toByteArray(), "text/html; charset=UTF-8"))
    }

    @Test fun invalidHttpCharsetFallsBackToHtmlMeta() {
        val html = "<meta charset='GB18030'>$bilingual"
        assertEquals(html, decode(html.toByteArray(Charset.forName("GB18030")), "text/html; charset=not-a-real-charset"))
    }

    @Test fun invalidHttpAndMetaCharsetsFallBackToUtf8() {
        val html = "<meta charset='invalid&charset'>$bilingual"
        assertEquals(html, decode(html.toByteArray(), "text/html; charset=not-a-real-charset"))
    }

    @Test fun invalidMetaDoesNotHideLaterValidMeta() {
        val html = "<meta charset='invalid&charset'><meta charset=' GB18030 '>$bilingual"
        assertEquals(html, decode(html.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun unrelatedHttpEquivIsNotACharsetDeclaration() {
        val html = "<meta http-equiv='refresh' content='text/html; charset=GB18030'>$bilingual"
        assertEquals(html, decode(html.toByteArray()))
    }

    @Test fun utf8BomOverridesHttpAndMetaAndIsRemoved() {
        val html = "<meta charset='GB18030'>$bilingual"
        assertEquals(html, decode(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + html.toByteArray(), "text/html; charset=windows-1252"))
    }

    @Test fun utf16BomsOverrideHttpCharsetAndAreRemoved() {
        for ((name, bom) in listOf("UTF-16LE" to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            "UTF-16BE" to byteArrayOf(0xfe.toByte(), 0xff.toByte()))) {
            assertEquals(name, bilingual, decode(bom + bilingual.toByteArray(Charset.forName(name)), "text/html; charset=UTF-8"))
        }
    }

    @Test fun utf32BomsAreNotMistakenForUtf16() {
        for ((name, bom) in listOf("UTF-32LE" to byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0),
            "UTF-32BE" to byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte()))) {
            assertEquals(name, bilingual, decode(bom + bilingual.toByteArray(Charset.forName(name))))
        }
    }

    @Test fun emptyBodyAndEmptyLimitAreSupported() {
        assertEquals("", byteArrayOf().toResponseBody().readHtmlLimited(0))
    }

    @Test fun bodyAtExactByteLimitIsAccepted() {
        val bytes = bilingual.toByteArray()
        assertEquals(bilingual, bytes.toResponseBody().readHtmlLimited(bytes.size.toLong()))
    }

    @Test fun knownOversizeBodyIsRejected() {
        assertThrows(IOException::class.java) { "123456".toResponseBody().readHtmlLimited(5) }
    }

    @Test fun unknownLengthBodyIsRejectedAndOnlyOneExtraByteIsConsumed() {
        val source = Buffer().writeUtf8("123456789")
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        assertThrows(IOException::class.java) { body.readHtmlLimited(5) }
        assertEquals("789", source.readUtf8())
    }

    @Test fun falseContentLengthCannotBypassLimit() {
        val source = Buffer().writeUtf8("123456")
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 1L
            override fun source() = source
        }
        assertThrows(IOException::class.java) { body.readHtmlLimited(5) }
    }

    @Test fun bomBytesCountTowardLimit() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "abc".toByteArray()
        assertThrows(IOException::class.java) { bytes.toResponseBody().readHtmlLimited(3) }
    }

    @Test fun charsetSniffingDoesNotScanBeyondBoundedPrefix() {
        val html = " ".repeat(64 * 1024) + "<meta charset='windows-1252'>$bilingual"
        assertEquals(html, decode(html.toByteArray()))
    }

    private fun decode(bytes: ByteArray, contentType: String = "text/html") =
        bytes.toResponseBody(contentType.toMediaType()).readHtmlLimited()
}
