package com.kzhovn.todoapp.repository

// One multi-edit applied to many tasks. Null means "leave as is"; the editable set is limited to
// what makes sense in bulk (no title, recurrence, or folder-ness).
data class BulkEdit(
    val starred: Boolean? = null,
    val maybe: Boolean? = null,
    val startDate: DateChange? = null,
    val dueDate: DateChange? = null,
    val moveTo: FolderChange? = null,
    val addContextIds: Set<Long> = emptySet(),
    val removeContextIds: Set<Long> = emptySet(),
    val dependsOnId: Long? = null
)

data class DateChange(val date: Long?)      // null date = clear it
data class FolderChange(val folderId: Long?) // null folder = move to top level
