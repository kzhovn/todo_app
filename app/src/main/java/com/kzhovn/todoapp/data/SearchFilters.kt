package com.kzhovn.todoapp.data

data class SearchFilters(
    val folderId: Long? = null,
    val contextId: Long? = null,
    val starredOnly: Boolean = false,
    val includeCompleted: Boolean = false,
    val dueAfter: Long? = null,
    val dueBefore: Long? = null
)
