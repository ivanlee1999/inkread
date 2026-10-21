package me.ash.reader.ui.page.nav3

import org.junit.Assert.assertEquals
import org.junit.Test

class AppBackNavigationTest {
    @Test fun toolbarAndSystemPopOneLevel() {
        val stack = mutableListOf("feeds", "articles", "settings")
        navigateAppBack(stack, "settings", "feeds")
        assertEquals(listOf("feeds", "articles"), stack)
        navigateAppBack(stack, "articles", "feeds")
        assertEquals(listOf("feeds"), stack)
    }

    @Test fun repeatedAndOutgoingToolbarCallbacksCannotPopNewerOrUnderlyingRoutes() {
        val stack = mutableListOf("feeds", "articles", "settings")
        navigateAppBack(stack, "articles", "feeds")
        assertEquals(listOf("feeds", "articles", "settings"), stack)
        navigateAppBack(stack, "settings", "feeds")
        navigateAppBack(stack, "settings", "feeds")
        assertEquals(listOf("feeds", "articles"), stack)
    }

    @Test fun standaloneExternalRouteReturnsHomeWithoutEmptyingTheStack() {
        val stack = mutableListOf("external article")
        navigateAppBack(stack, "external article", "feeds")
        assertEquals(listOf("feeds"), stack)
        navigateAppBack(stack, "external article", "feeds")
        assertEquals(listOf("feeds"), stack)
    }

    @Test fun rootAndDefensiveEmptyStackNeverBecomeInvalid() {
        val stack = mutableListOf("feeds")
        navigateAppBack(stack, "feeds", "feeds")
        assertEquals(listOf("feeds"), stack)
        stack.clear()
        navigateAppBack(stack, null, "feeds")
        assertEquals(listOf("feeds"), stack)
    }
}
