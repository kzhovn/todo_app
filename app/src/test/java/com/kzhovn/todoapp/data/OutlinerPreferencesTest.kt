package com.kzhovn.todoapp.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutlinerPreferencesTest {
    // preferencesDataStore's delegate caches one DataStore instance per JVM/classloader,
    // keyed by file name only, ignoring which Context.applicationContext requested it — so
    // Robolectric's per-test Application doesn't give each test method a fresh store. Reset
    // the shared instance's state before each test rather than relying on method order.
    @Before
    fun clearPersistedState() = runBlocking {
        val prefs = OutlinerPreferences(ApplicationProvider.getApplicationContext())
        prefs.collapsedIds().forEach { prefs.setCollapsed(it, false) }
    }

    @Test
    fun `folder is not collapsed by default`() = runBlocking {
        val prefs = OutlinerPreferences(ApplicationProvider.getApplicationContext())
        assertFalse(prefs.isCollapsed(1L))
    }

    @Test
    fun `setCollapsed true persists and is readable`() = runBlocking {
        val prefs = OutlinerPreferences(ApplicationProvider.getApplicationContext())
        prefs.setCollapsed(1L, true)
        assertTrue(prefs.isCollapsed(1L))
        assertEquals(setOf(1L), prefs.collapsedIds())
    }

    @Test
    fun `setCollapsed false removes it from the collapsed set`() = runBlocking {
        val prefs = OutlinerPreferences(ApplicationProvider.getApplicationContext())
        prefs.setCollapsed(2L, true)
        prefs.setCollapsed(2L, false)
        assertFalse(prefs.isCollapsed(2L))
    }
}
