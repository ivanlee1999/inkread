package me.ash.reader.infrastructure.rss

import android.system.Os
import android.system.OsConstants
import java.io.File
import javax.inject.Inject

/** Android's directory fsync makes archive publication durable before the Room body update. */
class FeedSourceDirectorySync @Inject constructor() {
    fun sync(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Earlier feed source parent is not a directory" }
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}
