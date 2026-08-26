package com.kzhovn.todoapp.ui

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.OutlinerPreferences
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerCheckBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerTitleFont
import com.kzhovn.todoapp.ui.theme.LedgerUiFont
import com.kzhovn.todoapp.ui.theme.folderColor
import kotlinx.coroutines.launch

@Composable
fun OutlinerScreen(
    tasks: List<Task>,
    onCheck: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onReparent: (Long, Long) -> Unit,
    onAddSubtask: (Long) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { OutlinerPreferences(context) }
    val scope = rememberCoroutineScope()
    var collapsed by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(Unit) {
        collapsed = prefs.collapsedIds()
    }

    val tree = remember(tasks) { buildOutlinerTree(tasks, hideCompleted = true) }
    val allById = remember(tasks) { tasks.associateBy { it.id } }

    fun toggle(folderId: Long) {
        val nowCollapsed = folderId !in collapsed
        collapsed = if (nowCollapsed) collapsed + folderId else collapsed - folderId
        scope.launch { prefs.setCollapsed(folderId, nowCollapsed) }
    }

    LazyColumn {
        renderNodes(tree, depth = 0, collapsed = collapsed, onToggle = ::toggle, onCheck = onCheck, onEdit = onEdit, onStar = onStar, onReparent = onReparent, onAddSubtask = onAddSubtask, allById = allById)
    }
}

private fun LazyListScope.renderNodes(
    nodes: List<OutlinerNode>,
    depth: Int,
    collapsed: Set<Long>,
    onToggle: (Long) -> Unit,
    onCheck: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onReparent: (Long, Long) -> Unit,
    onAddSubtask: (Long) -> Unit,
    allById: Map<Long, Task>
) {
    nodes.forEach { node ->
        item(key = node.task.id) {
            OutlinerRow(node, depth, node.task.id in collapsed, onToggle, onCheck, onEdit, onStar, onReparent, onAddSubtask, allById)
        }
        if (node.children.isNotEmpty() && node.task.id !in collapsed) {
            renderNodes(node.children, depth + 1, collapsed, onToggle, onCheck, onEdit, onStar, onReparent, onAddSubtask, allById)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OutlinerRow(
    node: OutlinerNode,
    depth: Int,
    isCollapsed: Boolean,
    onToggle: (Long) -> Unit,
    onCheck: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onReparent: (Long, Long) -> Unit,
    onAddSubtask: (Long) -> Unit,
    allById: Map<Long, Task>
) {
    val task = node.task
    val hasChildren = node.children.isNotEmpty()
    val indent = (8 + depth * 13).dp
    var isDropHover by remember { mutableStateOf(false) }

    val dropTarget = remember(task.id, allById) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isDropHover = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDropHover = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDropHover = false
                val draggedId = event.toAndroidDragEvent().clipData
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.text?.toString()?.toLongOrNull()
                    ?: return false
                if (draggedId == task.id || wouldCreateCycle(task.id, draggedId, allById)) return false
                onReparent(draggedId, task.id)
                return true
            }
        }
    }

    Row(
        modifier = Modifier
            .padding(start = indent, top = 3.dp, bottom = 3.dp, end = 12.dp)
            .then(if (isDropHover) Modifier.background(LedgerAccentSoft) else Modifier)
            .dragAndDropSource {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        startTransfer(DragAndDropTransferData(ClipData.newPlainText("task_id", task.id.toString())))
                    },
                    onDrag = { _, _ -> }
                )
            }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { it.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) },
                target = dropTarget
            )
            .clickable(enabled = hasChildren) { onToggle(task.id) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasChildren) {
            Icon(
                if (isCollapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                tint = LedgerMuted,
                modifier = Modifier.width(16.dp)
            )
        } else {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add subtask",
                tint = LedgerMuted,
                modifier = Modifier
                    .width(16.dp)
                    .clickable { onAddSubtask(task.id) }
            )
        }
        Spacer(Modifier.width(4.dp))
        when (task.type) {
            TaskType.FOLDER -> {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = folderColor(task.id), modifier = Modifier.width(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    task.title,
                    fontFamily = LedgerUiFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = LedgerInk,
                    modifier = Modifier.weight(1f).clickable { onEdit(task.id) }
                )
            }
            TaskType.TASK -> {
                TaskCheckbox(checked = task.isComplete, overdue = isOverdue(task), size = 13.dp, onCheckedChange = { onCheck(task.id) })
                Spacer(Modifier.width(6.dp))
                Text(
                    task.title,
                    fontFamily = LedgerTitleFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    textDecoration = if (task.isComplete) TextDecoration.LineThrough else null,
                    color = if (task.isComplete) LedgerMuted else LedgerInk,
                    modifier = Modifier.weight(1f).clickable { onEdit(task.id) }
                )
                IconButton(onClick = { onStar(task.id) }) {
                    Icon(
                        if (task.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Star",
                        tint = if (task.isStarred) LedgerStar else LedgerCheckBorder
                    )
                }
            }
        }
    }
}
