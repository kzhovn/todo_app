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
}
