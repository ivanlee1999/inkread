package me.ash.reader.infrastructure.library

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SavedLibraryFilesTest {
    private fun <T> withFiles(limits: SavedStorageLimits = SavedStorageLimits(), test: (SavedLibraryFiles, String) -> T): T {
        val directory = Files.createTempDirectory("saved-library-fixture").toFile()
        try { return test(SavedLibraryFiles(directory, limits), UUID.randomUUID().toString()) }
        finally { directory.deleteRecursively() }
    }
    @Test fun committedTextSurvivesNewStoreInstanceAndLivesOutsideCache() = withFiles { files, id ->
        val revision = files.commit(id) { "<article>Durable fixture text</article>" }
        val reopened = SavedLibraryFiles(files.root)
        assertEquals(revision.html, reopened.read(id, revision.revision))
        assertTrue(File(files.root, "$id/${revision.revision}/content.html").isFile)
        assertFalse(files.root.walkTopDown().any { it.name.startsWith(".pending-") })
    }
    @Test fun revisionsAreImmutableAndExistingImagePathsSurviveNewHtml() = withFiles { files, id ->
        val first = files.commit(id, mapOf("https://example.com/a.png" to byteArrayOf(1, 2, 3))) { "<p>first</p>" }
        val second = files.commit(id) { "<p>second</p>" }
        assertNotEquals(first.revision, second.revision)
        assertEquals("<p>first</p>", files.read(id, first.revision))
        files.pruneOldHtml(id, second.revision)
        assertNull(files.read(id, first.revision))
        assertTrue(File(first.assetPaths.values.single()).isFile)
        assertEquals("<p>second</p>", files.read(id, second.revision))
    }
    @Test fun quotaRefusesNewDataWithoutChangingDurableText() = withFiles(SavedStorageLimits(maxLibraryBytes = 60)) { files, id ->
        val original = files.commit(id) { "<p>Original durable text</p>" }
        try { files.commit(id) { "x".repeat(50) }; fail("Quota must reject") }
        catch (_: SavedStorageFullException) { }
        assertEquals(original.html, files.read(id, original.revision))
        assertTrue(files.bytesUsed() <= 60)
        assertFalse(files.root.walkTopDown().any { it.name.startsWith(".pending-") })
    }
    @Test fun enforcesPerImageAndPerArticleCapsBeforeWriting() = withFiles(
        SavedStorageLimits(maxImageBytes = 4, maxImagesPerArticle = 2, maxArticleImageBytes = 6),
    ) { files, id ->
        listOf(mapOf("a" to ByteArray(5)), mapOf("a" to ByteArray(4), "b" to ByteArray(4)),
            mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2), "c" to byteArrayOf(3))).forEach { images ->
            try { files.commit(id, images) { "text" }; fail("Per-article cap must reject") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0L, files.bytesUsed())
    }
    @Test fun enforcesUtf8TextByteLimit() = withFiles(SavedStorageLimits(maxHtmlBytes = 5)) { files, id ->
        try { files.commit(id) { "界界" }; fail("UTF-8 bytes, not chars") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0L, files.bytesUsed())
    }
    @Test fun userUrlsCannotBecomeFilePaths() = withFiles { files, _ ->
        try { files.commit("../../outside") { "text" }; fail("IDs must be opaque") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0L, files.bytesUsed())
    }
    @Test fun processDeathReconciliationRemovesOnlyOrphans() = withFiles { files, id ->
        val kept = files.commit(id, mapOf("https://example.com/a.png" to byteArrayOf(1))) { "<p>kept</p>" }
        val orphanId = UUID.randomUUID().toString()
        files.commit(orphanId) { "orphan" }
        File(files.root, "$id/.pending-fixture").mkdirs()
        files.recoverDirectories(setOf(id))
        assertFalse(File(files.root, orphanId).exists())
        assertFalse(File(files.root, "$id/.pending-fixture").exists())
        assertEquals(kept.html, files.read(id, kept.revision))
        assertTrue(File(kept.assetPaths.values.single()).isFile)
    }
    @Test fun interruptedUnpublishedImageRevisionIsReclaimed() = withFiles { files, id ->
        val current = files.commit(id) { "<p>text first</p>" }
        val orphan = files.commit(id, mapOf("https://example.com/a.png" to byteArrayOf(1))) { "unpublished" }
        files.reconcileItem(id, current.revision, emptySet())
        assertNull(files.read(id, orphan.revision))
        assertFalse(File(orphan.assetPaths.values.single()).exists())
        assertEquals(current.html, files.read(id, current.revision))
    }
    @Test fun removalDeletesTextAndAssets() = withFiles { files, id ->
        val revision = files.commit(id, mapOf("a" to byteArrayOf(1))) { "text" }
        files.remove(id)
        assertNull(files.read(id, revision.revision))
        assertEquals(0L, files.bytesUsed())
    }
}
