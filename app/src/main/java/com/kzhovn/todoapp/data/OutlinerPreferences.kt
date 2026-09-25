package com.kzhovn.todoapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.outlinerDataStore by preferencesDataStore(name = "outliner_prefs")

class OutlinerPreferences(private val context: Context) {
    private val collapsedKey = stringSetPreferencesKey("collapsed_folder_ids")

    suspend fun isCollapsed(folderId: Long): Boolean {
        val prefs = context.outlinerDataStore.data.first()
        return prefs[collapsedKey]?.contains(folderId.toString()) == true
    }

    suspend fun setCollapsed(folderId: Long, collapsed: Boolean) {
        context.outlinerDataStore.edit { prefs ->
            val current = prefs[collapsedKey] ?: emptySet()
            prefs[collapsedKey] = if (collapsed) current + folderId.toString() else current - folderId.toString()
        }
    }

    // Active's folded folder sections, kept apart from the All tree's folds (0 = the no-folder section).
    private val foldedSectionsKey = stringSetPreferencesKey("folded_active_sections")

    suspend fun setSectionFolded(folderId: Long, folded: Boolean) {
        context.outlinerDataStore.edit { prefs ->
            val current = prefs[foldedSectionsKey] ?: emptySet()
            prefs[foldedSectionsKey] = if (folded) current + folderId.toString() else current - folderId.toString()
        }
    }

    suspend fun foldedSectionIds(): Set<Long> =
        context.outlinerDataStore.data.first()[foldedSectionsKey]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    suspend fun collapsedIds(): Set<Long> {
        val prefs = context.outlinerDataStore.data.first()
        return prefs[collapsedKey]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }
}
