package com.kzhovn.todoapp.quickadd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch

class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TodoApp).repository
        setContent {
            var text by remember { mutableStateOf("") }
            Surface {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it })
                    Button(
                        onClick = {
                            val task = QuickAddParser.parse(text)
                            if (task.title.isBlank()) return@Button
                            lifecycleScope.launch {
                                repository.createTask(task)
                                TodoWidget().updateAll(applicationContext)
                                finish()
                            }
                        },
                        enabled = text.isNotBlank()
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}
