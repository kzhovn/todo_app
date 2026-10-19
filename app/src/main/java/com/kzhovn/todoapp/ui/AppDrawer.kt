package com.kzhovn.todoapp.ui

import androidx.compose.material3.NavigationDrawerItemDefaults
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import androidx.compose.material3.HorizontalDivider
import com.kzhovn.todoapp.data.Labels
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.modeFolder
import com.kzhovn.todoapp.sync.SyncSettings
import com.kzhovn.todoapp.sync.SyncWorker
import com.kzhovn.todoapp.ui.theme.folderColors
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.contexts.ContextsActivity
import com.kzhovn.todoapp.focus.FocusActivity
import com.kzhovn.todoapp.sync.SyncSettingsActivity
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.kzhovn.todoapp.MainActivity
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.forcedChoices
import com.kzhovn.todoapp.data.forcedShown
import com.kzhovn.todoapp.repository.ContextSummary
import com.kzhovn.todoapp.repository.contextSummaries
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerCheckBorder
import com.kzhovn.todoapp.ui.theme.LedgerGood

// The side panel, swiped open from the list screens and Review alike.
@Composable
fun AppDrawer(
    drawerState: DrawerState,
    reviewSelected: Boolean = false,
    calendarSelected: Boolean = false,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    @Composable
    fun Item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) = NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = { scope.launch { drawerState.close() }; onClick() },
        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = LedgerInk, unselectedIconColor = LedgerMuted),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    // Folder mode, at the top: the mode in its folder's colour (✕ leaves it), or "All folders" to pick one.
    val modeTick by AppSettings.modeChanges.collectAsState()
    var folders by remember { mutableStateOf<List<Task>>(emptyList()) }
    LaunchedEffect(modeTick, drawerState.isOpen) { folders = (context.applicationContext as TodoApp).repository.getFolders() }
    val mode = modeFolder(AppSettings.modeFolderId(context), folders.associateBy { it.id })
    var pickingMode by remember { mutableStateOf(false) }
    if (pickingMode) FolderPickerDialog(
        folders = folders, showNoFolderOption = true, title = "Mode", noFolderLabel = Labels.ALL_FOLDERS, selectedId = mode?.id,
        onPick = { setFolderMode(context, it?.id); pickingMode = false }, onDismiss = { pickingMode = false }
    )
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // Three groups split by plain rules (focusing, views, what tasks are made with), then the
            // cheat sheet and Settings in the bottom corner.
            ModalDrawerSheet {
                Spacer(Modifier.height(8.dp))
                Item(Labels.FOCUS, Icons.Filled.CenterFocusStrong, false) { context.startActivity(Intent(context, FocusActivity::class.java)) }
                NavigationDrawerItem(
                    label = { Text(mode?.let { "${it.title} mode" } ?: Labels.FOLDER_MODE, fontWeight = if (mode != null) FontWeight.SemiBold else null) },
                    icon = { Icon(Icons.Filled.OpenInFull, contentDescription = null, tint = mode?.let { folderColors(folders)[it.id] } ?: LedgerMuted) },
                    badge = {
                        if (mode != null) Icon(
                            Icons.Filled.Close, contentDescription = "Leave ${mode.title} mode", tint = LedgerMuted,
                            modifier = Modifier.size(36.dp).clickable { setFolderMode(context, null) }.padding(8.dp)
                        ) else Text("▾", color = LedgerMuted)
                    },
                    selected = false,
                    onClick = { pickingMode = true },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = LedgerInk),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Rule()
                Item(Labels.CALENDAR, Icons.Filled.CalendarMonth, calendarSelected) {
                    if (!calendarSelected) context.startActivity(Intent(context, CalendarActivity::class.java))
                }
                Item(Labels.REVIEW, Icons.Filled.BarChart, reviewSelected) {
                    if (!reviewSelected) context.startActivity(Intent(context, ReviewActivity::class.java))
                }
                Rule()
                Item(Labels.TEMPLATES, Icons.Filled.ContentCopy, false) { context.startActivity(Intent(context, TemplatesActivity::class.java)) }
                ContextsSection(drawerState.isOpen) { scope.launch { drawerState.close() } }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = LedgerBorder)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
                    Text(
                        Labels.SYNTAX, fontSize = 13.sp, color = LedgerMuted,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            scope.launch { drawerState.close() }
                            context.startActivity(Intent(context, SyntaxActivity::class.java))
                        }.padding(12.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { drawerState.close() }; context.startActivity(Intent(context, SyncSettingsActivity::class.java)) }) {
                        Icon(Icons.Filled.Settings, contentDescription = Labels.SETTINGS, tint = LedgerMuted)
                    }
                }
            }
        },
        content = content
    )
}

