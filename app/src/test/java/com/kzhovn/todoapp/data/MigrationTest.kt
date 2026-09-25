package com.kzhovn.todoapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// Builds a real v3 database from the exported v3 schema, then opens it with the current code.
// Room validates the migrated schema against the current entities and throws on any mismatch.
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @Test
    fun `v3 database migrates to the current version keeping its tasks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/com.kzhovn.todoapp.data.TodoDatabase/3.json").readText())
            .jsonObject.getValue("database").jsonObject

        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                val sql = listOf(entity.getValue("createSql")) + entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql") }
                sql.forEach { db.execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO tasks (id, type, title, sequential, isStarred, isComplete) VALUES (1, 'TASK', 'kept', 0, 1, 0)")
            db.version = 3
        }

        val room = Room.databaseBuilder(context, TodoDatabase::class.java, file.path)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).allowMainThreadQueries().build()
        val task = runBlocking { room.taskDao().getById(1) }!!
        room.close()

        assertEquals("kept", task.title)
        assertTrue(task.isStarred)
        assertFalse(task.isMaybe)
        assertEquals(null, task.expiresAt)
    }
}
