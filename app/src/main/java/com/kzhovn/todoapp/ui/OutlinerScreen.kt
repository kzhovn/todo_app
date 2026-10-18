package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.repository.dimmedInTree

import com.kzhovn.todoapp.data.waitingTail
import androidx.compose.ui.graphics.Color
import com.kzhovn.todoapp.data.Labels
import androidx.compose.material.icons.filled.OpenInFull
import com.kzhovn.todoapp.data.OutlinerNode
import com.kzhovn.todoapp.data.buildOutlinerTree
import com.kzhovn.todoapp.data.pruneTree
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.isTimedWait
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
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
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.folderColors
import kotlinx.coroutines.launch

@Composable
fun OutlinerScreen(
    tasks: List<Task>,
    onCheck: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onReparent: (Long, Long) -> Unit,
    onAddSubtask: (Long) -> Unit,
    onMove: (taskId: Long, anchorId: Long, after: Boolean) -> Unit = { _, _, _ -> },
    onSnooze: (Long, Long) -> Unit = { _, _ -> },
    onStillWaiting: (Long) -> Unit = {},
    selectedIds: Set<Long> = emptySet(),
    // Folder mode: the tree starts inside this folder, and each other folder has a button to zoom into it.
    rootId: Long? = null,
    onZoom: ((Long) -> Unit)? = null,
    // What isn't held back other than by contexts (computeActiveIgnoringContexts): the rest is dimmed.
    workable: Set<Long> = emptySet(),
    // A search's matches: the tree shows just them and their parents (muted), all unfolded.
    keep: Set<Long>? = null
) {
    val context = LocalContext.current
    val prefs = remember { OutlinerPreferences(context) }
    val scope = rememberCoroutineScope()
    var collapsed by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(Unit) {
        collapsed = prefs.collapsedIds()
    }

    val tree = remember(tasks, rootId, keep) {
        // A search for completed tasks keeps them.
        buildOutlinerTree(tasks, hideCompleted = keep == null, rootId = rootId).let { if (keep == null) it else pruneTree(it, keep) }
    }
    val allById = remember(tasks) { tasks.associateBy { it.id } }
    val counts = remember(tasks) { subtaskCounts(tasks) }
    val colors = remember(tasks) { folderColors(tasks) }

    fun toggle(folderId: Long) {
        val nowCollapsed = folderId !in collapsed
        collapsed = if (nowCollapsed) collapsed + folderId else collapsed - folderId
        scope.launch { prefs.setCollapsed(folderId, nowCollapsed) }
    }

    // Dragging near (or past) the list's top/bottom edge scrolls it slowly, so a task can be carried
    // beyond what's on screen. Rows report the drag position; it keeps scrolling once the finger leaves
    // the list (no row reports there) until the drag moves back inside or ends.
    val listState = rememberLazyListState()
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    var scrollDirection by remember { mutableStateOf(0) }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val onDragAt: (Float?) -> Unit = { y ->
        scrollDirection = when {
            y == null -> 0
            y < listBounds.top + edge -> -1
            y > listBounds.bottom - edge -> 1
            else -> 0
        }
    }
    LaunchedEffect(scrollDirection) {
        while (scrollDirection != 0) {
            listState.scrollBy(scrollDirection * 14f)
            delay(16)
        }
    }

    val actions = OutlinerActions(::toggle, onCheck, onEdit, onStar, onReparent, onAddSubtask, onMove, onDragAt, onZoom, onSnooze, onStillWaiting)
    LazyColumn(state = listState, modifier = Modifier.onGloballyPositioned { listBounds = it.boundsInRoot() }) {
        renderNodes(tree, depth = 0, collapsed = collapsed, actions = actions, allById = allById, counts = counts, colors = colors, selectedIds = selectedIds, workable = workable, keep = keep)
    }
}

private fun LazyListScope.renderNodes(
    nodes: List<OutlinerNode>,
    depth: Int,
    collapsed: Set<Long>,
    actions: OutlinerActions,
    allById: Map<Long, Task>,
    counts: Map<Long, Pair<Int, Int>>,
    colors: Map<Long, Color>,
    selectedIds: Set<Long>,
    workable: Set<Long>,
    keep: Set<Long>?
) {
    nodes.forEach { node ->
        val folded = keep == null && isFolded(node.task, collapsed)
        item(key = node.task.id) {
            OutlinerRow(node, depth, folded, actions, allById, counts[node.task.id], colors[node.task.id], node.task.id in selectedIds, workable, muted = keep != null && node.task.id !in keep)
        }
        if (node.children.isNotEmpty() && !folded) {
            renderNodes(node.children, depth + 1, collapsed, actions, allById, counts, colors, selectedIds, workable, keep)
        }
    }
}