// Kept in memory, not settings: each screen's drawer shares it, and it starts folded again with the app.
private var contextsOpen = false

// Contexts, opening in place: tapping one filters the lists by it (again, or ✕, clears it), going to them from
// another screen; a long press holds its Auto/On/Off, its editor and the contexts screen.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContextsSection(drawerOpen: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TodoApp
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(contextsOpen) }
    var summaries by remember { mutableStateOf<List<ContextSummary>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(drawerOpen, tick) {
        val now = System.currentTimeMillis()
        summaries = contextSummaries(app.contextRepository.getAllContexts(), app.contextRepository.getAllTimeWindows(), app.repository.listedTasks(now), app.repository.getAllTaskContexts(), now, app.repository.calendarEvents())
    }
    val picked by ContextFilter.id.collectAsState()
    val manage = { context.startActivity(Intent(context, ContextsActivity::class.java)) }
    NavigationDrawerItem(
        label = { Text(Labels.CONTEXTS) },
        icon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
        badge = { if (summaries.isNotEmpty()) Text(if (open) "▾" else "▸", color = LedgerMuted) },
        selected = false,
        // With none yet, there's nothing to open: the screen to add one.
        onClick = { if (summaries.isEmpty()) manage() else { open = !open; contextsOpen = open } },
        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = LedgerInk, unselectedIconColor = LedgerMuted),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
    if (open) summaries.forEach { summary ->
        val c = summary.context
        val on = picked == c.id
        var menu by remember { mutableStateOf(false) }
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (on) LedgerAccentSoft else Color.Transparent)
                    .combinedClickable(
                        onClick = {
                            ContextFilter.id.value = c.id.takeUnless { on }
                            onClose()
                            if (context !is MainActivity) context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        },
                        onLongClick = { menu = true }
                    )
                    .padding(start = 52.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
            ) {
                ContextDot(summary)
                Text(c.name, fontSize = 14.sp, color = if (on) LedgerAccent else LedgerInk, fontWeight = if (on) FontWeight.SemiBold else null, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Text(if (on) "✕" else summary.countText, fontSize = 12.sp, color = if (on) LedgerAccent else LedgerMuted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    forcedChoices(c).forEach { (value, label) ->
                        SelectablePill(label = label, selected = forcedShown(c) == value, fontSize = 13.sp, onClick = {
                            scope.launch { app.contextRepository.setForced(c.id, value); tick++ }
                        })
                        Spacer(Modifier.width(6.dp))
                    }
                }
                DropdownMenuItem(text = { Text(Labels.editContext(c.name)) }, onClick = {
                    menu = false
                    context.startActivity(Intent(context, ContextsActivity::class.java).putExtra(ContextsActivity.EXTRA_CONTEXT_ID, c.id))
                })
                DropdownMenuItem(text = { Text(Labels.MANAGE_CONTEXTS) }, onClick = { menu = false; manage() })
            }
        }
    }
}

// A context's dot: green while it holds, grey while it doesn't, a ring for a filter-only one (as on the web).
@Composable
internal fun ContextDot(summary: ContextSummary) {
    val shape = CircleShape
    val m = Modifier.size(8.dp)
    Box(
        when {
            !summary.holds -> m.background(LedgerCheckBorder, shape)
            summary.context.type == ContextType.LABEL -> m.border(1.5.dp, LedgerCheckBorder, shape)
            else -> m.background(LedgerGood, shape)
        }
    )
}

// Folder mode, on every device: set here, it goes out with a sync straight away.
fun setFolderMode(context: Context, folderId: Long?) {
    AppSettings.setMode(context, folderId)
    if (SyncSettings.config(context) != null) SyncWorker.requestSoon(context, delaySeconds = 0)
}

@Composable
private fun Rule() = HorizontalDivider(color = LedgerBorder, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
