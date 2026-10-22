package me.ash.reader.ui.page.home.reading

import java.io.File
import me.ash.reader.infrastructure.preference.ReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderTextAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EInkReaderFormattingTest {
    @Test fun latinAndCjkCoverageDoNotCompeteForSelectedGlyphs() {
        fun covers(range: String, codepoint: Int): Boolean = range.split(',').any { item ->
            val ends = item.removePrefix("U+").split('-').map { it.toInt(16) }
            codepoint in ends.first()..ends.last()
        }
        val latin = EInkReaderFormatting.LATIN_RANGE
        val cjk = EInkReaderFormatting.CJK_RANGE
        listOf('A', 'é', 'ö', '’').forEach {
            assertTrue(covers(latin, it.code)); assertFalse(covers(cjk, it.code))
        }
        listOf('中', '文', '，', '。', '「', 'あ').forEach {
            assertTrue(covers(cjk, it.code)); assertFalse(covers(latin, it.code))
        }
        listOf(0x1F600, 0x1F468, 0x2211, 0x1D400).forEach {
            assertFalse(covers(latin, it)); assertFalse(covers(cjk, it))
        }
        assertTrue(covers(cjk, 0x20000))
    }

    @Test fun cachedFacesUseExplicitOfflineResourcesAndDisjointAliases() {
        val latin = File("/offline/fonts/Literata-Regular.ttf")
        val cjk = File("/offline/fonts/NotoSansSC-Regular.otf")
        val faces = EInkReaderFormatting.fontFaces(latin, cjk)
        assertTrue(faces.contains("font-family: 'InkReadLatin'"))
        assertTrue(faces.contains("font-family: 'InkReadCjk'"))
        assertTrue(faces.contains(EInkFontResource(latin).url))
        assertTrue(faces.contains(EInkFontResource(cjk).url))
        assertTrue(faces.contains("format('opentype')"))
        assertTrue(faces.contains("unicode-range: ${EInkReaderFormatting.CJK_RANGE}"))
        assertFalse(faces.contains("fonts.gstatic.com"))
        assertFalse(faces.contains("file://"))
    }

    @Test fun absentDownloadsUseLocalFacesWithoutNetworkRequests() {
        val faces = EInkReaderFormatting.fontFaces(null, null)
        assertTrue(faces.contains("local('Noto Serif')"))
        assertTrue(faces.contains("local('Noto Sans CJK SC')"))
        assertFalse(faces.contains("url("))
        assertTrue(EInkReaderFormatting.stylesheet.contains("'InkReadLatin', 'InkReadCjk', 'Noto Color Emoji', serif"))
    }

    @Test fun payloadClampsBeforeCrossingTheJavascriptBoundary() {
        val payload = EInkReaderFormatting.payload(ReaderFormatting(
            fontSize = Int.MAX_VALUE, lineHeight = Float.NaN, marginLeft = -50,
            marginRight = 900, textWeight = 9000, textDarkness = Float.POSITIVE_INFINITY,
            alignment = ReaderTextAlignment.Justify, imageContrast = Float.NaN,
        ), "")
        assertTrue(payload.contains("\"fontSize\":48"))
        assertTrue(payload.contains("\"marginLeft\":0"))
        assertTrue(payload.contains("\"marginRight\":96"))
        assertTrue(payload.contains("\"textWeight\":900"))
        assertTrue(payload.contains("\"alignment\":\"justify\""))
        assertTrue(payload.contains("\"imageContrast\":1.0"))
        assertFalse(payload.contains("NaN"))
        assertFalse(payload.contains("Infinity"))
    }

    @Test fun contrastIsImageOnlyAndCodeMathRetainTheirFallbacks() {
        val stylesheet = EInkReaderFormatting.stylesheet
        val imageRule = stylesheet.substringAfter("#eink-content img {").substringBefore('}')
        assertTrue(imageRule.contains("filter: contrast(var(--reader-image-contrast)) grayscale(var(--reader-image-grayscale))"))
        assertEquals(1, Regex("filter:").findAll(stylesheet).count())
        assertTrue(stylesheet.contains("font-weight: var(--reader-text-weight)"))
        assertTrue(stylesheet.contains("color: var(--reader-text-color)"))
        assertTrue(stylesheet.contains("font-family: monospace !important"))
        assertTrue(stylesheet.contains("font-family: math, 'STIX Two Math', serif !important"))
    }

    @Test fun payloadStringQuotingPreservesCssAndCannotTerminateInlineScript() {
        assertEquals("\"a\\n\\\"\\\\\\u003c/script\\u003e\"", EInkReaderFormatting.quote("a\n\"\\</script>"))
    }
}
