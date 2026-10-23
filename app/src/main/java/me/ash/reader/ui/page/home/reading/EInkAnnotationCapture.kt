package me.ash.reader.ui.page.home.reading

/** UI-thread ownership of one atomic snapshot command already issued by a menu click. */
internal class EInkAnnotationCapture<Owner : Any> {
    enum class Event { NATIVE_MODE_ENDED, READER_DOWN, EXPLICIT_DISMISSAL, DOCUMENT_CHANGED, VIEW_DETACHED, MODAL_OPENED }

    class Ticket<Owner : Any> internal constructor(
        val generation: String,
        val owner: Owner,
        val addNote: Boolean,
        /** Captured at Save, never read from a newer resize request. */
        val annotationId: String? = null,
        val resizeToken: String? = null,
    )

    private var pending: Ticket<Owner>? = null
    val hasPending: Boolean get() = pending != null

    fun begin(generation: String, owner: Owner, addNote: Boolean, annotationId: String? = null, resizeToken: String? = null): Ticket<Owner>? {
        if (hasPending) return null
        return Ticket(generation, owner, addNote, annotationId, resizeToken).also { pending = it }
    }

    fun onEvent(event: Event) {
        // Menu replacement/destruction is not cancellation of an explicit click.
        // This retains only its already-issued command, never a range to recapture.
        if (event != Event.NATIVE_MODE_ENDED) pending = null
    }

    /** Result and timeout race for the same identity; neither can consume a newer ticket. */
    fun consume(ticket: Ticket<Owner>, generation: String, owner: Owner?, blocked: Boolean): Boolean {
        if (pending !== ticket) return false
        pending = null
        return ticket.generation == generation && ticket.owner === owner && !blocked
    }
}
