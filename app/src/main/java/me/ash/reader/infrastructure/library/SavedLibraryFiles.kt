package me.ash.reader.infrastructure.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class SavedStorageLimits(
    val maxHtmlBytes: Long = 2L * 1024 * 1024,
    val maxImageBytes: Long = 2L * 1024 * 1024,
    val maxImagesPerArticle: Int = 12,
    val maxArticleImageBytes: Long = 12L * 1024 * 1024,
    val maxLibraryBytes: Long = 256L * 1024 * 1024,
)

internal class SavedStorageFullException : IOException("Saved library storage is full. Remove an item and retry.")
internal data class SavedFileRevision(val revision: String, val html: String, val assetPaths: Map<String, String>)

/** Call only under the repository's mutation lock. All directories and names are opaque IDs. */
internal class SavedLibraryFiles(val root: File, val limits: SavedStorageLimits = SavedStorageLimits()) {
    private val opaque = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun itemDir(id: String): File {
        require(opaque.matches(id)) { "Invalid saved item ID" }
        return File(root, id)
    }
    private fun revisionDir(id: String, revision: String): File {
        require(opaque.matches(revision)) { "Invalid saved revision" }
        return File(itemDir(id), revision)
    }
    fun read(id: String, revision: String): String? {
        val file = File(revisionDir(id, revision), "content.html")
        if (!file.isFile || file.length() > limits.maxHtmlBytes) return null
        return file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }
    fun isRetainedAsset(id: String, path: String): Boolean {
        val file = File(path)
        return file.isFile && file.name.endsWith(".img") &&
            file.canonicalFile.toPath().startsWith(itemDir(id).canonicalFile.toPath())
    }
    fun estimatedAssetPath(id: String): String = File(
        revisionDir(id, "00000000-0000-0000-0000-000000000000"),
        "00000000-0000-0000-0000-000000000000.img",
    ).absolutePath
    fun bytesUsed(): Long = if (!root.exists()) 0 else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun remainingBytes(): Long = (limits.maxLibraryBytes - bytesUsed()).coerceAtLeast(0)

    /** Immutable new revision: fsync complete files, then rename the directory before Room points at it. */
    fun commit(id: String, images: Map<String, ByteArray> = emptyMap(), render: (Map<String, String>) -> String): SavedFileRevision {
        require(images.size <= limits.maxImagesPerArticle)
        require(images.values.all { it.size.toLong() <= limits.maxImageBytes })
        require(images.values.sumOf { it.size.toLong() } <= limits.maxArticleImageBytes)
        val revision = UUID.randomUUID().toString()
        val parent = itemDir(id)
        val destination = revisionDir(id, revision)
        val paths = images.keys.associateWith { File(destination, "${UUID.randomUUID()}.img").absolutePath }
        val html = render(paths)
        val bytes = html.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size.toLong() <= limits.maxHtmlBytes) { "Article text exceeds the saved-content limit" }
        val required = bytes.size.toLong() + images.values.sumOf { it.size.toLong() }
        if (required > remainingBytes()) throw SavedStorageFullException()
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create saved article directory" }
        val temporary = File(parent, ".pending-$revision")
        check(temporary.mkdir()) { "Cannot stage saved article" }
        try {
            images.forEach { (url, content) -> writeSynced(File(temporary, File(paths.getValue(url)).name), content) }
            writeSynced(File(temporary, "content.html"), bytes)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return SavedFileRevision(revision, html, paths)
        } finally {
            temporary.deleteRecursively()
        }
    }
    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    fun remove(id: String) { itemDir(id).deleteRecursively() }
    fun discard(id: String, revision: String) { revisionDir(id, revision).deleteRecursively() }

    /** Loaded readers hold HTML strings; retain their immutable image files across image-only revisions. */
    fun pruneOldHtml(id: String, currentRevision: String) {
        itemDir(id).listFiles()?.filter { it.name != currentRevision }?.forEach { directory ->
            if (directory.name.startsWith(".pending-")) directory.deleteRecursively()
            else {
                File(directory, "content.html").delete()
                if (directory.listFiles().isNullOrEmpty()) directory.delete()
            }
        }
    }
    fun recoverDirectories(knownIds: Set<String>) {
        root.listFiles()?.forEach { directory ->
            if (directory.name !in knownIds) directory.deleteRecursively()
            else directory.listFiles()?.filter { it.name.startsWith(".pending-") }?.forEach { it.deleteRecursively() }
        }
    }
    fun reconcileItem(id: String, currentRevision: String, retainedPaths: Set<String>) {
        itemDir(id).listFiles()?.forEach { revision ->
            revision.listFiles()?.forEach { file ->
                if (!(file.name == "content.html" && revision.name == currentRevision) && file.absolutePath !in retainedPaths) file.delete()
            }
            if (revision.listFiles().isNullOrEmpty()) revision.delete()
        }
    }
}
