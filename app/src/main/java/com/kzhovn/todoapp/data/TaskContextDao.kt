package com.kzhovn.todoapp.data

import androidx.room.Dao
import androidx.room.Insert
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

    @Insert
    suspend fun assignContext(crossRef: TaskContextCrossRef)
}
