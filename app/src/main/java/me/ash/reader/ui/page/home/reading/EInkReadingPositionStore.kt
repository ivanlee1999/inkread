package me.ash.reader.ui.page.home.reading

import java.io.File
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.launch

/** Current location only; no article text, URLs or account identifiers are written to disk. */
internal data class EInkReadingPosition(val fingerprint: String, val characterOffset: Int, val fraction: Double) {
    fun isValid() = fingerprint.matches(Regex("[a-f0-9]{64}")) && characterOffset >= 0 &&
        fraction.isFinite() && fraction in 0.0..1.0
}

/** Small app-private, atomic journal, bounded independently of the library size. Call on IO. */
internal class EInkReadingPositionStore(private val directory: File) {
    private val file = File(directory, "eink-reading-positions")
    private val positions = linkedMapOf<String, EInkReadingPosition>()
    private var loaded = false

    @Synchronized fun load(accountId: Int, articleId: String): EInkReadingPosition? {
        readOnce()
        return positions[key(accountId, articleId)]
    }

    @Synchronized fun save(accountId: Int, articleId: String, position: EInkReadingPosition) {
        if (!position.isValid()) return
        readOnce()
        val key = key(accountId, articleId)
        positions.remove(key)
        positions[key] = position
        while (positions.size > MAX_ENTRIES) positions.remove(positions.keys.first())
        directory.mkdirs()
        val temporary = File.createTempFile("resume-", ".tmp", directory)
        try {
            temporary.bufferedWriter().use { writer ->
                positions.forEach { (id, value) ->
                    writer.appendLine("$id=${value.fingerprint},${value.characterOffset},${value.fraction}")
                }
            }
            check(temporary.renameTo(file)) { "Could not replace reading positions" }
        } finally {
            temporary.delete()
        }
    }

    private fun readOnce() {
        if (loaded) return
        loaded = true
        // A corrupt/oversized journal should never prevent opening an article.
        if (!file.isFile || file.length() > 64 * 1024) return
        runCatching {
            file.useLines { lines ->
                lines.take(MAX_ENTRIES).forEach { line ->
                    val id = line.substringBefore('=')
                    val fields = line.substringAfter('=', "").split(',')
                    if (!id.matches(Regex("[a-f0-9]{64}")) || fields.size != 3) return@forEach
                    val offset = fields[1].toIntOrNull() ?: return@forEach
                    val fraction = fields[2].toDoubleOrNull() ?: return@forEach
                    val position = EInkReadingPosition(fields[0], offset, fraction)
                    if (position.isValid()) positions[id] = position
                }
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 128
        fun fingerprint(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            }
            OutputStreamWriter(DigestOutputStream(sink, digest), Charsets.UTF_8).use { writer ->
                // StreamEncoder retains a trailing high surrogate across writes. Never allocate
                // a UTF-8 copy of the whole article, even for multi-megabyte RSS content.
                var offset = 0
                while (offset < content.length) {
                    val count = minOf(FINGERPRINT_CHUNK_CHARS, content.length - offset)
                    writer.write(content, offset, count)
                    offset += count
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        const val FINGERPRINT_CHUNK_CHARS = 2048
        private fun key(accountId: Int, articleId: String) = fingerprint("$accountId:$articleId")
    }
}

/** Serialize IO writes outside a particular composition's cancellable lifetime. */
internal object EInkReadingPositionRepository {
    private val stores = mutableMapOf<File, EInkReadingPositionStore>()
    private val latest = java.util.concurrent.ConcurrentHashMap<Triple<EInkReadingPositionStore, Int, String>, EInkReadingPosition>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
        kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1))

    @Synchronized fun store(filesDir: File): EInkReadingPositionStore = stores.getOrPut(filesDir) {
        EInkReadingPositionStore(File(filesDir, "reading-resume"))
    }

    fun load(store: EInkReadingPositionStore, account: Int, article: String): EInkReadingPosition? =
        latest[Triple(store, account, article)] ?: store.load(account, article)

    fun save(store: EInkReadingPositionStore, account: Int, article: String, position: EInkReadingPosition) {
        if (!position.isValid()) return
        val key = Triple(store, account, article)
        if (latest.put(key, position) == position) return
        // Bound the live cache as well as the journal. Disk remains authoritative after eviction.
        if (latest.size > EInkReadingPositionStore.MAX_ENTRIES) latest.keys.firstOrNull { it != key }?.let(latest::remove)
        scope.launch { runCatching { store.save(account, article, position) } }
    }
}
