package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.Labels
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Search
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
    searchSelected: Boolean = false,
    reviewSelected: Boolean = false,
    onSearch: () -> Unit,
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
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Item("Search", Icons.Filled.Search, searchSelected, onSearch)
                Item("Focus", Icons.Filled.CenterFocusStrong, false) { context.startActivity(Intent(context, FocusActivity::class.java)) }
                Item("Review", Icons.Filled.BarChart, reviewSelected) {
                    if (!reviewSelected) context.startActivity(Intent(context, ReviewActivity::class.java))
                }
                Item("Settings", Icons.Filled.Sync, false) { context.startActivity(Intent(context, SyncSettingsActivity::class.java)) }
                Item("Contexts", Icons.Filled.AlternateEmail, false) { context.startActivity(Intent(context, ContextsActivity::class.java)) }
                Spacer(Modifier.weight(1f))
                QuickAddKey()
            }
        },
        content = content
    )
}

// Cheat sheet for quick-add syntax, at the bottom of the drawer.
@Composable
private fun QuickAddKey() {
    val discord = listOf(
        "--work: …" to "into a folder (else Personal)",
        "--d: …" to Labels.TODAY_ONLY.lowercase(),
        "reply to a todo" to "it depends on the new one",
        "✅ ❌ ⭐" to "complete / delete / star",
    )
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("Quick add", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerMuted)
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
