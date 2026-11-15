package me.ash.reader.infrastructure.report

import android.os.Build
import me.ash.reader.BuildConfig
import me.ash.reader.infrastructure.rss.ArticleContentSelection

internal object ExtractionProblemSnapshotFactory {
    private val bundledSourceRevision: String? by lazy {
        runCatching {
            javaClass.classLoader?.getResourceAsStream("META-INF/version-control-info.textproto")?.use { input ->
                val bytes = ByteArray(4097)
                var used = 0
                while (used < bytes.size) {
                    val count = input.read(bytes, used, bytes.size - used)
                    if (count < 0) break
                    used += count
                }
                if (used > 4096) null else Regex("revision:\\s*\"([0-9a-f]{40})\"")
                    .find(String(bytes, 0, used, Charsets.UTF_8))?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    fun create(
        articleUrl: String, newsletter: Boolean, privateSource: Boolean,
        requested: ExtractionSourceMode, displayed: ExtractionSourceMode,
        feed: String, displayedBody: String?, attempt: ExtractionAttemptSnapshot?, pending: Boolean,
    ): ExtractionProblemSnapshot {
        var publicUrl = ExtractionProblemPrivacy.articleUrl(articleUrl, newsletter)
        if (privateSource) publicUrl = publicUrl.copy(url = null, redacted = true,
            omissions = publicUrl.omissions + ExtractionPrivacyOmission.PRIVATE_URL)
        return ExtractionProblemPrivacy.sanitizeSnapshot(ExtractionProblemSnapshot(
            capturedAtEpochMillis = System.currentTimeMillis(), appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE, sourceRevision = bundledSourceRevision,
            deviceModel = Build.MODEL.orEmpty().ifBlank { "Unknown" }, androidApi = Build.VERSION.SDK_INT,
            publicArticleUrl = publicUrl.url, urlRedacted = publicUrl.redacted,
            privacyOmissions = publicUrl.omissions + ExtractionPrivacyOmission.TITLE,
            requestedSourceMode = requested, displayedSourceMode = displayed,
            presentationUpdatePending = pending,
            feedMetrics = ArticleContentSelection.diagnosticMetrics(feed)?.reportMetrics(),
            displayedMetrics = displayedBody?.let { ArticleContentSelection.diagnosticMetrics(it)?.reportMetrics() },
            lastLocalAttempt = attempt,
        ))
    }
}
