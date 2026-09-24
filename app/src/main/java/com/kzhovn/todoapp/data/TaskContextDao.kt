package com.kzhovn.todoapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskContextDao {
    @Insert
    suspend fun insert(context: TaskContext): Long

    @Update
    suspend fun update(context: TaskContext)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(context: TaskContext)

    @Query("SELECT * FROM contexts WHERE id = :id")
    suspend fun getById(id: Long): TaskContext?

    @Query("SELECT * FROM contexts")
    suspend fun getAll(): List<TaskContext>

    @Query("UPDATE contexts SET isCurrentlySatisfied = :satisfied WHERE id = :id")
    suspend fun setSatisfied(id: Long, satisfied: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignContext(crossRef: TaskContextCrossRef)

    @Query(
        """
        SELECT c.* FROM contexts c
        JOIN task_contexts tc ON tc.contextId = c.id
        WHERE tc.taskId = :taskId
        """
    )
    suspend fun getContextsForTask(taskId: Long): List<TaskContext>

    @Query("DELETE FROM task_contexts WHERE taskId = :taskId AND contextId = :contextId")
    suspend fun unassignContext(taskId: Long, contextId: Long)

    @Insert
    suspend fun insertTimeWindow(window: ContextTimeWindow): Long

    @Update
    suspend fun updateTimeWindow(window: ContextTimeWindow)

    @Query("DELETE FROM context_time_windows WHERE id = :id")
    suspend fun deleteTimeWindow(id: Long)

    @Query("SELECT * FROM context_time_windows WHERE contextId = :contextId")
    suspend fun getTimeWindows(contextId: Long): List<ContextTimeWindow>

    @Query("DELETE FROM context_time_windows WHERE contextId = :contextId")
    suspend fun deleteTimeWindowsForContext(contextId: Long)

    @Query("DELETE FROM task_contexts WHERE contextId = :contextId")
    suspend fun deleteContextAssignments(contextId: Long)

    @Query("DELETE FROM contexts WHERE id = :id")
    suspend fun deleteContext(id: Long)

    @Query("SELECT contextId FROM task_contexts WHERE taskId = :taskId")
    suspend fun getContextIdsForTask(taskId: Long): List<Long>

    @Query("DELETE FROM task_contexts WHERE taskId = :taskId")
    suspend fun deleteAssignmentsForTask(taskId: Long)

    @Query("SELECT * FROM task_contexts")
    suspend fun getAllCrossRefs(): List<TaskContextCrossRef>

    @Query("SELECT * FROM context_time_windows")
    suspend fun getAllTimeWindows(): List<ContextTimeWindow>
}
