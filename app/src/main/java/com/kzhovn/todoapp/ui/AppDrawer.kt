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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.contexts.ContextsActivity
import com.kzhovn.todoapp.focus.FocusActivity
import com.kzhovn.todoapp.sync.SyncSettingsActivity
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import kotlinx.coroutines.launch

// The side panel, swiped open from the list screens and Review alike.
@Composable
fun AppDrawer(
    drawerState: DrawerState,
    reviewSelected: Boolean = false,
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
        folders = folders, showNoFolderOption = true, title = "Mode", noFolderLabel = "All folders", selectedId = mode?.id,
        onPick = { setFolderMode(context, it?.id); pickingMode = false }, onDismiss = { pickingMode = false }
    )
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                NavigationDrawerItem(
                    label = { Text(mode?.let { "${it.title} mode" } ?: "All folders", fontWeight = if (mode != null) FontWeight.SemiBold else null) },
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
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
                )
                HorizontalDivider(color = LedgerBorder, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                Item("Focus", Icons.Filled.CenterFocusStrong, false) { context.startActivity(Intent(context, FocusActivity::class.java)) }
                Item("Review", Icons.Filled.BarChart, reviewSelected) {
                    if (!reviewSelected) context.startActivity(Intent(context, ReviewActivity::class.java))
                }
                Item("Settings", Icons.Filled.Sync, false) { context.startActivity(Intent(context, SyncSettingsActivity::class.java)) }
                Item("Contexts", Icons.Filled.AlternateEmail, false) { context.startActivity(Intent(context, ContextsActivity::class.java)) }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = LedgerBorder, modifier = Modifier.padding(horizontal = 24.dp))
                QuickAddKey()
            }
        },
        content = content
    )
}

// Folder mode, on every device: set here, it goes out with a sync straight away.
fun setFolderMode(context: Context, folderId: Long?) {
    AppSettings.setMode(context, folderId)
    if (SyncSettings.config(context) != null) SyncWorker.requestSoon(context, delaySeconds = 0)
}

// Cheat sheet for quick-add syntax, at the bottom of the drawer.
@Composable
private fun QuickAddKey() {
    val discord = listOf(
        "-- …" to "any quick add above",
        "reply to a todo" to "it depends on the new one",
        "✅ ❌ ⭐" to "complete / delete / star",
    )
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(Labels.QUICK_ADD, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerMuted)
        Labels.QUICK_ADD_SYNTAX.forEach { (syntax, meaning) -> KeyRow(syntax, meaning) }
        Spacer(Modifier.height(8.dp))
        Text("Discord (.help for more)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerMuted)
        discord.forEach { (syntax, meaning) -> KeyRow(syntax, meaning) }
    }
}

@Composable
private fun KeyRow(syntax: String, meaning: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = LedgerInk)) { append(syntax) }
            append("  $meaning")
        },
        fontSize = 11.sp, color = LedgerMuted, modifier = Modifier.padding(top = 2.dp)
    )
}
