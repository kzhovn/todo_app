package com.kzhovn.todoapp.data

import androidx.room.Dao
import androidx.room.Delete
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

    @Delete
    suspend fun delete(task: Task)

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

    @Query("SELECT * FROM tasks")
    suspend fun getAllOnce(): List<Task>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependency(dependency: TaskDependency)

    @Query("SELECT dependsOnTaskId FROM task_dependencies WHERE taskId = :taskId")
    suspend fun getDependencyIds(taskId: Long): List<Long>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId")
    suspend fun getChildren(parentId: Long): List<Task>

    @Query("DELETE FROM task_dependencies WHERE taskId = :taskId AND dependsOnTaskId = :dependsOnTaskId")
    suspend fun removeDependency(taskId: Long, dependsOnTaskId: Long)

    @Query(
        """
        SELECT * FROM tasks
        WHERE type != 'FOLDER'
          AND title LIKE '%' || :query || '%' COLLATE NOCASE
          AND (:includeCompleted OR isComplete = 0)
          AND (:folderId IS NULL OR parentId = :folderId)
          AND (:starredOnly = 0 OR isStarred = 1)
          AND (:dueAfter IS NULL OR dueDate >= :dueAfter)
          AND (:dueBefore IS NULL OR dueDate <= :dueBefore)
          AND (:contextId IS NULL OR id IN (SELECT taskId FROM task_contexts WHERE contextId = :contextId))
        """
    )
    suspend fun searchFiltered(
        query: String,
        includeCompleted: Boolean,
        folderId: Long?,
        starredOnly: Boolean,
        dueAfter: Long?,
        dueBefore: Long?,
        contextId: Long?
    ): List<Task>

    @Query("DELETE FROM task_dependencies WHERE taskId = :taskId")
    suspend fun deleteDependenciesOf(taskId: Long)

    @Query("SELECT * FROM task_dependencies")
    suspend fun getAllDependencies(): List<TaskDependency>
}
