package me.ash.reader.ui.page.adaptive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadStatusScopeTest {
    @Test fun `group has precedence and a strict cutoff`() {
        val scope = ReadStatusScope("group", "ignored-feed", "ignored-article", 100)
        assertTrue(scope.matches("a", "feed", "group", 99))
        assertFalse(scope.matches("a", "feed", "group", 100))
        assertFalse(scope.matches("ignored-article", "ignored-feed", "other", 1))
    }

    @Test fun `feed has precedence over article and respects cutoff`() {
        val scope = ReadStatusScope(null, "feed", "ignored-article", 100)
        assertTrue(scope.matches("a", "feed", "any", 99))
        assertFalse(scope.matches("ignored-article", "other", "any", 1))
        assertFalse(scope.matches("a", "feed", "any", 101))
    }

    @Test fun `single article ignores cutoff just like repository`() {
        val scope = ReadStatusScope(null, null, "a", 100)
        assertTrue(scope.matches("a", "any", "any", 101))
        assertFalse(scope.matches("b", "any", "any", 1))
    }

    @Test fun `all and null cutoff match database upper bound`() {
        assertTrue(ReadStatusScope(null, null, null, null).matches("a", "f", "g", 100))
        assertFalse(ReadStatusScope(null, null, null, null).matches("a", "f", "g", Long.MAX_VALUE))
        assertFalse(ReadStatusScope(null, null, null, 100).matches("a", "f", "g", 100))
    }
}
