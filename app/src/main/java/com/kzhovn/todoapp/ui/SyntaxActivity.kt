package com.kzhovn.todoapp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme

// Quick add's cheat sheet, and Discord's, opened from the side menu's bottom corner.
class SyntaxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LedgerTheme {
                Column(Modifier.fillMaxSize().background(LedgerBackground).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(Labels.SYNTAX, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)
                    Labels.QUICK_ADD_SYNTAX.forEach { (syntax, meaning) -> KeyRow(syntax, meaning) }
                    Text(Labels.DISCORD_SYNTAX_HEAD, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(top = 20.dp))
                    Labels.DISCORD_SYNTAX.forEach { (syntax, meaning) -> KeyRow(syntax, meaning) }
                }
            }
        }
    }
}

@Composable
private fun KeyRow(syntax: String, meaning: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = LedgerInk)) { append(syntax) }
            append("  $meaning")
        },
        fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(top = 8.dp)
    )
}
