package com.kzhovn.todoapp.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.outlinerDataStore by preferencesDataStore(name = "outliner_prefs")

class OutlinerPreferences(private val context: Context) {
    private val collapsedKey = stringSetPreferencesKey("collapsed_folder_ids")
    // Active's folded folder sections, kept apart from the All tree's folds (0 = the no-folder section).
    private val foldedSectionsKey = stringSetPreferencesKey("folded_active_sections")

    suspend fun setCollapsed(folderId: Long, collapsed: Boolean) = set(collapsedKey, folderId, collapsed)
    suspend fun collapsedIds(): Set<Long> = ids(collapsedKey)

    suspend fun setSectionFolded(folderId: Long, folded: Boolean) = set(foldedSectionsKey, folderId, folded)
    suspend fun foldedSectionIds(): Set<Long> = ids(foldedSectionsKey)

    private suspend fun set(key: Preferences.Key<Set<String>>, id: Long, on: Boolean) {
        context.outlinerDataStore.edit { prefs ->
            val current = prefs[key].orEmpty()
            prefs[key] = if (on) current + id.toString() else current - id.toString()
        }
    }

    private suspend fun ids(key: Preferences.Key<Set<String>>): Set<Long> =
        context.outlinerDataStore.data.first()[key]?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()
}
