package com.kzhovn.todoapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): Task?

    @Query("SELECT * FROM tasks")
    fun getAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks")
    suspend fun getAllOnce(): List<Task>

    @Insert
    suspend fun insertDependency(dependency: TaskDependency)

    @Query(
        """
        SELECT * FROM tasks
        WHERE type != 'FOLDER'
          AND isComplete = 0
          AND (startDate IS NULL OR startDate <= :now)
          AND id NOT IN (
            SELECT td.taskId FROM task_dependencies td
            JOIN tasks dep ON dep.id = td.dependsOnTaskId
            WHERE dep.isComplete = 0
          )
          AND (
            parentId IS NULL
            OR NOT EXISTS (SELECT 1 FROM tasks p WHERE p.id = tasks.parentId AND p.sequential = 1)
            OR id = (
              SELECT c.id FROM tasks c
              WHERE c.parentId = tasks.parentId AND c.isComplete = 0
              ORDER BY c.id ASC LIMIT 1
            )
          )
          AND id NOT IN (
            SELECT tc.taskId FROM task_contexts tc
            JOIN contexts ctx ON ctx.id = tc.contextId
            WHERE (ctx.type = 'PLACE' AND ctx.isCurrentlySatisfied = 0)
               OR (ctx.type = 'TIME' AND NOT (:currentMinuteOfDay BETWEEN ctx.windowStartMinute AND ctx.windowEndMinute))
          )
        """
    )
    suspend fun getActiveTasks(now: Long, currentMinuteOfDay: Int): List<Task>

    @Query(
        """
        WITH RECURSIVE descendants(id) AS (
            SELECT id FROM tasks WHERE parentId = :folderId
            UNION ALL
            SELECT t.id FROM tasks t JOIN descendants d ON t.parentId = d.id
        )
        SELECT * FROM tasks WHERE id IN (SELECT id FROM descendants) AND type != 'FOLDER'
        """
    )
    suspend fun getLeafTasksUnder(folderId: Long): List<Task>

    @Query("SELECT * FROM tasks WHERE type != 'FOLDER' AND title LIKE '%' || :query || '%' COLLATE NOCASE")
    suspend fun search(query: String): List<Task>
}
