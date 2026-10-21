package me.ash.reader.ui.page.nav3

/** One guarded pop for both toolbar and system Back, including single-destination deep links. */
internal fun <T> navigateAppBack(stack: MutableList<T>, expectedTop: T?, home: T) {
    if (stack.lastOrNull() != expectedTop) return
    when {
        stack.size > 1 -> stack.removeAt(stack.lastIndex)
        stack.size == 1 -> stack[0] = home
        else -> stack.add(home)
    }
}
