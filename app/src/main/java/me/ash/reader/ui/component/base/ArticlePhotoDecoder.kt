package me.ash.reader.ui.component.base

import android.graphics.BitmapFactory
import coil.ImageLoader
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import java.io.IOException

/** Editorial photographs use only bounded static raster decoding, including cached inputs. */
internal object ArticlePhotoDecoder : Decoder.Factory {
    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(result.source.source().peek().inputStream(), null, bounds)
        // Do not inherit GifDecoder/Movie or SVG's unconstrained intrinsic/aspect-ratio allocation.
        if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic", "image/avif", "image/bmp") ||
            bounds.outWidth !in 1..16_000 || bounds.outHeight !in 1..16_000 ||
            bounds.outWidth.toLong() * bounds.outHeight > 64_000_000L) {
            throw IOException("Unsupported or oversized editorial photograph")
        }
        // Coil's built-in BitmapFactoryDecoder applies the bounded FIT request below.
        return null
    }
}
