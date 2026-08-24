package com.kzhovn.todoapp

import android.app.Application
import androidx.room.Room
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.repository.TaskRepository

class TodoApp : Application() {
    val database: TodoDatabase by lazy {
        Room.databaseBuilder(this, TodoDatabase::class.java, "todo.db").build()
    }
    val repository: TaskRepository by lazy { TaskRepository(database.taskDao()) }
}
