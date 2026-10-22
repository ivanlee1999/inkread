package me.ash.reader.ui.page.nav3

/** Return to an existing route instead of destroying its retained list/filter/page model. */
internal fun <T> showRetainedDestination(stack: MutableList<T>, destination: T) {
    val index = stack.indexOf(destination)
    if (index >= 0) repeat(stack.size - index - 1) { stack.removeAt(stack.lastIndex) }
    else stack.add(destination)
}
