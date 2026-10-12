package com.kzhovn.todoapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: Task)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): Task?

    @Query(
        """
        WITH RECURSIVE descendants(id) AS (
            SELECT id FROM tasks WHERE parentId = :taskId
            UNION ALL
            SELECT t.id FROM tasks t JOIN descendants d ON t.parentId = d.id
        )
        SELECT * FROM tasks WHERE id IN (SELECT id FROM descendants)
        """
    )
    suspend fun getDescendants(taskId: Long): List<Task>

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: Long)

    // Every task, templates included: what changes are planned against (the lists read getListedOnce).
    @Query("SELECT * FROM tasks")
    suspend fun getAllOnce(): List<Task>

    // Everything but templates (see Templates.kt): what the lists, counts, search and pickers see.
    @Query("SELECT * FROM tasks WHERE isTemplate = 0")
    suspend fun getListedOnce(): List<Task>

    @Query("SELECT * FROM tasks WHERE isTemplate = 1")
    suspend fun getTemplatesOnce(): List<Task>

    @Query("SELECT id FROM tasks WHERE isTemplate = 1")
    suspend fun getTemplateIds(): List<Long>

    // pinnedTask() in :core, as one query: the open task pinned most recently.
    @Query("SELECT * FROM tasks WHERE pinnedAt IS NOT NULL AND isComplete = 0 ORDER BY pinnedAt DESC LIMIT 1")
    suspend fun getPinned(): Task?

    // CurrentTask.focusSession, as one query: the newest focus, even once its task is done.
    @Query("SELECT * FROM tasks WHERE focusedAt IS NOT NULL ORDER BY focusedAt DESC LIMIT 1")
    suspend fun getFocusSession(): Task?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependency(dependency: TaskDependency)

    @Query("SELECT dependsOnTaskId FROM task_dependencies WHERE taskId = :taskId")
    suspend fun getDependencyIds(taskId: Long): List<Long>


    @Query("DELETE FROM task_dependencies WHERE taskId = :taskId AND dependsOnTaskId = :dependsOnTaskId")
    suspend fun removeDependency(taskId: Long, dependsOnTaskId: Long)

    @Query("DELETE FROM task_dependencies WHERE taskId = :taskId")
    suspend fun deleteDependenciesOf(taskId: Long)

    @Query("SELECT * FROM task_dependencies")
    suspend fun getAllDependencies(): List<TaskDependency>
}
