package me.ash.reader.infrastructure.rss

import android.system.Os
import android.system.OsConstants
import java.io.File
import javax.inject.Inject

/** Android's directory fsync makes archive publication durable before the Room body update. */
class FeedSourceDirectorySync @Inject constructor() {
    fun sync(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}
