package me.ash.reader.ui.page.home.reading

import java.io.File
import me.ash.reader.infrastructure.preference.ReaderFormatting

/** Script-qualified faces keep a Latin generic family from swallowing selected CJK glyphs. */
internal object EInkReaderFormatting {
    const val LATIN_RANGE = "U+0000-024F,U+0300-036F,U+1E00-1EFF,U+2000-206F,U+20A0-20CF,U+FB00-FB06"
    const val CJK_RANGE = "U+2E80-2FFF,U+3000-303F,U+3040-30FF,U+3100-312F,U+31A0-31BF,U+31F0-31FF,U+3400-4DBF,U+4E00-9FFF,U+F900-FAFF,U+FE10-FE1F,U+FE30-FE4F,U+FF00-FFEF,U+20000-2FFFF,U+30000-323AF"

    fun fontFaces(englishFile: File?, chineseFile: File?): String =
        face("InkReadLatin", englishFile, "local('Noto Serif'), local('NotoSerif'), local('Georgia'), local('Times New Roman')", LATIN_RANGE) +
            face("InkReadCjk", chineseFile, "local('Noto Sans CJK SC'), local('NotoSansCJKsc-Regular'), local('Noto Sans SC'), local('Droid Sans Fallback')", CJK_RANGE)

    private fun face(name: String, file: File?, fallback: String, range: String): String {
        val source = file?.let {
            val format = if (it.extension.equals("otf", true)) "opentype" else "truetype"
            "url('${EInkFontResource(it).url}') format('$format')"
        } ?: fallback
        return "@font-face { font-family: '$name'; src: $source; font-style: normal; font-weight: 400; font-display: swap; unicode-range: $range; }\n"
    }

    fun payload(value: ReaderFormatting, faces: String): String {
        val safe = value.clamped()
        return """{
            "fontSize":${safe.fontSize},"lineHeight":${safe.lineHeight},
            "marginLeft":${safe.marginLeft},"marginRight":${safe.marginRight},
            "marginTop":${safe.marginTop},"marginBottom":${safe.marginBottom},
            "letterSpacing":${safe.letterSpacing},"wordSpacing":${safe.wordSpacing},
            "textDarkness":${safe.textDarkness},"textWeight":${safe.textWeight},
            "paragraphGap":${safe.paragraphGap},"paragraphIndent":${safe.paragraphIndent},
            "alignment":${quote(safe.alignment.cssValue)},
            "imageContrast":${safe.imageContrast},"imageGrayscale":${safe.imageGrayscale},
            "fontCss":${quote(faces)}
        }""".trimIndent()
    }

    internal fun quote(text: String): String = buildString {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                else -> if (ch.code < 32) append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }

    val stylesheet = """
        * { box-sizing: border-box; }
        *, *::before, *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
        html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; background: #fff !important; }
        #eink-content {
            position: absolute; padding: 0; margin: 0; column-fill: auto; overflow: visible;
            font-family: 'InkReadLatin', 'InkReadCjk', 'Noto Color Emoji', serif;
            font-size: var(--reader-font-size); line-height: var(--reader-line-height);
            letter-spacing: var(--reader-letter-spacing); word-spacing: var(--reader-word-spacing);
            font-weight: var(--reader-text-weight); text-align: var(--reader-alignment);
            color: var(--reader-text-color); visibility: hidden;
            -webkit-text-size-adjust: none; text-size-adjust: none;
        }
        #eink-content * { color: var(--reader-text-color) !important; background-color: transparent !important; font-family: inherit !important; }
        #eink-content a, #eink-content a:visited { text-decoration: underline; }
        #eink-content p { margin-block: var(--reader-paragraph-gap); text-indent: var(--reader-paragraph-indent); }
        #eink-content strong, #eink-content b, #eink-content h1, #eink-content h2, #eink-content h3,
        #eink-content h4, #eink-content h5, #eink-content h6 { font-weight: bolder; }
        #eink-content img {
            max-width: 100% !important; width: auto !important; height: auto !important;
            max-height: calc(var(--reader-column-height) - 16px) !important; object-fit: contain;
            break-inside: avoid; display: block; margin: 8px auto;
            filter: contrast(var(--reader-image-contrast)) grayscale(var(--reader-image-grayscale));
        }
        #eink-content h1, #eink-content h2, #eink-content h3, #eink-content h4, #eink-content h5, #eink-content h6 {
            break-inside: avoid-column; break-after: avoid;
        }
        #eink-content p, #eink-content li, #eink-content blockquote, #eink-content div {
            max-width: 100%; word-wrap: break-word; overflow-wrap: break-word; orphans: 3; widows: 3;
        }
        #eink-content pre { max-width: 100%; white-space: pre-wrap; word-wrap: break-word; overflow-wrap: break-word; break-inside: avoid; }
        #eink-content pre, #eink-content code, #eink-content pre *, #eink-content code * { font-family: monospace !important; }
        #eink-content math, #eink-content math * { font-family: math, 'STIX Two Math', serif !important; }
        #eink-content code { max-width: 100%; word-wrap: break-word; overflow-wrap: break-word; }
        #eink-content table { display: block; max-width: 100% !important; width: 100% !important; overflow-x: auto; table-layout: fixed; break-inside: avoid; }
        #eink-content td, #eink-content th { overflow-wrap: anywhere; word-break: break-word; max-width: 100%; }
        #eink-content svg, #eink-content canvas { max-width: 100% !important; height: auto !important; }
        #eink-content iframe, #eink-content video, #eink-content embed, #eink-content object { max-width: 100% !important; break-inside: avoid; }
        #eink-content .eink-metadata { margin-bottom: 1.4em; break-inside: avoid-column; }
        #eink-content .eink-metadata h1 { font-size: 1.3em; line-height: 1.3; margin: 0 0 0.4em 0; }
        #eink-content .eink-meta { font-size: 0.82em; margin: 0; text-indent: 0; }
    """.trimIndent()
}