// Everything a row can do, passed down the tree as one.
private data class OutlinerActions(
    val onToggle: (Long) -> Unit,
    val onCheck: (Long) -> Unit,
    val onEdit: (Long) -> Unit,
    val onStar: (Long) -> Unit,
    val onReparent: (Long, Long) -> Unit,
    val onAddSubtask: (Long) -> Unit,
    val onMove: (Long, Long, Boolean) -> Unit,
    val onDragAt: (Float?) -> Unit,
    val onZoom: ((Long) -> Unit)?,
    val onSnooze: (Long, Long) -> Unit,
    val onStillWaiting: (Long) -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OutlinerRow(
    node: OutlinerNode,
    depth: Int,
    isCollapsed: Boolean,
    actions: OutlinerActions,
    allById: Map<Long, Task>,
    // (checked, all) items of a checklist; the tree itself only shows the open ones.
    itemCounts: Pair<Int, Int>?,
    folderColor: Color?,
    selected: Boolean,
    workable: Set<Long>,
    muted: Boolean = false // a search's match's parent, there to place it
) {
    val (onToggle, onCheck, onEdit, onStar, onReparent, onAddSubtask, onMove, onDragAt, onZoom, onSnooze, onStillWaiting) = actions
    var menuOpen by remember { mutableStateOf(false) }
    val task = node.task
    val hasChildren = node.children.isNotEmpty()
    val hasMenu = task.type.isDoable || task.type == TaskType.WAITING
    val inOrderStep = allById[task.parentId]?.sequential == true
    val indent = (8 + depth * 18).dp
    // Where a drag hovering this row would land: the top/bottom quarter reorders it before/after
    // this row, the middle nests it inside.
    var dropZone by remember { mutableStateOf<DropZone?>(null) }
    var rowBounds by remember { mutableStateOf(Rect.Zero) }

    val dropTarget = remember(task.id, allById) {
        object : DragAndDropTarget {
            // Drag events arrive in the ComposeView's coordinates, the same space as boundsInRoot.
            private fun zoneOf(event: DragAndDropEvent): DropZone {
                val y = event.toAndroidDragEvent().y
                val quarter = rowBounds.height / 4
                return when {
                    y < rowBounds.top + quarter -> DropZone.BEFORE
                    y > rowBounds.bottom - quarter -> DropZone.AFTER
                    else -> DropZone.INTO
                }
            }

            override fun onEntered(event: DragAndDropEvent) { dropZone = zoneOf(event); onDragAt(event.toAndroidDragEvent().y) }
            override fun onMoved(event: DragAndDropEvent) { dropZone = zoneOf(event); onDragAt(event.toAndroidDragEvent().y) }
            override fun onExited(event: DragAndDropEvent) { dropZone = null }
            override fun onEnded(event: DragAndDropEvent) { dropZone = null; onDragAt(null) }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val zone = zoneOf(event)
                dropZone = null
                onDragAt(null)
                val draggedId = event.toAndroidDragEvent().clipData
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.text?.toString()?.toLongOrNull()
                    ?: return false
                if (draggedId == task.id) return false
                when (zone) {
                    DropZone.INTO -> {
                        if (wouldCreateCycle(task.id, draggedId, allById)) return false
                        onReparent(draggedId, task.id)
                    }
                    else -> onMove(draggedId, task.id, zone == DropZone.AFTER)
                }
                return true
            }
        }
    }

    // Swiping right adds a subtask (works whether or not the row already has children, so it
    // doesn't compete with the fold chevron).
    SwipeToAddSubtask({ onAddSubtask(task.id) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { rowBounds = it.boundsInRoot() }
                .background(if (dropZone == DropZone.INTO || selected) LedgerAccentSoft else LedgerBackground)
                .alpha(System.currentTimeMillis().let { now -> if (task.isBackburner(now)) BACKBURNER_ALPHA else if (muted || dimmedInTree(task, workable, allById, now)) LATER_ALPHA else 1f })
                .drawWithContent {
                    drawContent()
                    val line = 3.dp.toPx()
                    when (dropZone) {
                        DropZone.BEFORE -> drawRect(LedgerAccent, size = Size(size.width, line))
                        DropZone.AFTER -> drawRect(LedgerAccent, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                        else -> Unit
                    }
                }
                .padding(start = indent, top = 1.dp, bottom = 1.dp, end = 8.dp)
                .dragAndDropSource {
                    // Press and hold, then move: drags the task. Press and hold without moving: its menu,
                    // as on the lists' rows (not for folders and projects, which it doesn't fit; a waiting item's is Follow up).
                    var moved = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = { moved = false },
                        onDrag = { _, _ ->
                            if (!moved) {
                                moved = true
                                startTransfer(DragAndDropTransferData(ClipData.newPlainText("task_id", task.id.toString())))
                            }
                        },
                        onDragEnd = { if (!moved && hasMenu) menuOpen = true },
                        onDragCancel = { if (!moved && hasMenu) menuOpen = true }
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
                Spacer(Modifier.width(16.dp))
            }
            Spacer(Modifier.width(4.dp))
            when (task.type) {
                TaskType.FOLDER -> {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = folderColor ?: LedgerMuted, modifier = Modifier.width(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        task.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = LedgerInk,
                        modifier = Modifier.weight(1f).clickable { onEdit(task.id) }
                    )
                    // Zoom the whole app into this folder (folder mode).
                    onZoom?.let { zoom ->
                        Icon(
                            Icons.Filled.OpenInFull, contentDescription = "${task.title} mode", tint = LedgerMuted,
                            modifier = Modifier.size(36.dp).clickable { zoom(task.id) }.padding(10.dp)
                        )
                    }
                }
                // A checklist's section: a plain heading over its items (renamed in the checklist's editor).
                TaskType.SECTION -> Text(task.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                TaskType.TASK, TaskType.PROJECT, TaskType.CHECKLIST, TaskType.WAITING -> {
                    TaskMark(
                        task, itemCounts, task.dueDate?.takeUnless { task.isComplete }?.let { dueStatus(it, System.currentTimeMillis()) }, checkboxSize = 20.dp,
                        onCheck = { onCheck(task.id) }, onOpen = { onEdit(task.id) }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        task.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        textDecoration = if (task.isComplete) TextDecoration.LineThrough else null,
                        color = if (task.isComplete) LedgerMuted else LedgerInk,
                        modifier = Modifier.weight(1f).clickable { onEdit(task.id) }
                    )
                    if (task.isHighPriority) PriorityMark()
                    if (inOrderStep && task.alongsideNext) Text(Labels.ALONGSIDE_MARK, fontSize = 14.sp, color = LedgerAccent, modifier = Modifier.padding(horizontal = 4.dp).semantics { contentDescription = Labels.ALONGSIDE_NEXT })
                    if (!task.notes.isNullOrBlank()) NotesMark()
                    task.durationMinutes?.takeIf { task.type != TaskType.WAITING }?.let { TimerButton(task, it) } // a wait's timer runs by itself
                    if (task.type == TaskType.WAITING) {
                        // How long it's been waiting, or the day it resolves itself.
                        val now = System.currentTimeMillis()
                        waitingTail(task, now)?.let {
                            Text(it, fontSize = 11.sp, color = LedgerMuted, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        if (task.dueDate == null && !task.isComplete && !task.isTimedWait) DeferPill(task) { onStillWaiting(task.id) }
                    } else if (task.isMaybe) {
                        MaybeMark(36.dp)
                    } else {
                        IconButton(onClick = { onStar(task.id) }, modifier = Modifier.size(36.dp)) { StarIcon(task.isStarred) }
                    }
                }
            }
        }
        TaskRowMenu(task, menuOpen, onDismiss = { menuOpen = false }, onSnooze = onSnooze, inOrderStep = inOrderStep)
    }
}

// Checklists start folded (their items are clutter in the tree); the saved set holds the ones the
// user flipped, so for a checklist being in it means unfolded.
private fun isFolded(task: Task, flipped: Set<Long>) = (task.id in flipped) != (task.type == TaskType.CHECKLIST)

private enum class DropZone { BEFORE, INTO, AFTER }

// Swipe a row right to add a subtask under it; the row always snaps back, nothing is dismissed.
// Shared by the All tree and the Doing/Active lists.
@Composable
fun SwipeToAddSubtask(onAdd: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.StartToEnd) onAdd()
        false
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(LedgerAccentSoft).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = LedgerAccent)
                Text(Labels.SUBTASK, color = LedgerAccent, fontSize = 14.sp)
            }
        }
    ) {
        // Opaque, so the "+ Subtask" behind it only shows while swiping.
        Box(Modifier.background(LedgerBackground)) { content() }
    }
}
