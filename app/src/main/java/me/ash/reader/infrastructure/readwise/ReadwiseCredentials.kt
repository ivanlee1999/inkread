package me.ash.reader.infrastructure.readwise

/** Never put this object in UI state, SavedState, Room, WorkManager Data, or logs. */
internal class ReadwiseCredential(val connectionId: String, val token: String) {
    override fun toString(): String = "ReadwiseCredential(redacted)"
}

internal interface ReadwiseCredentialStore {
    fun read(): ReadwiseCredential?
    fun write(credential: ReadwiseCredential)
    fun clear()
}
